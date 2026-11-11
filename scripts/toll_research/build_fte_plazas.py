#!/usr/bin/env python3
"""Locates the Florida's Turnpike / FDOT rate-card plazas on the map, for gantry-passage pricing.

Output: docs/toll-pricing/data/gantries/fte.json - one entry per card row (mainline plaza or tolled
interchange) with the OSM toll nodes that charge it, clustered (nodes of one row within 150 m are
one toll point), plus a review list.

Inputs:
  * the card (build_rate_cards.py --fte) and the CFX gantry file (CFX's gantries are never reused,
    except the two plazas whose toll is split between CFX and the Turnpike - see SHARED)
  * OSM via our Overpass host: Florida's toll gantries and Turnpike/FDOT toll booths, the ways
    through them, motorways/trunks and their links within 1.5 km, motorway_junction nodes within
    4 km, and primary-tertiary roads within 350 m (a ramp fix must be clear of the road it meets).

The schedules name every row by MILEPOST, and Florida numbers exits by milepost, so:
  * a ramp row ("259 - Orlando (I-4)") owns the link gantries whose nearest exit on that road is
    junction ref 259 (or 259A/259B). A link gantry whose nearest exit is not a tolled row is left
    unassigned and listed.
  * a mainline plaza ("288 - LEESBURG PLAZA") owns the road's mainline gantry clusters whose
    milepost - from the gantry's name when it starts with the number, else interpolated between
    the two nearest numbered exits - is within MP_TOLERANCE of it; the nearest plaza wins.
OVERRIDES pins what this gets wrong, each with its reason.

  python scripts/toll_research/build_fte_plazas.py [--cache DIR]
"""
import argparse
import json
import math
import os
import re
import sys
import time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_cfx_plazas import clip, load, metres, way_dist  # noqa: E402

FACILITIES = os.path.join("docs", "toll-pricing", "data", "facilities.json")
CARD_DIR = os.path.join("docs", "toll-pricing", "data", "rate-cards")
CFX_GANTRIES = os.path.join("docs", "toll-pricing", "data", "gantries", "cfx.json")
OUT = os.path.join("docs", "toll-pricing", "data", "gantries", "fte.json")

BBOX = "24.4,-87.7,31.1,-79.9"
CLUSTER_M = 150
RAMP_CLIP_M = 250
MAINLINE_CLIP_M = 400
JUNCTION_REACH_M = 1200   # a ramp gantry this far from every exit of its road is not assigned
                          # (the farthest true match is 850 m; at 1.6 km Seminole exit 49 took a gantry
                          # beside the Lake Jesup plaza)
MP_TOLERANCE = 2.5        # miles between a mainline cluster's estimated milepost and the plaza's
CROSSING_ROADS = "primary|secondary|tertiary"


def _name(*names):
    return lambda t, lat, lon: any((t.get("name") or "").startswith(n) for n in names)


def _ref(refs, box=None):
    def match(t, lat, lon):
        if not any(r in (t.get("ref") or "").split(";") for r in refs):
            return False
        return box is None or (box[0] <= lat <= box[2] and box[1] <= lon <= box[3])
    return match


# Card road -> which OSM motorway/trunk ways it is. Boxes split roads that CFX and FTE share by ref.
ROADS = {
    # Some Homestead Extension ways are named plain "Florida's Turnpike": SR 91 ends at Golden Glades
    # (25.92 N, -80.21), so a "Florida's Turnpike" way south of 26.0 N and west of -80.28 is SR 821.
    "Turnpike Mainline (SR 91)": lambda t, lat, lon: _name("Florida's Turnpike")(t, lat, lon)
    and not (lat < 26.0 and lon < -80.28),
    "Homestead Extension (SR 821)": lambda t, lat, lon: _name("Homestead Extension of Florida's Turnpike")(t, lat, lon)
    or (_name("Florida's Turnpike")(t, lat, lon) and lat < 26.0 and lon < -80.28),
    "Beachline West Expressway (SR 528)": _ref(["SR 528 Toll"], (28.40, -81.48, 28.48, -81.34)),
    "Beachline East Expressway (SR 528)": _ref(["SR 528 Toll"], (28.35, -81.10, 28.50, -80.60)),
    "Seminole Expressway (SR 417)": lambda t, lat, lon: _name("Seminole Expressway")(t, lat, lon)
    or _ref(["SR 417 Toll"], (28.64, -81.40, 28.85, -81.15))(t, lat, lon),
    "Southern Connector (SR 417)": lambda t, lat, lon: _name("Southern Connector")(t, lat, lon)
    or _ref(["SR 417 Toll"], (28.25, -81.62, 28.36, -81.44))(t, lat, lon),
    "Daniel Webster Western Beltway, Part C (SR 429)": _ref(["SR 429 Toll"], (28.25, -81.70, 28.47, -81.50)),
    "Wekiva Parkway (SR 429)": lambda t, lat, lon: _name("Wekiva Parkway")(t, lat, lon)
    or _ref(["SR 429 Toll"], (28.74, -81.60, 28.85, -81.30))(t, lat, lon),
    "Polk Parkway (SR 570)": _ref(["SR 570 Toll"]),
    "Suncoast Parkway (SR 589)": _name("Suncoast Parkway"),
    "Veterans Expressway (SR 589)": _name("Veterans Expressway"),
    "Sawgrass Expressway (SR 869)": _ref(["SR 869 Toll"]),
    "First Coast Expressway": _ref(["SR 23 Toll"]),
    "I-4 Connector": _name("Crosstown Connector", "I-4 Connector"),
    "Sunshine Skyway Bridge (I-275)": _ref(["I 275"], (27.52, -82.72, 27.70, -82.58)),
    "Alligator Alley (Everglades Parkway)": _ref(["I 75"], (26.05, -81.72, 26.25, -80.40)),
    "Pinellas Bayway System (SR 682 and SR 679)": lambda t, lat, lon: _name("Pinellas Bayway")(t, lat, lon)
    or _ref(["SR 682", "SR 679", "SR 682 Toll", "SR 679 Toll"])(t, lat, lon),
    "Pinellas Bayway System (SR 690 and SR 686A)": _ref(["SR 690 Toll", "SR 686A Toll"]),
    "Garcon Point Bridge": _ref(["SR 281", "SR 281 Toll"], (30.40, -87.12, 30.60, -86.95)),
}

# Plazas whose toll is split: the Turnpike/FDOT portion is charged at CFX's own gantry cluster.
SHARED = {
    ("Beachline West Expressway (SR 528)", "6 - BEACHLINE WEST PLAZA (Turnpike portion)"):
        ("cfx", "SR 528", "Beachline West Main Plaza (TPK)"),
    ("Beachline East Expressway (SR 528)", "26 - DALLAS PLAZA (FDOT Portion)"):
        ("cfx", "SR 528", "Dallas Main Plaza"),
}

# OSM node id -> (card road, row name, reason). Located 2026-11-06 from the review lists.
_I4C = ("I-4 Connector", "0.4 - MAINLINE PLAZA (Z-Move)",
        "the Turnpike-operated gantries at the I-4 Connector's single plaza (27.9577 N); the ways carry I-4's "
        "exit numbers, so mileposts do not apply. The movement rule then picks the cheapest move")
_SKY_N = ("Sunshine Skyway Bridge (I-275)", "15 - NORTH PLAZA",
          "the Turnpike gantry/booth pair at the Skyway's north end (27.681 N); its way is tagged as a link")
_GATEWAY = ("Pinellas Bayway System (SR 690 and SR 686A)", "2 - GATEWAY PLAZA",
            "the only toll gantries on the Gateway Expressway (SR 690), untagged")
OVERRIDES = {
    962810390: _I4C, 962810723: _I4C, 2829418016: _I4C, 2829436353: _I4C,
    3018236210: _I4C, 3018236775: _I4C, 3018236860: _I4C, 3018236868: _I4C,
    569732567: _SKY_N, 10000000371: _SKY_N,
    11853774303: _GATEWAY, 11853774304: _GATEWAY, 11875447717: _GATEWAY,
    100612286: ("Garcon Point Bridge", "5 - GARCON POINT PLAZA",
                "named 'Garcon Point Bridge Toll Plaza'; the bridge's ways carry no ref the road list knows"),
    14089572775: ("First Coast Expressway", "43 - POW-MIA PARKWAY PLAZA",
                  "named 'POW-MIA Memorial Pkwy Toll Gantry'; by milepost it fell to Normandy (42), 1.5 km away"),
}

# OSM node id -> why it is never charged. Found by the second review (2026-11-10); each stands on a
# mainline the milepost rule gave to a plaza it is not. A through vehicle would pay the wrong plaza's
# rate there, so they are left out (a passage there is missed: estimate low) and listed.
EXCLUDED = {
    **dict.fromkeys([9346758586, 9346758587, 11498841033, 11499272319],
                    "Homestead Extension thru lanes at the Kendall Drive exit, 3 km south of Bird Road Plaza; OSM "
                    "prices them 0.54 SunPass / 0.80 plate (Bird Road's real gantries: 1.07 / 1.34). Not Bird Road; "
                    "which row they collect (Kendall Drive is a ramp row) is not published"),
    **dict.fromkeys([998410932, 617474237],
                    "Polk Parkway gantry pair named '20 Braddock Rd Toll Gantry' (SB; the NB node beside it is "
                    "unnamed), 1.8 km north of Eastern Plaza, whose own pair carries the card's 1.16 / 1.45. Braddock "
                    "Road (20) is a ramp row; whether through traffic pays it is not published"),
    **dict.fromkeys([616901040, 616901045],
                    "barrier toll booths on the lanes through Western Beltway Plaza (between its two mainline "
                    "clusters, OSM price 1.07 / 1.50 = the plaza's), which the exit rule gave to the US 192 ramp row: "
                    "a through vehicle in those lanes would be charged US 192 on top of the plaza"),
}

# Plazas that charge one direction only, as the schedules' maps print it ("Eastbound Tolls" beside WEST PLAZA).
# Each cluster's OSM gantries must all stand on one carriageway; its bearing becomes the cluster's
# charged_bearing, and PassageDetector ignores the cluster for a trail heading the other way. Without this a
# through crossing of Alligator Alley or the Skyway is charged at both ends: the opposite carriageway passes
# 22-58 m from each cluster, inside the 50 m passage threshold.
DIRECTIONAL = {
    ("Alligator Alley (Everglades Parkway)", "100 - WEST TOLL PLAZA"): "eastbound",
    ("Alligator Alley (Everglades Parkway)", "25 - EAST TOLL PLAZA"): "westbound",
    ("Sunshine Skyway Bridge (I-275)", "6 - SOUTH PLAZA"): "northbound",
    ("Sunshine Skyway Bridge (I-275)", "15 - NORTH PLAZA"): "southbound",
    ("Pinellas Bayway System (SR 682 and SR 679)", "1 - PINELLAS BAYWAY II (WEST PLAZA)"): "eastbound",
    ("Pinellas Bayway System (SR 682 and SR 679)", "3 - PINELLAS BAYWAY I (EAST PLAZA)"): "westbound",
    ("Pinellas Bayway System (SR 682 and SR 679)", "6 - PINELLAS BAYWAY IV (SOUTH PLAZA)"): "southbound",
}
DIRECTION_SOURCE = ("the regional schedules' maps: South Florida 8-3-2026 p9 (Alligator Alley), West Central "
                    "Florida 4-2026 (Sunshine Skyway, Pinellas Bayway)")
BEARING_AGREE_DEG = 30    # every gantry of a one-direction cluster must face within this of the others

# Mainline plazas whose clusters lie more than SPREAD_MAX_M apart: the build fails on any other. A through
# vehicle passes every site of such a plaza and must pay once; PassageDetector.SAME_DIRECTION_S charges a
# plaza once per direction of travel.
SPREAD_MAX_M = 1000
MULTI_SITE = {
    ("Suncoast Parkway (SR 589)", "65 - LECANTO PLAZA"):
        "two sites 2.8 km apart, both with gantries on both carriageways, all named 'Lecanto' and priced "
        "alike in OSM; which one is live is not provable from the map",
    ("Suncoast Parkway (SR 589)", "32 - SPRING HILL PLAZA"):
        "two sites 2.0 km apart, both with gantries on both carriageways and priced alike in OSM; which one "
        "is live is not provable from the map",
}


def leading_mp(text):
    m = re.match(r"(\d+(?:\.\d+)?)", text or "")
    return float(m.group(1)) if m else None


CHARGE_TOLERANCE = 0.30   # an OSM price tag this far from the row's rate is listed for review


def osm_amount(value):
    m = re.search(r"(\d+(?:\.\d+)?)", value or "")
    return float(m.group(1)) if m else None


def charge_disagrees(tags, row):
    """Why a toll node's own OSM price tag says it is not this row, or None. OSM prices go stale, so only a
    gap above CHARGE_TOLERANCE counts. charge:sunpass is compared with the SunPass rate; a bare charge
    (mappers use it for either) must be near one of the two rates."""
    def off(amount, rate):
        return rate and abs(amount - rate) / rate > CHARGE_TOLERANCE
    sunpass, plate = row["sunpass"]["2_axle"], row["toll_by_plate"]["2_axle"]
    tagged = osm_amount(tags.get("charge:sunpass"))
    if tagged is not None and off(tagged, sunpass):
        return f"its OSM charge:sunpass {tagged:.2f} is not the row's SunPass {sunpass:.2f}"
    bare = osm_amount(tags.get("charge"))
    if tagged is None and bare is not None and off(bare, sunpass) and off(bare, plate):
        return f"its OSM charge {bare:.2f} is neither the row's SunPass {sunpass:.2f} nor plate {plate:.2f}"
    return None


def ref_keys(number):
    """Card row number -> the junction refs that mean it: '18-A/B' -> {18A, 18B}; '49' -> {49, 49A, 49B}."""
    base = re.match(r"(\d+(?:\.\d+)?)([A-Z]?)", number)
    num, letter = base.group(1), base.group(2)
    if "A/B" in number:
        return {num + "A", num + "B", num + "A-B", num + "A/B"}
    if letter:
        return {num + letter}
    return {num, num + "A", num + "B", num + "A-B"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache Overpass responses in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)
    # The card the registry loads (a reprice adds a card there; the newest one names the plazas).
    entry = next(f for f in json.load(open(FACILITIES, encoding="utf-8"))["facilities"] if f["id"] == "fte")
    cards = [json.load(open(os.path.join(CARD_DIR, c), encoding="utf-8")) for c in entry["cards"]]
    card = max(cards, key=lambda c: c["valid_from"])
    cfx = json.load(open(CFX_GANTRIES, encoding="utf-8"))
    cfx_nodes = {n for p in cfx["plazas"] for c in p["clusters"] for n in c.get("osm_nodes", [])}

    tolls_q = (f"(node({BBOX})[highway=toll_gantry];node({BBOX})[barrier=toll_booth][operator~\"Turnpike|Tunrpike|"
               f"FDOT|Department of Transportation|SunPass\"];)->.t;")
    queries = [
        ("fte_tolls.json", f"[out:json][timeout:900];{tolls_q}.t out;way(bn.t);out body geom;"),
        ("fte_roads.json", f'[out:json][timeout:900];{tolls_q}way(around.t:1500)[highway~"^(motorway|trunk)(_link)?$"];'
                           "out body geom;"),
        ("fte_junctions.json", f"[out:json][timeout:900];{tolls_q}node(around.t:4000)[highway=motorway_junction];out;"),
        ("fte_crossing.json", f'[out:json][timeout:900];{tolls_q}way(around.t:350)[highway~"^({CROSSING_ROADS})'
                              '(_link)?$"];out body geom;'),
    ]
    data, first = {}, True
    for name, q in queries:
        if not (args.cache and os.path.exists(os.path.join(args.cache, name))):
            if not first:
                time.sleep(25)   # our Overpass host rate-limits back-to-back queries
            first = False
        data[name] = load(args.cache, name, q)
    osm_base = data["fte_tolls.json"].get("osm3s", {}).get("timestamp_osm_base")

    ways, nodes = {}, {}
    for d in data.values():
        for e in d["elements"]:
            if e["type"] == "way":
                ways[e["id"]] = e
            else:
                nodes[e["id"]] = e
    ways_of_node = defaultdict(set)
    for w in ways.values():
        for n in w.get("nodes", []):
            ways_of_node[n].add(w["id"])

    def hw(wid):
        return ways[wid].get("tags", {}).get("highway", "")

    def mid(w):
        g = w["geometry"][len(w["geometry"]) // 2]
        return g["lat"], g["lon"]

    def road_of_way(wid):
        if wid not in ways or hw(wid) not in ("motorway", "trunk"):
            return None
        lat, lon = mid(ways[wid])
        for road, match in ROADS.items():
            if match(ways[wid].get("tags", {}), lat, lon):
                return road
        return None

    road_ways = defaultdict(set)
    for wid in ways:
        r = road_of_way(wid)
        if r:
            road_ways[r].add(wid)
    junctions = defaultdict(list)   # road -> [(ref, lat, lon)]
    for n in nodes.values():
        t = n.get("tags", {})
        if t.get("highway") != "motorway_junction" or not t.get("ref"):
            continue
        roads = {road_of_way(w) for w in ways_of_node.get(n["id"], ())} - {None}
        for r in roads:
            for ref in t["ref"].split(";"):
                junctions[r].append((ref.strip(), n["lat"], n["lon"]))

    rows = {(p["road"], p["name"]): p for p in card["plazas"]}
    stale = [k for k in list(DIRECTIONAL) + list(MULTI_SITE) + list(SHARED) + [o[:2] for o in OVERRIDES.values()]
             if k not in rows]
    if stale:
        sys.exit(f"rows named in this script are not on the card (renamed by a reprice?): {sorted(set(stale))}")
    tolls = [n for n in nodes.values() if n.get("tags", {}).get("highway") == "toll_gantry"
             or n.get("tags", {}).get("barrier") == "toll_booth"]

    def estimate_mp(road, lat, lon):
        """Milepost by projecting onto the line through the two nearest exits with different numbers
        (extrapolating past them when the gantry lies beyond both)."""
        numeric = sorted(((metres(lat, lon, jl, jo), leading_mp(r), jl, jo) for r, jl, jo in junctions[road]
                          if leading_mp(r) is not None), key=lambda x: x[0])
        if not numeric:
            return None
        _, m1, a_lat, a_lon = numeric[0]
        other = next((x for x in numeric[1:] if x[1] != m1), None)
        if other is None:
            return m1
        _, m2, b_lat, b_lon = other
        k = math.cos(math.radians(lat))
        ax, ay = (b_lon - a_lon) * k, b_lat - a_lat
        px, py = (lon - a_lon) * k, lat - a_lat
        t = (px * ax + py * ay) / (ax * ax + ay * ay)
        return m1 + (m2 - m1) * t

    owner, why, unassigned = {}, {}, []
    for n in tolls:
        nid = n["id"]
        if nid in OVERRIDES:
            road, row, reason = OVERRIDES[nid]
            owner[nid], why[nid] = (road, row), "override: " + reason
            continue
        if nid in EXCLUDED:
            unassigned.append({"osm_node": nid, "lat": n["lat"], "lon": n["lon"], "name": n.get("tags", {}).get("name"),
                               "why": "excluded: " + EXCLUDED[nid]})
            continue
        if nid in cfx_nodes:
            continue
        through = ways_of_node.get(nid, set())
        on_road = {road_of_way(w) for w in through} - {None}
        on_link = any(hw(w).endswith("_link") for w in through)
        name = n.get("tags", {}).get("name")
        if on_road:
            road = sorted(on_road)[0]
            mp, source = leading_mp(name), "gantry name"
            if mp is None:
                mp, source = estimate_mp(road, n["lat"], n["lon"]), "nearest exits"
            plazas = [p for p in rows.values() if p["road"] == road and p["plaza_type"] == "mainline"
                      and (road, p["name"]) not in SHARED]
            best = min(((abs(leading_mp(p["number"]) - mp), p["toll_by_plate"]["2_axle"], p["name"]) for p in plazas),
                       default=None) if mp is not None else None
            if best and best[0] <= MP_TOLERANCE:
                owner[nid] = (road, best[2])
                why[nid] = f"mainline of {road}; milepost {mp:.1f} from the {source}"
            else:
                unassigned.append({"osm_node": nid, "lat": n["lat"], "lon": n["lon"], "name": name, "road": road,
                                   "why": f"mainline gantry, milepost {mp} matches no plaza within {MP_TOLERANCE}"})
            continue
        if on_link:
            near = []
            for w in ways.values():
                r = road_of_way(w["id"])
                if r and hw(w["id"]) in ("motorway", "trunk"):
                    near.append((way_dist(n["lat"], n["lon"], w), r))
            if not near:
                continue
            dist, road = min(near)
            if dist > 600:
                continue
            exits = sorted((metres(n["lat"], n["lon"], jl, jo), ref) for ref, jl, jo in junctions[road])
            if not exits or exits[0][0] > JUNCTION_REACH_M:
                unassigned.append({"osm_node": nid, "lat": n["lat"], "lon": n["lon"], "name": name, "road": road,
                                   "why": "ramp gantry with no numbered exit within reach"})
                continue
            ref = exits[0][1]
            match = [p for p in rows.values() if p["road"] == road and p["plaza_type"] == "ramp"
                     and ref in ref_keys(p["number"])]
            if len(match) == 1:
                owner[nid] = (road, match[0]["name"])
                why[nid] = f"ramp at exit {ref} of {road} ({exits[0][0]:.0f} m)"
            else:
                unassigned.append({"osm_node": nid, "lat": n["lat"], "lon": n["lon"], "name": name, "road": road,
                                   "why": f"ramp gantry nearest exit {ref} ({exits[0][0]:.0f} m) is "
                                          f"{'not a tolled row' if not match else 'ambiguous'}"})

    # Movement-priced rows sharing one gantry (I-4 Connector S/Z/T moves): the cheapest takes it.
    by_number = defaultdict(list)
    for p in rows.values():
        by_number[(p["road"], p["number"], p["plaza_type"])].append(p)
    for nid, (road, row) in list(owner.items()):
        p = rows[(road, row)]
        same = by_number[(road, p["number"], p["plaza_type"])]
        if len(same) > 1:
            cheapest = min(same, key=lambda q: (q["toll_by_plate"]["2_axle"], q["name"]))
            owner[nid] = (road, cheapest["name"])
            why[nid] += (f"; one gantry prices {len(same)} movements - assigned the cheapest "
                         f"({cheapest['name']}, estimate errs low)")

    link_ids = {w for w in ways if hw(w) == "motorway_link" or hw(w) == "trunk_link"}

    def crossing_road(wid):
        return re.fullmatch(f"({CROSSING_ROADS})(_link)?", hw(wid)) is not None

    def ramp_geometry(lat, lon, member_nodes):
        seeds = {w for n in member_nodes for w in ways_of_node.get(n, ()) if w in link_ids or hw(w) in ("motorway", "trunk")}
        ramp_ways, queue = set(seeds), [w for w in seeds if w in link_ids]
        while queue:
            w = ways[queue.pop()]
            for n in w.get("nodes", []):
                for other in ways_of_node.get(n, ()):
                    if other in link_ids and other not in ramp_ways and way_dist(lat, lon, ways[other]) <= RAMP_CLIP_M:
                        ramp_ways.add(other)
                        queue.append(other)
        ramp = [line for w in sorted(ramp_ways) for line in clip(ways[w]["geometry"], lat, lon, RAMP_CLIP_M)]
        for radius in (MAINLINE_CLIP_M, 2 * MAINLINE_CLIP_M):
            mainline = [line for w in sorted(ways) if hw(w) in ("motorway", "trunk") and w not in ramp_ways
                        for line in clip(ways[w]["geometry"], lat, lon, radius)]
            if mainline:
                cross = [line for w in sorted(ways) if crossing_road(w)
                         for line in clip(ways[w]["geometry"], lat, lon, RAMP_CLIP_M + 50)]
                return ramp, mainline + cross, radius
        return ramp, [], None

    def node_bearings(nid):
        """Compass bearing of travel through a node, one per way that carries it (from the way's own order)."""
        out = []
        for wid in ways_of_node.get(nid, ()):
            w = ways[wid]
            if not hw(wid).startswith(("motorway", "trunk")) or "geometry" not in w:
                continue
            i = w["nodes"].index(nid)
            a, b = w["geometry"][max(i - 1, 0)], w["geometry"][min(i + 1, len(w["geometry"]) - 1)]
            k = math.cos(math.radians(a["lat"]))
            out.append(math.degrees(math.atan2((b["lon"] - a["lon"]) * k, b["lat"] - a["lat"])) % 360)
        return out

    cfx_by_name = {(p["road"], p["name"]): p for p in cfx["plazas"]}
    out_plazas, missing_geometry = [], []
    for p in card["plazas"]:
        key = (p["road"], p["name"])
        row = {"name": p["name"], "number": p["number"], "road": p["road"], "plaza_type": p["plaza_type"]}
        clusters_out = []
        if key in SHARED:
            facility, croad, cname = SHARED[key]
            partner = cfx_by_name[(croad, cname)]
            for c in partner["clusters"]:
                clusters_out.append({"lat": c["lat"], "lon": c["lon"], "osm_nodes": c.get("osm_nodes", []),
                                     "match": f"shared plaza: CFX charges its portion at the same gantries",
                                     "shared_with": {"facility": facility, "plaza": f"{croad}|{cname}"}})
            row["clusters"] = clusters_out
            out_plazas.append(row)
            continue
        members = sorted(n for n, o in owner.items() if o == key)
        remaining, clusters = list(members), []
        while remaining:
            group, i = [remaining.pop(0)], 0
            while i < len(group):
                a = nodes[group[i]]
                near = [m for m in remaining if metres(a["lat"], a["lon"], nodes[m]["lat"], nodes[m]["lon"]) < CLUSTER_M]
                for m in near:
                    remaining.remove(m)
                group += near
                i += 1
            clusters.append(sorted(group))
        for c in clusters:
            lat = round(sum(nodes[n]["lat"] for n in c) / len(c), 6)
            lon = round(sum(nodes[n]["lon"] for n in c) / len(c), 6)
            entry = {"lat": lat, "lon": lon, "osm_nodes": c, "match": "; ".join(sorted({why[n] for n in c}))}
            if key in DIRECTIONAL:
                bearings = [b for n in c for b in node_bearings(n)]
                if not bearings:
                    sys.exit(f"{p['name']}: one-direction plaza, but no gantry of cluster {c} is on a way")
                mean = math.degrees(math.atan2(sum(math.sin(math.radians(b)) for b in bearings),
                                               sum(math.cos(math.radians(b)) for b in bearings))) % 360
                if any(abs((b - mean + 180) % 360 - 180) > BEARING_AGREE_DEG for b in bearings):
                    sys.exit(f"{p['name']}: one-direction plaza, but cluster {c} stands on ways facing {bearings}")
                entry["charged_direction"] = DIRECTIONAL[key]
                entry["charged_bearing"] = round(mean)
                entry["direction_source"] = DIRECTION_SOURCE
            if p["plaza_type"] == "ramp":
                entry["ramp_lines"], entry["mainline_lines"], radius = ramp_geometry(lat, lon, c)
                if radius and radius != MAINLINE_CLIP_M:
                    entry["mainline_clip_m"] = radius
                if not entry["ramp_lines"] or not entry["mainline_lines"]:
                    missing_geometry.append(f"{p['road']} | {p['name']} {lat},{lon}")
            clusters_out.append(entry)
        row["clusters"] = clusters_out
        out_plazas.append(row)

    # Every OSM price tag that disagrees with the row its node was given. Not acted on - OSM prices go stale
    # (Leesburg's still shows the pre-Minneola 2.94) - but each is a place a reviewer should look.
    charge_review = []
    for nid, key in sorted(owner.items()):
        off = charge_disagrees(nodes[nid].get("tags", {}), rows[key])
        if off:
            charge_review.append({"osm_node": nid, "row": f"{key[0]} | {key[1]}", "why": off})

    too_spread = []
    for p in out_plazas:
        cl = p["clusters"]
        spread = max((metres(a["lat"], a["lon"], b["lat"], b["lon"]) for a in cl for b in cl), default=0)
        if p["plaza_type"] == "mainline" and spread > SPREAD_MAX_M:
            if (p["road"], p["name"]) in MULTI_SITE:
                p["multi_site"] = MULTI_SITE[(p["road"], p["name"])]
            else:
                too_spread.append(f"{p['road']} | {p['name']}: {spread:.0f} m")
    if too_spread:
        sys.exit(f"mainline plazas mapped at sites more than {SPREAD_MAX_M} m apart (a through vehicle passes "
                 f"all of them): {too_spread} - find the wrong site, or list the plaza in MULTI_SITE with why")

    unmatched = [f"{p['road']} | {p['name']}" for p in out_plazas if not p["clusters"]]
    out = {"facility": card["facility"], "card_valid_from": card["valid_from"], "osm_base": osm_base,
           "generator": "scripts/toll_research/build_fte_plazas.py", "cluster_m": CLUSTER_M,
           "ramp_clip_m": RAMP_CLIP_M, "mainline_clip_m": MAINLINE_CLIP_M, "mp_tolerance": MP_TOLERANCE,
           "plazas": out_plazas,
           "review": {"plazas_without_location": unmatched,
                      "toll_nodes_not_assigned": unassigned,
                      "osm_price_disagrees_with_row": charge_review,
                      "ramp_clusters_without_geometry": missing_geometry,
                      "roads_without_osm_ways": sorted(set(ROADS) - set(road_ways))}}
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    located = sum(1 for p in out_plazas if p["clusters"])
    print(f"{located}/{len(out_plazas)} rows located; {len(unassigned)} toll nodes on FTE roads not assigned; "
          f"{len(missing_geometry)} ramps without geometry; roads without ways {out['review']['roads_without_osm_ways']}; "
          f"osm_base {osm_base} -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
