# PR: Cold-storage archival hardening (`riq-cold-storage-hardening`)

Fixes the data-loss failure modes in `scripts/archive_cold_storage.py` identified by the
2026-08-14 audit, adds an offline test suite and CI gates, and makes the existing
installer path (`package.sh` → makeself → `setup.sh`) provably ship what the repo
contains. No Java, schema, or partition changes (per scope). Full operational detail:
`docs/cold-storage-fix-runbook.md`.

## Closed failure modes (audit rank → commit)

| Rank | Failure mode | Commit |
|---|---|---|
| 1 | Crash-recovery path deleted the only copy of already-deleted rows (leftover `.tmp` auto-cleaned) | C4 `eb94ff3db` finalize-before-delete + C5 `ce08f3e36` tmp keys are evidence: abort group, never auto-delete |
| 2 | Whole device-month final parquet silently overwritten after a partial delete or lost marker | C6 `8067bd366` merge into existing finals — additive-only asserted before the copy, schema mismatch aborts |
| 3 | Rows inserted between export and delete destroyed unarchived (time-window DELETE) | C3 `5dbed01e6` delete by exported ids, 10k chunks, loud count-mismatch, no repair |
| 4 | `.done` marker starved late-arriving rows forever (and miscounted them as archived) | C6 `8067bd366` marker no longer skips; late rows merge |
| 5 | Dangling `tc_devices.positionid` after deletion | C3/D9 `5dbed01e6` latest-position ids fetched once per run, excluded from deletion (partial — see limitations) |
| 6 | Mid-month manual run archived-and-deleted rows younger than retention; unpinned session tz | C7 `c2fe7e225` cutoff-month skip + C1a `e2fce1108` UTC `init_command` |
| 7 | Clock-garbage months (2000-01 etc.) archived into the main key space with zero live retention | Quarantine `5d60a5b06` — `archive.quarantine.floor` config; garbage months to `<prefix>-quarantine/`, counted separately |
| F8 | Substring key verification (prefix-listing false positive); no concurrency guard | C1a `e2fce1108` exact-match; C1b+fixes `3be4afe69`/`6561c20f5`/`9848aa38f` non-blocking flock at a fixed config-independent path — verified in CI (contention tests) and on identity (L1: root crontabs, /run/lock 1777) |
| — | No verifiable rehearsal mode (`--dry-run` deletes its own upload) | C2 `acfc26dd6` `--archive-only` via delete-capability injection (structurally cannot delete) + validated `--prefix` |
| — | Discovery full-scanned ~730M rows every run (time-only filter, no usable index) | `23c81fcbf` device-iterated discovery (EXPLAIN-verified index range scans; end-of-run device summaries) |
| — | Undeclared Python dependencies; prod dies at import | C8 `c66d41272` `requirements.txt` pinned to the measured working set + `b8612e65b` setup.sh installs it for the cron's interpreter (`--break-system-packages`, matching the existing host's PEP 668 setup) |
| — | Nothing proved the installer shipped the repo's script | `02b28b08d` release build extracts the makeself payload and asserts presence + sha256 per artifact (carriers vs absent-by-design), blocking upload; self-tested in CI every push |
| — | No post-install verification | `47fa1736e` `--selfcheck` (read-only; printed by setup.sh after every install) |

Operational additions: `f2d52557d` `--max-groups` (clean group-boundary session bounding
for the bulk migration, exit 0 on limit-stop).

## Known limitations left open (by design or awaiting decision)

- **`tc_events.positionid` dangling**: a live event can reference a deleted position for
  up to a month (events keyed by eventtime, positions by fixtime). Bounded and
  self-resolving; measured by the Phase 1 baseline. No Java changes permitted.
- **Manual `DELETE /api/positions` is uncoordinated** with the archiver (the flock guards
  archiver self-overlap only). No Java changes permitted.
- **Zero-date rows crash the run, not the group** (prod's permissive sql_mode allows
  them; `date(yr, mo, 1)` is computed outside the per-group try). One-line hardening
  proposed in the runbook, awaiting approval.
- **`--dry-run` semantics unchanged**: uploads, verifies, then deletes its own upload —
  cannot serve as verification (documented; `--archive-only` is the verification mode).
- **Marker upload stays warn-only**: harmless under C6 (a missing marker re-merges; no
  overwrite is possible).
- **Server tz is `SYSTEM`** (currently resolving UTC): the script pins UTC per session;
  every other reader must pin explicitly (runbook required step).
- **Pre-C1 staging objects are probably-UTC, not provably** (D8 note); merges against
  them can be internally inconsistent in timestamp strings — confined to that legacy set.
- **F9 (partition-drop gating)** is out of scope by design; recorded for the future
  partitioning project.

## Phase 0 values this fix depends on (verified 2026-08-19; details in the runbook)

- Deployed script sha256 = HEAD (`a364cefc4`) on both hosts — no hand-edits (STOP 4 clear).
- Environments get **fully separate buckets** (STOP 5 resolution); bucket name is
  config-driven everywhere (`archive.spaces.bucket`; no literals in Java or the script);
  prod arms only when its configured bucket's `archive/` prefix lists empty.
- Bucket **versioning is OFF** (STOP 3) — console action required before anything writes.
- Prod is unarmed four independent ways: no cron, no `archive.*` config, empty prefix,
  no Python deps (dies at import). Staging's cron is hand-parked (yearly). No cron
  deadline exists anywhere.
- Retention: script default 6 months (prod has no configured value — cutoffs in planning
  are estimates keyed to that assumption).
- DB: `tc_positions` ≈730.7M rows with PRIMARY(id) + `(deviceid, fixtime)`; `tc_events`
  ≈103.4M with deviceid-led indexes; time-only queries are full scans — hence the
  device-iterated discovery and the index-shaped export/delete/baseline queries.
- Backlog (point-in-time 2026-08-19, re-take before cutover): 11,345 device-months /
  366.76M rows (~50% of the table) / 2,076 devices / 45 months / largest group ~197.5k
  rows / oldest = 2000-01 clock garbage → two-operation cutover (bulk migration +
  steady state) per the runbook.
- Server tz `SYSTEM` → `@@system_time_zone = UTC` (observation, not guarantee).
- Lock identity: `/run/lock` mode 1777 and root-owned archiver crontabs on both hosts —
  cron and sudo'd hand-runs share `/var/lock/traccar-archive.lock`.
- Dependency pins = staging's measured working set (PyMySQL 1.1.2, pandas 3.0.1,
  pyarrow 23.0.1, python-dateutil 2.9.0 on Python 3.12.7, both hosts); install method
  matches the existing host (system pip, PEP 668, `--break-system-packages`; dateutil is
  apt-managed — mixed provenance noted).

## Verification

- Offline pytest suite (`scripts/tests/`, no network/DB/bucket): **85 passed, 3
  platform-skipped locally**; the skipped POSIX flock contention tests run green in CI.
- CI on push/PR: `py_compile` gate + test suite + packaging-verifier self-test
  (faithful/tampered/missing/sneak artifacts).
- Release builds verify the built artifacts against the repo before uploading.

## Not in this PR (operator actions, sequenced in the runbook)

Versioning ON; access-policy verification (A0); the bucket-separation A-steps; the
`builds/` destination confirmation (F12); Phase 1 baselines; the bulk migration itself.
Do not merge-and-arm without the runbook's Phase 5 preconditions.
