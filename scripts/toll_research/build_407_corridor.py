#!/usr/bin/env python3
"""Builds the 407 ETR corridor file the Java pricer projects GPS points onto.

Output: docs/toll-pricing/data/corridors/407-etr.json - every interchange with its exit
number, name, corridor km and position, plus each rate zone as a km range.

Sources, all recorded in the file:
  * positions  - OSM highway=motorway_junction nodes on route relation 109574 (407 ETR),
                 fetched from our Overpass host; osm_base is stamped into the output.
  * km + names - the exit list of en.wikipedia "Ontario Highway 407" (MTO-derived km).
  * zones      - the rate card's zone table (from/to interchange NAMES), mapped to exits
                 by ZONE_BOUNDARY_EXITS below, which is the only hand-curated part.

Ontario exit numbers are rounded km, so a node's exit ref joins it to its exit-list row.
Two nodes usually exist per exit (one per carriageway); their midpoint is the interchange.

  python scripts/toll_research/build_407_corridor.py
"""
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

OVERPASS = os.environ.get("OVERPASS_URL", "http://roadinfo.iotrides.com/api/interpreter")
WIKI = ("https://en.wikipedia.org/w/api.php?action=parse&page=Ontario_Highway_407"
        "&prop=wikitext&format=json&formatversion=2")
UA = "RidesIQ-toll-research/1.0"
RELATION = 109574
CARD = os.path.join("docs", "toll-pricing", "data", "rate-cards", "407-etr-2026-light.json")
OUT = os.path.join("docs", "toll-pricing", "data", "corridors", "407-etr.json")

# Zone boundary names as the rate card prints them -> the exit that interchange carries.
# Curated by reading the exit list; every other value in the output is derived.
ZONE_BOUNDARY_EXITS = {
    "QEW": "1", "Dundas": "5", "Neyagawa": "18", "Highway 403": "24", "Highway 401": "34",
    "Highway 410": "46", "Highway 427": "58", "Highway 400": "66", "Yonge": "77",
    "Highway 404": "83", "McCowan": "90", "York Durham Line": "98", "Brock": "105",
}


def fetch(url, data=None):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.load(resp)


def haversine_km(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def exit_list():
    """{exit: (km or None, name)} for the ETR section, from the wikitext exit table."""
    text = fetch(WIKI)["parse"]["wikitext"]
    table = text[text.index("{{ONinttop"):]
    table = table[:table.index("{{jctbtm")]
    rows = {}
    for block in re.split(r"\{\{ONint\b", table)[1:]:
        km = re.search(r"\|\s*km\s*=\s*([^\n|]*)", block)
        ex = re.search(r"\|\s*exit\s*=\s*([^\n|]*)", block)
        # road runs until the next "| key =" line (usually notes)
        road = re.search(r"\|\s*road\s*=(.*?)(?:\n\s*\|\s*\w+\s*=|\Z)", block, re.S)
        exit_no = ex.group(1).strip() if ex else ""
        if not re.fullmatch(r"\d+", exit_no):
            continue
        km_val = km.group(1).strip() if km else ""
        names = []
        # unwrap [[target|label]] and [[label]] first, so pipes inside links don't split jcon args
        road_text = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", road.group(1) if road else "")
        road_text = road_text.replace("&nbsp;", " ")
        for jcon in re.findall(r"\{\{[Jj]con\|([^}]*)\}\}", road_text):
            parts = [p for p in jcon.split("|") if "=" not in p]
            label = parts[-1]
            names.append(label if not label.isdigit() and not label.isupper() else " ".join(parts[:2]))
        plain = re.sub(r"\{\{[^}]*\}\}|<[^>]*>", "", road_text).strip()
        name = " / ".join(names) if names else plain
        rows[exit_no] = (float(km_val) if re.fullmatch(r"[\d.]+", km_val) else None, name)
        if exit_no == "105":
            break
    return rows


def main():
    query = (f"[out:json][timeout:180];relation({RELATION});out tags;"
             "way(r);node(w)[highway=motorway_junction];out;")
    osm = fetch(OVERPASS, urllib.parse.urlencode({"data": query}).encode())
    osm_base = osm.get("osm3s", {}).get("timestamp_osm_base")
    nodes = {}
    for el in osm["elements"]:
        if el["type"] == "node":
            ref = re.match(r"\d+", el.get("tags", {}).get("ref", ""))
            if ref:
                nodes.setdefault(ref.group(), []).append((el["lat"], el["lon"], el["id"]))

    exits = exit_list()
    interchanges = []
    for exit_no, (km, name) in exits.items():
        pts = nodes.get(exit_no)
        if not pts:
            # Planned interchanges (e.g. exit 100, "future interchange") are listed but unbuilt.
            if exit_no in ZONE_BOUNDARY_EXITS.values():
                sys.exit(f"zone boundary exit {exit_no} ({name}) has no OSM junction node")
            print(f"skipped exit {exit_no} ({name}): no OSM junction node (unbuilt?)")
            continue
        lat = sum(p[0] for p in pts) / len(pts)
        lon = sum(p[1] for p in pts) / len(pts)
        interchanges.append({"exit": exit_no, "name": name, "km": km, "lat": round(lat, 6),
                             "lon": round(lon, 6), "osm_nodes": sorted(p[2] for p in pts),
                             "km_source": "wikipedia_exit_list" if km is not None else None})

    # Exit 1 (QEW) has no km in the exit list; derive it from exit 5's km minus the
    # straight-line distance between the two interchanges, and say so.
    for i, ic in enumerate(interchanges):
        if ic["km"] is None:
            nxt = next(x for x in interchanges[i + 1:] if x["km"] is not None)
            ic["km"] = round(nxt["km"] - haversine_km((ic["lat"], ic["lon"]), (nxt["lat"], nxt["lon"])), 1)
            ic["km_source"] = f"derived: exit {nxt['exit']} km minus straight-line distance"

    kms = [ic["km"] for ic in interchanges]
    if kms != sorted(kms):
        sys.exit("interchange km not monotonic - exit list parse is wrong")

    by_exit = {ic["exit"]: ic for ic in interchanges}
    card = json.load(open(CARD, encoding="utf-8"))
    zones = []
    for z in card["zones"]:
        a, b = ZONE_BOUNDARY_EXITS[z["from"]], ZONE_BOUNDARY_EXITS[z["to"]]
        zones.append({"zone_code": z["zone_code"], "from": z["from"], "to": z["to"],
                      "from_exit": a, "to_exit": b,
                      "from_km": by_exit[a]["km"], "to_km": by_exit[b]["km"]})

    out = {
        "facility": card["facility"],
        "osm_route_relation": RELATION,
        "osm_base": osm_base,
        "generator": "scripts/toll_research/build_407_corridor.py",
        "sources": {"positions": OVERPASS, "km_and_names": "https://en.wikipedia.org/wiki/Ontario_Highway_407",
                    "zones": CARD},
        "km_direction": "eastbound = increasing km (exit numbers rise west to east)",
        "west_terminus_km": 0.0,
        "west_terminus_note": "km 0 is the Highway 403 junction west of QEW; ETR's Zone 1 starts at QEW, "
                              "so km 0 to QEW is priced as Zone 1",
        "interchanges": interchanges,
        "zones": zones,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="\n", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    print(f"{len(interchanges)} interchanges, {len(zones)} zones, osm_base {osm_base} -> {OUT}")


if __name__ == "__main__":
    main()
