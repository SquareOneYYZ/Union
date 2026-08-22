#!/usr/bin/env bash
#
# Behavior test for setup.sh's cron gate (replaces a string-order assertion
# that only proved the test's name was plausible). Runs the ACTUAL gating
# block from setup.sh — extracted verbatim from the ARCHIVE_BUCKET line
# onward, with the absolute python3/conf paths rewritten to stubs — against
# a stubbed crontab, and asserts the BEHAVIOR:
#   1. selfcheck fails  -> the cron line is NOT installed
#   2. selfcheck fails with an existing armed line -> that line is COMMENTED
#      OUT (the staging parked-entry scenario: never leave a failing script
#      armed)
#   3. selfcheck passes -> the cron line IS installed
# Needs only sh/bash and sed; no root, no makeself.

set -eu
cd "$(dirname "$0")"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"

# Stub crontab: -l prints state file; stdin-mode replaces it. Stdin is
# buffered BEFORE the state file is touched — real crontab spools
# internally, and truncating early would race the -l on the pipeline's
# left side.
cat > "$WORK/bin/crontab" <<'EOF'
#!/bin/sh
if [ "${1:-}" = "-l" ]; then
    cat "$CRONTAB_STATE" 2>/dev/null
    exit 0
fi
content=$(cat)
printf '%s\n' "$content" > "$CRONTAB_STATE"
EOF
chmod +x "$WORK/bin/crontab"

# Stub python3: exit code driven by $SELFCHECK_RC.
cat > "$WORK/bin/python3" <<'EOF'
#!/bin/sh
exit "${SELFCHECK_RC:-0}"
EOF
chmod +x "$WORK/bin/python3"

# Config with the bucket key present (the gate's precondition).
cat > "$WORK/traccar.xml" <<'EOF'
<properties>
    <entry key='archive.spaces.bucket'>testbucket</entry>
</properties>
EOF

# Extract the gate: everything from the ARCHIVE_BUCKET= line onward is safe
# to run (the mutating install steps are all above it), with the absolute
# paths rewritten to the stubs.
sed -n '/^ARCHIVE_BUCKET=/,$p' setup.sh \
  | grep -v '^rm ' \
  | sed -e "s|/usr/bin/python3|$WORK/bin/python3|g" \
        -e "s|/opt/traccar/conf/traccar.xml|$WORK/traccar.xml|g" \
  > "$WORK/gate.sh"

run_gate() {
    ( export PATH="$WORK/bin:$PATH" CRONTAB_STATE="$WORK/crontab.state" \
             SELFCHECK_RC="$1"
      sh "$WORK/gate.sh" >/dev/null )
}

# --- 1. selfcheck fails on a clean host: NO cron line installed -----------
: > "$WORK/crontab.state"
run_gate 1
if grep -q "archive_cold_storage" "$WORK/crontab.state"; then
    echo "FAIL 1: cron line present after failed selfcheck"; exit 1
fi
echo "PASS 1: failed selfcheck installs no cron line"

# --- 2. selfcheck fails with an existing ARMED line: line commented out ---
echo "30 15 9 3 * $WORK/bin/python3 /opt/traccar/scripts/archive_cold_storage.py --config x" > "$WORK/crontab.state"
run_gate 1
if grep -q "^30 15 9 3" "$WORK/crontab.state"; then
    echo "FAIL 2: existing armed line left active after failed selfcheck"; exit 1
fi
if ! grep -q "^#30 15 9 3" "$WORK/crontab.state"; then
    echo "FAIL 2: existing line was not preserved as a comment"; exit 1
fi
echo "PASS 2: failed selfcheck comments out the existing armed line"

# --- 3. selfcheck passes: the cron line IS installed ----------------------
: > "$WORK/crontab.state"
run_gate 0
if ! grep -q "^0 4 1 \* \* .*archive_cold_storage" "$WORK/crontab.state"; then
    echo "FAIL 3: cron line missing after passing selfcheck"; exit 1
fi
echo "PASS 3: passing selfcheck installs the cron line"

echo "setup.sh cron gate: all three behaviors confirmed."
