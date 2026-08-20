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

**Outstanding as of 2026-08-19:** prod — P-F1 (hash re-take; first paste was empty), P-F9
(python + deps), `SHOW INDEX FROM tc_events` (completes P-F7b), `SELECT
@@system_time_zone;` (completes P-F6); both hosts — the L1 lock-identity checks; staging —
S-F6 (optional, informs D8); console — droplet count, versioning/lifecycle confirmation,
A0 access-policy verification; open question A1 (the 2026-08-13 temp-dir touch). Removed:
P-F7c (deferred — no usable index), P-F7d (not run — unsafe), P-DEV/S-DEV (obsoleted by
full bucket separation).

### Derived conclusions (prod partials 2026-08-18; staging set 2026-08-19 — all staging measurements VALID; raw output in the local answers file)

| Item | Environment | Conclusion |
|---|---|---|
| F0 host inventory | prod | **ANSWERED: 1 host** (operator-confirmed 2026-08-19; single Linux box per environment, no multi-host archiver) |
| F0 host inventory | staging | **ANSWERED: 1 host**, installed by the same 2026-07-07 installer run as prod. **Open question A1: archiver temp dir mtime 2026-08-13 with no bucket writes since 2026-05-04 — what ran on Aug 13? Must be answered before the staging repoint (Branch C)** |
| F1 deployed hash | prod | _pending — first collection came back empty, re-requested (file size matches staging's, which is current)_ |
| F1 deployed hash | staging | **matches `a364cefc4` (= HEAD)** → STOP 4 clear for staging |
| F2 archive cron | prod | **NOT INSTALLED** → no prod 2026-09-01 deadline (Branch A) |
| F2 archive cron | staging | **armed but hand-parked to a yearly schedule** — next fire 2027-03-09; the parking postdates the 2026-07-07 installer run (which resets the cron to monthly, proving the rearm-by-installer risk is real on staging too). No staging 2026-09-01 deadline |
| F3 archive.log | prod | **does not exist** — archiver has never run on prod |
| F3 run history | staging | **no archive.log exists** — run history comes from bucket object dates + markers instead: write bursts 2026-02-24→03-14 (dev era) and 2026-05-04; **5 `.done` markers prove real deleting runs against the staging DB** (2026-03-14 and 2026-05-04) |
| F4 bucket listing | shared | **VALID (exit=0, empty stderr): 15,177 `.parquet`, 5 `.done`, 0 `.tmp`** — prefix NOT empty, all objects staging-provenance → STOP 1 clear, STOP 5 TRIGGERED. Clock-garbage months confirmed at scale (a ~1,179-device cluster in month 2000-01, plus 1980/2004/2008/2013 strays) |
| F5 versioning/lifecycle | shared | **VALID: versioning OFF → STOP 3 TRIGGERED.** No lifecycle expiry rule. Bucket access-policy state is recorded in the local answers file and is verified under A0 (human, console) |
| F6 DB timezone | prod | **COLLECTED: `SYSTEM`/`SYSTEM`, currently resolving to UTC — an observation, not a guarantee.** `@@system_time_zone` follow-up outstanding; explicit tz pinning is a required step everywhere (see below) |
| F6 DB timezone | staging | _pending (optional — informs the D8 note on existing staging parquet; same server as prod)_ |
| F7 table state + discovery | prod | **P-F7a COLLECTED: tc_positions ≈730.7M, tc_events ≈103.4M** (larger than the ~623M planning figure). **P-F7b partial: positions = PRIMARY(id) + (deviceid, fixtime); tc_events index inventory OUTSTANDING.** P-F7c deferred (no fixtime-led index). P-F7d time-scan form withdrawn as unsafe; **BACKLOG INVENTORY COLLECTED 2026-08-19 via the per-device index-backed form: 11,345 device-months / 366.76M rows (~50% of table) / 2,076 devices / 45 months / largest group ~197.5k rows / oldest = 2000-01 clock garbage. Point-in-time — re-take before cutover (Phase 5)** |
| F9 python/deps | prod | _pending_ |
| F9 python/deps | staging | **Python 3.12.7, all four deps import** — the proven-working version set is recorded locally as the candidate C8 pins |
| F10 config keys | prod | **zero `archive.*` keys present** (database.* present) |
| F10 config keys | staging | **bucket = the shared bucket → STOP 5**; retention 6; no interpreter split (same python3 for script and s3cmd). **DB = same managed MySQL server as prod, different schema** — not the prod database (no full stop), but shared DB infrastructure |
| Device-id overlap | both DBs | _pending — the 5 staging markers sit on device ids recorded in the local answers file; prod's autoincrement space almost certainly also contains them_ |

**Consequences already in force:**

1. **The 2026-09-01 deadline is VOID — Branch A is active.** Prod has no archive cron, and
   with zero `archive.*` keys in prod's config: an installer re-run today would NOT install
   the cron (`setup/setup.sh:29-35` gates on `archive.spaces.bucket`), and a hand-run of the
   deployed script could not upload (`do_upload` fails with no bucket and no
   local_upload_dir). Prod is unarmed on both fronts.
2. **Phase 1 gains item 0 (standing rule, already in effect):** nobody runs `traccar.run` on
   prod outside the Phase 5 sequence, and nobody adds `archive.*` keys to prod's config
   before cutover — either action is what arms prod.
3. **Degenerate-date risk is live in prod:** the prod ingest layer runs with a permissive
   SQL mode, so zero/garbage dates cannot be ruled out in old rows. The audit's Inv. 5
   degenerate-date failure path (group with an unconstructible month → fails every run) must
   be handled, and the Phase 1 anomaly scan (`fixtime < '2015-01-01' OR fixtime IS NULL`)
   is not optional.

### STOP-condition status as of 2026-08-19

| # | Condition | Status |
|---|---|---|
| 1 | `.tmp` residue | **CLEAR** (valid F4: zero `.tmp`) |
| 2 | log vs `.done` reconciliation | **UNRESOLVABLE AS SPECIFIED** — no archive.log exists anywhere; deleting runs are evidenced by the 5 markers instead. Absorbed into the STOP 5 disposition: the 5 marker-months' parquet files are the only copies of staging rows already deleted from the staging DB, and must be preserved through any relocation |
| 3 | versioning OFF | **TRIGGERED** — enabling versioning (Phase 1 item 1, human/console) must precede anything that writes to the bucket |
| 4 | unknown deployed hash | **CLEAR for staging** (= HEAD); prod re-take pending |
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

### Measurement-validity rule (added 2026-08-18 after two invalid measurements)

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

**DECIDED (2026-08-19): Option A — device-iterated discovery, sequenced after C7**, with
one design gate. Enumerate device ids first, then per device run `SELECT YEAR(t),
MONTH(t), COUNT(*) ... WHERE deviceid = ? AND t < cutoff GROUP BY 1, 2` — an index range
scan touching only that device's old rows. The group loop is untouched (same groups list).

**Design gate — device-list source needs an EXPLAIN before commitment.**
`SELECT DISTINCT deviceid FROM tc_positions` is probably NOT cheap: MySQL/InnoDB does not
reliably apply a loose index scan to DISTINCT on a secondary index, so it may full-scan
the (deviceid, fixtime) index over ~730M rows at the top of every run. The operator runs
(read-only, does not execute the query):

```sql
EXPLAIN FORMAT=TREE SELECT DISTINCT deviceid FROM tc_positions;
EXPLAIN             SELECT DISTINCT deviceid FROM tc_positions;
-- (loose scan shows as "Using index for group-by" in Extra / a skip-scan
--  node in TREE; a plain index scan over the whole index means: too costly)
EXPLAIN             SELECT deviceid FROM tc_positions GROUP BY deviceid;
-- (equivalent GROUP BY form — the optimizer sometimes loose-scans this one
--  when it will not loose-scan the DISTINCT)
```

If EXPLAIN confirms the full index scan: enumerate devices from **`tc_devices`** (small,
cheap) instead, and record this tradeoff explicitly — **orphan positions (rows whose
deviceid no longer exists in tc_devices) would then never be discovered by the per-run
loop.** That gap is handled by a documented **one-off orphan sweep** (run manually,
off-peak, during cutover — not per-run), and the design must never silently stop
archiving rows the old time-scan discovery would have found. Additionally, the
restructure commit must **log the device count and per-device group counts at the end of
each run**, so a device that stops appearing is visible rather than silent.

Events side still gated on the missing `SHOW INDEX FROM tc_events` answer; if tc_events
lacks a deviceid-led index, the option applies to positions only until that is known.

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

### C1 lock decision — per-host `flock`; **mechanism VERIFIED in CI; path identity pending L1**

F0 is answered: **one Linux box per environment, no multi-host archiver anywhere**
(operator-confirmed). With separate buckets (Branch C) and separate DB schemas, the two
environments can never contend, so the Spaces lock-object design is **dropped**. `flock`
is scoped to what it actually protects: two invocations on the *same* box — the cron
firing while someone hand-runs the script, which is exactly the pattern staging's history
shows. Behavior: **non-blocking, fail fast and loud** — if the lock is held, the new
invocation logs an error and exits non-zero immediately; it never blocks and waits, so a
long-running manual run cannot silently queue a cron fire behind it.

**Lock mechanism VERIFIED (2026-08-19):** the branch was pushed and CI ran green — all
three POSIX `flock` contention tests passed on ubuntu (held lock → immediate exit 1;
reacquire after release; unopenable path → loud exit 1). What remains open is **only the
L1 identity question**: the lock file is fixed at `/var/lock/traccar-archive.lock` with
**no fallback path** — if the running identity cannot open it, the run fails loudly at
startup, because two identities resolving to two different lock files would mean no mutual
exclusion at all. Whether the cron's user and a human hand-run can share that path is a
host fact — L1 below answers it, and the path moves if the answers demand it.

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
   one-sided rows (tz pinned explicitly on the DB session).
4. Dangling-`positionid` count unchanged from baseline.
5. DB health back to pre-batch baseline (history list drained, no lingering lag).
Only then the next batch.

**Clock-garbage policy — DECISION REQUIRED before batch 1** (rows dated 2000-01 etc. get
archived-and-deleted first, having had zero live retention):
- **(a) Archive normally:** they flow through like any month — preserved in the archive
  under their (bogus) month keys, deleted from the DB; simplest, no code change.
- **(b) Floor-date exclusion:** the script skips groups older than a floor (e.g.
  2024-01); rows stay in the DB until a policy exists; small code change, backlog keeps
  carrying them.
- **(c) Quarantine:** archive them to a separate quarantine prefix and delete from the
  DB; keeps the main archive free of bogus months (the Java read path would otherwise
  serve them for date ranges nobody means); code change ≈ a prefix override per group
  class.

### Operation 2 — steady-state monthly operation

Begins only after the bulk migration completes and reconciles. One calendar month per
run (~8M rows at current volumes, ~250–2,000 device-months), minutes-to-an-hour of work,
not sessions. First steady-state month runs SUPERVISED with the cron still disabled, with
the same five success criteria as a bulk batch; the cron is re-enabled (installer or
manual line) only after that month is clean. Monthly checks thereafter: exit code, run
summary counts, `.tmp` sweep, and the dangling-pointer count — plus the end-of-run device
and group-count log (from the discovery restructure) reviewed for devices that stopped
appearing.

*(Phases 1–4 runbook procedure sections still to be appended: Phase 1 exact commands,
deploy/upgrade procedure with rollback, `--selfcheck` usage. Pending: setup.sh changes
(P-F9), discovery commit (EXPLAIN), C8.)*
