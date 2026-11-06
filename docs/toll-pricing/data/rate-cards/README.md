# Sourced toll rate cards

**Status: transcriptions, not validated rate cards.** Nothing here has been checked against
a real invoice. Treat as a sourced draft for Phase 2, never as a billing input.

Regenerate with `python scripts/toll_research/build_rate_cards.py` — the script fetches
from the operators live, so re-running is also how you detect a reprice.

| File | Facility | Model | Rows | Effective | Reprices |
|---|---|---|---:|---|---|
| `407-etr-2026-light.json` | 407 ETR (Ontario) | `distance_zoned` | 288 per-km cells | 2026-01-01 | **1 January** |
| `cfx-2026-07-01.json` | CFX (Central Florida) | `flat` | 58 plazas × 4 axle × 2 payment | 2026-07-01 | **1 July** (fiscal year) |
| `istha-2026-01-01.json` | Illinois Tollway | `flat` | 96 plazas × passenger + 3 truck tiers × day/night × 2 payment | 2026-01-01 (passenger since 2012) | until 2026-12-31 |
| `istha-2027-01-01.json` | Illinois Tollway | `flat` | 96 plazas × same × 3 payment | **2027-01-01** (approved 2026-08-19) | every 2 years from 2029 (CPI-U, max 8 %) |
| `e470-2025-01-01.json` | E-470 (Denver) | `flat` | 6 plazas (A-E + toll ramps) x 2-axle x 2 payment | not published; unchanged in archive since 2025-01-22 | **1 January** |
| `nwp-2026-05-01.json` | Northwest Parkway (Denver) | `flat` | 2 plazas x 2 time bands x 2 payment | 2026-05-01 | as announced ("Pending Toll Rates") |

Every row carries a `source_url`, a `source_type` and a `valid_from`, per the project's
non-negotiable constraint: never invent a rate, never store one without provenance.

## Where the data came from

Both are **operator-published material, free to fetch, no licence purchased**:

- **407 ETR** — the HTML rate tables at <https://www.407etr.com/en/rate-chart-light>. Four
  tables (weekday/weekend × east/west), 12 zones each, plus the fee schedule table.
- **Illinois Tollway** — <https://agency.illinoistollway.com/toll-rates> embeds both the "2026 Toll
  Rates by Plaza" and "2027 Toll Rates by Plaza" tables as JSON (with an `isRamp` flag per plaza).
  The builder takes those, and refuses to write unless every 2027 passenger I-PASS / Pay By Plate /
  Invoice rate equals, by plaza number, the board-approved PDF
  `https://agency.illinoistollway.com/documents/d/guest/2026-06_proposedrates-1` (still titled
  "Proposed", linked from the page as "2027 Toll Rates"). Checked 2026-11-06: 96 of 96 equal.
- **E-470** — the ExpressToll and License Plate Toll tables at <https://www.e-470.com/toll-rates/>.
- **Northwest Parkway** — the rate and fee-schedule tables at <https://nwpky.com/driver-information/>.
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
- **Operators not yet captured**: Florida's Turnpike Enterprise,
  Quebec A-30, the MTO 407 East Extension, Georgia SRTA, and
  Miami-Dade Expressway Authority — the last of which appears in no design document but owns
  42 of the priced gantries the facility matrix credits to Florida's Turnpike.
- **Dynamically-priced facilities** (Colorado HPTE, Florida FDOT express lanes, Georgia SRTA)
  cannot have a static card at all.
- **Validation.** No card here has been reconciled against an invoice. Until one is, the
  transcription itself is unverified.

## Illinois Tollway notes (2026-11-06)

- **Two cards, one facility.** The pricing job charges each passage from the card in effect on its
  Chicago-local date (`GantrySet.rateAt`), so trips before and after 2027-01-01 are both right
  without a redeploy. A passage before every card is `NO_RATE` (unknown), never a later card's price.
- **We price the invoice (fleet decision 2026-11-06: no passes, plates not registered).** The 2026
  table has no invoice column, so the builder derives `invoice.passenger` = Pay Online + the $3 fee
  per toll (e.g. Touhy 1.90 + 3 = 4.90). From 2027 the published Invoice rate (2× I-PASS, no
  per-toll fee) applies, so invoiced passenger tolls get *cheaper* on 2027-01-01 (Touhy 3.00).
  Pay By Plate goes from 2× to 1.5× I-PASS at the same time. Late-payment escalation and violation
  fines are not estimated (facilities.json `payment_column_note`).
- **Plaza numbers duplicate on purpose.** Genoa Road (3), Illinois 47 (6), Barrington Road (10) and
  Roselle Road (12) are priced per movement (e.g. "EB Exit/WB Entrance"); Genoa and IL 47 have
  different rates per movement. `build_istha_plazas.py` decides the movement from the ramp geometry.
- **The card's ramp flag is not always the map.** Eola Road (60) and Ogden Avenue (81) are mainline
  on the card but gantries on ramps (priced with the ramp rule); I-80 Westbound/Eastbound (43/45)
  are ramps on the card but gantries on the I-80 carriageways (proven against the other carriageway).
- **Not on the map** (never charged, estimate low): Illinois 23 (7A), Roselle Road EB Exit/WB
  Entrance (12), Meacham Road (12A), and I-490's Acorn Lane (303) and Franklin Avenue (304).

## E-470 and Northwest Parkway notes (2026-11-06)

- **Plaza classes, not interchanges.** E-470 publishes Plazas A-E and one rate for every toll ramp;
  the Northwest Parkway one mainline rate and one for its US 287 and Sheridan ramps. The OSM gantries
  are named ("Plaza B NB", "Gartrell Road Offramp EB"); Plaza C's pair is named only "Aurora" and is
  pinned in `build_e470_plazas.py`. 23 E-470 and 4 NWP ramp clusters, all provable from the track.
  A ramp fix must be 30 m clear of the mainline AND of the road the ramp meets (trunk to tertiary):
  the US 85 gantries stand at the far end of their ramps, so a US 85 driver must never prove one.
- **E-470's effective date is not published.** The page gives none; every archived copy from
  2025-01-22 to 2026-01-13 shows the same rates, so the card is dated 2025-01-01.
- **Time of day (NWP).** 06:00-21:00 and 21:00-06:00 Denver time, every day. The card stores bands
  (`[{"from", "until", "amount"}]`); `GantrySet.bands` checks they meet and `rateAt` picks the band at
  each passage's local time.
- **Fees we derive.** NWP charges vehicles without ExpressToll a $1.35 processing fee per transaction;
  `non_expresstoll` = base + 1.35. E-470's License Plate Toll rate already includes its premium.
  Per-bill (NWP $0.90 mailing) and late fees are not estimated.
