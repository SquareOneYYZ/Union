#!/usr/bin/env python3
"""Locates every CFX rate-card plaza on the map, for gantry-passage pricing.

Output: docs/toll-pricing/data/gantries/cfx.json - one entry per card plaza with the OSM toll
nodes that charge it, clustered (nodes within 150 m are one plaza: per-direction booths and
gantries are separate nodes, decision 23 / gap 8), plus a review list.

Inputs:
  * the CFX rate card (plaza name, road, mainline/ramp - build_rate_cards.py --cfx)
  * OSM via our Overpass host: the CFX toll route relations, every toll_gantry / toll_booth
    within 1.5 km of them, the ways those nodes sit on, and named roads within 600 m.

Matching is per road. A cluster's evidence is the text on the ways through it (destination,
destination:street, destination:ref, name) and the names/refs of nearby roads, weighted by
distance. A plaza's tokens are its name minus generic words. Mainline plazas only take
clusters on the route's own carriageway; ramp plazas only take clusters off it. Each cluster
goes to at most one plaza; a plaza may hold several clusters (on- and off-ramp booths, or a
mainline split by direction such as Conway West / Conway East). OVERRIDES pins what the text
cannot decide, each with its reason.

  python scripts/toll_research/build_cfx_plazas.py [--cache DIR]
"""
import argparse
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

OVERPASS = os.environ.get("OVERPASS_URL", "http://roadinfo.iotrides.com/api/interpreter")
UA = "RidesIQ-toll-research/1.0"
CARD = os.path.join("docs", "toll-pricing", "data", "rate-cards", "cfx-2026-07-01.json")
OUT = os.path.join("docs", "toll-pricing", "data", "gantries", "cfx.json")

# CFX toll route relations (network US:FL:Toll) plus SR 551, which has no Toll relation.
RELATIONS = {222120: "SR 417", 380109: "SR 408", 380127: "SR 429", 2111035: "SR 414",
             2111036: "SR 528", 8348276: "SR 453", 17072947: "SR 538", 380122: "SR 551"}
CLUSTER_M = 150
NEAR_ROAD_M = 600
MIN_SCORE = 1.0

GENERIC = {"main", "plaza", "road", "rd", "blvd", "boulevard", "avenue", "ave", "parkway", "pkwy",
           "drive", "dr", "street", "st", "sr", "cr", "s", "r", "c", "the", "int", "l", "access",
           "west", "east", "north", "south", "toll", "expressway", "way", "trail", "lane", "tpk",
           "us", "fl", "and", "of", "lake"}

# Pins for what text evidence cannot decide: plaza name -> OSM node ids (any node of a cluster
# pins the whole cluster) and the reason. Mainline plazas are named after areas, not ramps, so
# they are placed by the CFX Toll Facilities Reference Manual (Oct 2024), whose page for each
# mainline names the interchanges either side of it; the cluster lying between those is the plaza.
MANUAL = "CFX Toll Facilities Reference Manual Oct 2024"
OVERRIDES = {
    "Hiawassee Main Plaza": {"nodes": [503294699], "reason": f"{MANUAL} p9: between Good Homes Rd and Hiawassee Rd"},
    "Pine Hills Main Plaza": {"nodes": [503333054], "reason": f"{MANUAL} p12: between Hiawassee Rd and John Young Pkwy"},
    "Conway Main Plaza": {"nodes": [599439757, 616083075],
                          "reason": f"{MANUAL} p17-18: Conway West (WB) and Conway East (EB) mainlines, one card rate"},
    "Dean Main Plaza": {"nodes": [291212500], "reason": f"{MANUAL} p24: between Dean Rd and Rouse Rd"},
    "John Young Main Plaza": {"nodes": [475073346], "reason": f"{MANUAL} p32: between International Dr and John Young Pkwy"},
    "Boggy Creek Main Plaza": {"nodes": [511987402], "reason": f"{MANUAL} p36: between Landstar Blvd and Boggy Creek Rd"},
    "Curry Ford Main Plaza": {"nodes": [616853761], "reason": f"{MANUAL} p42: between Lee Vista Blvd and Curry Ford Rd"},
    "University Main Plaza": {"nodes": [54728282], "reason": f"{MANUAL} p45: between SR 50 and University Blvd"},
    "Independence Main Plaza": {"nodes": [616902370], "reason": f"{MANUAL} p53: between New Independence Pkwy and C.R. 535"},
    "Forest Lake Main Plaza": {"nodes": [616904486], "reason": f"{MANUAL} p49: between West Rd and C.R. 437A"},
    "Ponkan Main Plaza": {"nodes": [7104526488],
                          "reason": f"{MANUAL} p58: between US 441 and W Kelly Park Rd; 841 m from Ponkan Road"},
    "Mt. Plymouth Main Plaza": {"nodes": [7104526494], "reason": f"{MANUAL} p59: between SR 453 and SR 46, north of Ponkan"},
    "Coronado Main Plaza": {"nodes": [9989679400], "reason": f"{MANUAL} p61: the only SR 453 mainline plaza"},
    "Beachline Main Plaza": {"nodes": [616814475], "reason": f"{MANUAL} p65: between SR 417 and Innovation Way"},
    "Dallas Main Plaza": {"nodes": [1740570126], "reason": f"{MANUAL} p63: between Innovation Way and SR 520"},
    "Beachline West Main Plaza (TPK)": {"nodes": [5607010106],
                                        "reason": f"{MANUAL} p65: the mainline west of Beachline Main (Turnpike-operated)"},
    "Marigold Main Plaza": {"nodes": [5332344276],
                            "reason": f"{MANUAL} p71: between US 17-92 and Marigold Ave; the cluster nearer US 17-92"},
    "Koa Main Plaza": {"nodes": [5332344106],
                       "reason": f"{MANUAL} p72: between Marigold Ave and KOA St; 1.4 km from KOA Street"},
    "Goldenrod Main Plaza": {"nodes": [602692930],
                             "reason": "CFX booths on SR 551 Goldenrod Rd; OSM charge $0.50 equals the card's rate"},
    "C.R. 535": {"nodes": [616902376],
                 "reason": "160 m from the C.R. 535 ramp booth, 2 km from the Stoneybrook interchange"},
}


def fetch(query):
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(OVERPASS, data=data, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=900) as resp:
        return json.load(resp)


def load(cache, name, query):
    path = os.path.join(cache, name) if cache else None
    if path and os.path.exists(path):
        return json.load(open(path, encoding="utf-8"))
    result = fetch(query)
    if path:
        json.dump(result, open(path, "w", encoding="utf-8"))
    return result


def metres(lat1, lon1, lat2, lon2):
    x = (lon2 - lon1) * 111320 * math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot(x, (lat2 - lat1) * 110540)


def seg_dist(lat, lon, a, b):
    """Metres from a point to segment a-b (local equirectangular)."""
    k = math.cos(math.radians(lat))
    px, py = lon * k, lat
    ax, ay, bx, by = a["lon"] * k, a["lat"], b["lon"] * k, b["lat"]
    dx, dy = bx - ax, by - ay
    t = 0 if dx == dy == 0 else max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return metres(lat, lon, ay + t * dy, (ax + t * dx) / k)


def way_dist(lat, lon, way):
    g = way.get("geometry") or []
    if len(g) == 1:
        return metres(lat, lon, g[0]["lat"], g[0]["lon"])
    return min((seg_dist(lat, lon, a, b) for a, b in zip(g, g[1:])), default=float("inf"))


def tokens(text):
    text = text.lower().replace("int'l", "international")
    return {t for t in re.split(r"[^a-z0-9]+", text) if t and t not in GENERIC}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", help="directory to cache Overpass responses in")
    args = ap.parse_args()
    if args.cache:
        os.makedirs(args.cache, exist_ok=True)
    ids = ",".join(str(r) for r in RELATIONS)
    toll_set = (f"relation(id:{ids});way(r)->.main;(node(around.main:1500)[highway=toll_gantry];"
                "node(around.main:1500)[barrier=toll_booth];)->.t;")
    main_geom = load(args.cache, "maingeom.json", f"[out:json][timeout:600];relation(id:{ids});out tags;way(r);out tags geom;")
    rel_members = load(args.cache, "relmembers.json", f"[out:json][timeout:300];relation(id:{ids});out body;")
    tolls = load(args.cache, "cfxosm.json", f"[out:json][timeout:600];{toll_set}.t out;way(bn.t);out tags;")
    node_ways = load(args.cache, "nodeways.json", f"[out:json][timeout:600];{toll_set}way(bn.t);out skel;")
    named = load(args.cache, "named.json",
                 f"[out:json][timeout:600];{toll_set}way(around.t:{NEAR_ROAD_M})"
                 "[highway~\"^(trunk|primary|secondary|tertiary|unclassified|residential|"
                 "trunk_link|primary_link|secondary_link|tertiary_link)$\"][name];out tags geom;")
    osm_base = main_geom.get("osm3s", {}).get("timestamp_osm_base")

    ways = {e["id"]: e for e in main_geom["elements"] if e["type"] == "way"}
    road_ways = {}
    for rel in rel_members["elements"]:
        if rel["type"] == "relation":
            road_ways[RELATIONS[rel["id"]]] = [m["ref"] for m in rel["members"] if m["type"] == "way"]
    main_way_ids = {w for ws in road_ways.values() for w in ws}
    nodes = {e["id"]: e for e in tolls["elements"] if e["type"] == "node"}
    toll_way_tags = {e["id"]: e.get("tags", {}) for e in tolls["elements"] if e["type"] == "way"}
    ways_of_node = {}
    for w in node_ways["elements"]:
        for n in w.get("nodes", []):
            if n in nodes:
                ways_of_node.setdefault(n, set()).add(w["id"])
    named_ways = [e for e in named["elements"] if e["type"] == "way"]

    # Cluster toll nodes within CLUSTER_M (single linkage).
    remaining, clusters = sorted(nodes), []
    while remaining:
        seed = remaining.pop(0)
        group, i = [seed], 0
        while i < len(group):
            a = nodes[group[i]]
            near = [n for n in remaining if metres(a["lat"], a["lon"], nodes[n]["lat"], nodes[n]["lon"]) < CLUSTER_M]
            for n in near:
                remaining.remove(n)
            group += near
            i += 1
        clusters.append(sorted(group))

    info = []
    for c in clusters:
        lat = sum(nodes[n]["lat"] for n in c) / len(c)
        lon = sum(nodes[n]["lon"] for n in c) / len(c)
        dists = {road: min((way_dist(lat, lon, ways[w]) for w in ws if w in ways), default=float("inf"))
                 for road, ws in road_ways.items()}
        road = min(dists, key=dists.get)
        through = {w for n in c for w in ways_of_node.get(n, ())}
        on_mainline = bool(through & set(road_ways[road]))
        operators = sorted({str(nodes[n].get("tags", {}).get("operator")) for n in c})
        evidence = {}
        for w in through - main_way_ids:
            t = toll_way_tags.get(w, {})
            for key in ("destination", "destination:street", "destination:ref", "name", "ref"):
                for tok in tokens(t.get(key, "")):
                    evidence[tok] = max(evidence.get(tok, 0), 2.0)
        for w in named_ways:
            d = way_dist(lat, lon, w)
            if d <= NEAR_ROAD_M:
                weight = 1.5 if d < 200 else 1.0 if d < 400 else 0.5
                for key in ("name", "ref", "alt_name", "old_name"):
                    for tok in tokens(w["tags"].get(key, "")):
                        evidence[tok] = max(evidence.get(tok, 0), weight)
        info.append({"nodes": c, "lat": round(lat, 6), "lon": round(lon, 6), "road": road,
                     "road_dist_m": round(dists[road]), "mainline": on_mainline,
                     "operators": operators, "evidence": evidence})

    card = json.load(open(CARD, encoding="utf-8"))
    plazas = card["plazas"]
    pairs = []
    for pi, p in enumerate(plazas):
        want = tokens(p["name"])
        is_main = p["plaza_type"] == "mainline"
        tpk = "(TPK)" in p["name"]
        for ci, c in enumerate(info):
            if c["road"] != p["road"] or c["road_dist_m"] > 1500 or c["mainline"] != is_main:
                continue
            turnpike = any("Turnpike" in o or "Tunrpike" in o for o in c["operators"])
            if turnpike != tpk:
                continue
            score = sum(c["evidence"].get(t, 0) for t in want)
            if score >= MIN_SCORE:
                pairs.append((score, pi, ci))
    pairs.sort(reverse=True)
    owner = {}
    for score, pi, ci in pairs:
        if ci not in owner:
            owner[ci] = (pi, score)

    pinned = {}
    for name, node_ids in OVERRIDES.items():
        pi = next(i for i, p in enumerate(plazas) if p["name"] == name)
        for nid in node_ids["nodes"]:
            ci = next(i for i, c in enumerate(info) if nid in c["nodes"])
            owner[ci] = (pi, None)
            pinned[ci] = node_ids["reason"]

    out_plazas = []
    for pi, p in enumerate(plazas):
        mine = [ci for ci, (o, _) in owner.items() if o == pi]
        out_plazas.append({
            "name": p["name"], "road": p["road"], "plaza_type": p["plaza_type"],
            "clusters": [{"lat": info[ci]["lat"], "lon": info[ci]["lon"], "osm_nodes": info[ci]["nodes"],
                          "match": "override: " + pinned[ci] if ci in pinned
                          else f"text score {owner[ci][1]:.1f}"} for ci in sorted(mine)],
        })
    unmatched_plazas = [p["name"] + " (" + p["road"] + ")" for p in out_plazas if not p["clusters"]]
    unclaimed = [{"lat": c["lat"], "lon": c["lon"], "road": c["road"], "mainline": c["mainline"],
                  "operators": c["operators"], "osm_nodes": c["nodes"],
                  "evidence": sorted(c["evidence"], key=c["evidence"].get, reverse=True)[:8]}
                 for ci, c in enumerate(info)
                 if ci not in owner and c["road_dist_m"] <= 300
                 and any("Central Florida" in o for o in c["operators"])]

    out = {"facility": card["facility"], "card_valid_from": card["valid_from"], "osm_base": osm_base,
           "generator": "scripts/toll_research/build_cfx_plazas.py", "cluster_m": CLUSTER_M,
           "plazas": out_plazas,
           "review": {"plazas_without_location": unmatched_plazas,
                      "cfx_clusters_without_plaza": unclaimed}}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(out, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    located = sum(1 for p in out_plazas if p["clusters"])
    print(f"{located}/{len(out_plazas)} plazas located; {len(unmatched_plazas)} without location; "
          f"{len(unclaimed)} CFX clusters unclaimed; osm_base {osm_base} -> {OUT}")


if __name__ == "__main__":
    sys.exit(main())
