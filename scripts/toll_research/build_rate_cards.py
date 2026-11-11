#!/usr/bin/env python3
"""Build sourced toll rate cards from operator-published material.

Every rate row carries a provenance URL and an effective date, per the project's
non-negotiable constraint: never invent a rate, never store one without provenance.

Sources are operator publications, fetched live:
  407 ETR  https://www.407etr.com/en/rate-chart-light   (HTML tables)
  CFX      https://www.cfxway.com/wp-content/uploads/2026/07/
           CFX-Toll-Rates-As-of-July-1-2026-FINAL.pdf   (PDF, needs PyMuPDF)
  ISTHA    https://agency.illinoistollway.com/toll-rates  (2026 and 2027 plaza tables, embedded
           JSON), 2027 cross-checked against the board-approved PDF (needs PyMuPDF)
  E-470    https://www.e-470.com/toll-rates/  (HTML: ExpressToll and License Plate Toll tables)
  NWP      https://nwpky.com/driver-information/  (HTML: time-banded rates and the fee schedule)
  FTE      https://floridasturnpike.com/tolls/toll-rates/  (six regional PDFs, needs PyMuPDF)

Output: docs/toll-pricing/data/rate-cards/*.json

THESE ARE TRANSCRIPTIONS, NOT VALIDATED RATE CARDS. Nothing here has been checked
against a real invoice. Treat as a sourced draft for Phase 2, not a billing input.

Usage:  python scripts/toll_research/build_rate_cards.py [--etr] [--cfx] [--istha] [--e470] [--nwp] [--fte]
"""
import argparse
import html
import json
import os
import re
import sys
import urllib.request
from html.parser import HTMLParser

UA = "RidesIQ-toll-research/1.0"
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


ISTHA_URL = "https://agency.illinoistollway.com/toll-rates"
ISTHA_2027_PDF = "https://agency.illinoistollway.com/documents/d/guest/2026-06_proposedrates-1"
# OSM gantries are tagged with the plaza number; the Tollway's number for these is 15 (one plaza,
# one rate) but OSM splits the interchange into 15A (IL 53) and 15B (I-290). Used by the plaza script.
ISTHA_TRUCK_TIERS = ("small", "medium", "large")


def istha_tables(page):
    """The two plaza tables the rates page embeds (2026, then 2027), each keyed by its heading year."""
    tables = {}
    for m in re.finditer(r"var tollRatesDataRaw = '(.*?)';", page, re.S):
        heading = re.findall(r"(20\d\d) Toll Rates by Plaza", re.sub(r"<[^>]+>", " ", page[:m.start()]), re.I)
        if not heading:
            sys.exit("ISTHA: rate table without a year heading - page layout changed")
        tables[heading[-1]] = json.loads(m.group(1).encode().decode("unicode_escape"))
    return tables


ISTHA_INVOICE_FEE = 3.00   # per toll on a 2026 passenger invoice


def istha_pdf_passenger_rates(pdf):
    """Sorted [(plaza number, I-PASS, Pay By Plate, Invoice)] passenger rates from the 2027 PDF."""
    import fitz
    doc = fitz.open(stream=pdf, filetype="pdf")
    lines = [l.strip() for page in doc for l in page.get_text().split("\n") if l.strip()]
    out = []
    for i, line in enumerate(lines):
        if line == "I-PASS" and lines[i + 8] == "Pay By Plate" and lines[i + 16] == "Invoice":
            out.append((lines[i - 1],) + tuple(lines[i + k].lstrip("$") for k in (1, 9, 17)))
    return sorted(out)


def build_istha():
    """Illinois Tollway: flat per-plaza rates, 2026 and 2027 cards."""
    tables = istha_tables(fetch(ISTHA_URL))
    if set(tables) != {"2026", "2027"}:
        sys.exit(f"ISTHA: expected 2026 and 2027 tables, found {sorted(tables)}")
    pdf_rates = istha_pdf_passenger_rates(fetch(ISTHA_2027_PDF, binary=True))
    web_rates = sorted((p["number"], p["autoRates"]["ipass"], p["autoRates"]["payOnline"], p["autoRates"]["invoice"])
                       for tw in tables["2027"]["tollways"] for p in tw["plazas"])
    if pdf_rates != web_rates:
        bad = sorted(set(web_rates) ^ set(pdf_rates))
        sys.exit(f"ISTHA: 2027 web table and PDF disagree ({len(web_rates)} vs {len(pdf_rates)} rows): {bad[:6]}")

    cards = {}
    for year, table in sorted(tables.items()):
        plazas = []
        for tw in table["tollways"]:
            for p in tw["plazas"]:
                auto = p["autoRates"]
                columns = {"ipass": "truckRates", "pay_by_plate": "truckRatesPayOnline",
                           "invoice": "truckRatesInvoice"}
                row = {"name": p["name"], "number": p["number"], "road": tw["name"],
                       "plaza_type": "ramp" if p["isRamp"] else "mainline"}
                for column, truck_key in columns.items():
                    auto_key = "payOnline" if column == "pay_by_plate" else column
                    if auto_key not in auto:
                        continue
                    rates = {"passenger": float(auto[auto_key])}
                    truck = p.get(truck_key, {})
                    for period in ("daytime", "overnight"):
                        for tier in ISTHA_TRUCK_TIERS:
                            if tier in truck.get(period, {}):
                                rates[f"{tier}_{period}"] = float(truck[period][tier])
                    row[column] = rates
                if "invoice" not in row:
                    # 2026 publishes no invoice column: an unpaid plate toll is invoiced at the
                    # Pay Online rate plus a $3 fee per toll. Passenger only (truck fees differ).
                    row["invoice"] = {"passenger": round(row["pay_by_plate"]["passenger"] + ISTHA_INVOICE_FEE, 2)}
                plazas.append(row)
        is_2027 = year == "2027"
        cards[year] = {
            "facility": "Illinois Tollway",
            "operator": "Illinois State Toll Highway Authority",
            "country": "US", "region": "IL", "currency": "USD",
            "rate_model_type": "flat",
            "rate_model_note": "Flat amount per plaza. NOT distance-based. Passenger rates have no "
                               "time-of-day or day-of-week variation; truck rates are daytime/overnight.",
            "valid_from": f"{year}-01-01",
            "valid_to": None if is_2027 else "2026-12-31",
            "reprices_on": "1 January 2027 (board vote 2026-08-19); then every two years from 2029 by CPI-U, max 8%",
            "source_url": ISTHA_URL,
            "source_type": "published",
            "source_note": (f"The rates page embeds the '{year} Toll Rates by Plaza' table as JSON. "
                            + (f"Every passenger I-PASS / Pay By Plate / Invoice rate was checked equal, by "
                               f"plaza number, to the board-approved PDF {ISTHA_2027_PDF} (titled 'Proposed', "
                               "linked as '2027 Toll Rates')." if is_2027 else
                               "Passenger rates are unchanged since 2012; truck rates took effect 2026-01-01. "
                               "The 2026 table publishes no invoice column; invoice.passenger here is DERIVED as "
                               "Pay Online + the $3 fee charged per toll on an invoice.")),
            "payment_premium_note": ("Pay By Plate is 1.5x I-PASS and Invoice 2x (rounded to the nickel)."
                                     if is_2027 else "Pay By Plate / Pay Online is exactly 2x I-PASS."),
            "plazas": plazas,
        }
    return cards


E470_URL = "https://www.e-470.com/toll-rates/"
E470_VALID_FROM = "2025-01-01"
E470_ARCHIVE_NOTE = ("The page states no effective date. The 2-axle rates are identical in every archived copy "
                     "checked (web.archive.org 2025-01-22, 2025-06-24, 2025-11-17, 2026-01-13) and on the live "
                     "page 2026-11-06; E-470 changes rates on 1 January, so the card is dated 2025-01-01 - "
                     "the earliest date the archive proves, not a published one.")
E470_COLUMNS = ["Mainline Toll Plaza A", "Mainline Toll Plaza C", "Mainline Toll Plazas B, D and E", "Toll Ramps"]

NWP_URL = "https://nwpky.com/driver-information/"
NWP_PROCESSING_FEE = 1.35   # per transaction, every non-ExpressToll trip (effective 2026-02-02)


def page_text(page):
    """Visible text of an HTML page, one '|' per element boundary."""
    page = re.sub(r"<script.*?</script>|<style.*?</style>", "", page, flags=re.S | re.I)
    return html.unescape(re.sub(r"\s*\n\s*", "|", re.sub(r"<[^>]+>", "\n", page)))


def money(cell):
    m = re.fullmatch(r"\$(\d+\.\d\d)", cell)
    if not m:
        sys.exit(f"not a dollar amount: {cell!r} - page layout changed")
    return float(m.group(1))


def e470_table(text, heading):
    """{column: 2-axle rate} from one of the page's 2-axle tables."""
    i = text.find(heading)
    if i < 0:
        sys.exit(f"E-470: table {heading!r} not found - page layout changed")
    cells = text[i:].split("|")
    header = cells[2:6]
    if cells[1] != "Vehicle class" or header != E470_COLUMNS or cells[6] != "2 Axles":
        sys.exit(f"E-470: table {heading!r} has columns {cells[1:7]} - page layout changed")
    return dict(zip(header, (money(c) for c in cells[7:11])))


def build_e470():
    """E-470: flat per plaza; four published columns, one rate at all hours for 2 axles."""
    text = page_text(fetch(E470_URL))
    expresstoll = e470_table(text, "ExpressToll 2-Axle Rates – All Hours")
    plate = e470_table(text, "License Plate Toll Rates – All Hours")
    plazas = []
    for column in E470_COLUMNS:
        if column == "Toll Ramps":
            names, plaza_type = ["Toll Ramps"], "ramp"
        else:
            letters = re.findall(r"\b([A-E])\b", column.replace("Mainline Toll Plaza", ""))
            names, plaza_type = [f"Plaza {x}" for x in letters], "mainline"
        for name in names:
            plazas.append({"name": name, "road": "E-470", "plaza_type": plaza_type, "published_as": column,
                           "expresstoll": {"2_axle": expresstoll[column]},
                           "license_plate_toll": {"2_axle": plate[column]}})
    if [p["name"] for p in plazas] != ["Plaza A", "Plaza C", "Plaza B", "Plaza D", "Plaza E", "Toll Ramps"]:
        sys.exit(f"E-470: unexpected plazas {[p['name'] for p in plazas]}")
    return {
        "facility": "E-470",
        "operator": "E-470 Public Highway Authority",
        "country": "US", "region": "CO", "currency": "USD",
        "rate_model_type": "flat",
        "rate_model_note": "Flat amount per mainline plaza (A-E) and one amount for every toll ramp. "
                           "2-axle rates are the same at all hours; 3+ axle ExpressToll rates vary by hour "
                           "and are not captured.",
        "valid_from": E470_VALID_FROM,
        "valid_to": None,
        "reprices_on": "1 January (when the board changes rates)",
        "source_url": E470_URL,
        "source_type": "published",
        "source_note": E470_ARCHIVE_NOTE,
        "payment_premium_note": "License Plate Toll is what a vehicle without an ExpressToll account pays. "
                                "A statement not paid by its due date adds a one-time $5 late fee (not estimated).",
        "plazas": plazas,
    }


NWP_BAND = re.compile(r"\|(Mainline|Ramps\|US287 & Sheridan)\|(\d+) (am|pm) – (\d+) (am|pm)\|"
                      r"(\$\d+\.\d\d)\|(\$\d+\.\d\d)")


def hhmm(hour, half):
    h = int(hour) % 12 + (12 if half == "pm" else 0)
    return f"{h:02d}:00"


def build_nwp():
    """Northwest Parkway: flat per plaza, two time bands; non-ExpressToll trips add a processing fee."""
    import datetime
    text = page_text(fetch(NWP_URL))
    m = re.search(r"Current Toll Rates\.\|Effective (\w+) (\d+)(?:st|nd|rd|th)?, (\d{4})", text)
    if not m:
        sys.exit("NWP: 'Current Toll Rates. Effective ...' not found - page layout changed")
    published = f"{m.group(1)} {m.group(2)}, {m.group(3)}"
    valid_from = datetime.datetime.strptime(published, "%B %d, %Y").date().isoformat()
    if "No Pending Toll Rate Changes" not in text:
        sys.exit("NWP: the page lists pending toll rates - build a second card for them")
    fee = re.search(r"Processing Fee\|N/A\|\$(\d+\.\d\d)/ transaction", text)
    if not fee or float(fee.group(1)) != NWP_PROCESSING_FEE:
        sys.exit(f"NWP: processing fee changed or moved: {fee and fee.group(1)}")
    bands = {}
    for row in NWP_BAND.finditer(text):
        place, start, end = row.group(1), hhmm(row.group(2), row.group(3)), hhmm(row.group(4), row.group(5))
        bands.setdefault(place, []).append((start, end, money(row.group(6))))
    if set(bands) != {"Mainline", "Ramps|US287 & Sheridan"}:
        sys.exit(f"NWP: rate rows {sorted(bands)} - page layout changed")
    plazas = []
    for place, rows in bands.items():
        rows.sort()
        if sorted(r[1] for r in rows) != [r[0] for r in rows]:
            sys.exit(f"NWP: {place} bands {rows} do not cover the day")
        base = [{"from": a, "until": b, "amount": x} for a, b, x in rows]
        plate = [{"from": a, "until": b, "amount": round(x + NWP_PROCESSING_FEE, 2)} for a, b, x in rows]
        ramp = place != "Mainline"
        plazas.append({"name": "Ramps (US 287 and Sheridan)" if ramp else "Mainline",
                       "road": "Northwest Parkway", "plaza_type": "ramp" if ramp else "mainline",
                       "base": {"2_axle": base}, "non_expresstoll": {"2_axle": plate}})
    return {
        "facility": "Northwest Parkway",
        "operator": "Northwest Parkway LLC (VINCI Highways)",
        "country": "US", "region": "CO", "currency": "USD",
        "rate_model_type": "flat",
        "rate_model_note": "Flat amount per plaza by local time of day (America/Denver): each band runs from "
                           "'from' until the next band starts. The same on every day of the week.",
        "valid_from": valid_from,
        "valid_to": None,
        "reprices_on": "as announced on the page ('Pending Toll Rates')",
        "source_url": NWP_URL,
        "source_type": "published",
        "source_note": (f"'Current Toll Rates. Effective {published}'. non_expresstoll is DERIVED: the base "
                        "charge plus the $1.35 processing fee the fee schedule charges per transaction on every "
                        "trip without an ExpressToll transponder (effective 2026-02-02). The $0.90 mailing fee "
                        "is per bill and not estimated."),
        "payment_premium_note": "The page publishes one base charge; vehicles without ExpressToll add the "
                                "processing fee per transaction.",
        "plazas": plazas,
    }


FTE_RATES_PAGE = "https://floridasturnpike.com/tolls/toll-rates/"
FTE_UPLOADS = "https://floridasturnpike.com/wp-content/uploads/"
# The regional schedules the rates page linked on 2026-11-06. A different set means FTE published a
# new schedule: refuse to build until it is reviewed and listed here.
FTE_PDFS = [
    "2026/08/Central-Florida-08-14-2026.pdf",
    "2023/06/South-Central-Florida.pdf",
    "2026/07/South-Florida-8-3-2026.pdf",
    "2026/04/West-Central-Florida-4-2026.pdf",
    "2026/06/North_East_Florida-6-2026.pdf",
    "2025/09/Panhandle-9-2025.pdf",
]
FTE_NOT_SCHEDULES = ("AET-Night-Of-Flyer", "Phishing", "Construction", "CONSTRUCTION")
FTE_VALUE = re.compile(r"^(\$\d+\.\d\d|-|--|- -)$")
FTE_COLUMNS = ["2_axle", "3_axle", "4_axle", "5_axle", "additional_axle"]


def fte_road(title):
    """'TURNPIKE MAINLINE - SR 91 (MP 236 - MP 309)' -> 'Turnpike Mainline (SR 91)'."""
    if title.startswith("TURNPIKE MAINLINE - SR 91"):
        return "Turnpike Mainline (SR 91)"
    if title.startswith("TURNPIKE MAINLINE (SR 821)"):
        return "Homestead Extension (SR 821)"
    if title.startswith("TURNPIKE MAINLINE: BEACHLINE WEST"):
        return "Beachline West Expressway (SR 528)"
    if title.startswith("BEACHLINE EAST"):
        return "Beachline East Expressway (SR 528)"
    return re.sub(r"\s+", " ", title.title().replace("Sr ", "SR ").replace("I - 275", "I-275")
                  .replace("Ii", "II").replace("Iv", "IV").replace("And", "and").replace("Part C", "Part C"))


def fte_tables(lines):
    """[(title, [(label, [10 cells])])] from one page's text lines."""
    out = []
    for i, line in enumerate(lines):
        if line != "Mile Post - Description":
            continue
        title = lines[i - 1]
        j, seen_axles = i + 1, 0
        while j < len(lines) and seen_axles < 2:
            seen_axles += lines[j].startswith("Axles")
            j += 1
        rows = []
        while j < len(lines):
            label = []
            while j < len(lines) and not FTE_VALUE.match(lines[j]) and len(label) < 3:
                label.append(lines[j])
                j += 1
            cells = []
            while j < len(lines) and FTE_VALUE.match(lines[j]):
                cells.append(lines[j])
                j += 1
            if not cells:
                break
            if len(cells) != 10:
                sys.exit(f"FTE: {title} row {label} has {len(cells)} cells, not 10 - layout changed")
            rows.append((" ".join(label), cells))
        if not rows:
            sys.exit(f"FTE: table {title} has no rows - layout changed")
        out.append((title, rows))
    return out


def fte_amount(cell):
    return float(cell[1:]) if cell.startswith("$") else None


def build_fte():
    """Florida's Turnpike system and FDOT toll facilities: flat per plaza / interchange."""
    import datetime
    import fitz
    page = fetch(FTE_RATES_PAGE)
    linked = sorted({m for m in re.findall(r"wp-content/uploads/(\d{4}/\d\d/[^\"']+?\.pdf)", page)
                     if not any(x in m for x in FTE_NOT_SCHEDULES)})
    if linked != sorted(FTE_PDFS):
        sys.exit(f"FTE: the rates page links {linked}, not the reviewed schedules {sorted(FTE_PDFS)}")
    plazas, sources, effective_dates = {}, [], set()
    for pdf in FTE_PDFS:
        doc = fitz.open(stream=fetch(FTE_UPLOADS + pdf, binary=True), filetype="pdf")
        found = 0
        for page_no, pg in enumerate(doc):
            lines = [l.strip() for l in pg.get_text().split("\n") if l.strip()]
            dates = {datetime.datetime.strptime(m, "%B %d, %Y").date().isoformat()
                     for m in re.findall(r"FDOT Toll Rates - (?:Effective )?(\w+ \d+, \d{4})", " ".join(lines))}
            # The Gateway Expressway page prints "FDOT Toll Rates - May 3, 2024", without "Effective".
            tables = fte_tables(lines)
            if tables and len(dates) > 1:
                sys.exit(f"FTE: {pdf} page {page_no + 1} has effective dates {dates}")
            for title, rows in tables:
                found += 1
                effective = next(iter(dates)) if dates else None
                if effective:
                    effective_dates.add(effective)
                road = fte_road(title)
                for label, cells in rows:
                    if label.startswith(("Central Florida Expressway portion", "6 - MAINLINE PLAZA TOTAL")):
                        continue   # CFX's share is on the CFX card; the total is the two summed
                    name = label
                    if label == "Turnpike portion":
                        name = "6 - BEACHLINE WEST PLAZA (Turnpike portion)"
                    name = re.sub(r"\s*–\s*", " - ", name)
                    m = re.match(r"([\d.]+[A-Z]?(?:\s*-\s*A/B)?)\s*-\s*(.+)", name)
                    if not m:
                        sys.exit(f"FTE: row label without a milepost: {label!r}")
                    number, desc = m.group(1).replace(" ", ""), m.group(2)
                    plaza_type = "mainline" if re.search(r"\bPLAZA\b", desc) else "ramp"
                    row = {"name": f"{number} - {desc}", "number": number, "road": road, "plaza_type": plaza_type,
                           "effective": effective, "source": FTE_UPLOADS + pdf,
                           "sunpass": dict(zip(FTE_COLUMNS, map(fte_amount, cells[:5]))),
                           "toll_by_plate": dict(zip(FTE_COLUMNS, map(fte_amount, cells[5:])))}
                    key = (road, row["name"])
                    if key in plazas:
                        a, b = plazas[key], row
                        if (a["sunpass"], a["toll_by_plate"]) != (b["sunpass"], b["toll_by_plate"]):
                            sys.exit(f"FTE: {key} listed twice with different rates")
                        continue   # a border plaza both regional schedules print (Three Lakes, Lantana)
                    plazas[key] = row
        if not found:
            sys.exit(f"FTE: {pdf} has no rate tables - layout changed")
        sources.append(FTE_UPLOADS + pdf)
    valid_from = max(effective_dates)
    return {
        "facility": "Florida's Turnpike System and FDOT toll facilities",
        "operator": "Florida's Turnpike Enterprise (FDOT)",
        "country": "US", "region": "FL", "currency": "USD",
        "rate_model_type": "flat",
        "rate_model_note": "Flat amount per mainline plaza and per tolled interchange (ramp gantries), by axle "
                           "count. All-electronic; no time-of-day pricing. Each row keeps its table's own "
                           "effective date.",
        "valid_from": valid_from,
        "valid_from_note": "The newest table's effective date. A row with an older 'effective' date is priced "
                           "from that date (GantrySet.rowStart); a row without one only from this date.",
        "valid_to": None,
        "reprices_on": "per regional schedule; FTE indexes rates to CPI (typically 1 July)",
        "source_url": FTE_RATES_PAGE,
        "source_pdfs": sources,
        "source_type": "published",
        "source_note": "The six regional 'Toll Rates for Florida's Turnpike System and FDOT Toll Facilities' "
                       "PDFs linked from the rates page. Where a plaza is shared with CFX (Beachline West, "
                       "Dallas) only the Turnpike/FDOT portion is listed; CFX's portion is on the CFX card.",
        "payment_premium_note": "TOLL-BY-PLATE is what a vehicle without SunPass (or an interoperable "
                                "transponder) pays; FTE also adds a $2.50 administrative charge per monthly "
                                "invoice (not estimated).",
        "plazas": list(plazas.values()),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--etr", action="store_true")
    ap.add_argument("--cfx", action="store_true")
    ap.add_argument("--istha", action="store_true")
    ap.add_argument("--e470", action="store_true")
    ap.add_argument("--nwp", action="store_true")
    ap.add_argument("--fte", action="store_true")
    args = ap.parse_args()
    do_all = not (args.etr or args.cfx or args.istha or args.e470 or args.nwp or args.fte)

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
    if args.istha or do_all:
        for year, card in build_istha().items():
            path = os.path.join(OUT_DIR, f"istha-{year}-01-01.json")
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(card, fh, indent=1, ensure_ascii=False)
                fh.write("\n")
            print(f"wrote {path}: {len(card['plazas'])} plazas")
    for flag, build, prefix in ((args.e470, build_e470, "e470"), (args.nwp, build_nwp, "nwp"),
                                 (args.fte, build_fte, "fte")):
        if flag or do_all:
            card = build()
            path = os.path.join(OUT_DIR, f"{prefix}-{card['valid_from']}.json")
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(card, fh, indent=1, ensure_ascii=False)
                fh.write("\n")
            print(f"wrote {path}: {len(card['plazas'])} plazas")


if __name__ == "__main__":
    main()
