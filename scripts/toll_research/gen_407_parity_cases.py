#!/usr/bin/env python3
"""Writes the 407 parity fixture the Java estimator is tested against.

Every expected value comes from price_407_reference.price(), so DistanceZonedEstimatorTest
proves the Java and the hand-check script agree on the same card. Deterministic (seeded).

  python scripts/toll_research/gen_407_parity_cases.py
"""
import csv
import datetime as dt
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from price_407_reference import load_card, price  # noqa: E402

OUT = os.path.join("src", "test", "resources", "toll-pricing", "407-parity-cases.csv")


def main():
    card = load_card()
    zones = [f"Z{i}" for i in range(1, 13)]
    rng = random.Random(407)
    # Band edges and the minute either side of them, both day types, plus overnight.
    times = ["00:00", "04:59", "05:00", "06:59", "07:00", "08:29", "08:30", "09:29", "09:30", "09:59",
             "10:00", "10:29", "10:30", "14:29", "14:30", "15:29", "15:30", "17:59", "18:00", "18:59",
             "19:00", "20:59", "21:00", "23:59"]
    days = [dt.date(2026, 3, 16) + dt.timedelta(days=i) for i in range(7)]  # Mon..Sun
    rows = []
    for _ in range(400):
        day = rng.choice(days)
        t = rng.choice(times) if rng.random() < 0.6 else f"{rng.randrange(24):02d}:{rng.randrange(60):02d}"
        entry = dt.datetime.fromisoformat(f"{day.isoformat()}T{t}:00")
        direction = rng.choice(["eastbound", "westbound"])
        holiday = rng.random() < 0.1
        start = rng.randrange(12)
        n = rng.randint(1, 12 - start)
        span = zones[start:start + n] if direction == "eastbound" else list(reversed(zones[start:start + n]))
        zone_km = {z: round(rng.uniform(0.1, 14.0), rng.choice([1, 2, 3])) for z in span}
        r = price(card, entry, direction, zone_km, holiday)
        rows.append([entry.isoformat(), direction, str(holiday).lower(),
                     ";".join(f"{z}={km}" for z, km in zone_km.items()),
                     r["day_type"], r["band_applied"], f'{r["total_cad"]:.2f}'])
    with open(OUT, "w", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["entry_local", "direction", "holiday", "zone_km", "day_type", "band", "total_cad"])
        w.writerows(rows)
    print(f"wrote {len(rows)} cases to {OUT}")


if __name__ == "__main__":
    main()
