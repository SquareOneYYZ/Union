#!/usr/bin/env python3
"""Build sourced toll rate cards from operator-published material.

Every rate row carries a provenance URL and an effective date, per the project's
non-negotiable constraint: never invent a rate, never store one without provenance.

Sources are operator publications, fetched live:
  407 ETR  https://www.407etr.com/en/rate-chart-light   (HTML tables)
  CFX      https://www.cfxway.com/wp-content/uploads/2026/07/
           CFX-Toll-Rates-As-of-July-1-2026-FINAL.pdf   (PDF, needs PyMuPDF)

Output: docs/toll-pricing/data/rate-cards/*.json

THESE ARE TRANSCRIPTIONS, NOT VALIDATED RATE CARDS. Nothing here has been checked
against a real invoice. Treat as a sourced draft for Phase 2, not a billing input.

Usage:  python scripts/toll_research/build_rate_cards.py [--etr] [--cfx]
"""
import argparse
import json
import os
import re
import sys
import urllib.request
from html.parser import HTMLParser

UA = "RidesIQ-toll-research/1.0 (prab@ridesiq.com)"
OUT_DIR = os.path.join("docs", "toll-pricing", "data", "rate-cards")

ETR_URL = "https://www.407etr.com/en/rate-chart-light"
CFX_URL = ("https://www.cfxway.com/wp-content/uploads/2026/07/"
           "CFX-Toll-Rates-As-of-July-1-2026-FINAL.pdf")

# Zone boundaries as published by 407 ETR. NOT present in OSM in any form - verified:
# the 480 tolled 407 ways carry only `toll` and `charge` among 24 distinct keys, and
# there is no toll:zone / tariff / section tag anywhere. Zone geometry must therefore
# be curated by us. OSM does give usable anchors: 88 motorway_junction nodes on the
# corridor, 86 with an exit `ref`, and Ontario exit numbers are kilometre distances
# (observed range 1..120 km), so boundaries can be pinned to a linear reference.
ETR_ZONES = [
    (1, "QEW", "Dundas"), (2, "Dundas", "Neyagawa"), (3, "Neyagawa", "Highway 403"),
    (4, "Highway 403", "Highway 401"), (5, "Highway 401", "Highway 410"),
    (6, "Highway 410", "Highway 427"), (7, "Highway 427", "Highway 400"),
    (8, "Highway 400", "Yonge"), (9, "Yonge", "Highway 404"),
    (10, "Highway 404", "McCowan"), (11, "McCowan", "York Durham Line"),
    (12, "York Durham Line", "Brock"),
]


def fetch(url, binary=False):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=120) as r:
        raw = r.read()
    return raw if binary else raw.decode("utf-8", "replace")


class TableParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tables, self.cur, self.row, self.cell = [], None, None, None

    def handle_starttag(self, tag, attrs):
        if tag == "table":
            self.cur = []
        elif tag == "tr" and self.cur is not None:
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = []

    def handle_endtag(self, tag):
        if tag == "table" and self.cur is not None:
            self.tables.append(self.cur)
            self.cur = None
        elif tag == "tr" and self.row is not None:
            if self.row:
                self.cur.append(self.row)
            self.row = None
        elif tag in ("td", "th") and self.cell is not None:
            self.row.append(re.sub(r"\s+", " ", "".join(self.cell)).strip())
            self.cell = None

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)


def build_etr():
    """407 ETR: per-km rates by zone x direction x day type x time band."""
    p = TableParser()
    p.feed(fetch(ETR_URL))
    if len(p.tables) < 6:
        sys.exit(f"407 ETR: expected >=6 tables, got {len(p.tables)} - page layout changed")

    # Table order on the page, verified 2026-09-01.
    layout = [(0, "westbound", "weekday"), (1, "westbound", "weekend_or_holiday"),
              (2, "eastbound", "weekday"), (3, "eastbound", "weekend_or_holiday")]
    rates = []
    for idx, direction, day_type in layout:
        table = p.tables[idx]
        for row in table[1:]:
            band = row[0].strip()
            for zone, cell in enumerate(row[1:13], start=1):
                val = cell.replace("¢", "").replace("�", "").strip()
                if not val:
                    continue
                rates.append({
                    "zone_code": f"Z{zone}",
                    "direction": direction,
                    "day_type": day_type,
                    "band_start_local": band,
                    "rate_cents_per_km": float(val),
                })

    fees = []
    for row in p.tables[5][1:]:
        if len(row) >= 3:
            fees.append({
                "name": re.sub(r"\s+", " ", row[0]).strip(),
                "with_transponder": row[1].strip(),
                "without_transponder": row[2].strip(),
            })

    return {
        "facility": "407 ETR",
        "operator": "407 International Inc.",
        "osm_route_relation": 109574,
        "osm_network_tag": "CA:ON:private_toll",
        "country": "CA", "region": "ON", "currency": "CAD",
        "rate_model_type": "distance_zoned",
        "vehicle_class": "light",
        "vehicle_class_note": (
            "407 ETR publishes FIVE classes: motorcycle, light, medium, "
            "heavy single unit, heavy multiple unit. Only light is captured here."),
        "valid_from": "2026-01-01",
        "valid_to": None,
        "reprices_on": "January 1 (annual)",
        "source_url": ETR_URL,
        "source_type": "published",
        "time_band_rule": (
            "Priced by TIME OF ENTRY for the whole trip - ETR does not split a "
            "traversal at band boundaries. Bands are LOCAL time (America/Toronto)."),
        "band_boundaries_local": {
            "weekday": ["5 a.m.", "7 a.m.", "9:30 a.m.", "10:30 a.m.",
                        "2:30 p.m.", "3:30 p.m.", "6 p.m.", "9 p.m."],
            "weekend_or_holiday": ["8:30 a.m.", "10 a.m.", "7 p.m.", "9 p.m."],
        },
        "zones": [{"zone_code": f"Z{n}", "from": a, "to": b,
                   "geometry_source": "NOT IN OSM - must be curated"}
                  for n, a, b in ETR_ZONES],
        "rates": rates,
        "fees": fees,
    }


# Rows the PDF leaves without a road label. Goldenrod is on SR 551 per the CFX Toll
# Facilities Reference Manual (October 2024), bookmark "SR 551 > Goldenrod Mainline Plaza".
CFX_UNLABELLED_ROADS = {"Goldenrod Main Plaza": "SR 551"}
CFX_MANUAL_URL = ("https://www.cfxway.com/wp-content/uploads/2025/06/"
                  "Toll-Facilities-Reference-Manual-October-2024-Update-w-Bookmarks.pdf")


def add_cfx_roads(page, plazas):
    """Adds road, plaza_type, name and footnote to each plaza.

    The road column is a set of merged cells: a rotated label ("SR 408") centred between
    two horizontal borders. A plaza row belongs to the road whose borders enclose it.
    """
    # Horizontal borders crossing the road column.
    borders = set()
    for drawing in page.get_drawings():
        for item in drawing["items"]:
            if item[0] == "l" and abs(item[1].y - item[2].y) < 0.5 \
                    and min(item[1].x, item[2].x) < 140 < 145 < max(item[1].x, item[2].x):
                borders.add(round(item[1].y, 1))
            elif item[0] == "re" and item[1].x0 < 140 and item[1].x1 > 145 and item[1].height < 1.5:
                borders.add(round(item[1].y0, 1))
    borders = sorted(borders)

    labels, rows = [], []
    for block in page.get_text("dict")["blocks"]:
        for line in block.get("lines", []):
            text = "".join(s["text"] for s in line["spans"]).strip()
            x0, y0, _, y1 = line["bbox"]
            if line["dir"] != (1.0, 0.0) and x0 < 148:
                labels.append(((y0 + y1) / 2, x0, text))
            elif 145 < x0 < 160 and text and not text.startswith("$"):
                rows.append((y0, text))
    rows.sort()

    def band(y):
        for lo, hi in zip(borders, borders[1:]):
            if lo <= y < hi:
                return lo
        return None

    label_by_band = {}
    for y, x, text in sorted(labels, key=lambda t: (t[0], t[1])):
        label_by_band.setdefault(band(y), []).append(text)
    label_by_band = {k: re.sub(r"\s+", " ", " ".join(v)).strip() for k, v in label_by_band.items()}

    names = [p["plaza_name"] for p in plazas]
    row_iter = iter([(y, t) for y, t in rows if t in names])
    for plaza in plazas:
        y, text = next(row_iter)
        if text != plaza["plaza_name"]:
            sys.exit(f"CFX: row order mismatch at {plaza['plaza_name']!r}")
        m = re.fullmatch(r"(.*?)\s+([A-D])", plaza["plaza_name"])
        name = m.group(1) if m else plaza["plaza_name"].strip()
        plaza["name"] = name
        plaza["footnote"] = m.group(2) if m else None
        plaza["plaza_type"] = "mainline" if "Main Plaza" in name else "ramp"
        road = label_by_band.get(band(y + 1))
        if road is None and name in CFX_UNLABELLED_ROADS:
            road = CFX_UNLABELLED_ROADS[name]
            plaza["road_source"] = CFX_MANUAL_URL
        if road is None:
            sys.exit(f"CFX: no road for {name!r}")
        plaza["road"] = road


def build_cfx():
    """CFX: flat per-plaza rates by axle count x payment method."""
    try:
        import fitz
    except ImportError:
        sys.exit("CFX needs PyMuPDF (import fitz)")
    pdf = fetch(CFX_URL, binary=True)
    doc = fitz.open(stream=pdf, filetype="pdf")
    lines = [l.strip() for l in doc[0].get_text().split("\n") if l.strip()]

    plazas, i = [], 0
    while i < len(lines):
        if (not lines[i].startswith("$") and i + 8 < len(lines)
                and all(lines[i + k].startswith("$") for k in range(1, 9))):
            vals = [float(lines[i + k][1:].replace(",", "")) for k in range(1, 9)]
            plazas.append({
                "plaza_name": lines[i],
                "electronic": dict(zip(("2_axle", "3_axle", "4_axle", "5_axle"), vals[:4])),
                "pay_by_plate": dict(zip(("2_axle", "3_axle", "4_axle", "5_axle"), vals[4:])),
            })
            i += 9
        else:
            i += 1
    if not plazas:
        sys.exit("CFX: parsed zero plazas - PDF layout changed")
    add_cfx_roads(doc[0], plazas)

    return {
        "facility": "Central Florida Expressway Authority",
        "operator": "Central Florida Expressway Authority",
        "country": "US", "region": "FL", "currency": "USD",
        "rate_model_type": "flat",
        "rate_model_note": "Flat amount per plaza/gantry. NOT distance-based.",
        "valid_from": "2026-07-01",
        "valid_to": None,
        "reprices_on": "July 1 (CFX fiscal year; PDF is internally titled 'FY 2027')",
        "source_url": CFX_URL,
        "source_type": "published",
        "payment_premium_note": (
            "Pay By Plate is exactly 2.00x Electronic on 57 of 58 plazas. The single "
            "exception is Beachline West Main Plaza (TPK), a Turnpike-operated plaza, "
            "where the two are equal."),
        "plazas": plazas,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--etr", action="store_true")
    ap.add_argument("--cfx", action="store_true")
    args = ap.parse_args()
    do_all = not (args.etr or args.cfx)

    os.makedirs(OUT_DIR, exist_ok=True)
    if args.etr or do_all:
        card = build_etr()
        path = os.path.join(OUT_DIR, "407-etr-2026-light.json")
        json.dump(card, open(path, "w"), indent=1)
        print(f"wrote {path}: {len(card['rates'])} per-km cells, "
              f"{len(card['fees'])} fees, {len(card['zones'])} zones")
    if args.cfx or do_all:
        card = build_cfx()
        path = os.path.join(OUT_DIR, "cfx-2026-07-01.json")
        json.dump(card, open(path, "w"), indent=1)
        print(f"wrote {path}: {len(card['plazas'])} plazas x 4 axle classes "
              f"x 2 payment methods")


if __name__ == "__main__":
    main()
