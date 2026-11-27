#!/usr/bin/env python3
"""Reference toll calculator for 407 ETR, driven by the committed rate card.

PURPOSE: to prove the cached rate card is queryable and to validate it against
invoices. This is NOT production code and NOT a Phase 2 artefact - no DDL, no
service, no pipeline. It exists so a human can check a number by hand.

Fleet assumptions baked in, per the stated fleet profile:
  vehicle_class  = light          (sedans and SUVs, no trailer, under 6.09 m / 2.03 m)
  payment_method = license_plate  (no transponder)

Both are CONSTANTS here. That is the whole reason this is simple: the two error
terms the design worried about most are resolved by fleet facts, not by code.

Usage:
  python scripts/toll_research/price_407_reference.py \
      --entry "2026-03-17T08:12:00" --direction eastbound --zones Z6=4.1,Z7=5.2,Z8=3.7
"""
import argparse
import datetime as dt
from decimal import Decimal, ROUND_HALF_UP
import json
import os
import re
import sys

CARD = os.path.join("docs", "toll-pricing", "data", "rate-cards",
                    "407-etr-2026-light.json")

# Band labels as published, mapped to the local clock time they start at.
BAND_START = {
    "5 a.m.": (5, 0), "7 a.m.": (7, 0), "9:30 a.m.": (9, 30), "10:30 a.m.": (10, 30),
    "2:30 p.m.": (14, 30), "3:30 p.m.": (15, 30), "6 p.m.": (18, 0), "9 p.m.": (21, 0),
    "8:30 a.m.": (8, 30), "10 a.m.": (10, 0), "7 p.m.": (19, 0),
    "12:30 p.m.": (12, 30),                     # weekday band new in 2027
}


def load_card(path=CARD):
    with open(path) as fh:
        return json.load(fh)


def camera_fee(card):
    """The per-trip camera charge as the card's fee table states it ($5.30 in 2026, $5.40 in 2027)."""
    for fee in card["fees"]:
        if fee["name"].startswith("Camera Charge"):
            m = re.search(r"\$(\d+(?:\.\d+)?) per trip", fee["without_transponder"])
            if m:
                return Decimal(m.group(1))
    sys.exit("card has no per-trip Camera Charge")


def resolve_band(card, entry_local, day_type):
    """Pick the band in force at entry_local. ETR prices the WHOLE trip by entry time."""
    bands = card["band_boundaries_local"][day_type]
    minutes = entry_local.hour * 60 + entry_local.minute
    starts = sorted(((BAND_START[b][0] * 60 + BAND_START[b][1], b) for b in bands))
    chosen = starts[-1][1]                      # before the first start -> previous day's last band
    for start_min, label in starts:
        if minutes >= start_min:
            chosen = label
    return chosen


def price(card, entry_local, direction, zone_km, is_holiday=False):
    day_type = ("weekend_or_holiday"
                if is_holiday or entry_local.weekday() >= 5 else "weekday")
    band = resolve_band(card, entry_local, day_type)

    lookup = {(r["zone_code"], r["direction"], r["day_type"], r["band_start_local"]):
              r["rate_cents_per_km"] for r in card["rates"]}

    # Exact decimal, rounded half-up once on the distance component and once on the total -
    # the same arithmetic as DistanceZonedEstimator. Binary floats put exact half-cent ties
    # (3.0 km x 66.5 c = $1.995) on either side of the tie at random.
    cent = Decimal("0.01")
    lines, distance_total = [], Decimal(0)
    for zone, km in zone_km.items():
        key = (zone, direction, day_type, band)
        if key not in lookup:
            sys.exit(f"no rate for {key} - rate card does not cover this combination")
        rate = lookup[key]
        amount = Decimal(repr(km)) * Decimal(repr(rate)) / 100
        distance_total += amount
        lines.append({"zone": zone, "km": km, "rate_cents_per_km": rate,
                      "amount": float(amount.quantize(cent, ROUND_HALF_UP))})

    trip_charge = Decimal("1.00")                   # applies to ALL customers, per entry
    camera_charge = camera_fee(card)                # plate-billed only - our fleet
    total = distance_total + trip_charge + camera_charge

    return {
        "entry_local": entry_local.isoformat(),
        "day_type": day_type,
        "band_applied": band,
        "band_rule": "whole trip priced at time of entry (ETR does not split at boundaries)",
        "direction": direction,
        "vehicle_class": "light",
        "payment_method": "license_plate",
        "zone_lines": lines,
        "distance_component": float(distance_total.quantize(cent, ROUND_HALF_UP)),
        "trip_toll_charge": float(trip_charge),
        "camera_charge": float(camera_charge),
        "total_cad": float(total.quantize(cent, ROUND_HALF_UP)),
        "rate_card": {"valid_from": card["valid_from"], "source_url": card["source_url"]},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--entry", required=True, help="local entry time, ISO, e.g. 2026-03-17T08:12:00")
    ap.add_argument("--direction", required=True, choices=["eastbound", "westbound"])
    ap.add_argument("--zones", required=True, help="Z6=4.1,Z7=5.2,Z8=3.7 (km per zone)")
    ap.add_argument("--holiday", action="store_true", help="Ontario statutory holiday")
    ap.add_argument("--card", help="rate card JSON (default: 407-etr-<entry year>-light.json)")
    args = ap.parse_args()

    zone_km = {}
    for part in args.zones.split(","):
        z, _, km = part.partition("=")
        zone_km[z.strip()] = float(km)

    entry = dt.datetime.fromisoformat(args.entry)
    card_path = args.card or os.path.join(os.path.dirname(CARD), f"407-etr-{entry.year}-light.json")
    card = load_card(card_path)
    if not card["valid_from"][:4] == str(entry.year) or entry.date() < dt.date.fromisoformat(card["valid_from"]):
        sys.exit(f"entry {entry.date()} is not in the rate year of {card_path} (valid_from {card['valid_from']})")
    result = price(card, entry, args.direction, zone_km, args.holiday)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
