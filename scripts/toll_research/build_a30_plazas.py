#!/usr/bin/env python3
"""Locates the A-30 toll station (pont P-10942) on the map, for gantry-passage pricing.

Output: docs/toll-pricing/data/gantries/a30.json - the card's one row with the OSM toll booths that
charge it, clustered (booths within CLUSTER_M are one toll point).

The A-30's only toll is the station on the Saint Lawrence crossing, charged in both directions at the
same rate. Its booths are OSM barrier=toll_booth nodes on the tolled A-30 ways (toll=yes) between
Salaberry-de-Valleyfield and Beauharnois. The build refuses to write unless exactly one cluster is
found, and unless every booth lies on a tolled way, so a map change cannot add or move a charge
silently.

  python scripts/toll_research/build_a30_plazas.py [--cache DIR]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_cfx_plazas import load, metres  # noqa: E402

CARD_DIR = os.path.join("docs", "toll-pricing", "data", "rate-cards")
FACILITIES = os.path.join("docs", "toll-pricing", "data", "facilities.json")
OUT = os.path.join("docs", "toll-pricing", "data", "gantries", "a30.json")

BBOX = "45.15,-74.25,45.40,-73.80"   # the A-30 between Hwy 20/A-20 and Beauharnois
CLUSTER_M = 150
ROW = ("A-30", "Pont P-10942")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache the Overpass response in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)
    entry = next(f for f in json.load(open(FACILITIES, encoding="utf-8"))["facilities"] if f["id"] == "a30")
    cards = [json.load(open(os.path.join(CARD_DIR, c), encoding="utf-8")) for c in entry["cards"]]
    card = max(cards, key=lambda c: c["valid_from"])
    rows = [(p["road"], p["name"]) for p in card["plazas"]]
    if rows != [ROW]:
        sys.exit(f"A-30: the card lists {rows}, not the one toll station {ROW} - update this builder")

    q = (f"[out:json][timeout:180];way({BBOX})[highway][toll=yes][ref~\"(^|;)30(;|$)\"]->.t;"
         ".t out body geom;node(w.t)[barrier=toll_booth];out;node(w.t)[highway=toll_gantry];out;")
    data = load(args.cache, "a30_tolls.json", q)
    osm_base = data.get("osm3s", {}).get("timestamp_osm_base")
    ways = [e for e in data["elements"] if e["type"] == "way"]
    booths = {e["id"]: e for e in data["elements"] if e["type"] == "node"}
    if not ways or not booths:
        sys.exit(f"A-30: {len(ways)} tolled ways, {len(booths)} booths - the map changed")
    on_tolled = {n for w in ways for n in w.get("nodes", [])}
    if set(booths) - on_tolled:
        sys.exit(f"A-30: booths not on a tolled A-30 way: {sorted(set(booths) - on_tolled)}")

    ids = sorted(booths)
    clusters, remaining = [], list(ids)
    while remaining:
        group, i = [remaining.pop(0)], 0
        while i < len(group):
            a = booths[group[i]]
            near = [m for m in remaining if metres(a["lat"], a["lon"], booths[m]["lat"], booths[m]["lon"]) < CLUSTER_M]
            for m in near:
                remaining.remove(m)
            group += near
            i += 1
        clusters.append(sorted(group))
    if len(clusters) != 1:
        sys.exit(f"A-30: {len(clusters)} booth clusters {clusters}, expected the one toll station")
    c = clusters[0]
    lat = round(sum(booths[n]["lat"] for n in c) / len(c), 6)
    lon = round(sum(booths[n]["lon"] for n in c) / len(c), 6)
    out = {"facility": card["facility"], "card_valid_from": card["valid_from"], "osm_base": osm_base,
           "generator": "scripts/toll_research/build_a30_plazas.py", "cluster_m": CLUSTER_M,
           "plazas": [{"name": ROW[1], "road": ROW[0], "plaza_type": "mainline",
                       "clusters": [{"lat": lat, "lon": lon, "osm_nodes": c,
                                     "match": "toll booths on the tolled A-30 ways (the only toll station)"}]}],
           "review": {"plazas_without_location": [], "booth_tags": {str(n): booths[n].get("tags", {}) for n in c}}}
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    print(f"1 toll station at {lat},{lon} from booths {c}; osm_base {osm_base} -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
