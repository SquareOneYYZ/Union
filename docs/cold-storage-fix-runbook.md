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
| F7 table state + discovery | prod | **P-F7a COLLECTED: tc_positions ≈730.7M, tc_events ≈103.4M** (larger than the ~623M planning figure). **P-F7b partial: positions = PRIMARY(id) + (deviceid, fixtime); tc_events index inventory OUTSTANDING.** P-F7c deferred (no fixtime-led index). **P-F7d NOT RUN — unsafe (time-only filter cannot use the index; ~730M-row scan) — removed from the checklist** |
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

*(Phases 1–5 sections to be appended after the Phase 0 gate clears.)*
