# Cold-Storage Archival Fix — Runbook

**Branch:** `riq-cold-storage-hardening` (from `master` @ `bc0d58dd2`)
**Source documents:** `docs/cold-storage-recon-2026-08-17.md` (read-only recon),
`rides-iq-cold-storage-archive-audit-2026-08-14.md` (data-loss audit).
All line references below were re-verified against the tree at `bc0d58dd2` on 2026-08-17.

**Decisions in force (D1–D10):** merge late rows into the month file; delete by exported id
in 10k chunks; finalize before delete (`.parquet` = archived, `.done` = archived AND deleted,
`.tmp` = incomplete destructive run, never auto-cleaned); run state stays in Spaces objects;
deploy only via `package.sh` → makeself → `setup.sh`; no Java/schema changes; UTC pinned on
the script's DB session; a device's latest position (`tc_devices.positionid`) is excluded
from deletion; dependencies via `requirements.txt` + system pip in `setup.sh`.

**Decision revisions (2026-08-18):**

- **D5 (revised):** the archiver has never run in prod — it has run only in staging. The
  rehearsal target is now **staging**, not a prod scratch prefix — but only once STOP 5
  (below) is resolved and staging is confirmed to use a bucket or prefix that prod will
  never read. If staging shares the prod bucket, fixing that isolation is a prerequisite to
  using staging for anything.
- **C1 lock-design input (open):** with a confirmed second environment, the lock choice
  (per-host `flock` vs. Spaces lock object) is decided by whether staging and prod could
  ever run against the same DB or the same bucket. Recorded under "C1 lock decision input"
  below once staging's config answers arrive.

**Backup branch note:** `riq-cold-storage-hardening-preclean` is a LOCAL-ONLY backup of the
pre-scrub history of this branch (kept so nothing is lost during the clean rebuild). It must
**never be pushed** to any remote.

**Data-handling rule for this document:** this repo is public. Raw Phase 0 output — listings,
config entries, hostnames, device IDs, log excerpts — is logged ONLY in
`docs/.phase0-answers.local.md` (gitignored, never committed). This committed runbook records
**derived conclusions only**: counts, ON/OFF states, match/mismatch verdicts.

---

## Phase 0 — host state (two environments)

**Status: BLOCKED** on the staging evidence set and the remaining prod items. As of
2026-08-18, staging is the **primary evidence source** (real run history, real objects, real
behaviour); prod's questions have narrowed to: **is it armed, and is its key space already
contaminated.**

**COLLECTION COMPLETE (2026-08-19): every Phase 0 question is answered.** What remains are
ACTIONS, not answers: the console items (versioning ON, A0 access-policy verification,
droplet-count confirmation), the Branch C A-steps, the A1 answer (2026-08-13 temp-dir
touch — still open as a courtesy check before the staging repoint), S-F6 (optional,
informational only now that the server tz is known UTC), and **one late-opened question
that blocks only the setup.sh commit: HOW staging's Python packages were installed**
(pip/apt/venv — see the C8 consequences below).

### Derived conclusions (prod partials 2026-08-18; staging set 2026-08-19 — all staging measurements VALID; raw output in the local answers file)

| Item | Environment | Conclusion |
|---|---|---|
| F0 host inventory | prod | **ANSWERED: 1 host** (operator-confirmed 2026-08-19; single Linux box per environment, no multi-host archiver) |
| F0 host inventory | staging | **ANSWERED: 1 host**, installed by the same 2026-07-07 installer run as prod. **Open question A1: archiver temp dir mtime 2026-08-13 with no bucket writes since 2026-05-04 — what ran on Aug 13? Must be answered before the staging repoint (Branch C)** |
| F1 deployed hash | prod | **matches `a364cefc4` (= HEAD)** → STOP 4 clear; no hand-edits |
| F1 deployed hash | staging | **matches `a364cefc4` (= HEAD)** → STOP 4 clear; no hand-edits |
| F2 archive cron | prod | **NOT INSTALLED** → no prod 2026-09-01 deadline (Branch A) |
| F2 archive cron | staging | **armed but hand-parked to a yearly schedule** — next fire 2027-03-09; the parking postdates the 2026-07-07 installer run (which resets the cron to monthly, proving the rearm-by-installer risk is real on staging too). No staging 2026-09-01 deadline |
| F3 archive.log | prod | **does not exist** — archiver has never run on prod |
| F3 run history | staging | **no archive.log exists** — run history comes from bucket object dates + markers instead: write bursts 2026-02-24→03-14 (dev era) and 2026-05-04; **5 `.done` markers prove real deleting runs against the staging DB** (2026-03-14 and 2026-05-04) |
| F4 bucket listing | shared | **VALID (exit=0, empty stderr): 15,177 `.parquet`, 5 `.done`, 0 `.tmp`** — prefix NOT empty, all objects staging-provenance → STOP 1 clear, STOP 5 TRIGGERED. Clock-garbage months confirmed at scale (a ~1,179-device cluster in month 2000-01, plus 1980/2004/2008/2013 strays) |
| F5 versioning/lifecycle | shared | **VALID: versioning OFF → STOP 3 TRIGGERED.** No lifecycle expiry rule. Bucket access-policy state is recorded in the local answers file and is verified under A0 (human, console) |
| F6 DB timezone | prod/staging (same server) | **COLLECTED: `SYSTEM`/`SYSTEM` and `@@system_time_zone = UTC`.** The D8 concern about pre-C1 objects rendered in a non-UTC zone is closed in practice — the C1 pin matches what's on disk. Framing stays observation-not-guarantee (SYSTEM follows the OS); explicit tz pinning remains a required step everywhere |
| F7 table state + discovery | prod | **P-F7a COLLECTED: tc_positions ≈730.7M, tc_events ≈103.4M** (larger than the ~623M planning figure). **P-F7b COMPLETE: positions = PRIMARY(id) + (deviceid, fixtime); tc_events = PRIMARY(id) + (deviceid, eventtime) + (deviceid, type, eventtime) — deviceid-led like positions, so device-iterated discovery applies to events too.** P-F7c deferred (no fixtime-led index). P-F7d time-scan form withdrawn as unsafe; **BACKLOG INVENTORY COLLECTED 2026-08-19 via the per-device index-backed form: 11,345 device-months / 366.76M rows (~50% of table) / 2,076 devices / 45 months / largest group ~197.5k rows / oldest = 2000-01 clock garbage. Point-in-time — re-take before cutover (Phase 5)** |
| F9 python/deps | prod | **Python 3.12.7, none of the three pip packages installed (apt's python3-dateutil IS present) — the archiver dies at import.** The FOURTH independent unarmed reason; the setup.sh install (now shipped) fetches three of the four on prod's first install |
| F9 python/deps | staging | **Python 3.12.7, all four deps import** — the measured working set IS the C8 pins. Install method ANSWERED: system pip in /usr/local (PEP 668 marker present, no venv), dateutil apt-managed (mixed provenance) — setup.sh matches it with `--break-system-packages` |
| F10 config keys | prod | **zero `archive.*` keys present** (database.* present) |
| F10 config keys | staging | **bucket = the shared bucket → STOP 5**; retention 6; no interpreter split (same python3 for script and s3cmd). **DB = same managed MySQL server as prod, different schema** — not the prod database (no full stop), but shared DB infrastructure |
| Device-id overlap | both DBs | _pending — the 5 staging markers sit on device ids recorded in the local answers file; prod's autoincrement space almost certainly also contains them_ |

**Consequences already in force:**

1. **The 2026-09-01 deadline is VOID — Branch A is active.** Prod is unarmed FOUR
   independent ways (updated 2026-08-19): no archive cron; zero `archive.*` keys in its
   config (so an installer re-run would not install the cron, and a hand-run could not
   upload); an empty archive prefix in its future bucket; and **none of the four Python
   dependencies installed — the script dies at import before doing anything.** The
   unarmed verdict does not rest on the cron check alone.
2. **Phase 1 gains item 0 (standing rule, already in effect):** nobody runs `traccar.run` on
   prod outside the Phase 5 sequence, and nobody adds `archive.*` keys to prod's config
   before cutover — either action is what arms prod.
3. **Degenerate-date risk is live in prod:** the prod ingest layer runs with a permissive
   SQL mode, so zero/garbage dates cannot be ruled out in old rows. The audit's Inv. 5
   degenerate-date failure path (group with an unconstructible month → fails every run) must
   be handled, and the Phase 1 anomaly scan (`fixtime < '2015-01-01' OR fixtime IS NULL`)
   is not optional.

### Config-value validation map (third review: every config read, its validator, its failure mode)

Ordered by the third occurrence of the same defect class — a guard sitting on a path
production doesn't take (`--dry-run` vs verification, the CLI `--months` vs the config
retention, the fabricated packaging self-test vs `package.sh`). Every config value the
script reads, audited:

| Key | Validated by | On absent / malformed |
|---|---|---|
| `archive.retention.months` | **`resolve_retention_months`** (at the RESOLUTION point — covers the cron path, not just `--months`) | absent → 6; non-numeric or < 1 → fatal, named message |
| `archive.quarantine.floor` | **`parse_quarantine_floor`** (main: fatal via `configure_quarantine_floor`; selfcheck: report-and-continue) | absent → quarantine disabled; malformed → fatal (main) / reported FAIL (selfcheck) — a bad floor fails the gate at install, not a run later |
| `archive.s3cmd.timeout` | **`parse_s3_timeout`** (main: fatal via `configure_s3_timeout`; selfcheck: report-and-continue) | absent → 300 s; malformed/non-positive → fatal (main) / reported FAIL (selfcheck) |
| `archive.python.exe`, `archive.s3cmd.script` | **`build_s3cmd_base`** | absent → fatal (`sys.exit(1)`) |
| `archive.spaces.bucket` | no dedicated validator; **fail-closed downstream**: `do_upload` errors, probes error (`s3:///…`), selfcheck reports missing | absent → every group fails; selfcheck FAIL |
| `archive.temp.dir` | `ensure_temp_dir` creates it; selfcheck checks existence + writability | absent → `/tmp/traccar-archive`. **Held pending the F10 temp-dir answer — do not change until it arrives** |
| `database.url` | **regex in `get_connection` — FLAGGED: silent-default hazard.** An unparseable URL falls back to `localhost:3306/traccar` rather than failing. On prod hosts (no local MySQL) the connect then fails loudly; on a dev box with a local DB it would silently hit the wrong database. Noted, not changed (behavior predates this series); the selfcheck's DB check exercises the real config at install time | absent/unparseable → localhost defaults → connect fails loudly on the real hosts |
| `database.user` / `database.password` | none (defaults `root`/empty) | wrong values → connect fails loudly |
| `archive.s3cmd.configFile` | none — **FLAGGED: silent-default hazard.** Empty means s3cmd runs with its own default config (`~/.s3cfg` of the invoking user), which may be a different identity's credentials. Noted, not changed; the selfcheck's reachability + probe-premise checks exercise whatever config is actually in effect | absent → s3cmd defaults |
| `archive.local.upload.dir` | none — **FLAGGED: mode switch.** If set, ALL uploads divert to a local directory instead of the bucket. Visible: the startup banner prints `Mode: LOCAL TEST` and the KEY SPACE line. Must be ABSENT on prod (arming checklist) | absent → normal Spaces mode |

Rule of the map: a key with no validator row named here may not be added to the script —
new config reads get a validator or an explicit flagged entry, at review time.

### STOP-condition status as of 2026-08-19

| # | Condition | Status |
|---|---|---|
| 1 | `.tmp` residue | **CLEAR** (valid F4: zero `.tmp`) |
| 2 | log vs `.done` reconciliation | **UNRESOLVABLE AS SPECIFIED** — no archive.log exists anywhere; deleting runs are evidenced by the 5 markers instead. Absorbed into the STOP 5 disposition: the 5 marker-months' parquet files are the only copies of staging rows already deleted from the staging DB, and must be preserved through any relocation |
| 3 | versioning OFF | **TRIGGERED** — enabling versioning (Phase 1 item 1, human/console) must precede anything that writes to the bucket |
| 4 | unknown deployed hash | **CLEAR on BOTH hosts** (= HEAD, 2026-08-19); no hand-edits anywhere |
| 5 | shared bucket / key collision | **TRIGGERED** — staging's bucket IS the bucket prod will use; 15,177 staging objects + 5 markers occupy prod's future key space. Resolution path in Branch C below |

### STOP conditions — definitions (if any is true, halt; no code ships until resolved)

**STOP 5 — shared bucket / key collision (HIGHEST PRIORITY, new 2026-08-18).** Staging has
produced real archive objects; prod has produced none. If staging's
`archive.spaces.bucket` is the bucket prod will use (`iotrides`), then every object under
`archive/` is of staging provenance and sits in prod's future key space: staging `.done`
markers would make prod's first run **skip real prod device-months while counting them
archived** (`archive_cold_storage.py:383-386`), and staging `.parquet` files collide with
prod keys (same `archive/positions/{deviceid}/{YYYY-MM}.parquet` format, autoincrement
device ids overlapping across independent DBs). This is a live severity-1 condition, not a
hypothetical. Resolution requires: staging's bucket + s3cmd config identified (S-F10), the
bucket listed (S-F4), device-id overlap measured on both DBs, and staging confirmed to
write where prod will never read. Also confirmed as part of STOP 5: staging's
`database.url` does **not** point at the prod MySQL — if it does, staging's archiver
deletes prod rows: immediate full stop.

1. **Any `*.tmp` object exists under `archive/`** (S-F4) → an unfinished destructive run
   (staging's, by provenance — still evidence; audit Inv. 4 applies wherever it ran).
2. **Staging's `archive.log` shows a completed deleting run whose group count doesn't
   reconcile against the `.done` inventory** (S-F3 vs S-F4).
3. **Versioning is OFF on any bucket the fixed script will write to** (S-F5) → Phase 1
   item 1 must land before any code that writes to it.
4. **A deployed script hash (P-F1 or S-F1) matches no row in the hash table below** → that
   host was hand-edited; diff before trusting anything it produced (for staging this taints
   the run-history evidence, not just the future).

### Leftover tmp keys — resolution procedure (referenced by the C5 abort message)

A leftover `.parquet.tmp` aborts its own group only — other groups continue, and the run
exits non-zero at the end with a count of aborted groups. The code never deletes a tmp:
it cannot know which semantics produced it.

- **New semantics** (C4 onward, finalize-before-delete): the run died before finalize →
  the DB still holds every row for that device-month; the tmp is redundant residue.
- **Old semantics** (anything written before C4 deployed): the run died mid-delete → the
  tmp may be the ONLY copy of rows already deleted from the DB.

Resolution, read-only first, in order:

1. **Date the object**: `s3cmd info` on the tmp key — written before or after the C4
   deploy date on that host?
2. **Does the final `.parquet` exist** for the same device-month key?
3. **Does the DB still hold the rows?** Per-device count (index-safe — never a time-only
   scan):
   `SELECT COUNT(*) FROM <table> WHERE deviceid = <id> AND <timecol> >= '<month-start>'
   AND <timecol> < '<next-month-start>';`

Decision:

- DB holds the full month → the tmp is redundant: a human may delete it (record it in the
  local answers file first); the group re-runs normally next cycle.
- DB is missing rows AND no final key exists → **the tmp is the only copy: PRESERVE IT**;
  a restore is required before anything else touches that device-month.
- DB is missing rows AND a final key exists → compare the tmp's and the final's row sets
  before deciding anything; they were written by different attempts.

Automation never deletes a tmp. Only a human, after steps 1–3, on the record.

### Collection-window rule

Every answers-file section carries a `Collected:` UTC timestamp. If the window from first to
last collection spans **more than 24 hours**, S-F4 (bucket listing) and P-F7d (group
discovery) are stale relative to each other — re-take them together before any
STOP-condition verdict is treated as final.

### Measurement-validity rule (added 2026-08-18; ENFORCED IN CODE since the second review)

**Enforced by:** `run_s3cmd` (timeout/launch failure → None, never a result) and
`probe_key` (three-state: present / absent / probe FAILED) in
`scripts/archive_cold_storage.py`; every existence call site in `archive_table` fails its
group on a failed probe. Hard timeout via `archive.s3cmd.timeout` (default 300 s).

**Lesson, on the record:** this rule was written into this runbook on day one, after the
Phase 0 placeholder incident — and the implementation then encoded its OPPOSITE in three
places (marker, leftover-tmp, and final-exists probes all read "s3cmd failed" as "key
absent"), surviving 90 green tests and a completed audit-closure mapping until an
independent reviewer caught it. **A rule in a document is not a rule in the code.** Where
a runbook rule has a code counterpart, this document must name the function that enforces
it — and a rule with no code counterpart must say it is procedural. Enforcement pointers
for the other safety rules: leftover-tmp-is-evidence → the tmp probe/abort in
`archive_table` (C5); never-blind-overwrite → the final-exists probe + `merge_with_existing_final`
(C6); finalize-before-delete → `finalize_parquet` + the destructive-branch ordering (C4);
delete-only-exported-ids → `batch_delete_by_ids` + the count assertion (C3); latest-position
exclusion → `fetch_protected_position_ids` + `id_exclusions` (D9); cutoff-month skip →
the `cutoff_month_start` guard (C7); quarantine floor → `parse_quarantine_floor` + the
`period_end <= floor` routing; overlap exclusion → `acquire_run_lock`/`_lock_path`;
session bounding → `GroupBudget`; rehearsal isolation → `validate_prefix`;
no-arming-without-verification → the selfcheck gate in `setup/setup.sh`. Procedural
(no code counterpart, humans enforce): tz pinning for other readers, the collection-window
rule, the A-step sequencing, and the standing second-review requirement.

For every s3cmd-based item (S-F4, S-F4b, S-F5): **a zero count or empty output is only a
valid answer when the recorded exit code is 0 and stderr is empty.** A non-zero exit or any
stderr (access denied, no such bucket, bad config path) is an **invalid measurement**, not
evidence of an empty prefix — record it as "invalid — <reason>" and re-take it. Staging's
credentials may not be able to read `iotrides` at all; "denied" and "empty" must never be
conflated.

### Credential-hygiene note (2026-08-18)

A prod credential was mishandled during Phase 0 collection (pasted unredacted into the
local answers file). Verified same day: the local answers file has never been committed on
any ref (`git log --all --full-history`), no commit content anywhere in history contains
the credential (`git log --all -S`), the gitignore rule covers the file, and it is
untracked. **Rotation of the affected credential is required regardless of the clean git
result.**

### Command block A — STAGING (primary evidence; run first)

```bash
############################################################
# PHASE 0A — STAGING evidence set (ALL READ-ONLY)
# Staging droplet, as root. Paste into docs/.phase0-answers.local.md
# Part A, with date -u timestamps per section.
############################################################

### S-F0 — staging host inventory (+ DO console droplet list for the host count)
date -u +"%Y-%m-%dT%H:%M:%SZ"
hostname; hostname -I; uptime
ls -la /opt/traccar/ /opt/traccar/scripts/ /opt/traccar/logs/ 2>/dev/null

### S-F1 — staging deployed script hash (compare to the 8-row table below)
sha256sum /opt/traccar/scripts/archive_cold_storage.py

### S-F2 — staging crontab(s) — is staging's cron armed? It fires 0 4 1 * * too.
crontab -l
sudo -u traccar crontab -l 2>/dev/null || echo "no crontab for user traccar (or no such user)"
ls -la /etc/cron.d/ 2>/dev/null && cat /etc/cron.d/* 2>/dev/null
grep -r archive /var/spool/cron/ 2>/dev/null

### S-F3 — staging archive.log — THE run history
ls -la /opt/traccar/logs/archive.log*
tail -n 300 /opt/traccar/logs/archive.log
grep -n -E "Archive complete|WARNING|ERROR|CRITICAL|MISMATCH" /opt/traccar/logs/archive.log | tail -50

### S-F10 — staging config (REDACT passwords). Decides STOP 5:
### bucket, s3cmd config path, and whether database.url is the PROD MySQL.
grep -n "archive\.\|database\." /opt/traccar/conf/traccar.xml

### S-F4 — bucket listing. VARIABLE FORM — do not paste a <placeholder> into the
### command line (attempt #1 became a shell redirection and never ran). Exit code
### and stderr are captured so "empty" is distinguishable from "denied"/"no such
### bucket": a zero count is VALID ONLY when exit=0 and stderr is empty.
S3CFG="/path/from/S-F10/archive.s3cmd.configFile"   # <- EDIT THIS LINE FIRST
s3cmd --config "$S3CFG" ls --recursive s3://iotrides/archive/ > /tmp/archive_listing.txt 2>/tmp/archive_err.txt
echo "exit=$?"
cat /tmp/archive_err.txt
wc -l /tmp/archive_listing.txt
grep -c '\.parquet\.tmp$' /tmp/archive_listing.txt   # ANY hit => STOP 1
grep -c '\.parquet$'      /tmp/archive_listing.txt
grep -c '\.done$'         /tmp/archive_listing.txt
# Record exit code + stderr + whole listing in the local answers file, then:
rm -f /tmp/archive_listing.txt /tmp/archive_err.txt

### S-F4b — ONLY if S-F10 shows a staging bucket other than iotrides (same
### exit/stderr capture rule):
# s3cmd --config "$S3CFG" ls --recursive s3://<staging-bucket>/archive/ > /tmp/archive_listing.txt 2>/tmp/archive_err.txt
# echo "exit=$?"; cat /tmp/archive_err.txt; wc -l /tmp/archive_listing.txt
# grep -c '\.parquet\.tmp$' /tmp/archive_listing.txt; grep -c '\.parquet$' /tmp/archive_listing.txt; grep -c '\.done$' /tmp/archive_listing.txt
# rm -f /tmp/archive_listing.txt /tmp/archive_err.txt

### S-F5 — versioning + lifecycle for iotrides (and the staging bucket if
### different). Same rule: output only counts with exit=0 and empty stderr.
s3cmd --config "$S3CFG" info s3://iotrides 2>/tmp/info_err.txt
echo "exit=$?"
cat /tmp/info_err.txt
rm -f /tmp/info_err.txt
# Plus DO console -> Spaces -> Settings per bucket: versioning ON/OFF, every lifecycle rule.

### S-F9 — staging python + deps (the versions the script has actually RUN with)
/usr/bin/python3 --version
/usr/bin/python3 -c "import pymysql, pandas, pyarrow, dateutil; print('ok')" || echo "IMPORT FAILURE ^"
/usr/bin/python3 -m pip list 2>/dev/null | grep -i -E "pymysql|pandas|pyarrow|dateutil"
# If S-F10's archive.python.exe differs from /usr/bin/python3, repeat with that interpreter.
```

```sql
-- ### Staging DB (read-only) — run against the DB named by STAGING's database.url
-- ### (which per STOP 5 must NOT be the prod MySQL — verify the hostname first).

-- S-F6 — staging DB timezone (interprets the tz baked into staging's existing parquet — D8)
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

-- S-DEV — staging device-id inventory (STOP 5 collision cross-check vs prod)
SELECT id, name FROM tc_devices ORDER BY id;
```

### Command block B — PROD (remaining items only)

```bash
############################################################
# PHASE 0B — PROD remainder (ALL READ-ONLY)
# Prod droplet, as root. Already collected 2026-08-18: F0, F2 (no cron),
# F3 (no log), F10 (no archive keys). Still needed: F1, F9, and the DB set.
############################################################

### P-F1 — deployed script hash (first paste was empty — this is still required)
date -u +"%Y-%m-%dT%H:%M:%SZ"
sha256sum /opt/traccar/scripts/archive_cold_storage.py

### P-F9 — prod python + deps (decides whether requirements.txt pins can install cleanly)
/usr/bin/python3 --version
/usr/bin/python3 -c "import pymysql, pandas, pyarrow, dateutil; print('ok')" || echo "IMPORT FAILURE ^"
/usr/bin/python3 -m pip list 2>/dev/null | grep -i -E "pymysql|pandas|pyarrow|dateutil"
```

```sql
-- ### Prod managed MySQL (read-only). Estimates: tc_positions ~730.7M rows,
-- ### tc_events ~103.4M rows (P-F7a, 2026-08-19). No time-only-filtered
-- ### query is safe on tc_positions: the sole time-bearing index is
-- ### (deviceid, fixtime), led by deviceid.

-- P-F6 — COLLECTED: SYSTEM/SYSTEM; NOW() = UTC_TIMESTAMP() at collection.
-- "Currently UTC" is an OBSERVATION, not a guarantee (SYSTEM follows the OS
-- zone). One follow-up remains:
SELECT @@system_time_zone;

-- P-F7a — COLLECTED (estimates above).

-- P-F7b — PARTIALLY COLLECTED: tc_positions = PRIMARY(id) +
-- position_deviceid_fixtime(deviceid, fixtime). STILL NEEDED:
SHOW INDEX FROM tc_events;

-- P-F7c — DEFERRED per the recorded rule: no fixtime-led index exists, so a
-- global ORDER BY fixtime LIMIT 1 is a full scan. Do not run. (Per-device
-- min/max IS index-supported if ever needed.)

-- P-F7d — REMOVED FROM THIS CHECKLIST: NOT RUN — no usable index; a
-- time-only-filtered GROUP BY is a full scan of ~730M rows on an instance
-- staging shares. If a group inventory is needed later, obtain it
-- per-device (deviceid = ? AND fixtime < ? uses the index) or from
-- information_schema estimates.

-- P-DEV / S-DEV — OBSOLETED by Branch C full bucket separation (separate
-- buckets cannot produce colliding keys; the A5 empty-prefix gate covers
-- legacy objects). Optional background data only.
```

### Required step (from P-F6): explicit tz pinning everywhere

The DB server runs `time_zone = SYSTEM` and merely *happens* to resolve to UTC today. The
script's own sessions are pinned by C1 (`init_command SET time_zone '+00:00'`). Every
OTHER reader must pin explicitly too: the DuckDB/reconciliation sessions in Phase 5, any
ad-hoc client used for verification queries, and any future partition DDL (out of scope
here, D7). Record `@@system_time_zone` when collected. No step in this runbook may rely
on the current coincidence.

### Performance defect (distinct from the data-loss series) — decision pending

The deployed script's group-discovery query (`archive_cold_storage.py:355-360`) filters on
the time column alone (`WHERE fixtime < cutoff GROUP BY deviceid, year, month`). With the
only time-bearing index led by `deviceid`, **every cron fire begins with a full scan of
tc_positions (~730.7M rows), plus the analogous tc_events scan**, on a DB instance both
environments share. Export and delete are unaffected (they filter `deviceid` + time,
matching the index). Restructure options are recorded here; implementation waits for an
explicit decision. Whether tc_events has a usable `(deviceid, eventtime)` index is still
unknown (P-F7b outstanding) and affects the events side of any option.

**CONFIRMED VIABLE (2026-08-19) — Option A goes ahead as a bulk-migration precondition.**
Operator EXPLAIN + measurement on prod: the per-device group query runs as a `range` scan
on `position_deviceid_fixtime` with `Using where; Using index; Using temporary` — a
covering index that never touches row data. Measured 0.42 s for a 204k-row device with 13
device-months; a full per-device sweep of ~3,600 devices completed in ~35 minutes wall
clock on a single connection with no observed impact.

Final design, as decided: **devices are enumerated from `tc_devices`** (small, cheap);
discovery stays inside the script on its single persistent connection; each device's
groups come from the index-backed `deviceid = ? AND t < cutoff` GROUP BY. The group loop
is untouched (same groups list). The end of each run logs the device count and per-device
group counts, so a device that stops appearing is visible rather than silent.

**Known gap, by decision — orphan positions.** Rows whose deviceid no longer exists in
`tc_devices` are not discovered by the per-run loop. Handled by a documented **one-off
orphan sweep**, run manually and off-peak during cutover (this is a full index scan —
that is why it is one-off, not per-run):

```sql
-- ONE-OFF, OFF-PEAK: orphan positions (deviceid absent from tc_devices)
SELECT p.deviceid, COUNT(*) AS cnt
FROM tc_positions p LEFT JOIN tc_devices d ON d.id = p.deviceid
WHERE d.id IS NULL GROUP BY p.deviceid;

SELECT e.deviceid, COUNT(*) AS cnt
FROM tc_events e LEFT JOIN tc_devices d ON d.id = e.deviceid
WHERE d.id IS NULL GROUP BY e.deviceid;
```

Any orphans found get archived by targeted manual runs or an explicit decision — the
device-iterated design must never silently stop archiving rows the old time-scan would
have found, and the sweep is the mechanism that proves it hasn't.

**Events caveat DROPPED (2026-08-19):** tc_events carries `(deviceid, eventtime)` — the
same deviceid-led shape as positions — so device-iterated discovery applies to events too
(and the shipped `discover_groups` already handles both tables generically).

**Option B — add a fixtime-led index:** ruled out (D7 forbids DDL; an online index build
on 730M rows is its own project). **Option C — leave as-is:** rejected with Option A's
adoption; the cron being unarmed everywhere buys the time to do it properly.

### Historical script hashes (for F1 on either host / STOP condition 4)

Every version of `scripts/archive_cold_storage.py` ever committed
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

Match `a364cefc4` = current. Older row = stale deploy (diff before trusting). No row =
STOP 4.

### C1 lock decision — per-host `flock`; **VERIFIED on mechanism (CI) AND identity (L1)**

F0 is answered: **one Linux box per environment, no multi-host archiver anywhere**
(operator-confirmed). With separate buckets (Branch C) and separate DB schemas, the two
environments can never contend, so the Spaces lock-object design is **dropped**. `flock`
is scoped to what it actually protects: two invocations on the *same* box — the cron
firing while someone hand-runs the script, which is exactly the pattern staging's history
shows. Behavior: **non-blocking, fail fast and loud** — if the lock is held, the new
invocation logs an error and exits non-zero immediately; it never blocks and waits, so a
long-running manual run cannot silently queue a cron fire behind it.

**Lock FULLY VERIFIED (2026-08-19).** Mechanism: CI green — all three POSIX `flock`
contention tests passed on ubuntu (held lock → immediate exit 1; reacquire after release;
unopenable path → loud exit 1). Identity (L1, both hosts): `/var/lock` → `/run/lock`, mode
1777 (sticky, world-writable), and the archiver crontab is root's on both hosts — so the
cron and sudo'd hand-runs converge on the same `/var/lock/traccar-archive.lock`. The
design stands unchanged: fixed path, no fallback, fail-loud on unopenable. Operational
note: a hand-run WITHOUT sudo would work (1777 permits creating the file) but is
**discouraged for consistency** — once root's cron owns the lock file, a non-root run
fails loudly at open rather than sharing the lock, which is the safe behavior but a
confusing one; hand-runs should use sudo.

### L1 — lock-path identities (Phase 0 addendum; run on BOTH hosts)

```bash
### L1 — who can lock where (staging AND prod; all read-only)
ls -ld /var/lock /run/lock
id
stat -c '%U %G %a' /opt/traccar/scripts
ls -l /var/lock/traccar-archive.lock 2>/dev/null || echo "no lock file yet"
```

Also answer in the local answers file, per host:
- Which user owns the crontab that runs (or will run) the archiver? (Staging's is a user
  crontab — F2 showed it; the owning identity matters.)
- Which user would a human hand-run the script as (root? sudo? a personal account?)

---

## Phase 0 branch plans

### Branch A — prod cron not installed → **ACTIVE (confirmed 2026-08-18)**

- No 2026-09-01 deadline exists for prod. Phase 1 item 3 ("neutralize the cron") is
  satisfied by verification; the recorded risk is arming-by-side-effect: an installer run
  plus `archive.*` config on prod would install the cron (`setup/setup.sh:29-35`).
  **Standing rule (Phase 1 item 0): no `traccar.run` on prod and no `archive.*` keys in
  prod config outside the Phase 5 sequence.**
- Phase 1 re-sequences without calendar pressure: (1) bucket versioning — still first,
  gates any bucket write; (2) baseline queries — any time; (3) DB snapshot — deferred to
  immediately before the first destructive run in Phase 5.
- **Staging caveat (resolved 2026-08-19):** staging's cron is armed but hand-parked to a
  yearly schedule (next fire 2027-03-09) — no 2026-09-01 deadline on staging either. The
  parking postdates the 2026-07-07 installer run, which had reset the schedule to monthly:
  live proof that `setup.sh:29-35` rearms the cron on every installer run. The standing
  rule therefore extends to staging: an installer run there un-parks the cron to monthly,
  so no `traccar.run` on staging either outside a planned sequence.

### Branch B — `archive/` prefix empty → **INACTIVE (ruled out 2026-08-19)**

A valid S-F4 shows 15,177 objects. Full legacy disposition is required; the
no-legacy-reconciliation shortcut does not apply.

### Branch C — STOP 5 resolution → **REVISED 2026-08-19: full bucket separation**

Prod and staging use **entirely separate storage — different buckets, no shared prefix**.
Verified 2026-08-19 that this is a pure config change with no D7 collision: the bucket name
is config-driven on both sides — Java reads only `archive.spaces.bucket`
(`ArchiveResource.java:114-122` via `Keys.java:2096`), the script reads the same key
(`archive_cold_storage.py:102`), and no literal bucket name appears anywhere in
`src/main/java` or the script. The only fixed element is the `archive/` key *prefix*, which
is identical per bucket and works unchanged in both environments.

Sequence (human executes all console/bucket actions; none are run by Claude):

- **A0 — access-policy verification (first, blocking):** verify the bucket access
  policy against the record in the local answers file (DO console).
- **A1 — open question, answered before the staging repoint:** what touched the archiver
  temp dir on 2026-08-13? No longer a data-safety issue, but if someone is mid-experiment,
  changing the bucket under them is disruptive. Stays open until answered.
- **A2 — versioning ON for the prod bucket** before anything writes to it (closes STOP 3
  for the bucket that matters).
- **A3 — repoint staging's `archive.spaces.bucket` to its own bucket.**
- **A4 — legacy objects become cleanup, not a blocker:** with staging repointed, the 15,177
  objects in the old shared prefix are inert. Both options stay open and are sequenced
  AFTER the prod work: (a) copy across to the staging bucket, or (b) delete under
  versioning. The 5 marker-months' parquet files remain the only copies of staging rows
  already deleted from the staging DB — that fact weighs on the choice.
- **A5 — prod arming gate:** the `archive/` prefix of **whichever bucket prod's config
  names** must list EMPTY on a valid measurement (exit=0, empty stderr). Corollary: if prod
  were ever pointed at the old shared bucket instead of a fresh one, the legacy objects
  become a blocker again — the gate is stated against prod's configured bucket, not any
  particular bucket name.

---

## Audit-closure mapping (post-C8; against the 2026-08-14 audit's ranked list)

| # | Audit violation (rank) | Status | Closed by / disposition |
|---|---|---|---|
| 1 | Inv. 4 — resume path deletes the sole copy of already-deleted rows | **CLOSED** | C4 `eb94ff3db` (finalize-before-delete: for all new runs a leftover tmp means the DB is intact, never the sole copy) + C5 `ce08f3e36` (no tmp is EVER auto-deleted — old-semantics tmps included; abort-group, dual-reading error, runbook procedure) |
| 2 | Inv. 2/4 — final-parquet overwrite destroys the only copy | **CLOSED** | C6 `8067bd366` (existing finals are merged, additive-only asserted BEFORE the copy, schema mismatch aborts; an abort leaves the final untouched) |
| 3 | Inv. 1a — mid-run late-arrival race (window delete kills unexported rows) | **CLOSED** | C3 `5dbed01e6` (delete keyed to exported ids in 10k chunks; a row the export never saw cannot be deleted; count mismatch fails the group loudly, no repair) |
| 4 | Inv. 2 — marker-skip starves late rows (and miscounts them as archived) | **CLOSED** | C6 `8067bd366` (marker no longer skips; marker + rows = late data, merged and correctly counted) |
| 5 | Inv. 6 — dangling `positionid` | **PARTIALLY CLOSED** | C3/D9 `5dbed01e6`: `tc_devices.positionid` is fetched once per run and excluded from deletion (positions only; bounded residue ≤1 row/device, self-healing). **Open by design:** a live `tc_events.positionid` can reference a deleted position for up to a month (events keyed by eventtime, positions by fixtime) — bounded, self-resolving when the event itself ages out; measured by Phase 1 baseline 4b. Also open by design (D7, no Java changes): no coordination with the manual `DELETE /api/positions` endpoint — the flock guards archiver self-overlap only |
| 6 | Inv. 3 — cutoff/full-month mismatch; unpinned session tz | **CLOSED (code)** | C7 `c2fe7e225` (groups extending past the cutoff month start are skipped) + C1a `e2fce1108` (UTC pinned via init_command). Residual, procedural: every OTHER reader pins tz explicitly (runbook required step); server tz stays SYSTEM — observation, not guarantee |
| 7 | Inv. 5 — far-past (clock-garbage) rows leave the live DB within a month | **CLOSED PER DECISION** | Quarantine `5d60a5b06`: garbage months archive to `<prefix>-quarantine/` (invisible to the read path) and leave the DB — the zero-live-retention aspect accepted by decision (c), with floor + separate counts. **Zero-date finding CLOSED (approved 2026-08-19):** a malformed (yr, mo) from discovery — zero dates permitted by prod's permissive SQL mode — now fails its own group (counted, logged with device and month named, run exits non-zero at the end) instead of crashing the whole run partway through an 11,345-group migration |
| — | F8 hygiene (substring verify, no lock, no count alert) | **CLOSED** | C1a `e2fce1108` (exact-match verify), C1b+fixes `3be4afe69`/`6561c20f5`/`9848aa38f` (flock — verified in CI and on identity via L1), C3 (deleted-vs-exported assertion) |
| — | F9 — partition-drop job gated on the archiver | **OUT OF SCOPE BY DESIGN** | D7 forbids partition DDL; recorded for the future partitioning project: any DROP PARTITION must require markers for every device-month in the window AND a zero live count |

**Accepted behaviors, documented rather than changed:** `--dry-run` still deletes its own
upload and cannot serve as verification (help text and logs now say so; `--archive-only`
is the verification mode, per the task). The `.done`-marker upload failure stays warn-only:
under C6 a missing marker is harmless — the group re-merges and no overwrite is possible.
InnoDB files do not shrink on delete (Phase 5 expectation).

**Open items that are actions, not code:** STOP 3 (versioning ON), A0 (access-policy
verification), the Branch C A-steps, F12 (builds/ destination), A1 (Aug-13 temp-dir
touch).

### Follow-ups (filed 2026-08-21 — post-staging; no further review rounds before staging)

Item 1 is the exception to "post-staging": it is scheduled **before Phase 5 batch 1**, for
the reason stated in it. Items 2–6 are genuinely post-staging.

1. **Reconciliation value-checking gap — AFFECTS PHASE 5 DIRECTLY; do it before batch 1.**
   (Surfaced by the merge-collision limitation.) The DuckDB Invariant-7 query compares row
   counts, id min/max, and id sums. All three are *identity* checks: they prove the same
   set of ids exists on both sides. None of them compares the row's **contents**, so a row
   whose values diverged between archive and DB — the merge-collision case, where
   `drop_duplicates(keep="first")` retains the stale archived copy and the newer DB row is
   then deleted — reconciles as `OK`.

   Why this is a Phase 5 problem and not a cosmetic one: Invariant-7 is the per-batch gate
   (stop-and-verify criterion 3 below) and the rehearsal gate (precondition 7). Phase 5
   treats a green reconciliation as "this batch is verified"; what it actually establishes
   today is "no rows were lost or duplicated". That is a weaker statement than the gate is
   relied on for, and the gap is invisible in the output.

   **The asymmetry that sets the timing:** once a batch's rows are deleted from the live
   table there is nothing left to compare against, so a value check added after batch *N*
   protects only batch *N+1* onward — every earlier batch stays permanently unverifiable at
   value level, with no way to go back. A weak gate is recoverable while it is still ahead
   of you and unrecoverable once it is behind you.

   **What the remedy costs:** one extra aggregate expression on each side of the existing
   query — a hash/checksum over a stable column subset, per device, compared exactly like
   the id-sum column already is. No extra scan and no second pass: both sides already read
   these rows to compute counts and id sums, so the marginal cost is the hash function over
   columns already in flight. The real work is *choosing* the column subset and making the
   two sides agree on representation — the exporter stringifies datetime columns
   (`astype(str)`), so any column crossing that boundary must be normalised identically in
   the MySQL-side expression, and the subset should be columns the write path does not
   legitimately update. Excluding volatile columns is fine; the check only needs to be
   sensitive to the divergence class it exists to catch.

   **Verdict: worth doing before batch 1.** The cost lands while precondition 7's rehearsal
   is already exercising and validating the query for the first time — the one moment the
   marginal cost is close to zero — and it buys a gate that means what Phase 5 assumes it
   means for every batch rather than for every batch after the retrofit.
2. **Marker-probe strictness:** the marker's only remaining use is an informational log,
   yet a failed marker probe fails the whole group — uniform strictness vs. downgrading
   that one probe to warn-and-continue (the final-exists probe is the load-bearing one).
3. **C5 abort message's third history:** the message describes two tmp histories
   (pre-C4 mid-delete / post-C4 pre-finalize); a third now exists — a tmp left by a
   failed tmp-delete *after* a successful finalize (final present, DB intact), and
   dry-run's failed self-cleanup is a fourth of the same benign shape. Extend the
   message and the "Leftover tmp keys" procedure.
4. **Unanchored greps in `verify_artifacts.sh`:** the NOCARRY and leak checks match
   substrings of `unzip -l` output; anchor them to the exact entry path so an unrelated
   filename containing the pattern can't false-positive (or false-negative a rename).
5. **Non-uniform exit codes:** CLI validation exits 2, config fatals exit 1, selfcheck
   0/1, limit-stop 0 — document the scheme or unify it, so wrapper scripts can
   distinguish operator error from environment failure.
6. **Selfcheck temp-dir gate** (held from the third review): fix per the F10
   `archive.temp.dir` answer once it arrives — do not guess.

## Standing requirement — independent second review before production contact

**Before any change in this line of work touches production data — this series and every
future one — a fresh reviewer with no context from the building session reviews the
diff.** This is a permanent gate, not a one-off for this series.

Why, on the record: at the point the 2026-08-20 second review ran, this branch had 90
green tests, a clean self-audit, and a completed audit-closure mapping — and still
carried a data-loss path (existence probes reading "s3cmd failed" as "key absent",
bypassing the C5 tmp protection and C6's overwrite protection), plus a packaging leak the
CI self-test could not see because the self-test exercised a fabrication of the packaging
step rather than the step itself. The test fakes could not even EXPRESS the state that
caused the worst bug — a probe that errors rather than answers — because builder and
test-author shared one blind spot. That is a property of reviewing your own work, not of
this particular series: the author's model of the system generates both the code and the
tests, so a gap in the model is invisible to both. Only a reviewer who does not share the
model finds it.

Operationally: the reviewer gets the diff and the runbook, not the building session; their
findings are applied and re-verified (including a removed-behavior audit of the
safety-critical invariants) before the Phase 5 preconditions can be declared met.

## Phase 5 — cutover (REFRAMED 2026-08-19: two separate operations)

The backlog inventory (collected 2026-08-19, per-device index-backed form, provisional
cutoff 2026-02-18) shows this is not "a large first monthly run":

| Metric | Value |
|---|---|
| Device-months older than cutoff | 11,345 |
| Rows older than cutoff | 366,756,138 (~50% of the ~730.7M-row table) |
| Distinct devices with backlog | 2,076 of ~3,600 |
| Calendar months spanned | 45 |
| Largest single device-month | ~197,542 rows (≈20 delete chunks) |
| Average device-month | ~32.3k rows (≈3–4 delete chunks) |
| Oldest groups | clock garbage (2000-01, small counts) |

**These figures are point-in-time and drift monthly (a new month crosses the cutoff every
month). Re-take the inventory with the same per-device query IMMEDIATELY before cutover.
Never plan a batch from stale figures.**

### Operation 1 — one-time bulk migration (~half the table)

**Preconditions, all of them, before batch 1:**
1. Phase 1 safety nets in place (versioning ON for the prod bucket, DB snapshot procedure
   agreed, baseline queries recorded).
2. Branch C A0–A5 complete: prod's configured bucket's `archive/` prefix lists empty on a
   valid measurement.
3. Fixed script deployed and proven: P4 artifact assertion green in the release build,
   host-side sha256 matches the repo, `--selfcheck` passes (once it lands).
4. **Discovery restructure landed** — otherwise every batch run opens with a full scan of
   the 730.7M-row table (the known performance defect), once per supervised session.
5. **Clock-garbage policy decided** (below) — oldest-first batching reaches those groups
   first.
6. Inventory re-taken (same query), batch plan sized from the fresh numbers.
7. Rehearsal: `--archive-only` against a scratch `--prefix`, oldest real month, then the
   DuckDB Invariant-7 reconciliation (tz pinned per the required-step section) — every
   device OK, no one-sided rows.
8. Prod arming config includes `archive.quarantine.floor` (the quarantine decision needs a
   floor value chosen) alongside the bucket keys — unset floor means garbage months would
   archive normally into the main key space. Optional: `archive.s3cmd.timeout` (seconds,
   default 300) for hosts where transfers legitimately run long. **Must be ABSENT:**
   `archive.local.upload.dir` (it silently diverts all uploads to a local directory —
   see the config-value validation map).
9. **The independent second review (standing requirement above) has run on the final diff
   and its findings are applied and re-verified.**

**Batching axis — month-major, oldest month first; device-bounded within a month.**
Justification: months are the unit the key layout, the reconciliation query, and the C7
guard already think in, so each batch gets a natural, month-scoped verify step; oldest
months are smallest (gentle ramp, calibration on cheap batches) and their late-arrival risk
is nil; and the clock-garbage groups surface in batch 1, immediately after the policy
decision rather than mid-migration. Mechanically, month-major batching needs NO new code:
run with `--months N` counting DOWN (e.g. 24, 23, … 6) — each run's cutoff admits exactly
one more month, and C7 keeps partial months out. Within the large recent months (a single
month can approach ~2,000 device-months), a bounded supervised session needs a clean stop
point: **proposed small addition, pending approval — a `--max-groups N` flag that stops
cleanly after N groups** (markers + merge make re-runs idempotent, so the next session
resumes where the last stopped). Without it the only mid-month stop is interrupting between
groups, and an interrupt that lands mid-group leaves a tmp for C5 to abort on.

**Sizing (estimates — the FIRST batch is the calibration batch; recalibrate from its
measured throughput before sizing the rest):**
- Per average group (~32k rows): export range-scan + parquet write ≈ seconds; upload +
  listing-verify + verify-download + copy + tmp-delete + marker ≈ 5–10 s of S3 round
  trips; 3–4 delete chunks ≈ seconds. Estimate **~10–20 s per average group**.
- Per largest group (~197k rows): larger export/upload/verify-download plus ~20 delete
  chunks — estimate **1.5–3 min**.
- Throughput ≈ **200–350 groups/hour**; a 4-hour supervised session ≈ **800–1,400 groups
  ≈ 25–45M rows**. Whole backlog ≈ **8–14 such sessions**. Old months are much faster
  than these averages; the recent months dominate.

**DB-side impact — what to watch, when to stop.** Deleting ~366M rows in 10k-chunk
commits is sustained purge and binlog load on a managed instance BOTH environments share:
- Watch during every batch: InnoDB history list length (`SHOW ENGINE INNODB STATUS`) —
  pause the batch if it exceeds ~1M and keeps climbing; DO console disk usage (row-based
  binlog logs every deleted row — expect tens of GB of binlog across the migration; stop
  if disk headroom falls below the agreed floor); replica lag if any replica exists;
  instance CPU/IOPS; and application-facing symptoms on BOTH prod and staging (shared
  instance) — ingestion latency, API responsiveness.
- Stop rules: halt only at batch boundaries under normal conditions; halt immediately
  mid-batch on disk-floor breach or app-facing degradation (the abort is safe at any
  point: C4 ordering means a kill costs at most one group's tmp, with the DB intact).
- Expectation to state up front: **deleting rows does not shrink the table files** —
  InnoDB reuses freed pages internally. The wins are query performance, backup size, and
  future partitioning headroom, not immediate disk reclaim.

**Stop-and-verify between batches (success criteria per batch):**
1. Zero failed groups in the run log (exit code 0).
2. `.tmp` sweep of the prod bucket's `archive/` prefix: empty (valid measurement).
3. DuckDB Invariant-7 reconciliation for the batch's months: every device `OK`, no
   one-sided rows (tz pinned explicitly on the DB session). **Scope of this gate as it
   stands: identity only** — counts, id min/max, id sums prove no row was lost or
   duplicated, NOT that archived and live values agree (see follow-up 1, scheduled before
   batch 1). Until the value check lands, read a green Invariant-7 as "no rows lost", not
   "batch verified".
4. Dangling-`positionid` count unchanged from baseline.
5. DB health back to pre-batch baseline (history list drained, no lingering lag).
Only then the next batch.

**Clock-garbage policy — DECIDED (2026-08-19): (c) QUARANTINE.** Bogus-dated groups are
archived to a separate quarantine key space (built on the C2 prefix machinery: the run's
key prefix + `-quarantine`, e.g. `archive-quarantine/…` — a sibling of `archive/`, so the
Java read path never serves fabricated months) and deleted from the live table.
Rationale: archiving them normally permanently pollutes the main archive with months the
read path will serve for date ranges nobody means; floor-date exclusion defers rather
than resolves. Two binding requirements: **the floor date defining "garbage" is a config
value (`archive.quarantine.floor`, YYYY-MM-DD), never a literal in code** — a group is
quarantined when its ENTIRE calendar month lies strictly before the floor date (the
comparison is exclusive-month-end <= floor; set the floor to the first day of a month,
e.g. floor 2024-01-01 quarantines through 2023-12); and **the run logs the count of
quarantined groups (and their rows) separately**, so they never silently blend into the
normal archive totals. No floor configured = quarantine disabled (everything archives
normally); prod gets a floor set at arming time.

**`--max-groups N` — APPROVED (2026-08-19)** for bounded supervised sessions. Binding
requirements: stops only at a group boundary, never mid-group; logs clearly that it
stopped on the limit rather than exhausting the work; and exits ZERO on a clean
limit-stop so it is never confused with a failure exit.

### Operation 2 — steady-state monthly operation

Begins only after the bulk migration completes and reconciles. One calendar month per
run (~8M rows at current volumes, ~250–2,000 device-months), minutes-to-an-hour of work,
not sessions. First steady-state month runs SUPERVISED with the cron still disabled, with
the same five success criteria as a bulk batch; the cron is re-enabled (installer or
manual line) only after that month is clean. Monthly checks thereafter: exit code, run
summary counts, `.tmp` sweep, and the dangling-pointer count — plus the end-of-run device
and group-count log (from the discovery restructure) reviewed for devices that stopped
appearing.

## Phase 1 — safety nets (exact commands; executor flagged per item)

**Item 0 — standing rule (in effect since 2026-08-18, verified):** no `traccar.run` and no
`archive.*` config keys on prod outside the Phase 5 sequence; no installer runs on staging
outside a planned sequence either (an installer run re-arms the cron to monthly wherever
`archive.spaces.bucket` is present — proven live on staging by the 2026-07-07 install).

1. **Versioning ON for the prod bucket — HUMAN, DO console.** (Bucket name set at Branch C
   A-steps; STOP 3 stays triggered until this lands.) Then verify from a host, exit/stderr
   captured per the measurement-validity rule:
   ```bash
   S3CFG="/path/to/s3cmd.ini"
   s3cmd --config "$S3CFG" info s3://<prod-bucket> 2>/tmp/e; echo "exit=$?"; cat /tmp/e; rm -f /tmp/e
   ```
   Also confirm in the console that no lifecycle rule expires anything under `archive/`.
2. **DB snapshot — HUMAN, DO console.** Timed: taken IMMEDIATELY before the first
   destructive bulk batch (Phase 5), not now — a snapshot taken today ages uselessly.
3. **Cron neutralization — VERIFIED NOT NEEDED.** Prod has no archive cron; staging's is
   hand-parked to a yearly schedule. Item 0 is what keeps it that way. To disable a cron
   line if one ever appears: `crontab -l | sed 's|^\(.*archive_cold_storage.*\)$|#\1|' | crontab -`
   and restore by removing the leading `#` the same way.
4. **Baseline queries — SQL, human-run.** The first is cheap (small table + PK lookups);
   the second and third are one-off full scans — run OFF-PEAK, same class as the orphan
   sweep. Record all results in the local answers file:
   ```sql
   -- 4a. Dangling device pointers (cheap): should be 0 before, and unchanged after, runs
   SELECT COUNT(*) FROM tc_devices d
   LEFT JOIN tc_positions p ON p.id = d.positionid
   WHERE d.positionid IS NOT NULL AND p.id IS NULL;

   -- 4b. Events pointing at missing positions (HEAVY one-off, off-peak)
   SELECT COUNT(*) FROM tc_events e
   LEFT JOIN tc_positions p ON p.id = e.positionid
   WHERE e.positionid IS NOT NULL AND p.id IS NULL;

   -- 4c. Anomaly scan (HEAVY one-off, off-peak; the inventory already sized the
   --     clock-garbage months — this adds the NULL count and exact totals)
   SELECT COUNT(*) FROM tc_positions
   WHERE fixtime < '2015-01-01' OR fixtime IS NULL;
   ```

## Phase 4 — deploy & upgrade procedure (D6: the last hop stays human, now provable)

1. **Build:** dispatch `release.yml` with a version. Two independent gates must be green:
   the `python-tests` workflow on the same commit (test suite + the packaging-verifier
   self-test), and the release build's own "Verify packaged archive script against repo"
   step — which extracts the makeself payload from the BUILT artifacts and blocks the
   Spaces upload on any mismatch or missing file (script AND requirements.txt).
2. **F12, one-time:** confirm in the console which bucket actually holds `builds/`
   (`release.yml:53` names `s3://traccar/builds/` with `--host-bucket=iotrides`); record
   the answer in the local answers file before relying on the download path.
3. **Transfer:** download `traccar-linux-64-<version>.zip`, move it to the host by the
   normal manual means. No transfer automation exists or gets added (D6).
4. **Pre-install awareness:** `setup.sh` overwrites `/opt/traccar` IN PLACE (only
   `conf/traccar.xml` is preserved) and re-installs the monthly cron wherever
   `archive.spaces.bucket` is present in the config. On staging: re-park the cron after
   any install. On prod: keep `archive.*` keys absent until the Phase 5 arming step.
5. **Install:** `sudo ./traccar.run` (as root).
6. **Prove the deploy:** on the host and locally, compare:
   ```bash
   sha256sum /opt/traccar/scripts/archive_cold_storage.py /opt/traccar/scripts/requirements.txt
   # locally: git show <released-commit>:scripts/archive_cold_storage.py | sha256sum
   #          git show <released-commit>:scripts/requirements.txt | sha256sum
   ```
7. **Dependencies: automatic on install.** `setup.sh` runs
   `/usr/bin/python3 -m pip install --break-system-packages -r
   /opt/traccar/scripts/requirements.txt` — the cron's interpreter, the method matching
   the existing working host (system pip, PEP 668 environment, no venv). Idempotent;
   a failure prints an unmissable warning but does not abort the traccar upgrade —
   step 8 is the gate. Provenance note: `python-dateutil` is apt-managed on the
   existing host (the other three are pip-managed); apt's 2.9.0 satisfies the pin
   today, and if apt ever drifts, the next installer run shadows it with the pinned
   version in `/usr/local` (which precedes dist-packages on sys.path). **Prod today
   has none of the three pip packages but does have apt's dateutil — its first
   install fetches three of the four.**
8. **`--selfcheck`, immediately after install** — a failed dependency install must surface
   at deploy time, not at the first cron fire:
   ```bash
   sudo /usr/bin/python3 /opt/traccar/scripts/archive_cold_storage.py \
       --config /opt/traccar/conf/traccar.xml --selfcheck
   # must exit 0; checks imports, config keys, temp dir, lock path for THIS
   # identity, s3cmd reachability (ls only), DB connect (SELECT only)
   ```
9. **Cron state check:** `crontab -l | grep archive_cold_storage` — prod: must be absent
   until cutover; staging: re-park if the installer reset it to monthly. Note (second
   review): `setup.sh` now runs the selfcheck itself and **installs/updates the cron only
   when it passes** — a host that cannot pass the selfcheck is never armed; a selfcheck
   failure leaves any existing crontab untouched and prints how to proceed.

**Rollback:** the only artifact history is the versioned installer zips in Spaces
`builds/`. Download the previous version, run its `traccar.run` (same overwrite-in-place
semantics, config preserved), then repeat steps 6–9 against that version's hashes. There
are no versioned release directories on the host and no other rollback mechanism.

*(All held items in this section have landed: `setup.sh` installs the pins and prints the
selfcheck command on every install.)*
