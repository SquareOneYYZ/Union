# Sourced toll rate cards

**Status: transcriptions, not validated rate cards.** Nothing here has been checked against
a real invoice. Treat as a sourced draft for Phase 2, never as a billing input.

Regenerate with `python scripts/toll_research/build_rate_cards.py` — the script fetches
from the operators live, so re-running is also how you detect a reprice.

| File | Facility | Model | Rows | Effective | Reprices |
|---|---|---|---:|---|---|
| `407-etr-2026-light.json` | 407 ETR (Ontario) | `distance_zoned` | 288 per-km cells | 2026-01-01 | **1 January** |
| `cfx-2026-07-01.json` | CFX (Central Florida) | `flat` | 58 plazas × 4 axle × 2 payment | 2026-07-01 | **1 July** (fiscal year) |

Every row carries a `source_url`, a `source_type` and a `valid_from`, per the project's
non-negotiable constraint: never invent a rate, never store one without provenance.

## Where the data came from

Both are **operator-published material, free to fetch, no licence purchased**:

- **407 ETR** — the HTML rate tables at <https://www.407etr.com/en/rate-chart-light>. Four
  tables (weekday/weekend × east/west), 12 zones each, plus the fee schedule table.
- **CFX** — the PDF at
  `https://www.cfxway.com/wp-content/uploads/2026/07/CFX-Toll-Rates-As-of-July-1-2026-FINAL.pdf`,
  parsed with PyMuPDF. Per-gantry rates by axle count and payment method.

Neither came from OSM. **OSM has no usable road-toll prices** — 1 parseable price across
13,482 tolled ways in our nine operating regions, and it is a ferry.

## What these cards show that the design got wrong

1. **The plate/video premium is not "+30–50%".** It is a **flat CAD $5.30 per trip** on the
   407 and an **exact ×2.00 multiplier** on CFX (57 of 58 plazas; the exception is the
   Turnpike-operated plaza). Illinois is also ×2 (I-PASS is "50% off"). Three facilities,
   three mechanisms, none of them a 30–50% multiplier.
2. **Reprice dates are not synchronised and are not February.** ETR and ISTHA reprice
   1 January; CFX reprices 1 July on its fiscal year — and the CFX file is internally titled
   "FY 2027" while being dated July 2026, which is an effective-dating trap.
3. **Vehicle-class taxonomies do not align.** 407 ETR publishes five named classes
   (motorcycle, light, medium, heavy single unit, heavy multiple unit); CFX prices by axle
   count 2/3/4/5; Illinois uses four tiers by axles and tires. `vehicle_class` cannot be one
   shared enum across operators.
4. **Direction is a first-class rate dimension on the 407**, and the spread is large — Zone 10
   weekday 15:30 is 62.29 ¢/km westbound against 102.73 ¢/km eastbound (1.65×).
5. **Time band swamps everything else.** Zone 7 eastbound ranges 50.56 ¢/km at 21:00 to
   119.21 ¢/km at 15:30 — a 2.36× spread inside one zone, one direction, one vehicle class.

## Rate zones are not in OSM — verified

The 407's 12 zones are defined by interchange pairs (Z1 QEW→Dundas … Z12 York Durham
Line→Brock). **OSM carries no zone, tariff or section tag**: the 480 tolled 407 ways expose
24 distinct keys, of which the only toll-related ones are `toll` and `charge`.

So `toll_segment.zone_code` has to be curated by us — which is what the ERD already assumed.
OSM does give good anchors to curate *from*:

- **88 `highway=motorway_junction` nodes** on the corridor, **86 carrying an exit `ref`**
- Ontario exit numbers are **kilometre distances** (observed range 1–120 km), so they provide
  a linear reference along the corridor rather than just point locations
- **204 clean toll gantries** (see `../collection-points.json` and the A4 re-derivation in
  `../../19-adversarial-review.md`)

Note the observed exit range runs to 120 km while the ETR section is 108 km — the tail is the
MTO-operated 407 East Extension, a different operator on the same route number.

## What is missing

- **407 ETR**: only the **light** class of five. Medium, heavy single unit and heavy multiple
  unit are published at `/en/rate-chart-medium`, `/rate-chart-heavy`, `/rate-chart-multi`.
- **Operators not yet captured**: Illinois Tollway (ISTHA), Florida's Turnpike Enterprise,
  E-470 / Northwest Parkway, Quebec A-30, the MTO 407 East Extension, Georgia SRTA, and
  Miami-Dade Expressway Authority — the last of which appears in no design document but owns
  42 of the priced gantries the facility matrix credits to Florida's Turnpike.
- **Dynamically-priced facilities** (Colorado HPTE, Florida FDOT express lanes, Georgia SRTA)
  cannot have a static card at all.
- **Validation.** No card here has been reconciled against an invoice. Until one is, the
  transcription itself is unverified.
