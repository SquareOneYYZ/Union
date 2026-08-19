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

### Derived conclusions (prod partials 2026-08-18; staging set 2026-08-19 — all staging measurements VALID; raw output in the local answers file)

| Item | Environment | Conclusion |
|---|---|---|
| F0 host inventory | prod | 1 prod archiver host — operator-attested, console verification still requested |
| F0 host inventory | staging | 1 staging host observed; installed by the same 2026-07-07 installer run as prod. **Open question: archiver temp dir mtime 2026-08-13 with no bucket writes since 2026-05-04 — what ran on Aug 13?** Console droplet count still pending |
| F1 deployed hash | prod | _pending — first collection came back empty, re-requested (file size matches staging's, which is current)_ |
| F1 deployed hash | staging | **matches `a364cefc4` (= HEAD)** → STOP 4 clear for staging |
| F2 archive cron | prod | **NOT INSTALLED** → no prod 2026-09-01 deadline (Branch A) |
| F2 archive cron | staging | **armed but hand-parked to a yearly schedule** — next fire 2027-03-09; the parking postdates the 2026-07-07 installer run (which resets the cron to monthly, proving the rearm-by-installer risk is real on staging too). No staging 2026-09-01 deadline |
| F3 archive.log | prod | **does not exist** — archiver has never run on prod |
| F3 run history | staging | **no archive.log exists** — run history comes from bucket object dates + markers instead: write bursts 2026-02-24→03-14 (dev era) and 2026-05-04; **5 `.done` markers prove real deleting runs against the staging DB** (2026-03-14 and 2026-05-04) |
| F4 bucket listing | shared | **VALID (exit=0, empty stderr): 15,177 `.parquet`, 5 `.done`, 0 `.tmp`** — prefix NOT empty, all objects staging-provenance → STOP 1 clear, STOP 5 TRIGGERED. Clock-garbage months confirmed at scale (a ~1,179-device cluster in month 2000-01, plus 1980/2004/2008/2013 strays) |
| F5 versioning/lifecycle | shared | **VALID: versioning OFF → STOP 3 TRIGGERED.** No lifecycle expiry rule. Bucket access-policy state is recorded in the local answers file and is verified under A0 (human, console) |
| F6 DB timezone | prod / staging | _pending both_ |
| F7 table state + discovery | prod | _pending — discovery output will be an ESTIMATE keyed to assumed retention (prod has no configured `archive.retention.months`; staging's configured value is also 6)_ |
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
-- ### Prod managed MySQL (read-only). Sized for ~623M rows: no full-table
-- ### COUNT/MIN/MAX scans. P-F7d runs LAST, ALONE, OFF-PEAK.

-- P-F6 — prod DB timezone
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();

-- P-F7a — approximate row counts (statistics read, no scan)
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('tc_positions', 'tc_events');

-- P-F7b — index inventory FIRST (decides whether P-F7c is safe)
SHOW INDEX FROM tc_positions;
SHOW INDEX FROM tc_events;

-- P-F7c — min/max, ONLY if P-F7b shows an index whose LEADING column is fixtime
-- (resp. eventtime). Otherwise SKIP and record "deferred — no index".
SELECT fixtime   FROM tc_positions ORDER BY fixtime  ASC  LIMIT 1;
SELECT fixtime   FROM tc_positions ORDER BY fixtime  DESC LIMIT 1;
SELECT eventtime FROM tc_events    ORDER BY eventtime ASC  LIMIT 1;
SELECT eventtime FROM tc_events    ORDER BY eventtime DESC LIMIT 1;

-- P-DEV — prod device-id inventory (STOP 5 collision cross-check vs staging)
SELECT id, name FROM tc_devices ORDER BY id;

-- P-F7d — group discovery. LAST, ALONE, OFF-PEAK. The one unavoidably heavy
-- query (same shape the script runs: archive_cold_storage.py:355-360). If P-F7b
-- shows no usable time-column index and the scan is unacceptable, DO NOT run
-- it — "discovery cannot run safely" is itself a critical answer.
-- CUTOFF IS PROVISIONAL: prod config has NO archive.retention.months, so 6 is
-- the script default, not a configured prod value. Whatever retention gets set
-- on prod at arming time determines the real cutoff — record this output as an
-- ESTIMATE keyed to the assumed retention, not a fixed group inventory.
-- Assumed retention 6, run date 2026-08-18 -> cutoff '2026-02-18'.
SELECT deviceid, YEAR(fixtime) AS yr, MONTH(fixtime) AS mo, COUNT(*) AS cnt
FROM tc_positions WHERE fixtime < '2026-02-18'
GROUP BY deviceid, YEAR(fixtime), MONTH(fixtime)
ORDER BY deviceid, yr, mo;

SELECT deviceid, YEAR(eventtime) AS yr, MONTH(eventtime) AS mo, COUNT(*) AS cnt
FROM tc_events WHERE eventtime < '2026-02-18'
GROUP BY deviceid, YEAR(eventtime), MONTH(eventtime)
ORDER BY deviceid, yr, mo;
```

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

### C1 lock decision input — VERDICT (2026-08-19)

The environments **share the bucket today** (STOP 5) and share the managed MySQL server
(different schemas). Decision: **per-host `flock`**, contingent on the STOP 5 isolation
landing first — after isolation each environment owns its bucket/key space and runs on a
single host, so the only real overlap (cron vs. manual run on the same host) is exactly what
`flock` guards. A Spaces lock object is not needed once no two hosts can write the same
keys; the isolation itself is the cross-environment guard and is a precondition for
everything else anyway.

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

### Branch C — STOP 5 resolution → **ACTIVE: staging bucket = the shared bucket**

Isolation is now the prerequisite to everything. Constraint that decides the direction:
`ArchiveResource.java` hardcodes the `archive/...` key prefix (`:807-812`) and D7 forbids
Java changes — so **prod must own `<shared-bucket>/archive/` and staging must vacate it**;
"move prod to a different prefix" is not available.

Recommended resolution (human executes all bucket writes; nothing here is run by Claude):

1. Create a **separate staging bucket**, same region, same `archive/` key layout — the
   archive read path then works unchanged in both environments.
2. **Relocate** (server-side copy, then delete originals only after a verified count match)
   all 15,177 objects + 5 markers from the shared bucket's `archive/` to the staging
   bucket. Relocate, do NOT delete: the 5 marker-months' parquet files are the only copies
   of staging rows already deleted from the staging DB (STOP 2 evidence), and the rest is
   staging's dev-era archive.
3. Point staging's `archive.spaces.bucket` at the new bucket.
4. Only after the shared bucket's `archive/` prefix lists **empty** (a valid, exit-0
   measurement) does prod arming become possible, and staging becomes usable as the D5
   rehearsal target.

Until step 4 completes, any prod run — old code or new — would skip colliding device-months
as "already archived" (markers on device ids recorded in the local answers file) and, under the C6 merge semantics,
would merge prod rows into staging parquet files. Nothing arms prod before the prefix is
verified empty.

---

*(Phases 1–5 sections to be appended after the Phase 0 gate clears.)*
