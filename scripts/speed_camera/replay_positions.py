#!/usr/bin/env python3
"""
Offline replay harness for the speed camera handler (docs/plans/speed-camera-fix-plan.md, section 4.1).

Stage A scope (this version): run V1-A. Replay every event position in the pinned prod export
under the rule that shipped before the branch ("old") and the stage A rule ("new"), compare the
new verdicts with independent references derived from the audit's full-check file row by row,
and write a per-row verdict file, a per-device diff and a summary.

The rule semantics are copied from the Java source, with line references, so the harness and the
code cannot drift silently:

  old rule (origin/master, SpeedCameraEventHandler.java)
    :90   Double speedLimit = position.getDouble(KEY_SPEED_LIMIT)   -> 0.0 when absent, never null
    :91   speedKmh = position.getSpeed() * 1.852                     -> km/h
    :96   isSpeedCamera |= highway in event.speedCamera.highwayTypes
    :103  isSpeedCamera |= enforcement in event.speedCamera.enforcementTypes
    :110  if (speedLimit == null) ... dead branch
    :113  fire if isSpeedCamera && speedKmh > speedLimit            -> km/h vs knots (finding D1)

  new rule (this branch, SpeedCameraEventHandler.java)
    :50   SPEED_EQUALITY_EPSILON_KNOTS = 0.01                        -> equality guard for conversion noise
    :64   bufferKnots = UnitsConverter.knotsFromKph(event.speedCamera.buffer)   (km/h, default 5; Keys.java)
    :127  speedLimitKnots = position.getDouble(KEY_SPEED_LIMIT)     -> 0.0 when absent
    :128  speedKnots = position.getSpeed()
    :134/:141  isSpeedCamera as before
    :149  if speedLimitKnots <= 0 -> non-reading, readNoLimit++   (finding D2)
    :155  fire if speedKnots > speedLimitKnots + bufferKnots + SPEED_EQUALITY_EPSILON_KNOTS

  UnitsConverter.java:20  KNOTS_TO_KPH_RATIO = 0.539957; knotsFromKph(v) = v * ratio

Both rules are evaluated per position; the 60 s per-highway lock in SpeedCameraState.java:60 is
not replayed because every row of the export is an event that already cleared it.

Two independent references come from the audit file (speed_camera_group8_full_check.tsv):
  - `verdict_unit_fix_only` (real | under_limit | no_limit): the rule with no buffer
  - `speed_kmh - stored_limit_kmh > buffer` on the audit's own km/h columns: the buffered rule

Usage:
  python scripts/speed_camera/replay_positions.py v1a \
      --export "data prod/updated camrea events.tsv" \
      --audit  "data prod/speed_camera_group8_full_check.tsv" \
      --out    "data prod/stage-a"
"""

import argparse
import collections
import csv
import os
import sys

KNOTS_TO_KMH = 1.852  # SpeedCameraEventHandler.java:91 (old rule) literal
KNOTS_TO_KPH_RATIO = 0.539957  # UnitsConverter.java:20
SPEED_EQUALITY_EPSILON_KNOTS = 0.01  # SpeedCameraEventHandler.java:50

# Prod values from data prod/traccar.xml (received 2026-09-10). Code defaults differ
# (highwayTypes defaults to motorway_link), which is finding D8; the harness takes prod values.
DEFAULT_HIGHWAY_TYPES = "speed_camera"
DEFAULT_ENFORCEMENT_TYPES = "maxspeed,speed"
DEFAULT_BUFFER_KPH = 5.0  # Keys.EVENT_SPEED_CAMERA_BUFFER default


def knots_from_kph(value):
    """UnitsConverter.knotsFromKph."""
    return value * KNOTS_TO_KPH_RATIO


def parse_set(value):
    return {v.strip().lower() for v in value.split(",") if v.strip()}


def parse_double_or_zero(value):
    """ExtendedModel.getDouble: absent or unparsable -> 0.0."""
    if value is None:
        return 0.0
    value = value.strip()
    if value == "" or value.upper() == "NULL":
        return 0.0
    try:
        return float(value)
    except ValueError:
        return 0.0


def is_speed_camera(highway, enforcement, highway_types, enforcement_types):
    """SpeedCameraEventHandler.java:96-107 (old) / :131-144 (new): same logic in both."""
    zone = False
    if highway and highway.upper() != "NULL" and highway.lower() in highway_types:
        zone = True
    if enforcement and enforcement.upper() != "NULL" and enforcement.lower() in enforcement_types:
        zone = True
    return zone


def old_rule(speed_knots, limit_knots, zone):
    """origin/master :90-:113. Returns True when the old handler would have called addDetection."""
    speed_kmh = speed_knots * KNOTS_TO_KMH  # :91
    # :110 `speedLimit == null` never true; :113 compares km/h to knots
    return zone and speed_kmh > limit_knots


def new_rule(speed_knots, limit_knots, zone, buffer_knots):
    """this branch :127-:155. Returns one of: not_camera, no_limit, under_limit, fires."""
    if not zone:
        return "not_camera"
    if limit_knots <= 0:  # :149
        return "no_limit"
    if speed_knots > limit_knots + buffer_knots + SPEED_EQUALITY_EPSILON_KNOTS:  # :155
        return "fires"
    return "under_limit"


def load_audit(path, buffer_kph):
    """eventid -> (verdict_unit_fix_only, expected buffered verdict) from the audit's full-check file.

    The buffered expectation uses the audit's own km/h columns (already rounded to 0.1 km/h), so it is
    an independent computation of the same policy, not a copy of the harness's arithmetic.
    """
    refs = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            unit = row["verdict_unit_fix_only"]
            if unit == "real":
                margin = float(row["speed_kmh"]) - float(row["stored_limit_kmh"])
                buffered = "fires" if margin > buffer_kph else "under_limit"
            elif unit == "under_limit":
                buffered = "under_limit"
            elif unit == "no_limit":
                buffered = "no_limit"
            else:
                buffered = ""
            refs[row["eventid"]] = (unit, buffered)
    return refs


AUDIT_MAP = {"real": "fires", "under_limit": "under_limit", "no_limit": "no_limit"}


def run_v1a(args):
    highway_types = parse_set(args.highway_types)
    enforcement_types = parse_set(args.enforcement_types)
    buffer_knots = knots_from_kph(args.buffer_kph)
    refs = load_audit(args.audit, args.buffer_kph) if args.audit else {}

    os.makedirs(args.out, exist_ok=True)
    verdict_path = os.path.join(args.out, "v1a_verdicts.tsv")
    summary_path = os.path.join(args.out, "v1a_summary.md")

    n = 0
    old_fires = 0
    counts = collections.Counter()
    counts_nb = collections.Counter()
    old_miss_by_tags = collections.Counter()
    agree = collections.Counter()
    disagree = collections.Counter()
    disagree_rows = []
    per_group_new = collections.Counter()
    per_group_all = collections.Counter()
    per_month_new = collections.Counter()
    per_month_all = collections.Counter()
    per_device_all = collections.Counter()
    per_device_new = collections.Counter()
    device_names = {}
    limit_absent = 0

    with open(args.export, newline="", encoding="utf-8") as f, \
            open(verdict_path, "w", newline="", encoding="utf-8") as out:
        reader = csv.DictReader(f, delimiter="\t")
        writer = csv.writer(out, delimiter="\t", lineterminator="\n")
        writer.writerow([
            "eventid", "eventtime", "deviceid", "groupid", "speed_knots", "speed_kmh",
            "limit_knots", "limit_kmh", "margin_kmh", "highway", "enforcement", "is_camera",
            "old_fires", "new_verdict", "new_verdict_no_buffer",
            "audit_unit_fix_only", "audit_buffered_expected", "agree_no_buffer", "agree_buffered",
        ])
        for row in reader:
            n += 1
            speed_knots = parse_double_or_zero(row["speed"])
            limit_knots = parse_double_or_zero(row["speedLimit_knots"])
            if row["speedLimit_knots"].strip().upper() in ("", "NULL"):
                limit_absent += 1
            highway = row["highway"]
            enforcement = row["enforcement"]
            zone = is_speed_camera(highway, enforcement, highway_types, enforcement_types)

            fired_old = old_rule(speed_knots, limit_knots, zone)
            verdict = new_rule(speed_knots, limit_knots, zone, buffer_knots)
            verdict_nb = new_rule(speed_knots, limit_knots, zone, 0.0)

            if fired_old:
                old_fires += 1
            else:
                old_miss_by_tags[(highway, enforcement)] += 1
            counts[verdict] += 1
            counts_nb[verdict_nb] += 1

            unit, buffered = refs.get(row["eventid"], ("", ""))
            expected_nb = AUDIT_MAP.get(unit, "")
            a_nb = a_b = ""
            if expected_nb:
                a_nb = "yes" if expected_nb == verdict_nb else "no"
                if a_nb == "yes":
                    agree["no_buffer"] += 1
                else:
                    disagree[("no_buffer", expected_nb, verdict_nb)] += 1
            if buffered:
                a_b = "yes" if buffered == verdict else "no"
                if a_b == "yes":
                    agree["buffered"] += 1
                else:
                    disagree[("buffered", buffered, verdict)] += 1
                    if len(disagree_rows) < 50:
                        disagree_rows.append((row["eventid"], speed_knots, limit_knots,
                                              highway, enforcement, buffered, verdict))

            group = row["groupid"]
            month = row["eventtime"][:7]
            device = row["deviceid"]
            device_names[device] = row["devicename"]
            per_group_all[group] += 1
            per_month_all[month] += 1
            per_device_all[device] += 1
            if verdict == "fires":
                per_group_new[group] += 1
                per_month_new[month] += 1
                per_device_new[device] += 1

            writer.writerow([
                row["eventid"], row["eventtime"], device, group,
                f"{speed_knots:.4f}", f"{speed_knots * KNOTS_TO_KMH:.1f}",
                f"{limit_knots:.4f}", f"{limit_knots * KNOTS_TO_KMH:.1f}",
                f"{(speed_knots - limit_knots) * KNOTS_TO_KMH:.1f}",
                highway, enforcement, "yes" if zone else "no",
                "yes" if fired_old else "no", verdict, verdict_nb, unit, buffered, a_nb, a_b,
            ])

    new_fires = counts["fires"]
    target = args.acceptance
    drift = (new_fires - target) / target * 100 if target else 0.0
    accepted = abs(drift) <= 1.0
    nb_fires = counts_nb["fires"]
    nb_target = args.acceptance_no_buffer
    nb_drift = (nb_fires - nb_target) / nb_target * 100 if nb_target else 0.0
    nb_accepted = abs(nb_drift) <= 1.0

    lines = []
    lines.append("# V1-A: stage A rule replayed on the pinned export\n")
    lines.append(f"Export: `{os.path.basename(args.export)}` ({n:,} event positions). "
                 f"Audit file: `{os.path.basename(args.audit) if args.audit else 'none'}`. "
                 f"Prod config: highwayTypes=`{args.highway_types}`, enforcementTypes=`{args.enforcement_types}`. "
                 f"Buffer: **{args.buffer_kph:g} km/h** ({buffer_knots:.4f} kn).\n")
    lines.append("| Rule | Result | Count | Share |")
    lines.append("|---|---|---|---|")
    lines.append(f"| old (origin/master :113, km/h vs knots) | fires | {old_fires:,} | {old_fires / n:.1%} |")
    lines.append(f"| old | would not fire on the exported row | {n - old_fires:,} | {(n - old_fires) / n:.1%} |")
    for key in ("fires", "under_limit", "no_limit", "not_camera"):
        lines.append(f"| new, buffer {args.buffer_kph:g} km/h (this branch :149/:155) | {key} | {counts[key]:,} | {counts[key] / n:.1%} |")
    lines.append(f"| new, buffer 0 (same rule, for continuity with the audit column) | fires | {nb_fires:,} | {nb_fires / n:.1%} |")
    lines.append("")
    lines.append(f"**Acceptance V1-A (buffer {args.buffer_kph:g} km/h):** fires = **{new_fires:,}** vs target {target:,} "
                 f"({drift:+.2f} %). **{'PASS' if accepted else 'FAIL'}** (tolerance ±1 %).  ")
    lines.append(f"**Continuity check (buffer 0):** fires = **{nb_fires:,}** vs {nb_target:,} "
                 f"({nb_drift:+.2f} %). **{'PASS' if nb_accepted else 'FAIL'}**.\n")
    lines.append(f"Rows with the limit attribute absent (`NULL`): {limit_absent:,}; the new rule's `no_limit` count "
                 f"is {counts['no_limit']:,} (absent plus any non-positive value inside a camera zone).\n")
    if refs:
        lines.append(f"Row-by-row agreement with the audit: buffer 0 vs `verdict_unit_fix_only` {agree['no_buffer']:,} agree; "
                     f"buffer {args.buffer_kph:g} km/h vs the audit's own km/h margin {agree['buffered']:,} agree; "
                     f"{sum(disagree.values()):,} disagreements in total.")
        if disagree:
            lines.append("")
            lines.append("| Check | Reference says | New rule says | Rows |")
            lines.append("|---|---|---|---|")
            for (chk, exp, got), c in disagree.most_common():
                lines.append(f"| {chk} | {exp} | {got} | {c:,} |")
            lines.append("")
            lines.append("First buffered disagreements (eventid, speed kn, limit kn, highway, enforcement, reference, new):")
            for r in disagree_rows[:20]:
                lines.append(f"- {r[0]}: {r[1]:.4f} kn vs {r[2]:.4f} kn, `{r[3]}`/`{r[4]}`, ref={r[5]}, new={r[6]}")
        lines.append("")
    if old_miss_by_tags:
        lines.append("Exported events the old rule does not reproduce from the stored attributes, by (highway, enforcement):")
        lines.append("")
        lines.append("| highway | enforcement | Rows |")
        lines.append("|---|---|---|")
        for (h, e), c in old_miss_by_tags.most_common(12):
            lines.append(f"| {h} | {e} | {c:,} |")
        lines.append("")
    lines.append("## Per month\n")
    lines.append("| Month | Exported | New rule fires | Share |")
    lines.append("|---|---|---|---|")
    for m in sorted(per_month_all):
        lines.append(f"| {m} | {per_month_all[m]:,} | {per_month_new[m]:,} | {per_month_new[m] / per_month_all[m]:.1%} |")
    lines.append("")
    lines.append("## Per group\n")
    lines.append("| Group | Exported | New rule fires | Share |")
    lines.append("|---|---|---|---|")
    for g, c in per_group_all.most_common():
        lines.append(f"| {g} | {c:,} | {per_group_new[g]:,} | {per_group_new[g] / c:.1%} |")
    lines.append("")
    lines.append("## Per device diff (largest reductions)\n")
    lines.append("| Device | Name | Exported | New rule fires | Removed |")
    lines.append("|---|---|---|---|---|")
    removed = sorted(per_device_all, key=lambda d: per_device_all[d] - per_device_new[d], reverse=True)
    for d in removed[:15]:
        lines.append(f"| {d} | {device_names[d]} | {per_device_all[d]:,} | {per_device_new[d]:,} | "
                     f"{per_device_all[d] - per_device_new[d]:,} |")
    lines.append("")
    lines.append(f"Per-row verdicts: `{os.path.basename(verdict_path)}` ({n:,} rows).")

    text = "\n".join(lines) + "\n"
    with open(summary_path, "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
    return 0 if (accepted and nb_accepted) else 2


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="run", required=True)
    p = sub.add_parser("v1a", help="stage A regression pin against the pinned export")
    p.add_argument("--export", required=True, help="pinned export TSV (updated camrea events.tsv)")
    p.add_argument("--audit", default=None, help="audit full-check TSV with verdict_unit_fix_only and km/h columns")
    p.add_argument("--out", required=True, help="output directory")
    p.add_argument("--highway-types", default=DEFAULT_HIGHWAY_TYPES)
    p.add_argument("--enforcement-types", default=DEFAULT_ENFORCEMENT_TYPES)
    p.add_argument("--buffer-kph", type=float, default=DEFAULT_BUFFER_KPH, help="event.speedCamera.buffer in km/h")
    p.add_argument("--acceptance", type=int, default=7567,
                   help="plan section 4.1 V1-A target for the default 5 km/h buffer")
    p.add_argument("--acceptance-no-buffer", type=int, default=11638,
                   help="plan section 4.1 continuity target for buffer 0 (the audit's unit-fix column)")
    args = parser.parse_args(argv)
    if args.run == "v1a":
        return run_v1a(args)
    return 1


if __name__ == "__main__":
    sys.exit(main())
