#!/usr/bin/env bash
#
# phase0_collect_staging.sh — Phase 0A STAGING evidence collection (READ-ONLY).
#
# What it does: collects S-F0, S-F1, S-F2, S-F3, S-F10, S-F4, S-F4b, S-F5, S-F9
# into a single output file in /tmp, with a UTC "Collected:" timestamp per
# section and exit/stderr captured for every s3cmd call (a zero count is valid
# only with exit=0 and empty stderr — the runbook's measurement-validity rule).
#
# What it does NOT do: it never runs archive_cold_storage.py, never connects to
# any database (the staging SQL is printed at the end for a human to run), and
# uses s3cmd only with `ls` and `info` — no put/del/cp/mv/sync/setacl anywhere.
#
# Usage (as root on the STAGING droplet):
#     sudo bash phase0_collect_staging.sh          # asks for confirmation
#     sudo bash phase0_collect_staging.sh --yes    # skips the prompt
#
# Afterwards: paste the output file into docs/.phase0-answers.local.md (Part A),
# then DELETE it from the host — it contains the full archive listing and the
# (password-redacted) config.

set -u

CONF="/opt/traccar/conf/traccar.xml"
PROD_BUCKET="iotrides"
OUT="/tmp/phase0a_staging_$(date -u +%Y%m%dT%H%M%SZ).txt"

if [ "${1:-}" != "--yes" ]; then
    echo "READ-ONLY Phase 0A collection. Intended for the STAGING droplet."
    echo "This host is: $(hostname)"
    printf "Continue? [y/N] "
    read -r reply
    case "$reply" in y|Y|yes|YES) ;; *) echo "Aborted."; exit 1 ;; esac
fi

: > "$OUT"
echo "Phase 0A staging collection — started $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(hostname)" >> "$OUT"

if [ "$(id -u)" -ne 0 ]; then
    echo "WARNING: not running as root — crontab/log/config reads may be incomplete." | tee -a "$OUT"
fi

section () {
    {
        echo ""
        echo "=============================================================="
        echo "## $1"
        echo "Collected: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "=============================================================="
    } >> "$OUT"
}

run () {    # run <cmd...>: append the command, its merged output, and exit code
    echo "\$ $*" >> "$OUT"
    "$@" >> "$OUT" 2>&1
    echo "[exit=$?]" >> "$OUT"
}

redact () { # mask values of entries whose key mentions password/secret/token,
            # and password= fragments inside URLs
    sed -E 's/(<entry key=[^>]*(password|secret|token)[^>]*>)[^<]*/\1[REDACTED]/Ig; s/(password=)[^&<]*/\1[REDACTED]/Ig'
}

getcfg () { # getcfg <key> -> value (empty if absent); handles ' and " quoting
    grep -o "<entry key=[\"']$1[\"']>[^<]*</entry>" "$CONF" 2>/dev/null \
        | sed -e 's/^[^>]*>//' -e 's/<.*$//' | head -1
}

# --------------------------------------------------------------------------
section "S-F0 — staging host inventory"
run hostname
run hostname -I
run uptime
run ls -la /opt/traccar/ /opt/traccar/scripts/ /opt/traccar/logs/
echo "NOTE: the staging host COUNT must come from the DO console droplet list (manual step)." >> "$OUT"

# --------------------------------------------------------------------------
section "S-F1 — deployed script sha256 (compare to the 8-row table in the runbook)"
run sha256sum /opt/traccar/scripts/archive_cold_storage.py

# --------------------------------------------------------------------------
section "S-F2 — crontabs (is STAGING's archive cron armed?)"
run crontab -l
if id traccar >/dev/null 2>&1; then
    run sudo -u traccar crontab -l
else
    echo "no 'traccar' user on this host" >> "$OUT"
fi
run ls -la /etc/cron.d/
for f in /etc/cron.d/*; do
    [ -f "$f" ] && run cat "$f"
done
run grep -r archive /var/spool/cron/

# --------------------------------------------------------------------------
section "S-F3 — archive.log (THE run history)"
run ls -la /opt/traccar/logs/
run tail -n 300 /opt/traccar/logs/archive.log
run grep -n -E "Archive complete|WARNING|ERROR|CRITICAL|MISMATCH" /opt/traccar/logs/archive.log

# --------------------------------------------------------------------------
section "S-F10 — archive.* / database.* config (passwords auto-redacted)"
if [ -f "$CONF" ]; then
    echo "\$ grep -n 'archive\\.|database\\.' $CONF   (values redacted where key contains password/secret/token)" >> "$OUT"
    grep -n "archive\.\|database\." "$CONF" | redact >> "$OUT"
    echo "REMINDER: compare database.url's HOST against prod's (local answers file, P-F10) — STOP 5." >> "$OUT"
else
    echo "MISSING: $CONF — every s3cmd section below will be an INVALID MEASUREMENT." >> "$OUT"
fi

# --------------------------------------------------------------------------
# s3cmd invocation: system binary if present, else the config's python+script
# (the same pair the archiver itself uses).
S3CFG="$(getcfg archive.s3cmd.configFile)"
STAGING_BUCKET="$(getcfg archive.spaces.bucket)"
if command -v s3cmd >/dev/null 2>&1; then
    S3=(s3cmd)
else
    PYEXE="$(getcfg archive.python.exe)"
    S3SCRIPT="$(getcfg archive.s3cmd.script)"
    if [ -n "$PYEXE" ] && [ -n "$S3SCRIPT" ]; then
        S3=("$PYEXE" "$S3SCRIPT")
    else
        S3=()
    fi
fi

s3_unavailable () {
    echo "INVALID MEASUREMENT — cannot invoke s3cmd." >> "$OUT"
    echo "  s3cmd binary: $(command -v s3cmd || echo none)" >> "$OUT"
    echo "  archive.python.exe: '$(getcfg archive.python.exe)'  archive.s3cmd.script: '$(getcfg archive.s3cmd.script)'" >> "$OUT"
    echo "  archive.s3cmd.configFile: '${S3CFG:-<absent>}'" >> "$OUT"
    echo "  Per the runbook's measurement-validity rule this is NOT evidence of an empty prefix." >> "$OUT"
}

list_bucket () { # list_bucket <label> <s3-url>  (READ-ONLY: s3cmd ls)
    section "$1 — recursive listing of $2 (zero counts valid ONLY with exit=0 and empty stderr)"
    if [ ${#S3[@]} -eq 0 ] || [ -z "$S3CFG" ]; then
        s3_unavailable
        return
    fi
    local lst err rc
    lst=$(mktemp) || return
    err=$(mktemp) || { rm -f "$lst"; return; }
    "${S3[@]}" --config "$S3CFG" ls --recursive "$2" > "$lst" 2> "$err"
    rc=$?
    {
        echo "\$ ${S3[*]} --config $S3CFG ls --recursive $2"
        echo "exit=$rc"
        echo "--- stderr ---"
        cat "$err"
        echo "--- counts ---"
        echo "lines:        $(wc -l < "$lst")"
        echo ".parquet.tmp: $(grep -c '\.parquet\.tmp$' "$lst")   # ANY hit => STOP 1"
        echo ".parquet:     $(grep -c '\.parquet$' "$lst")"
        echo ".done:        $(grep -c '\.done$' "$lst")"
        echo "--- full listing ---"
        cat "$lst"
    } >> "$OUT"
    rm -f "$lst" "$err"
}

s3_info () { # s3_info <label> <s3-url>  (READ-ONLY: s3cmd info)
    section "$1 (zero/empty output valid ONLY with exit=0 and empty stderr)"
    if [ ${#S3[@]} -eq 0 ] || [ -z "$S3CFG" ]; then
        s3_unavailable
        return
    fi
    local body err rc
    body=$(mktemp) || return
    err=$(mktemp) || { rm -f "$body"; return; }
    "${S3[@]}" --config "$S3CFG" info "$2" > "$body" 2> "$err"
    rc=$?
    {
        echo "\$ ${S3[*]} --config $S3CFG info $2"
        echo "exit=$rc"
        echo "--- stderr ---"
        cat "$err"
        echo "--- stdout ---"
        cat "$body"
    } >> "$OUT"
    rm -f "$body" "$err"
}

list_bucket "S-F4" "s3://$PROD_BUCKET/archive/"

if [ -n "$STAGING_BUCKET" ] && [ "$STAGING_BUCKET" != "$PROD_BUCKET" ]; then
    list_bucket "S-F4b" "s3://$STAGING_BUCKET/archive/"
else
    section "S-F4b — staging-bucket listing"
    echo "n/a — archive.spaces.bucket is '${STAGING_BUCKET:-<absent>}' (same as $PROD_BUCKET, or absent)" >> "$OUT"
fi

s3_info "S-F5 — info s3://$PROD_BUCKET" "s3://$PROD_BUCKET"
if [ -n "$STAGING_BUCKET" ] && [ "$STAGING_BUCKET" != "$PROD_BUCKET" ]; then
    s3_info "S-F5 — info s3://$STAGING_BUCKET" "s3://$STAGING_BUCKET"
fi
echo "REMINDER: versioning ON/OFF and lifecycle rules also need the DO console (Spaces -> Settings per bucket) — s3cmd info alone may not show them." >> "$OUT"

# --------------------------------------------------------------------------
section "S-F9 — python + deps (the versions the script has actually RUN with)"
run /usr/bin/python3 --version
run /usr/bin/python3 -c "import pymysql, pandas, pyarrow, dateutil; print('ok')"
echo "\$ /usr/bin/python3 -m pip list | grep -iE 'pymysql|pandas|pyarrow|dateutil'" >> "$OUT"
/usr/bin/python3 -m pip list 2>/dev/null | grep -i -E "pymysql|pandas|pyarrow|dateutil" >> "$OUT"
echo "[exit=$?]" >> "$OUT"
PYEXE2="$(getcfg archive.python.exe)"
if [ -n "$PYEXE2" ] && [ "$PYEXE2" != "/usr/bin/python3" ]; then
    echo "archive.python.exe differs from /usr/bin/python3 — checking it too:" >> "$OUT"
    run "$PYEXE2" --version
    run "$PYEXE2" -c "import pymysql, pandas, pyarrow, dateutil; print('ok')"
fi

# --------------------------------------------------------------------------
section "MANUAL REMAINDER — staging DB (NOT run by this script)"
cat >> "$OUT" <<'EOF'
Run by hand in the STAGING MySQL client. FIRST verify the database.url host in
S-F10 above is NOT the prod DB (STOP 5) — if it is, stop and report immediately.

  -- S-F6 — staging DB timezone
  SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

  -- S-DEV — staging device-id inventory (STOP 5 cross-check vs prod)
  SELECT id, name FROM tc_devices ORDER BY id;
EOF

echo ""
echo "DONE. Output written to: $OUT"
echo "  1. Review it — passwords are auto-redacted, but eyeball before sharing."
echo "  2. Paste it into docs/.phase0-answers.local.md (Part A)."
echo "  3. Then delete it from this host:  rm -f $OUT"
