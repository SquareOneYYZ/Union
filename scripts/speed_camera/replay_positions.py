#!/usr/bin/env python3
"""
Offline replay harness for the speed camera handler (docs/plans/speed-camera-fix-plan.md, section 4.1).

Stage A scope (this version): run V1-A. Replay every event position in the pinned prod export
under the rule that shipped before the branch ("old") and the stage A rule ("new"), compare the
new verdict with the audit's `verdict_unit_fix_only` column row by row, and write a per-row
verdict file, a per-device diff and a summary.

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
    :107  speedLimitKnots = position.getDouble(KEY_SPEED_LIMIT)     -> 0.0 when absent
    :108  speedKnots = position.getSpeed()
    :114/:121  isSpeedCamera as before
    :129  if speedLimitKnots <= 0 -> non-reading, readNoLimit++   (finding D2)
    :135  fire if speedKnots > speedLimitKnots + SPEED_EQUALITY_EPSILON_KNOTS (0.01 kn, :48)
          -> knots vs knots; the epsilon absorbs km/h-to-knots conversion noise so a vehicle
             exactly at the posted limit does not fire (539 export rows within 0.0001 kn)

Both rules are evaluated per position; the 60 s per-highway lock in SpeedCameraState.java:60 is
not replayed because every row of the export is an event that already cleared it.

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
SPEED_EQUALITY_EPSILON_KNOTS = 0.01  # SpeedCameraEventHandler.SPEED_EQUALITY_EPSILON_KNOTS (new rule)

# Prod values from data prod/traccar.xml (received 2026-09-10). Code defaults differ
# (highwayTypes defaults to motorway_link), which is finding D8; the harness takes prod values.
DEFAULT_HIGHWAY_TYPES = "speed_camera"
DEFAULT_ENFORCEMENT_TYPES = "maxspeed,speed"


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
    """SpeedCameraEventHandler.java:96-107 (old) / :111-124 (new): same logic in both."""
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


def new_rule(speed_knots, limit_knots, zone):
    """this branch :107-:135. Returns one of: not_camera, no_limit, under_limit, fires."""
    if not zone:
        return "not_camera"
    if limit_knots <= 0:  # :129
        return "no_limit"
    if speed_knots > limit_knots + SPEED_EQUALITY_EPSILON_KNOTS:  # :135
        return "fires"
    return "under_limit"


def load_audit(path):
    """eventid -> verdict_unit_fix_only from the audit's full-check file (real | under_limit | no_limit)."""
    verdicts = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            verdicts[row["eventid"]] = row["verdict_unit_fix_only"]
    return verdicts


def run_v1a(args):
    highway_types = parse_set(args.highway_types)
    enforcement_types = parse_set(args.enforcement_types)
    audit = load_audit(args.audit) if args.audit else {}

    os.makedirs(args.out, exist_ok=True)
    verdict_path = os.path.join(args.out, "v1a_verdicts.tsv")
    summary_path = os.path.join(args.out, "v1a_summary.md")

    n = 0
    old_fires = 0
    new_counts = collections.Counter()
    old_miss_by_tags = collections.Counter()
    agree = 0
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

    audit_map = {"real": "fires", "under_limit": "under_limit", "no_limit": "no_limit"}

    with open(args.export, newline="", encoding="utf-8") as f, \
            open(verdict_path, "w", newline="", encoding="utf-8") as out:
        reader = csv.DictReader(f, delimiter="\t")
        writer = csv.writer(out, delimiter="\t", lineterminator="\n")
        writer.writerow([
            "eventid", "eventtime", "deviceid", "groupid", "speed_knots", "speed_kmh",
            "limit_knots", "limit_kmh", "highway", "enforcement", "is_camera",
            "old_fires", "new_verdict", "audit_unit_fix_only", "agree",
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
            verdict_new = new_rule(speed_knots, limit_knots, zone)

            if fired_old:
                old_fires += 1
            else:
                old_miss_by_tags[(highway, enforcement)] += 1
            new_counts[verdict_new] += 1

            audit_verdict = audit.get(row["eventid"], "")
            expected = audit_map.get(audit_verdict, "")
            if expected:
                if expected == verdict_new:
                    agree += 1
                    agreed = "yes"
                else:
                    disagree[(expected, verdict_new)] += 1
                    agreed = "no"
                    if len(disagree_rows) < 50:
                        disagree_rows.append((row["eventid"], speed_knots, limit_knots,
                                              highway, enforcement, expected, verdict_new))
            else:
                agreed = ""

            group = row["groupid"]
            month = row["eventtime"][:7]
            device = row["deviceid"]
            device_names[device] = row["devicename"]
            per_group_all[group] += 1
            per_month_all[month] += 1
            per_device_all[device] += 1
            if verdict_new == "fires":
                per_group_new[group] += 1
                per_month_new[month] += 1
                per_device_new[device] += 1

            writer.writerow([
                row["eventid"], row["eventtime"], device, group,
                f"{speed_knots:.4f}", f"{speed_knots * KNOTS_TO_KMH:.1f}",
                f"{limit_knots:.4f}", f"{limit_knots * KNOTS_TO_KMH:.1f}",
                highway, enforcement, "yes" if zone else "no",
                "yes" if fired_old else "no", verdict_new, audit_verdict, agreed,
            ])

    new_fires = new_counts["fires"]
    target = args.acceptance
    drift = (new_fires - target) / target * 100 if target else 0.0
    accepted = abs(drift) <= 1.0

    lines = []
    lines.append("# V1-A: stage A rule replayed on the pinned export\n")
    lines.append(f"Export: `{os.path.basename(args.export)}` ({n:,} event positions). "
                 f"Audit column: `verdict_unit_fix_only` from `{os.path.basename(args.audit) if args.audit else 'none'}`. "
                 f"Prod config: highwayTypes=`{args.highway_types}`, enforcementTypes=`{args.enforcement_types}`.\n")
    lines.append("| Rule | Result | Count | Share |")
    lines.append("|---|---|---|---|")
    lines.append(f"| old (origin/master :113, km/h vs knots) | fires | {old_fires:,} | {old_fires / n:.1%} |")
    lines.append(f"| old | would not fire on the exported row | {n - old_fires:,} | {(n - old_fires) / n:.1%} |")
    for key in ("fires", "under_limit", "no_limit", "not_camera"):
        lines.append(f"| new (this branch :129/:135, knots vs knots) | {key} | {new_counts[key]:,} | {new_counts[key] / n:.1%} |")
    lines.append("")
    lines.append(f"**Acceptance V1-A:** new-rule fires = **{new_fires:,}** vs target {target:,} "
                 f"({drift:+.2f} %). **{'PASS' if accepted else 'FAIL'}** (tolerance ±1 %).\n")
    lines.append(f"Rows with the limit attribute absent (`NULL`): {limit_absent:,}; the new rule's `no_limit` count "
                 f"is {new_counts['no_limit']:,} (absent plus any non-positive value inside a camera zone).\n")
    if audit:
        lines.append(f"Row-by-row agreement with the audit column: {agree:,} agree, "
                     f"{sum(disagree.values()):,} disagree, {n - agree - sum(disagree.values()):,} rows without an audit verdict.")
        if disagree:
            lines.append("")
            lines.append("| Audit says | New rule says | Rows |")
            lines.append("|---|---|---|")
            for (exp, got), c in disagree.most_common():
                lines.append(f"| {exp} | {got} | {c:,} |")
            lines.append("")
            lines.append("First disagreements (eventid, speed kn, limit kn, highway, enforcement, audit, new):")
            for r in disagree_rows[:20]:
                lines.append(f"- {r[0]}: {r[1]:.4f} kn vs {r[2]:.4f} kn, `{r[3]}`/`{r[4]}`, audit={r[5]}, new={r[6]}")
        lines.append("")
    if old_miss_by_tags:
        lines.append("Exported events the old rule does not reproduce from the stored attributes, by (highway, enforcement):")
        lines.append("")
        lines.append("| highway | enforcement | Rows |")
        lines.append("|---|---|---|")
        for (h, e), c in old_miss_by_tags.most_common(12):
            lines.append(f"| {h} | {e} | {c:,} |")
        lines.append("")
        lines.append("These rows fired in prod, so the attributes prod evaluated at event time differed from what the export "
                     "carries now, or the config differed on that day. They are reported, not hidden.")
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
    return 0 if accepted else 2


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="run", required=True)
    p = sub.add_parser("v1a", help="stage A regression pin against the pinned export")
    p.add_argument("--export", required=True, help="pinned export TSV (updated camrea events.tsv)")
    p.add_argument("--audit", default=None, help="audit full-check TSV with verdict_unit_fix_only")
    p.add_argument("--out", required=True, help="output directory")
    p.add_argument("--highway-types", default=DEFAULT_HIGHWAY_TYPES)
    p.add_argument("--enforcement-types", default=DEFAULT_ENFORCEMENT_TYPES)
    p.add_argument("--acceptance", type=int, default=11638, help="plan section 4.1 V1-A target")
    args = parser.parse_args(argv)
    if args.run == "v1a":
        return run_v1a(args)
    return 1


if __name__ == "__main__":
    sys.exit(main())
