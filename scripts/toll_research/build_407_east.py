#!/usr/bin/env python3
"""Builds the Highway 407 East geometry the 407 pricer uses to recognise the toll-free continuation.

Output: docs/toll-pricing/data/corridors/407-east.json - every carriageway of OSM route relation 6328033
(Highway 407 East, the MTO's section from Brock Road to Highway 35/115) as [lat, lon] lines.

Why: its positions do not stay near the ETR corridor's extended last segment (up to 2.6 km off it by
Highway 35/115, past the corridor's 2 km projection limit), so without its own shape a traversal there
would be OFF_CORRIDOR instead of NOT_BILLABLE.

The build refuses to write unless the relation is still CA:ON:primary motorway, starts within 1.5 km of
the ETR corridor's last interchange (Brock Road) and carries no toll=yes way east of it.

  python scripts/toll_research/build_407_east.py [--cache DIR]
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_cfx_plazas import load, metres  # noqa: E402

RELATION = 6328033
CORRIDOR = os.path.join("docs", "toll-pricing", "data", "corridors", "407-etr.json")
OUT = os.path.join("docs", "toll-pricing", "data", "corridors", "407-east.json")
BROCK_REACH_M = 1500
TOLL_YES_ALLOWED_M = 1200   # ETR's own Brock Road toll ways run onto the relation's first metres


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache the Overpass response in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)
    corridor = json.load(open(CORRIDOR, encoding="utf-8"))
    brock = corridor["interchanges"][-1]
    if brock["name"] != "Brock Road":
        sys.exit(f"407 East: the corridor ends at {brock['name']!r}, not Brock Road")

    q = f"[out:json][timeout:180];relation({RELATION});out tags;way(r);out tags geom;"
    data = load(args.cache, "r6328033.json", q)
    rel = next(e for e in data["elements"] if e["type"] == "relation")
    if rel["tags"].get("network") != "CA:ON:primary" or rel["tags"].get("ref") != "407":
        sys.exit(f"407 East: relation {RELATION} is now {rel['tags']}")
    ways = [e for e in data["elements"] if e["type"] == "way"]
    if any(w["tags"].get("highway") != "motorway" for w in ways):
        sys.exit("407 East: a member way is no longer a motorway")
    lines = [[[round(p["lat"], 6), round(p["lon"], 6)] for p in w["geometry"]] for w in ways]
    nearest = min(metres(brock["lat"], brock["lon"], p[0], p[1]) for line in lines for p in line)
    if nearest > BROCK_REACH_M:
        sys.exit(f"407 East: nearest node is {nearest:.0f} m from Brock Road")
    for w in ways:
        if w["tags"].get("toll") == "yes":
            far = max(metres(brock["lat"], brock["lon"], p["lat"], p["lon"]) for p in w["geometry"])
            if far > TOLL_YES_ALLOWED_M:
                sys.exit(f"407 East: way {w['id']} is toll=yes {far:.0f} m from Brock Road - tolls are back?")
    out = {"facility": "Highway 407 East (MTO)", "osm_route_relation": RELATION,
           "osm_base": data.get("osm3s", {}).get("timestamp_osm_base"),
           "generator": "scripts/toll_research/build_407_east.py",
           "note": "Toll-free since 2025-06-01. Used only to recognise the continuation past Brock Road.",
           "lines": lines}
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, separators=(",", ":"))
        fh.write("\n")
    print(f"{len(lines)} ways, {sum(len(x) for x in lines)} nodes, nearest {nearest:.0f} m from Brock -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
