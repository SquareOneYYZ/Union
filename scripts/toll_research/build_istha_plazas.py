#!/usr/bin/env python3
"""Locates every Illinois Tollway rate-card plaza on the map, for gantry-passage pricing.

Output: docs/toll-pricing/data/gantries/istha.json - one entry per card plaza with the OSM toll
nodes that charge it, clustered (nodes of one plaza within 150 m are one toll point: per-direction
gantries are separate nodes, decision 23 / gap 8), plus a review list.

Inputs:
  * the Tollway rate cards (build_rate_cards.py --istha); both years must list the same plazas
  * OSM via our Overpass host: every toll_gantry (not the Skyway's or Indiana's) and Tollway
    toll_booth in the Tollway's bounding box, the ways through them, and the motorways and
    motorway_links within 450 m.

Matching is by plaza NUMBER: the Tollway's OSM gantries carry it as `ref` (unlike CFX, where
names had to be scored). The node's name is cross-checked against the card and every mismatch is
listed for review. Nodes without a ref are matched by name. A number the card uses twice is an
interchange priced per movement ("Genoa Road (EB Exit)" / "(WB Exit)"): the ramp's own geometry
decides which - an exit ramp leaves the motorway upstream of the booth, an entrance joins it
downstream, and the motorway's heading there gives EB/WB. When the geometry cannot decide, the
cluster goes to the cheaper movement (the estimate errs low, never high). OVERRIDES pins what the
tags get wrong, each with its reason.

Ramp clusters carry ramp_lines and mainline_lines for the "prove it from the track" rule
(PassageDetector): a ramp is charged only when a fix lies on the ramp and clear of the mainline.

  python scripts/toll_research/build_istha_plazas.py [--cache DIR]
"""
import argparse
import json
import math
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_cfx_plazas import clip, load, metres, way_dist  # noqa: E402

CARDS = [os.path.join("docs", "toll-pricing", "data", "rate-cards", f"istha-{y}-01-01.json")
         for y in (2026, 2027)]
OUT = os.path.join("docs", "toll-pricing", "data", "gantries", "istha.json")

BBOX = "41.40,-89.80,42.55,-87.50"
CLUSTER_M = 150
RAMP_CLIP_M = 250       # ramp geometry kept within this of the booth
MAINLINE_CLIP_M = 400   # mainline geometry kept within this of the booth
WALK_LINKS = 12         # how many link ways to follow when deciding exit vs entrance

# The Tollway prices plaza 15 (I-290, Illinois 53) once; OSM numbers its two ramp groups 15A/15B.
REF_ALIASES = {"15A": "15", "15B": "15"}

GENERIC = {"toll", "plaza", "road", "rd", "street", "st", "avenue", "ave", "boulevard", "blvd",
           "drive", "dr", "lane", "mainline", "illinois", "route", "il", "us", "u", "s", "i",
           "interstate", "the", "eb", "wb", "nb", "sb", "exit", "entrance", "ent", "eastbound",
           "westbound", "northbound", "southbound", "and"}

# Pins for what the tags get wrong or leave out: OSM node id -> (plaza number, reason). The pin
# replaces the node's ref before clustering, so per-movement plazas still go through the ramp test,
# unless a third element names the plaza outright.
# Located 2026-11-06 from the named roads within 300 m and the ramps' destination tags.
_IL47 = "untagged I-90 ramp gantry at the IL 47 (Huntley) interchange; its link is signed IL 47 / I 90"
_GENOA = "named 'Belvidere Genoa Road', no ref; on the I-90 ramps at Genoa Road"
_GENOA_WB = (_GENOA + "; node 6779977879's link is signed 'Belvidere' and leaves I-90 westward: the WB exit "
             "(its partner sits on an unsigned loop, so the ramp walk sees WB Entrance too)")
_BARRINGTON = "untagged I-90 ramp gantry 300-600 m from Barrington Road; no other interchange there"
_WINFIELD = "untagged I-88 ramp gantries beside Winfield Road; the card's Winfield Road plaza has no other node"
OVERRIDES = {
    5078243653: ("6", _IL47), 5079368630: ("6", _IL47), 14169538900: ("6", _IL47), 14169575401: ("6", _IL47),
    6779977827: ("3", _GENOA_WB, "Genoa Road (WB Exit)"), 6779977879: ("3", _GENOA_WB, "Genoa Road (WB Exit)"),
    6779977886: ("3", _GENOA), 6779977888: ("3", _GENOA),
    14169529227: ("10", _BARRINGTON), 14169570018: ("10", _BARRINGTON),
    4678091831: ("58", _WINFIELD), 4678106025: ("58", _WINFIELD),
    856809363: ("12", "tagged ref 11 (Illinois Route 31) but named Roselle Road and on the I-90 ramps at "
                      "Roselle Road, 15 km east of IL 31"),
    5209121983: ("42", "tagged ref 40 (U.S. 6, 159th Street) but named Illinois 83 Toll Plaza and 20 m from "
                       "plaza 42's gantries at 147th Street, 2.5 km north of 159th; the ramp test charged it as 42"),
}


def tokens(text):
    text = (text or "").lower().replace("halstead", "halsted")
    return {t for t in re.split(r"[^a-z0-9]+", text) if t and t not in GENERIC}


def movements(name):
    """'Genoa Road (EB Exit)' -> {'EB Exit'}; '(EB Ent/WB Exit)' -> {'EB Entrance', 'WB Exit'}."""
    m = re.search(r"\(([^)]*)\)", name)
    if not m:
        return set()
    out = set()
    for part in m.group(1).split("/"):
        part = part.strip().replace("Ent", "Entrance").replace("Entrancerance", "Entrance")
        if re.fullmatch(r"(EB|WB|NB|SB) (Exit|Entrance)", part):
            out.add(part)
    return out


def bearing_label(a, b):
    dx = (b[1] - a[1]) * math.cos(math.radians(a[0]))
    dy = b[0] - a[0]
    if abs(dx) >= abs(dy):
        return "EB" if dx > 0 else "WB"
    return "NB" if dy > 0 else "SB"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache Overpass responses in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)

    cards = [json.load(open(p, encoding="utf-8")) for p in CARDS]
    keys = [sorted((p["road"], p["name"], p["number"], p["plaza_type"]) for p in c["plazas"]) for c in cards]
    if any(k != keys[0] for k in keys):
        sys.exit("the 2026 and 2027 cards list different plazas - regenerate per card")
    card = cards[-1]
    plazas = card["plazas"]

    toll_set = (f'node({BBOX})[highway=toll_gantry][operator!~"Skyway|Indiana"]->.g;'
                f'node({BBOX})[barrier=toll_booth][operator="Illinois Tollway"]->.b;'
                f'node({BBOX})[barrier=toll_booth][!operator][!name]->.u;(.g;.b;.u;)->.t;')
    cached = args.cache and os.path.exists(os.path.join(args.cache, "istha_motorways.json"))
    tolls = load(args.cache, "istha_tolls.json", f"[out:json][timeout:600];{toll_set}.t out;way(bn.t);out body geom;")
    if not cached:
        time.sleep(20)   # our Overpass host rate-limits back-to-back queries
    motorways = load(args.cache, "istha_motorways.json",
                     f'[out:json][timeout:600];{toll_set}way(around.t:450)[highway~"^motorway(_link)?$"];'
                     "out body geom;")
    osm_base = tolls.get("osm3s", {}).get("timestamp_osm_base")

    ways = {e["id"]: e for e in tolls["elements"] if e["type"] == "way"}
    ways.update({e["id"]: e for e in motorways["elements"] if e["type"] == "way"})
    coords = {}
    for w in ways.values():
        for n, g in zip(w.get("nodes", []), w.get("geometry", [])):
            coords[n] = (g["lat"], g["lon"])
    ways_of_node = {}
    for w in ways.values():
        for n in w.get("nodes", []):
            ways_of_node.setdefault(n, set()).add(w["id"])

    def highway(wid):
        return ways[wid].get("tags", {}).get("highway")

    motorway_nodes = {n for w in ways.values() if w.get("tags", {}).get("highway") == "motorway"
                      for n in w.get("nodes", [])}
    starts, ends = {}, {}
    for w in ways.values():
        if highway(w["id"]) == "motorway_link" and w.get("nodes"):
            starts.setdefault(w["nodes"][0], []).append(w["id"])
            ends.setdefault(w["nodes"][-1], []).append(w["id"])

    nodes = {}
    for e in tolls["elements"]:
        if e["type"] != "node":
            continue
        t = e.get("tags", {})
        on_road = any(highway(w) in ("motorway", "motorway_link", "trunk", "trunk_link", "primary")
                      for w in ways_of_node.get(e["id"], ()))
        if t.get("barrier") == "toll_booth" and not t.get("operator") and not on_road:
            continue   # unnamed booths off the road network: parking, attractions
        ref = OVERRIDES[e["id"]][0] if e["id"] in OVERRIDES else t.get("ref")
        e["key"] = REF_ALIASES.get(ref, ref) if ref else ("name:" + t["name"] if t.get("name") else None)
        nodes[e["id"]] = e

    # Cluster nodes that share a key (plaza number, or name when untagged) within CLUSTER_M.
    remaining, clusters = sorted(nodes), []
    while remaining:
        seed = remaining.pop(0)
        group, i = [seed], 0
        while i < len(group):
            a = nodes[group[i]]
            near = [n for n in remaining if nodes[n]["key"] == a["key"]
                    and metres(a["lat"], a["lon"], nodes[n]["lat"], nodes[n]["lon"]) < CLUSTER_M]
            for n in near:
                remaining.remove(n)
            group += near
            i += 1
        clusters.append(sorted(group))

    def movement(c):
        """{'EB Exit', ...}: the movements a ramp cluster's link ways make, from the ramp geometry."""
        booth_links = {w for n in c for w in ways_of_node.get(n, ()) if highway(w) == "motorway_link"}
        found = set()
        for direction in ("up", "down"):
            frontier, seen = list(booth_links), set(booth_links)
            for _ in range(WALK_LINKS):
                nxt = []
                for wid in frontier:
                    w = ways[wid]["nodes"]
                    joint = w[0] if direction == "up" else w[-1]
                    if joint in motorway_nodes:
                        for mw in ways_of_node[joint]:
                            if highway(mw) != "motorway":
                                continue
                            mn = ways[mw]["nodes"]
                            k = mn.index(joint)
                            a, b = coords[mn[max(0, k - 1)]], coords[mn[min(len(mn) - 1, k + 1)]]
                            found.add(bearing_label(a, b) + (" Exit" if direction == "up" else " Entrance"))
                        continue
                    for other in (ends if direction == "up" else starts).get(joint, []):
                        if other not in seen:
                            seen.add(other)
                            nxt.append(other)
                frontier = nxt
        return found

    by_number = {}
    for pi, p in enumerate(plazas):
        by_number.setdefault(p["number"], []).append(pi)

    info, owner, notes, review_names = [], {}, {}, []
    for ci, c in enumerate(clusters):
        lat = sum(nodes[n]["lat"] for n in c) / len(c)
        lon = sum(nodes[n]["lon"] for n in c) / len(c)
        key = nodes[c[0]]["key"]
        names = sorted({nodes[n].get("tags", {}).get("name", "") for n in c} - {""})
        through = {w for n in c for w in ways_of_node.get(n, ())}
        on_motorway = any(highway(w) == "motorway" for w in through)
        on_link = any(highway(w) == "motorway_link" for w in through)
        info.append({"nodes": c, "lat": round(lat, 6), "lon": round(lon, 6), "key": key, "names": names,
                     "on_motorway": on_motorway, "on_link": on_link})
        name_tokens = set().union(*(tokens(n) for n in names)) if names else set()
        candidates = []
        pins = sorted({OVERRIDES[n][1] for n in c if n in OVERRIDES})
        if pins:
            notes[ci] = "override: " + "; ".join(pins)
        if key and not key.startswith("name:"):
            candidates = by_number.get(key, [])
            if candidates and name_tokens and not pins and not any(name_tokens & tokens(plazas[pi]["name"]) for pi in candidates):
                review_names.append({"osm_nodes": c, "lat": info[-1]["lat"], "lon": info[-1]["lon"],
                                     "ref": key, "osm_names": names,
                                     "card": [plazas[pi]["name"] for pi in candidates]})
        elif name_tokens:
            scored = sorted(((len(name_tokens & tokens(p["name"])), pi) for pi, p in enumerate(plazas)), reverse=True)
            if scored[0][0] and (len(scored) == 1 or scored[0][0] > scored[1][0]
                                 or plazas[scored[0][1]]["number"] == plazas[scored[1][1]]["number"]):
                candidates = by_number[plazas[scored[0][1]]["number"]]
                notes[ci] = f"name match ({names[0]})"
        if not candidates:
            continue
        named = {OVERRIDES[n][2] for n in c if n in OVERRIDES and len(OVERRIDES[n]) > 2}
        if named:
            candidates = [pi for pi in candidates if plazas[pi]["name"] in named]
            if len(candidates) != 1:
                sys.exit(f"override names {named}, matching {len(candidates)} card plazas")
        if len(candidates) == 1:
            owner[ci] = candidates[0]
            notes.setdefault(ci, f"ref {key}" if not key.startswith("name:") else notes.get(ci))
            continue
        move = movement(c)
        label = "/".join(sorted(move)) or "undetermined"
        pick = [pi for pi in candidates if move and move <= movements(plazas[pi]["name"])]
        if len(pick) == 1:
            owner[ci] = pick[0]
            notes[ci] = notes.get(ci, f"ref {key}") + f"; ramp geometry: {label}"
        else:
            cheapest = min(candidates, key=lambda pi: (plazas[pi]["pay_by_plate"]["passenger"], pi))
            owner[ci] = cheapest
            notes[ci] = (notes.get(ci, f"ref {key}") + f"; movement {label} - "
                         "assigned the cheaper movement (estimate errs low)")

    link_ids = {w for w in ways if highway(w) == "motorway_link"}

    def ramp_geometry(ci):
        c = info[ci]
        seeds = {w for n in c["nodes"] for w in ways_of_node.get(n, ()) if highway(w) in ("motorway_link", "motorway")}
        ramp_ways, queue = set(seeds), [w for w in seeds if w in link_ids]
        while queue:
            w = ways[queue.pop()]
            for n in w.get("nodes", []):
                for other in ways_of_node.get(n, ()):
                    if other in link_ids and other not in ramp_ways \
                            and way_dist(c["lat"], c["lon"], ways[other]) <= RAMP_CLIP_M:
                        ramp_ways.add(other)
                        queue.append(other)
        ramp = [line for w in sorted(ramp_ways) for line in clip(ways[w]["geometry"], c["lat"], c["lon"], RAMP_CLIP_M)]
        mainline = [line for w in sorted(ways) if highway(w) == "motorway" and w not in ramp_ways
                    for line in clip(ways[w]["geometry"], c["lat"], c["lon"], MAINLINE_CLIP_M)]
        return ramp, mainline

    out_plazas, missing_ramp_geometry, type_mismatch = [], [], []
    for pi, p in enumerate(plazas):
        clusters_out = []
        mine = sorted(c for c, o in owner.items() if o == pi)
        plaza_type, type_note = p["plaza_type"], None
        if plaza_type == "mainline" and mine and not any(info[ci]["on_motorway"] for ci in mine):
            # Distance to a gantry on a ramp would charge passing mainline traffic: prove it from the track.
            plaza_type = "ramp"
            type_note = "the card calls it mainline, but every gantry sits on a motorway_link: priced as a ramp"
            type_mismatch.append(f"{p['name']} ({p['number']}): {type_note}")
        for ci in mine:
            c = info[ci]
            entry = {"lat": c["lat"], "lon": c["lon"], "osm_nodes": c["nodes"], "match": notes.get(ci)}
            if plaza_type == "ramp":
                entry["ramp_lines"], entry["mainline_lines"] = ramp_geometry(ci)
                if not entry["ramp_lines"] or not entry["mainline_lines"]:
                    missing_ramp_geometry.append(f"{p['name']} ({p['number']}) {c['lat']},{c['lon']}")
            elif not c["on_motorway"]:
                type_mismatch.append(f"card mainline {p['name']} ({p['number']}) has a cluster off the motorway "
                                     f"{c['lat']},{c['lon']} (on link: {c['on_link']})")
            clusters_out.append(entry)
        row = {"name": p["name"], "number": p["number"], "road": p["road"], "plaza_type": plaza_type}
        if type_note:
            row["plaza_type_note"] = type_note
        row["clusters"] = clusters_out
        out_plazas.append(row)
    unmatched = [f"{p['name']} ({p['number']}, {p['road']})" for p in out_plazas if not p["clusters"]]
    unclaimed = [{"lat": c["lat"], "lon": c["lon"], "key": c["key"], "osm_names": c["names"],
                  "osm_nodes": c["nodes"], "on_motorway": c["on_motorway"], "on_link": c["on_link"]}
                 for ci, c in enumerate(info) if ci not in owner and (c["on_motorway"] or c["on_link"])]

    out = {"facility": card["facility"], "card_valid_from": card["valid_from"], "osm_base": osm_base,
           "generator": "scripts/toll_research/build_istha_plazas.py", "cluster_m": CLUSTER_M,
           "ramp_clip_m": RAMP_CLIP_M, "mainline_clip_m": MAINLINE_CLIP_M,
           "plazas": out_plazas,
           "review": {"plazas_without_location": unmatched,
                      "osm_name_differs_from_card": review_names,
                      "toll_clusters_without_plaza": unclaimed,
                      "card_type_differs_from_map": type_mismatch,
                      "ramp_clusters_without_geometry": missing_ramp_geometry}}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    located = sum(1 for p in out_plazas if p["clusters"])
    print(f"{located}/{len(out_plazas)} plazas located; {len(unmatched)} without location; "
          f"{len(review_names)} name mismatches; {len(unclaimed)} clusters unclaimed; "
          f"{len(type_mismatch)} card-type mismatches; {len(missing_ramp_geometry)} ramps without geometry; "
          f"osm_base {osm_base} -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
