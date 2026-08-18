# Cold-Storage Archival Fix — Runbook

**Branch:** `riq-cold-storage-hardening` (from `master` @ `bc0d58dd2`)
**Source documents:** `docs/cold-storage-recon-2026-08-17.md` (read-only recon),
`rides-iq-cold-storage-archive-audit-2026-08-14.md` (data-loss audit).
All line references below were re-verified against the tree at `bc0d58dd2` on 2026-08-17.

**Decisions in force (D1–D10):** merge late rows into the month file; delete by exported id
in 10k chunks; finalize before delete (`.parquet` = archived, `.done` = archived AND deleted,
`.tmp` = incomplete destructive run, never auto-cleaned); run state stays in Spaces objects;
rehearsal = `--archive-only` against production into a scratch prefix; deploy only via
`package.sh` → makeself → `setup.sh`; no Java/schema changes; UTC pinned on the script's DB
session; a device's latest position (`tc_devices.positionid`) is excluded from deletion;
dependencies via `requirements.txt` + system pip in `setup.sh`.

---

## Phase 0 — Host state as of <PENDING>

**Status: BLOCKED.** No code will be written until every item below is answered or explicitly
waived in writing. Answers get recorded here **verbatim**.

All commands are read-only. Run on the production droplet as root unless marked otherwise.
SQL goes to the managed MySQL — run it yourself; nothing here should be executed by automation.

### Command block (copy-paste)

```bash
############################################################
# PHASE 0 — host-state gate (ALL READ-ONLY)
# Production droplet, as root, unless a line says otherwise.
############################################################

### F1 — deployed script hash
sha256sum /opt/traccar/scripts/archive_cold_storage.py
# Repo reference at master bc0d58dd2 (git show HEAD:scripts/archive_cold_storage.py | sha256sum):
#   0973d424f52ef2e201c31e571c68fde3d83e0ec10a6b912f434f3c7345f1776c
# Mismatch => STOP condition 4 below.

### F2 — live crontab(s)
crontab -l
sudo -u traccar crontab -l 2>/dev/null || echo "no crontab for user traccar (or no such user)"
ls -la /etc/cron.d/ 2>/dev/null && cat /etc/cron.d/* 2>/dev/null
grep -r archive /var/spool/cron/ 2>/dev/null

### F3 — archive.log history
ls -la /opt/traccar/logs/archive.log*
tail -n 300 /opt/traccar/logs/archive.log
grep -n -E "Archive complete|WARNING|ERROR|CRITICAL|MISMATCH" /opt/traccar/logs/archive.log | tail -50

### F4 — FULL recursive listing of archive/ (need every .tmp, .parquet, .done key)
# <host-s3cmd.ini> = the file named by archive.s3cmd.configFile in F10.
s3cmd --config <host-s3cmd.ini> ls --recursive s3://iotrides/archive/ | tee /tmp/archive_listing.txt
grep -c '\.parquet\.tmp$' /tmp/archive_listing.txt   # ANY hit => STOP condition 1
grep -c '\.parquet$'      /tmp/archive_listing.txt
grep -c '\.done$'         /tmp/archive_listing.txt
# Send back the WHOLE /tmp/archive_listing.txt, not just the counts.

### F5 — bucket versioning + lifecycle
s3cmd --config <host-s3cmd.ini> info s3://iotrides
# Plus DO console -> Spaces -> iotrides -> Settings: versioning ON/OFF, and every
# lifecycle rule (especially anything that could expire keys under archive/).
```

```sql
-- ### F6 — DB timezone (managed MySQL, read-only)
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

-- ### F7 — live table state + group discovery at the current cutoff (read-only)
-- CAUTION: the GROUP BY scans below can be heavy on a large table; run off-peak.
SELECT COUNT(*), MIN(fixtime),   MAX(fixtime)   FROM tc_positions;
SELECT COUNT(*), MIN(eventtime), MAX(eventtime) FROM tc_events;

-- Cutoff = (UTC today − archive.retention.months) as a date; retention comes from F10
-- (script default 6, scripts/archive_cold_storage.py:616-617). With retention 6 and a
-- run date of 2026-08-17, cutoff = '2026-02-17'. Adjust if you run this another day.
SELECT deviceid, YEAR(fixtime) AS yr, MONTH(fixtime) AS mo, COUNT(*) AS cnt
FROM tc_positions WHERE fixtime < '2026-02-17'
GROUP BY deviceid, YEAR(fixtime), MONTH(fixtime)
ORDER BY deviceid, yr, mo;

SELECT deviceid, YEAR(eventtime) AS yr, MONTH(eventtime) AS mo, COUNT(*) AS cnt
FROM tc_events WHERE eventtime < '2026-02-17'
GROUP BY deviceid, YEAR(eventtime), MONTH(eventtime)
ORDER BY deviceid, yr, mo;
```

```bash
### F9 — host Python + deps (check BOTH interpreters; the cron uses /usr/bin/python3,
### s3cmd subprocesses use archive.python.exe from F10 — they may differ)
/usr/bin/python3 --version
/usr/bin/python3 -c "import pymysql, pandas, pyarrow, dateutil; print('ok')" \
  || echo "IMPORT FAILURE ^"
/usr/bin/python3 -m pip list 2>/dev/null | grep -i -E "pymysql|pandas|pyarrow|dateutil"
# If F10's archive.python.exe != /usr/bin/python3, repeat the three lines with that path.

### F10 — real host config values (REDACT database.password and any credential values —
### key names and non-secret values only; I do not need the secrets)
grep -n "archive\.\|database\." /opt/traccar/conf/traccar.xml
```

### STOP conditions — if any is true, halt; no code gets written until it is resolved

1. **Any `*.tmp` object exists under `archive/`** (F4) → an unfinished destructive run.
   Reconciliation comes first; changing tmp-key semantics on top of live residue would
   destroy evidence (the tmp may be the only copy of already-deleted rows — audit Inv. 4).
2. **`archive.log` shows a completed deleting run whose group count doesn't reconcile
   against the `.done` inventory** (F3 vs F4).
3. **Bucket versioning is OFF** (F5) → Phase 1 item 1 (enable versioning) must land before
   any code that writes to the bucket.
4. **The deployed script hash (F1) matches no commit in
   `git log -- scripts/archive_cold_storage.py`** → prod was hand-edited; diff it first.

### Answers (verbatim, to be filled)

| # | Item | Answer |
|---|------|--------|
| F1 | Deployed script sha256 | _pending_ |
| F2 | Live crontab(s) | _pending_ |
| F3 | archive.log tail + grep | _pending_ |
| F4 | Full `archive/` listing (.tmp/.parquet/.done) | _pending_ |
| F5 | Versioning + lifecycle | _pending_ |
| F6 | `@@global.time_zone`, `@@session.time_zone`, `NOW()`, `UTC_TIMESTAMP()` | _pending_ |
| F7 | Row counts, min/max times, group discovery | _pending_ |
| F9 | Python version + imports (both interpreters) | _pending_ |
| F10 | `archive.*` / `database.*` config entries (redacted) | _pending_ |

Not gated in Phase 0 but needed later (will be requested at Phase 4/5): F12 — which bucket
`release.yml:53` actually uploads to (`s3://traccar/builds/` URL vs `--host-bucket=iotrides`);
F11 — who holds Spaces write credentials.
