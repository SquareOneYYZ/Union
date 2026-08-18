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

**Data-handling rule for this document:** this repo is public. Raw Phase 0 output — listings,
config entries, device IDs, log excerpts — is logged ONLY in `docs/.phase0-answers.local.md`
(gitignored, never committed). This committed runbook records **derived conclusions only**:
counts, ON/OFF states, match/mismatch verdicts.

---

## Phase 0 — Host state as of <PENDING>

**Status: BLOCKED.** No code will be written until every item below is answered or explicitly
waived in writing.

Raw command output goes into `docs/.phase0-answers.local.md` (a template with one section per
item exists there; it is gitignored). Derived conclusions get recorded in the table at the end
of this section.

All commands are read-only. Run on the production droplet as root unless marked otherwise.
SQL goes to the managed MySQL — run it yourself; nothing here should be executed by automation.

### Command block (copy-paste) — order matters: F10 runs before F4/F9, which need its values

```bash
############################################################
# PHASE 0 — host-state gate (ALL READ-ONLY)
# Production droplet, as root, unless a line says otherwise.
# Paste raw output into docs/.phase0-answers.local.md (NOT the runbook).
# Record `date -u` alongside every section you collect; the answers file
# carries a Collected: line per section and a top-level collection window.
############################################################

### F0 — host inventory (FIRST). The recon never verified single-host; do not
### assume it. Confirm from the DO console droplet list how many hosts run this
### archiver. If more than one: run EVERY host-side item below (F0-F3, F9, F10)
### on EACH host, as its own subsection in the answers file.
date -u +"%Y-%m-%dT%H:%M:%SZ"
hostname; hostname -I; uptime
ls -la /opt/traccar/ /opt/traccar/scripts/ 2>/dev/null

### F1 — deployed script hash
sha256sum /opt/traccar/scripts/archive_cold_storage.py
# Compare against the eight-row historical hash table in the runbook.
# No match against ANY row => STOP condition 4.

### F2 — live crontab(s)
crontab -l
sudo -u traccar crontab -l 2>/dev/null || echo "no crontab for user traccar (or no such user)"
ls -la /etc/cron.d/ 2>/dev/null && cat /etc/cron.d/* 2>/dev/null
grep -r archive /var/spool/cron/ 2>/dev/null

### F3 — archive.log history
ls -la /opt/traccar/logs/archive.log*
tail -n 300 /opt/traccar/logs/archive.log
grep -n -E "Archive complete|WARNING|ERROR|CRITICAL|MISMATCH" /opt/traccar/logs/archive.log | tail -50

### F10 — real host config values (RUNS BEFORE F4/F9 — they need these paths)
### REDACT database.password and any credential values — key names and
### non-secret values only.
grep -n "archive\.\|database\." /opt/traccar/conf/traccar.xml

### F4 — FULL recursive listing of archive/ (every .tmp, .parquet, .done key)
# <host-s3cmd.ini> = the file named by archive.s3cmd.configFile from F10.
s3cmd --config <host-s3cmd.ini> ls --recursive s3://iotrides/archive/ | tee /tmp/archive_listing.txt
grep -c '\.parquet\.tmp$' /tmp/archive_listing.txt   # ANY hit => STOP condition 1
grep -c '\.parquet$'      /tmp/archive_listing.txt
grep -c '\.done$'         /tmp/archive_listing.txt
# The whole /tmp/archive_listing.txt goes into the local answers file.
# Then remove it — it is a full map of the archive sitting on a prod host:
rm -f /tmp/archive_listing.txt

### F5 — bucket versioning + lifecycle
s3cmd --config <host-s3cmd.ini> info s3://iotrides
# Plus DO console -> Spaces -> iotrides -> Settings: versioning ON/OFF and every
# lifecycle rule (especially anything that could expire keys under archive/).

### F9 — host Python + deps (check BOTH interpreters: cron uses /usr/bin/python3,
### s3cmd subprocesses use archive.python.exe from F10 — they may differ)
/usr/bin/python3 --version
/usr/bin/python3 -c "import pymysql, pandas, pyarrow, dateutil; print('ok')" \
  || echo "IMPORT FAILURE ^"
/usr/bin/python3 -m pip list 2>/dev/null | grep -i -E "pymysql|pandas|pyarrow|dateutil"
# If F10's archive.python.exe != /usr/bin/python3, repeat the three lines with that path.
```

```sql
-- ### SQL section — managed MySQL, read-only. Sized for a ~623M-row tc_positions:
-- ### no full-table COUNT/MIN/MAX scans. Run in the order given; F7d runs LAST,
-- ### on its own, off-peak.

-- ### F6 — DB timezone
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

-- ### F7a — approximate row counts (statistics read, no scan; table_rows is an
-- ### estimate and that is fine for this gate)
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('tc_positions', 'tc_events');

-- ### F7b — index inventory FIRST (decides whether F7c is safe to run)
SHOW INDEX FROM tc_positions;
SHOW INDEX FROM tc_events;

-- ### F7c — min/max time, ONLY if F7b shows an index whose LEADING column is
-- ### fixtime (resp. eventtime). If not: SKIP these and record min/max as
-- ### "deferred — no index" in the answers file. Do NOT run MIN()/MAX() or any
-- ### unindexed ORDER BY on these tables.
SELECT fixtime   FROM tc_positions ORDER BY fixtime  ASC  LIMIT 1;
SELECT fixtime   FROM tc_positions ORDER BY fixtime  DESC LIMIT 1;
SELECT eventtime FROM tc_events    ORDER BY eventtime ASC  LIMIT 1;
SELECT eventtime FROM tc_events    ORDER BY eventtime DESC LIMIT 1;

-- ### F7d — group discovery. Run LAST, ALONE, OFF-PEAK. This is the one
-- ### unavoidably heavy query (the deployed script runs this exact shape every
-- ### monthly cron fire — scripts/archive_cold_storage.py:355-360). If F7b
-- ### showed no usable index on the time column and you judge the scan
-- ### unacceptable, DO NOT run it — report that instead; "the discovery query
-- ### cannot be run safely" is itself a critical Phase 0 answer.
--
-- Cutoff = (UTC today - archive.retention.months) as a date; retention from F10
-- (script default 6). With retention 6 and run date 2026-08-17 -> '2026-02-17'.
SELECT deviceid, YEAR(fixtime) AS yr, MONTH(fixtime) AS mo, COUNT(*) AS cnt
FROM tc_positions WHERE fixtime < '2026-02-17'
GROUP BY deviceid, YEAR(fixtime), MONTH(fixtime)
ORDER BY deviceid, yr, mo;

SELECT deviceid, YEAR(eventtime) AS yr, MONTH(eventtime) AS mo, COUNT(*) AS cnt
FROM tc_events WHERE eventtime < '2026-02-17'
GROUP BY deviceid, YEAR(eventtime), MONTH(eventtime)
ORDER BY deviceid, yr, mo;
```

### Historical script hashes (for F1 / STOP condition 4)

Every version of `scripts/archive_cold_storage.py` that has ever been committed
(`git log --format=%H -- scripts/archive_cold_storage.py`, 8 commits), hashed via
`git show <sha>:scripts/archive_cold_storage.py | sha256sum`:

| Commit | Date | sha256 |
|---|---|---|
| `a364cefc4` (= HEAD) | 2026-05-05 | `0973d424f52ef2e201c31e571c68fde3d83e0ec10a6b912f434f3c7345f1776c` |
| `51c527004` | 2026-03-14 | `53cc9090deba71c978ce8da4dfabd75be819b6a8d8ed06b547c1f9848c8d0713` |
| `767d7cfb7` | 2026-03-06 | `b7c86e11f5e680fe1f2d3904e5f84c7f5f68a1129286108ec8465ba3cd036ade` |
| `a5280ebf9` | 2026-03-03 | `6e34a05728967fb7a06a0868c5e59e5f7679b48a9b07966d3808c8804c6b7892` |
| `3ecbfa173` | 2026-03-02 | `917d82e749918f3bcdf50b0aca7b961548282df6fc31a6b52f0fc0db1861dc1e` |
| `3f3da9faa` | 2026-03-02 | `2248f1c3ac5f686ed3cd8dbee0b9315c2cabea0e605b2180d612494de31f7b72` |
| `571468402` | 2026-02-25 | `ea0b886c95d051d3c80e26f8f336b82fa56d5a6485d0ba12054b6657fe15acf7` |
| `172a12288` | 2026-02-24 | `b53c8fda4fd98340a27b6677d6fd5a4510b5a5060b2299fd9e617095fb1ab014` |

F1 matching `a364cefc4` = deployed script is current. Matching an older row = stale deploy
(diff it before changing anything). Matching **no** row = STOP condition 4.

### Collection-window rule

Every answers-file section carries a `Collected:` UTC timestamp. If the window from first to
last collection spans **more than 24 hours**, F4 (bucket listing) and F7d (group discovery)
are stale relative to each other — they must be re-taken together before any STOP-condition
verdict below is treated as final.

### STOP conditions — if any is true, halt; no code gets written until it is resolved

1. **Any `*.tmp` object exists under `archive/`** (F4) → an unfinished destructive run.
   Reconciliation comes first; changing tmp-key semantics on top of live residue would
   destroy evidence (the tmp may be the only copy of already-deleted rows — audit Inv. 4).
2. **`archive.log` shows a completed deleting run whose group count doesn't reconcile
   against the `.done` inventory** (F3 vs F4).
3. **Bucket versioning is OFF** (F5) → Phase 1 item 1 (enable versioning) must land before
   any code that writes to the bucket.
4. **The deployed script hash (F1) matches no row in the table above** → prod was
   hand-edited; diff it first.

### Derived conclusions (committed — counts, states, and verdicts ONLY; raw output stays in `docs/.phase0-answers.local.md`)

| # | Item | Conclusion |
|---|------|------------|
| F0 | Number of hosts running the archiver (per DO console droplet list) | _pending_ |
| F1 | Deployed hash matches commit … / no commit (per host if F0 > 1) | _pending_ |
| F2 | Archive cron installed: yes/no; where | _pending_ |
| F3 | Completed deleting runs: count; errors present: yes/no | _pending_ |
| F4 | `.tmp` count / `.parquet` count / `.done` count | _pending_ |
| F5 | Versioning ON/OFF; lifecycle touching `archive/`: yes/no | _pending_ |
| F6 | Global tz / session tz (values are not secrets) | _pending_ |
| F7 | Approx row counts; time-col indexed: yes/no; min/max or "deferred"; group count | _pending_ |
| F9 | Python version; all four deps import: yes/no (per interpreter) | _pending_ |
| F10 | Archive keys present: list of key NAMES only; retention value | _pending_ |

Not gated in Phase 0 but needed later (will be requested at Phase 4/5): F12 — which bucket
`release.yml:53` actually uploads to (`s3://traccar/builds/` URL vs `--host-bucket=iotrides`);
F11 — who holds Spaces write credentials.

---

## Phase 0 branch plans (which fork of the plan applies, decided by F2/F4)

### Branch A — F2 shows NO archive cron installed anywhere

Then nothing fires on 2026-09-01 and **there is no deadline**. Consequences, explicitly:

- Phase 1 item 3 ("neutralize the cron") becomes a **verification**, not an action: record
  its absence in the answers file. The live risk inverts — it is no longer "the cron fires
  before the fix lands" but "**an installer run installs the cron**": `setup/setup.sh:29-35`
  installs it on any `traccar.run` execution whenever `archive.spaces.bucket` is present in
  `/opt/traccar/conf/traccar.xml`. Rule until cutover: no one runs an installer on the host
  outside the Phase 5 sequence.
- Phase 1 re-sequences from "cron-neutralize first, everything else after" to risk order
  with no calendar pressure: (1) bucket versioning — still first, it gates any bucket write
  including the rehearsal; (2) baseline queries — any time; (3) DB snapshot — deferred to
  immediately before the first destructive run in Phase 5, where it belongs.
- Phases 2–4 proceed at normal pace; the Phase 5 sequence is unchanged (it already assumes
  the cron is disabled until the very last step, which becomes "install/enable cron" rather
  than "re-enable").

### Branch B — F4 shows an empty or near-empty `archive/` prefix

Then no (or almost no) legacy objects were written under the **old** semantics, and D3's
semantics change (`.parquet` = archived; `.done` = archived AND deleted; `.tmp` = evidence)
requires **no reconciliation of pre-existing state**. Consequences:

- STOP conditions 1 and 2 are evaluated trivially: no `.tmp` residue can exist, and there is
  no `.done` inventory to reconcile against `archive.log` (F3 must still be read — a log
  showing deleting runs WITH an empty bucket is a worse finding, not a better one: rows
  deleted with no archive copy → full stop, D0-level incident, report immediately).
- Phase 5 drops these steps: the legacy `.tmp` sweep as a *precondition* (the post-run
  `.tmp`-sweep check stays); the log-vs-`.done` legacy reconciliation; and the D8 caveat
  recording the host-tz rendering of *existing* Parquet files (nothing exists to record —
  all files will be born under pinned UTC, and the runbook's tz-discrepancy note reduces to
  "none: archive created entirely post-fix").
- "Near-empty" is not "empty": every object that DOES exist still gets individually
  accounted for in the answers file (key name, which semantics wrote it, whether its rows
  are still in the DB) before any destructive run.
