#!/usr/bin/env python3
"""Locates the E-470 and Northwest Parkway rate-card plazas on the map, for gantry-passage pricing.

Output: docs/toll-pricing/data/gantries/e470.json and nwp.json - one entry per card plaza with the
OSM toll gantries that charge it, clustered (gantries of one plaza within 150 m are one toll point),
plus a review list.

Inputs:
  * the cards (build_rate_cards.py --e470 --nwp)
  * OSM via our Overpass host: every toll_gantry in the Denver bounding box, the ways through them,
    and the motorways and motorway_links within 450 m.

Both cards price by plaza CLASS, not by interchange: E-470 publishes Plazas A-E and one rate for
every toll ramp; the Northwest Parkway one mainline and one rate for its US 287 and Sheridan ramps.
So a gantry's plaza follows from where it stands:
  * on the E-470 / Northwest Parkway motorway: a mainline plaza, named by the gantry's name
    ("Plaza B NB" -> Plaza B; the Northwest Parkway has one mainline plaza). OVERRIDES pins what
    the tags leave out.
  * on a motorway_link whose nearest toll motorway is E-470 or the Northwest Parkway: that
    facility's ramp plaza. The interchange (from the gantry's name, or the nearest named gantry)
    is kept as a note only.
  * anywhere else (CDOT express lanes on I-25, parking): not ours; listed if on a motorway.

Ramp clusters carry ramp_lines and mainline_lines for the "prove it from the track" rule
(PassageDetector): a ramp is charged only when a fix lies on the ramp and clear of the mainline.

  python scripts/toll_research/build_e470_plazas.py [--cache DIR]
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_cfx_plazas import clip, load, metres, way_dist  # noqa: E402

CARD_DIR = os.path.join("docs", "toll-pricing", "data", "rate-cards")
OUT_DIR = os.path.join("docs", "toll-pricing", "data", "gantries")

BBOX = "39.45,-105.20,40.10,-104.55"
CLUSTER_M = 150
RAMP_CLIP_M = 250
MAINLINE_CLIP_M = 400
CROSSING_ROADS = "trunk|primary|secondary|tertiary"   # roads a ramp fix must also be clear of
NAME_NOTE_M = 400      # an unnamed ramp gantry borrows the interchange name of a named one this close

FACILITIES = {
    "e470": {"card": "e470-2025-01-01.json", "out": "e470.json", "ramp_plaza": "Toll Ramps",
             "motorway": lambda t: t.get("ref") == "E470" or t.get("name") == "E-470"},
    "nwp": {"card": "nwp-2026-05-01.json", "out": "nwp.json", "ramp_plaza": "Ramps (US 287 and Sheridan)",
            "motorway": lambda t: t.get("name") == "Northwest Parkway" or t.get("ref") == "NW"},
}

# OSM node id -> (facility, plaza, reason). Located 2026-11-06.
OVERRIDES = {
    12072517713: ("e470", "Plaza C", "named only 'Aurora'; the E-470 mainline gantry pair between Plaza B "
                                     "(39.668 N) and Plaza D (39.861 N), where the card's Plaza C stands"),
    12072517714: ("e470", "Plaza C", "named only 'Aurora'; the other direction of the same pair"),
}


def mainline_plaza(facility, name):
    if facility == "nwp":
        return "Mainline"
    m = re.match(r"Plaza ([A-E])\b", name or "")
    return f"Plaza {m.group(1)}" if m else None


def interchange(name):
    """'Gartrell Road Offramp EB' -> 'Gartrell Road'."""
    return re.split(r"\s+(?:On|Off)[- ]?[Rr]amp\b", name or "")[0].strip() or None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache Overpass responses in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)

    cards = {f: json.load(open(os.path.join(CARD_DIR, cfg["card"]), encoding="utf-8"))
             for f, cfg in FACILITIES.items()}

    toll_set = f"node({BBOX})[highway=toll_gantry]->.t;"
    def fetch(name, query):
        if not (args.cache and os.path.exists(os.path.join(args.cache, name))):
            time.sleep(20)   # our Overpass host rate-limits back-to-back queries
        return load(args.cache, name, query)

    tolls = load(args.cache, "e470_tolls.json", f"[out:json][timeout:600];{toll_set}.t out;way(bn.t);out body geom;")
    # 900 m: the US 85 ramp gantries stand ~450 m off E-470, and the mainline fallback reaches 800 m.
    motorways = fetch("e470_motorways_900.json",
                      f'[out:json][timeout:600];{toll_set}way(around.t:900)[highway~"^motorway(_link)?$"];'
                      "out body geom;")
    crossing = fetch("e470_crossing.json",
                     f'[out:json][timeout:600];{toll_set}way(around.t:{RAMP_CLIP_M + 100})'
                     f'[highway~"^({CROSSING_ROADS})(_link)?$"];out body geom;')
    osm_base = tolls.get("osm3s", {}).get("timestamp_osm_base")

    ways = {e["id"]: e for e in tolls["elements"] if e["type"] == "way"}
    ways.update({e["id"]: e for e in motorways["elements"] if e["type"] == "way"})
    ways.update({e["id"]: e for e in crossing["elements"] if e["type"] == "way"})
    ways_of_node = {}
    for w in ways.values():
        for n in w.get("nodes", []):
            ways_of_node.setdefault(n, set()).add(w["id"])

    def highway(wid):
        return ways[wid].get("tags", {}).get("highway")

    def owner_of_motorway(wid):
        t = ways[wid].get("tags", {})
        if t.get("highway") != "motorway":
            return None
        for f, cfg in FACILITIES.items():
            if cfg["motorway"](t):
                return f
        return None

    gantries = [e for e in tolls["elements"] if e["type"] == "node"]
    named = [g for g in gantries if g.get("tags", {}).get("name")]
    assigned, skipped = {}, []
    for g in gantries:
        name = g.get("tags", {}).get("name") or g.get("tags", {}).get("ref")
        through = ways_of_node.get(g["id"], set())
        if g["id"] in OVERRIDES:
            f, plaza, reason = OVERRIDES[g["id"]]
            assigned[g["id"]] = (f, plaza, "override: " + reason)
            continue
        mainline = {owner_of_motorway(w) for w in through} - {None}
        if mainline:
            f = mainline.pop()
            plaza = mainline_plaza(f, name)
            if plaza:
                assigned[g["id"]] = (f, plaza, f"on the {f} motorway, named {name!r}")
            else:
                skipped.append({"osm_node": g["id"], "lat": g["lat"], "lon": g["lon"], "name": name,
                                "why": f"on the {f} motorway but its name names no plaza"})
            continue
        if any(highway(w) == "motorway_link" for w in through):
            nearest = min(((way_dist(g["lat"], g["lon"], w), owner_of_motorway(w["id"]))
                           for w in ways.values() if w.get("tags", {}).get("highway") == "motorway"),
                          key=lambda x: x[0], default=(None, None))
            if nearest[1]:
                place = interchange(name)
                if not place:
                    near = sorted((metres(g["lat"], g["lon"], o["lat"], o["lon"]), interchange(o["tags"]["name"]))
                                  for o in named if o is not g)
                    if near and near[0][0] <= NAME_NOTE_M:
                        place = f"{near[0][1]} (nearest named gantry, {near[0][0]:.0f} m)"
                assigned[g["id"]] = (nearest[1], FACILITIES[nearest[1]]["ramp_plaza"],
                                     f"ramp at {place or 'an unnamed interchange'}; nearest toll motorway "
                                     f"{nearest[1]} at {nearest[0]:.0f} m")
                continue
        on = sorted({f"{highway(w)} {ways[w].get('tags', {}).get('ref') or ways[w].get('tags', {}).get('name')}"
                     for w in through})
        skipped.append({"osm_node": g["id"], "lat": g["lat"], "lon": g["lon"], "name": name,
                        "why": "not on E-470 or the Northwest Parkway or their ramps", "ways": on})

    nodes = {g["id"]: g for g in gantries}
    link_ids = {w for w in ways if highway(w) == "motorway_link"}

    def crossing_road(wid):
        return re.fullmatch(f"({CROSSING_ROADS})(_link)?", highway(wid) or "") is not None

    def ramp_geometry(lat, lon, member_nodes):
        seeds = {w for n in member_nodes for w in ways_of_node.get(n, ()) if highway(w) in ("motorway_link", "motorway")}
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
            # A wide interchange (US 85) keeps its gantries ~450 m off the mainline: reach further
            # rather than leave the ramp unprovable. More mainline only makes "clear of it" stricter.
            mainline = [line for w in sorted(ways) if highway(w) == "motorway" and w not in ramp_ways
                        for line in clip(ways[w]["geometry"], lat, lon, radius)]
            if mainline:
                # The road the ramp meets counts as "mainline" too: a fix proves the ramp only when it
                # is clear of both, so traffic driving past on the crossing road (US 85, whose ramp
                # gantries stand at the far end of the ramps) is never charged.
                cross = [line for w in sorted(ways) if crossing_road(w)
                         for line in clip(ways[w]["geometry"], lat, lon, RAMP_CLIP_M + 50)]
                return ramp, mainline + cross, radius
        return ramp, [], None

    for f, cfg in FACILITIES.items():
        card = cards[f]
        mine = {n: v for n, v in assigned.items() if v[0] == f}
        out_plazas, missing_geometry = [], []
        card_names = {p["name"] for p in card["plazas"]}
        stray = sorted({v[1] for v in mine.values()} - card_names)
        if stray:
            sys.exit(f"{f}: gantries assigned to plazas the card does not list: {stray}")
        for p in card["plazas"]:
            members = sorted(n for n, v in mine.items() if v[1] == p["name"])
            remaining, clusters = list(members), []
            while remaining:
                group, i = [remaining.pop(0)], 0
                while i < len(group):
                    a = nodes[group[i]]
                    near = [n for n in remaining
                            if metres(a["lat"], a["lon"], nodes[n]["lat"], nodes[n]["lon"]) < CLUSTER_M]
                    for n in near:
                        remaining.remove(n)
                    group += near
                    i += 1
                clusters.append(sorted(group))
            clusters_out = []
            for c in clusters:
                lat = round(sum(nodes[n]["lat"] for n in c) / len(c), 6)
                lon = round(sum(nodes[n]["lon"] for n in c) / len(c), 6)
                entry = {"lat": lat, "lon": lon, "osm_nodes": c,
                         "match": "; ".join(sorted({mine[n][2] for n in c}))}
                if p["plaza_type"] == "ramp":
                    entry["ramp_lines"], entry["mainline_lines"], radius = ramp_geometry(lat, lon, c)
                    if radius and radius != MAINLINE_CLIP_M:
                        entry["mainline_clip_m"] = radius
                    if not entry["ramp_lines"] or not entry["mainline_lines"]:
                        missing_geometry.append(f"{p['name']} {lat},{lon}")
                clusters_out.append(entry)
            out_plazas.append({"name": p["name"], "road": p["road"], "plaza_type": p["plaza_type"],
                               "clusters": clusters_out})
        unmatched = [p["name"] for p in out_plazas if not p["clusters"]]
        not_ours = [s for s in skipped if any("motorway" in w for w in s.get("ways", [])) or "why" in s
                    and s["why"].startswith("on the " + f)]
        out = {"facility": card["facility"], "card_valid_from": card["valid_from"], "osm_base": osm_base,
               "generator": "scripts/toll_research/build_e470_plazas.py", "cluster_m": CLUSTER_M,
               "ramp_clip_m": RAMP_CLIP_M, "mainline_clip_m": MAINLINE_CLIP_M,
               "plazas": out_plazas,
               "review": {"plazas_without_location": unmatched,
                          "toll_gantries_not_assigned": not_ours,
                          "ramp_clusters_without_geometry": missing_geometry}}
        path = os.path.join(OUT_DIR, cfg["out"])
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(out, fh, indent=1, ensure_ascii=False)
            fh.write("\n")
        counts = {p["name"]: len(p["clusters"]) for p in out_plazas}
        print(f"{f}: clusters per plaza {counts}; {len(unmatched)} unlocated; "
              f"{len(missing_geometry)} ramps without geometry; osm_base {osm_base} -> {path}")
    print(f"gantries not assigned: {len(skipped)}")
    for s in skipped:
        print("  ", s)


if __name__ == "__main__":
    sys.exit(main())
