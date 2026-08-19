#!/usr/bin/env python3
"""
archive_cold_storage.py
=======================
Archives old tc_positions and tc_events rows from MySQL to DigitalOcean Spaces
in Parquet format using s3cmd.

Each table is archived INDEPENDENTLY (no joins).
Data is grouped by deviceid x year x month -- one Parquet file per group.

SNAPSHOT TABLES (store only, NEVER deleted from DB):
  - tc_geofences, tc_drivers, tc_devices, tc_device_geofence_segment

Usage:
    python archive_cold_storage.py [--config /path/to/traccar.xml] [--dry-run] [--months 6]

Requirements:
    pip install pymysql pandas pyarrow python-dateutil
"""

# ---------------------------------------------------------------------------
# Imports -- all at top (#3 fix)
# ---------------------------------------------------------------------------
import argparse
import logging
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import date, timezone
from datetime import datetime as dt
from dateutil.relativedelta import relativedelta

import pandas as pd
import pymysql
import pymysql.cursors

try:
    import fcntl  # POSIX only -- the deployed hosts; None on Windows dev boxes
except ImportError:
    fcntl = None

# ---------------------------------------------------------------------------
# Logging -- replaces all print() (#10 fix)
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler()],
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args():
    script_dir   = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)

    # Auto-detect: Linux server = traccar.xml, Windows dev = debug.xml
    linux_config   = "/opt/traccar/conf/traccar.xml"
    windows_config = os.path.join(project_root, "debug.xml")
    default_config = linux_config if os.path.exists(linux_config) else windows_config

    parser = argparse.ArgumentParser(
        description="Archive old Traccar positions & events to DigitalOcean Spaces"
    )
    parser.add_argument("--config",  default=default_config,
                        help="Path to traccar.xml or debug.xml")
    parser.add_argument("--dry-run", action="store_true",
                        help="Upload, verify, then delete the verification upload again; "
                             "skips DB deletion and finalization. Leaves NO artifact in "
                             "the bucket, so it cannot serve as verification -- use "
                             "--archive-only for that.")
    parser.add_argument("--months",  type=int, default=None,
                        help="Override retention_months from config")
    parser.add_argument("--archive-only", action="store_true",
                        help="Export + verify + finalize only: no DB deletes, no .done "
                             "markers. For rehearsals and pre-populating the archive.")
    parser.add_argument("--prefix", default=None,
                        help="Rehearsal key-space prefix replacing the leading 'archive'. "
                             "Requires --archive-only; must not resolve into the "
                             "production 'archive/' key space.")
    return parser.parse_args()


# The production key space. The Java read path (ArchiveResource) constructs
# keys under this prefix, so rehearsals must never write into it.
PROD_KEY_PREFIX = "archive"


def validate_prefix(prefix: str) -> str:
    """Normalize and validate a rehearsal key prefix.

    Rejects anything that would land keys in the production key space, and
    path tricks that could resolve there: empty or absolute paths, '.'/'..'
    segments, empty segments, characters outside [A-Za-z0-9._-].
    """
    cleaned = (prefix or "").strip().strip("/")
    if not cleaned:
        raise ValueError("prefix is empty")
    for seg in cleaned.split("/"):
        if seg in ("", ".", ".."):
            raise ValueError(f"segment not allowed: {seg!r}")
        if not re.fullmatch(r"[A-Za-z0-9._-]+", seg):
            raise ValueError(f"segment has unsupported characters: {seg!r}")
    if cleaned.split("/")[0] == PROD_KEY_PREFIX:
        raise ValueError(
            f"prefix resolves into the production '{PROD_KEY_PREFIX}/' key space")
    return cleaned


def resolve_run_options(args):
    """Cross-validate CLI flags. Returns (key_prefix, archive_only)."""
    if args.archive_only and args.dry_run:
        logger.error("--archive-only and --dry-run are mutually exclusive.")
        sys.exit(2)
    if args.prefix is not None and not args.archive_only:
        logger.error("--prefix is a rehearsal option and requires --archive-only; "
                     "a destructive run must write to the production key space.")
        sys.exit(2)
    if args.prefix is None:
        return PROD_KEY_PREFIX, args.archive_only
    try:
        return validate_prefix(args.prefix), args.archive_only
    except ValueError as e:
        logger.error("Invalid --prefix: %s", e)
        sys.exit(2)


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

def load_config_xml(xml_path: str) -> dict:
    if not os.path.exists(xml_path):
        logger.error("Config file not found: %s", xml_path)
        sys.exit(1)
    tree  = ET.parse(xml_path)
    root  = tree.getroot()
    props = {}
    for entry in root.findall("entry"):
        key   = entry.get("key")
        value = (entry.text or "").strip()
        props[key] = value
    logger.info("Loaded config from: %s", xml_path)
    return props


class PropsConfig:
    """Wraps flat XML props dict with section/key access."""

    def __init__(self, props: dict):
        self._props = props
        # #2 fix: added local_upload_dir mapping
        self._map = {
            ("spaces",  "bucket"):           "archive.spaces.bucket",
            ("spaces",  "s3cmd_config"):     "archive.s3cmd.configFile",
            ("spaces",  "temp_dir"):         "archive.temp.dir",
            ("spaces",  "python_exe"):       "archive.python.exe",
            ("spaces",  "s3cmd_script"):     "archive.s3cmd.script",
            ("spaces",  "local_upload_dir"): "archive.local.upload.dir",  # optional
            ("archive", "retention_months"): "archive.retention.months",
        }

    def get(self, section, key, fallback=""):
        flat_key = self._map.get((section, key))
        if flat_key is None:
            return fallback
        return self._props.get(flat_key, fallback)

    def getint(self, section, key):
        return int(self.get(section, key, 0))


# ---------------------------------------------------------------------------
# DB connection
# ---------------------------------------------------------------------------

def get_connection(props: dict):
    """Parse JDBC URL from config and connect."""
    url   = props.get("database.url", "")
    match = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/(\w+)", url)
    host   = match.group(1) if match else "localhost"
    port   = int(match.group(2)) if match and match.group(2) else 3306
    dbname = match.group(3) if match else "traccar"
    return pymysql.connect(
        host=host, port=port,
        user=props.get("database.user", "root"),
        password=props.get("database.password", ""),
        database=dbname,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        # Pin the session to UTC so cutoff, month windows, and the rendered
        # datetime strings in Parquet are independent of the server timezone.
        init_command="SET time_zone = '+00:00'",
    )


# ---------------------------------------------------------------------------
# s3cmd helpers
# ---------------------------------------------------------------------------

def build_s3cmd_base(cfg) -> list:
    python_exe   = cfg.get("spaces", "python_exe")
    s3cmd_script = cfg.get("spaces", "s3cmd_script")
    s3cmd_config = cfg.get("spaces", "s3cmd_config")

    if not python_exe:
        logger.error("archive.python.exe not configured")
        sys.exit(1)
    if not s3cmd_script:
        logger.error("archive.s3cmd.script not configured")
        sys.exit(1)

    cmd = [python_exe, s3cmd_script]

    # #1 fix: pass --config so s3cmd uses correct endpoint
    if s3cmd_config:
        cmd += ["--config", s3cmd_config]

    return cmd


def key_in_listing(stdout: str, dest: str) -> bool:
    """True only if a listing line's key column equals dest exactly.

    `s3cmd ls <dest>` is a prefix listing: asking for X.parquet also returns
    X.parquet.tmp, so a substring test against stdout false-positives. Compare
    the final whitespace-separated token of each line instead.
    """
    for line in stdout.splitlines():
        parts = line.split()
        if parts and parts[-1] == dest:
            return True
    return False


def verify_upload(cfg, spaces_key: str) -> bool:
    """Verify file landed in Spaces after upload. (#4 fix: no delete without verify)"""
    bucket = cfg.get("spaces", "bucket")
    dest   = f"s3://{bucket}/{spaces_key}"
    cmd    = build_s3cmd_base(cfg) + ["ls", dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    exists = result.returncode == 0 and key_in_listing(result.stdout, dest)
    if not exists:
        logger.warning("Verification failed -- file not found at %s", dest)
    return exists

def check_temp_key_exists(cfg, temp_spaces_key: str) -> bool:
    """Check if a previous run left a temp upload behind."""
    bucket = cfg.get("spaces", "bucket")
    dest   = f"s3://{bucket}/{temp_spaces_key}"
    cmd    = build_s3cmd_base(cfg) + ["ls", dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode == 0 and key_in_listing(result.stdout, dest)


def delete_spaces_key(cfg, spaces_key: str):
    """Delete a key from Spaces (used for temp key cleanup)."""
    bucket = cfg.get("spaces", "bucket")
    dest   = f"s3://{bucket}/{spaces_key}"
    cmd    = build_s3cmd_base(cfg) + ["del", dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.warning("  [S3] Could not delete temp key %s: %s", spaces_key, result.stderr.strip())


def copy_spaces_key(cfg, src_key: str, dst_key: str) -> bool:
    """Copy a key within Spaces (temp -> final)."""
    bucket = cfg.get("spaces", "bucket")
    cmd    = build_s3cmd_base(cfg) + [
        "cp",
        f"s3://{bucket}/{src_key}",
        f"s3://{bucket}/{dst_key}",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error("  [S3] Copy failed %s -> %s: %s", src_key, dst_key, result.stderr.strip())
        return False
    return True


def download_key(cfg, spaces_key: str, local_path: str) -> bool:
    """Download a key from Spaces to a local file."""
    bucket = cfg.get("spaces", "bucket")
    cmd = build_s3cmd_base(cfg) + ["get", "--force",
                                   f"s3://{bucket}/{spaces_key}", local_path]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error("  [S3] Download failed for %s: %s",
                     spaces_key, result.stderr.strip())
        return False
    return True


def merge_with_existing_final(cfg, spaces_key: str, df, temp_dir: str,
                              table: str):
    """Merge a fresh export into an already-existing final parquet (C6).

    Additive-only: every id already in the archive must survive the merge and
    the merged frame can never be smaller than the archived one; on id
    collision the ARCHIVED row wins (the archive is the immutable record).
    A column-set mismatch aborts instead of coercing -- older script versions
    wrote the existing objects, and concat across schemas silently produces
    null-filled columns that still pass a row-count check. Returns the merged
    DataFrame, or None to abort the group with the final key untouched.
    """
    local_existing = os.path.join(
        temp_dir, f"merge_{os.path.basename(spaces_key)}")
    try:
        if not download_key(cfg, spaces_key, local_existing):
            logger.error("  [%s] Could not download existing final %s for "
                         "merge -- aborting group (final key untouched).",
                         table, spaces_key)
            return None
        try:
            old_df = pd.read_parquet(local_existing)
        except Exception as e:
            logger.error("  [%s] Existing final %s unreadable (%s) -- "
                         "aborting group (final key untouched).",
                         table, spaces_key, e)
            return None

        old_cols, new_cols = set(old_df.columns), set(df.columns)
        if old_cols != new_cols:
            logger.error(
                "  [%s] SCHEMA MISMATCH vs existing final %s -- aborting "
                "group, no coercion. Only in archive: %s; only in export: %s.",
                table, spaces_key,
                sorted(old_cols - new_cols) or "none",
                sorted(new_cols - old_cols) or "none")
            return None

        merged = pd.concat([old_df, df], ignore_index=True)
        merged = merged.drop_duplicates(subset="id", keep="first")
        merged = merged[list(df.columns)]  # order only; same set, no coercion

        original_ids = set(int(i) for i in old_df["id"])
        merged_ids   = set(int(i) for i in merged["id"])
        if not original_ids.issubset(merged_ids) or len(merged) < len(old_df):
            lost = sorted(original_ids - merged_ids)[:10]
            logger.error(
                "  [%s] MERGE NOT ADDITIVE for %s: archived rows would be "
                "lost (%d -> %d rows; sample lost ids %s) -- aborting group "
                "(final key untouched).",
                table, spaces_key, len(old_df), len(merged), lost)
            return None

        logger.info("  [%s] Merged %d archived + %d exported -> %d rows "
                    "(dedupe on id; archived rows win collisions).",
                    table, len(old_df), len(df), len(merged))
        return merged
    finally:
        if os.path.exists(local_existing):
            os.remove(local_existing)


def finalize_parquet(cfg, temp_key: str, spaces_key: str) -> bool:
    """Promote a verified temp upload to its final key.

    copy tmp -> final, verify the final key exists, then delete the tmp.
    Returns False -- leaving the temp key in place -- if the copy or the
    final-key verification fails. Callers run this BEFORE any DB delete (D3),
    so a False here always means nothing has been removed yet.
    """
    if not copy_spaces_key(cfg, temp_key, spaces_key):
        logger.error("  [S3] Could not copy %s -> %s; temp key preserved.",
                     temp_key, spaces_key)
        return False
    if not verify_upload(cfg, spaces_key):
        logger.error("  [S3] Final key %s not verifiable after copy; temp key "
                     "preserved at %s.", spaces_key, temp_key)
        return False
    delete_spaces_key(cfg, temp_key)
    return True


def verify_row_count(cfg, spaces_key: str, expected_rows: int) -> bool:
    """Download uploaded Parquet and verify row count matches DB."""
    bucket     = cfg.get("spaces", "bucket")
    temp_dir   = cfg.get("spaces", "temp_dir") or "/tmp/traccar-archive"
    local_path = os.path.join(temp_dir, f"verify_{os.path.basename(spaces_key)}")

    cmd = build_s3cmd_base(cfg) + ["get", "--force",
                                    f"s3://{bucket}/{spaces_key}",
                                    local_path]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            logger.warning("  [VERIFY] Could not download for row count check: %s",
                           result.stderr.strip())
            return False

        df           = pd.read_parquet(local_path)
        actual_rows  = len(df)

        if actual_rows != expected_rows:
            logger.error(
                "  [VERIFY] ROW COUNT MISMATCH! DB had %d rows, Parquet has %d rows"
                " -- skipping deletion.", expected_rows, actual_rows
            )
            return False

        logger.info("  [VERIFY] Row count OK: DB=%d, Parquet=%d ✓",
                    expected_rows, actual_rows)
        return True

    except Exception as e:
        logger.error("  [VERIFY] Row count check failed: %s", e)
        return False

    finally:
        if os.path.exists(local_path):
            os.remove(local_path)


def s3cmd_upload(cfg, local_file: str, bucket: str, key: str) -> bool:
    dest   = f"s3://{bucket}/{key}"
    cmd    = build_s3cmd_base(cfg) + ["put", "--acl-private", local_file, dest]
    logger.info("  [S3] Uploading %s -> %s", os.path.basename(local_file), dest)
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error("  [S3 ERROR] %s", result.stderr.strip())
        return False
    return True


def local_upload(local_file: str, upload_dir: str, key: str) -> bool:
    dest = os.path.join(upload_dir, key.replace("/", os.sep))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    logger.info("  [LOCAL] Copying %s -> %s", os.path.basename(local_file), dest)
    try:
        shutil.copy2(local_file, dest)
        return True
    except Exception as e:
        logger.error("  [LOCAL ERROR] %s", e)
        return False


def do_upload(cfg, local_path: str, spaces_key: str) -> bool:
    bucket           = cfg.get("spaces", "bucket")
    local_upload_dir = cfg.get("spaces", "local_upload_dir")
    if local_upload_dir:
        return local_upload(local_path, local_upload_dir, spaces_key)
    if not bucket:
        logger.error("bucket not configured and local_upload_dir is empty")
        return False
    return s3cmd_upload(cfg, local_path, bucket, spaces_key)


# ---------------------------------------------------------------------------
# Parquet helpers
# ---------------------------------------------------------------------------

def ensure_temp_dir(temp_dir: str) -> str:
    expanded = os.path.expanduser(temp_dir)
    os.makedirs(expanded, exist_ok=True)
    return expanded


def write_parquet(df: pd.DataFrame, path: str):
    df.to_parquet(path, engine="pyarrow", index=False, compression="snappy")


# ---------------------------------------------------------------------------
# Chunked reads -- avoids fetchall() OOM on large tables (#6 fix)
# ---------------------------------------------------------------------------

def fetch_chunked(conn, query: str, params: tuple, chunk_size: int = 50000):
    """Yield DataFrame chunks to avoid loading all rows into memory at once."""
    with conn.cursor() as cur:
        cur.execute(query, params)
        while True:
            rows = cur.fetchmany(chunk_size)
            if not rows:
                break
            yield pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Batched DELETE by exported ids (D2) -- never by time window
# ---------------------------------------------------------------------------

def batch_delete_by_ids(conn, table: str, ids: list,
                        chunk_size: int = 10000) -> int:
    """Delete exactly the given ids, in chunks of chunk_size.

    Keyed by the exported ids, never by a time window, so a row the export
    never saw (e.g. late-arriving data inserted mid-run) can never be
    deleted. Committed per chunk to keep table locks short. Returns the
    number of rows actually deleted; the caller compares it against the
    exported count.
    """
    total_deleted = 0
    for start in range(0, len(ids), chunk_size):
        chunk = ids[start:start + chunk_size]
        placeholders = ", ".join(["%s"] * len(chunk))
        with conn.cursor() as cur:
            cur.execute(
                f"DELETE FROM {table} WHERE id IN ({placeholders})",
                tuple(chunk),
            )
            deleted = cur.rowcount
        conn.commit()
        total_deleted += deleted
        logger.info("    Deleted batch of %d rows...", deleted)
    return total_deleted


def fetch_protected_position_ids(conn) -> set:
    """tc_devices.positionid values -- a device's latest position is never
    deleted (D9). Fetched once per run and applied as a Python-side filter,
    keeping the DELETE a plain id IN (...) instead of a correlated subquery
    against a table that changes constantly.
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT positionid FROM tc_devices WHERE positionid IS NOT NULL")
        return {row["positionid"] for row in cur.fetchall()}


# ---------------------------------------------------------------------------
# Generic archive function -- replaces duplicated archive_positions/events (#8 fix)
# ---------------------------------------------------------------------------

def archive_table(conn, cfg, table: str, time_col: str, columns: list,
                  spaces_prefix: str, cutoff: date, temp_dir: str,
                  dry_run: bool, datetime_cols: list = None,
                  key_prefix: str = PROD_KEY_PREFIX, deleter=None,
                  id_exclusions: set = None) -> tuple:
    """
    Archive rows older than cutoff for any time-series table.
    Returns (total_rows_archived, failure_count).

    The DB-delete capability is injected via `deleter` (main passes
    batch_delete_by_ids for a destructive run). With deleter=None
    (--archive-only) the deleting branch is never entered: this function then
    holds no reference to any delete code, so no flag check guards a delete
    call -- the capability simply is not there.

    `id_exclusions` (D9): ids never handed to the deleter even when exported
    -- currently the tc_devices.positionid set, so a device's latest position
    stays in the DB (still archived; it is deleted by a later run once the
    device reports again and the pointer moves on).
    """
    total      = 0
    failures   = 0
    tmp_aborts = 0

    with conn.cursor() as cur:
        cur.execute(
            f"SELECT deviceid, YEAR({time_col}) AS yr, MONTH({time_col}) AS mo, COUNT(*) AS cnt "
            f"FROM {table} WHERE {time_col} < %s "
            f"GROUP BY deviceid, YEAR({time_col}), MONTH({time_col}) "
            f"ORDER BY deviceid, yr, mo",
            (cutoff,)
        )
        groups = cur.fetchall()

    if not groups:
        logger.info("[%s] Nothing to archive.", table)
        return 0, 0

    logger.info("[%s] Found %d device/month group(s) to archive.", table, len(groups))

    for g in groups:
        device_id    = g["deviceid"]
        yr, mo       = g["yr"], g["mo"]
        period_start = date(yr, mo, 1)
        period_end   = date(yr + 1, 1, 1) if mo == 12 else date(yr, mo + 1, 1)
        label        = f"{yr}-{mo:02d}"
        local_path   = os.path.join(temp_dir, f"{table}_{device_id}_{label}.parquet")
        spaces_key   = f"{key_prefix}/{spaces_prefix}/{device_id}/{label}.parquet"
        temp_key     = f"{key_prefix}/{spaces_prefix}/{device_id}/{label}.parquet.tmp"
        marker_key   = f"{key_prefix}/{spaces_prefix}/{device_id}/{label}.done"

        logger.info("  [%s] device=%d period=%s rows=%d", table, device_id, label, g["cnt"])

        # C6: a marker no longer skips the group. Discovery only returns
        # groups that still have rows, so marker + rows = late-arriving data
        # that the old skip starved forever (and even counted as archived).
        if verify_upload(cfg, marker_key):
            logger.info(
                "  [%s] .done marker present but the group still has %d "
                "row(s) -- late-arriving data; proceeding instead of "
                "skipping (the export merges with the final object if it "
                "still exists).", table, g["cnt"])

        if check_temp_key_exists(cfg, temp_key):
            # C5: a leftover tmp is EVIDENCE, never garbage. This code cannot
            # tell which history produced it, so it aborts the group and
            # leaves the object exactly as found. Other groups continue.
            logger.error(
                "  [%s] ABORTING GROUP device=%d %s: leftover temp key %s. "
                "Two possible histories and this script cannot tell them "
                "apart: (a) written by THIS version (finalize-before-delete) "
                "-- the run died before finalize, the DB still holds every "
                "row, the tmp is redundant residue; (b) written by an OLDER "
                "version (delete-before-finalize) -- the run died mid-delete "
                "and the tmp may be the ONLY copy of rows already deleted "
                "from the DB. Resolve before touching it: does the final key "
                "%s exist, and does the DB still hold this device-month's "
                "rows? Procedure: docs/cold-storage-fix-runbook.md, section "
                "'Leftover tmp keys'. Other groups continue; this run will "
                "exit non-zero.",
                table, device_id, label, temp_key, spaces_key,
            )
            failures += 1
            tmp_aborts += 1
            continue

        try:
            cols  = ", ".join(columns)
            query = (f"SELECT {cols} FROM {table} "
                     f"WHERE deviceid = %s AND {time_col} >= %s AND {time_col} < %s "
                     f"ORDER BY {time_col}")

            # #6 fix: chunked read
            chunks = list(fetch_chunked(conn, query, (device_id, period_start, period_end)))
            if not chunks:
                logger.info("  [%s] No rows found (skipping).", table)
                continue

            df = pd.concat(chunks, ignore_index=True)

            if datetime_cols:
                for col in datetime_cols:
                    if col in df.columns:
                        df[col] = df[col].astype(str)

            # C6: never overwrite an existing final object blindly -- merge
            # the fresh export into it (additive-only, schema-checked).
            merged_df = df
            if verify_upload(cfg, spaces_key):
                merged_df = merge_with_existing_final(
                    cfg, spaces_key, df, temp_dir, table)
                if merged_df is None:
                    failures += 1
                    continue

            write_parquet(merged_df, local_path)
            logger.info("  [%s] Parquet written: %s (%d rows)",
                        table, local_path, len(merged_df))

            if not do_upload(cfg, local_path, temp_key):
                logger.error("  [%s] Upload to temp key failed -- skipping.", table)
                failures += 1
                continue

            if not verify_upload(cfg, temp_key):
                logger.error("  [%s] Temp key verification failed -- skipping.", table)
                failures += 1
                continue

            if not verify_row_count(cfg, temp_key, len(merged_df)):
                logger.error("  [%s] Row count mismatch on temp key -- skipping.", table)
                failures += 1
                continue

            if dry_run:
                logger.info("  [%s] --dry-run: skipping DB deletion and finalization. "
                            "The temp upload is deleted again now -- dry-run leaves no "
                            "artifact and cannot serve as verification (use "
                            "--archive-only for that).", table)
                delete_spaces_key(cfg, temp_key)
            elif deleter is None:
                # --archive-only: finalize the parquet and stop. No delete
                # capability was injected, so this branch cannot touch the DB;
                # no .done marker either (marker means "archived AND deleted").
                if not finalize_parquet(cfg, temp_key, spaces_key):
                    failures += 1
                    continue
                logger.info("  [%s] Archive-only: finalized %s "
                            "(no DB delete, no marker).", table, spaces_key)
            else:
                # D3: finalize BEFORE any delete. A failure from here up
                # means the group failed with the DB untouched.
                if not finalize_parquet(cfg, temp_key, spaces_key):
                    logger.error("  [%s] Finalize failed for device=%d %s -- "
                                 "group failed BEFORE any DB delete; nothing "
                                 "was removed.", table, device_id, label)
                    failures += 1
                    continue

                # C6: delete only the NEWLY-EXPORTED ids (df, never the
                # merged frame) -- rows already in the archive were deleted
                # from the DB by the prior run that archived them.
                ids = [int(i) for i in df["id"].tolist()]
                if id_exclusions:
                    keep = [i for i in ids if i not in id_exclusions]
                    excluded = len(ids) - len(keep)
                    if excluded:
                        logger.info(
                            "  [%s] Excluding %d row(s) referenced as a "
                            "device's latest position from deletion "
                            "(still archived).", table, excluded)
                    ids = keep
                deleted = deleter(conn, table, ids)
                if deleted != len(ids):
                    logger.error(
                        "  [%s] DELETE COUNT MISMATCH for device=%d %s: "
                        "expected %d, deleted %d. Failing this group loudly: "
                        "the final parquet %s is safely in place, no marker "
                        "uploaded, no repair attempted -- rows may be "
                        "partially deleted; reconcile manually.",
                        table, device_id, label, len(ids), deleted, spaces_key)
                    failures += 1
                    continue
                logger.info("  [%s] Deleted %d rows by id in batches.",
                            table, deleted)

                marker_path = os.path.join(temp_dir, f"{table}_{device_id}_{label}.done")
                try:
                    open(marker_path, 'w').close()
                    do_upload(cfg, marker_path, marker_key)
                    logger.info("  [%s] Done marker uploaded: %s", table, marker_key)
                except Exception as e:
                    logger.warning("  [%s] Could not upload done marker: %s", table, e)
                finally:
                    if os.path.exists(marker_path):
                        os.remove(marker_path)

            total += len(df)

        except Exception as exc:
            logger.error("  [%s] ERROR device=%d %s: %s", table, device_id, label, exc)
            failures += 1  # #7 fix: track failures

        finally:
            if os.path.exists(local_path):
                os.remove(local_path)

    if tmp_aborts:
        logger.error(
            "[%s] %d group(s) aborted on leftover temp keys -- resolve per "
            "the runbook ('Leftover tmp keys') before their next run.",
            table, tmp_aborts)

    return total, failures


# ---------------------------------------------------------------------------
# Column definitions
# ---------------------------------------------------------------------------

POSITIONS_COLUMNS = [
    "id", "deviceid", "servertime", "devicetime", "fixtime",
    "valid", "latitude", "longitude", "altitude", "speed",
    "course", "address", "accuracy", "network", "attributes",
]

EVENTS_COLUMNS = [
    "id", "deviceid", "type", "eventtime",
    "positionid", "geofenceid", "maintenanceid", "attributes",
]

GEOFENCES_COLUMNS = ["id", "name", "description", "area", "calendarid", "attributes"]

DRIVERS_COLUMNS = ["id", "name", "uniqueid", "attributes"]

DEVICES_COLUMNS = [
    "id", "name", "uniqueid", "status", "lastupdate", "positionid",
    "groupid", "phone", "model", "contact", "category", "disabled",
    "expirationtime", "calendarid", "attributes",
]

DEVICE_GEOFENCE_SEGMENT_COLUMNS = [
    "id", "deviceid", "geofenceid", "type",
    "enterpositionid", "exitpositionid",
    "entertime", "exittime",
    "odostart", "odoend", "distance", "open",
]


# ---------------------------------------------------------------------------
# Archive wrappers (#8 fix: one-liners using generic function)
# ---------------------------------------------------------------------------

def archive_positions(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool,
                      key_prefix: str = PROD_KEY_PREFIX, deleter=None,
                      id_exclusions: set = None) -> tuple:
    return archive_table(
        conn, cfg, "tc_positions", "fixtime", POSITIONS_COLUMNS,
        "positions", cutoff, temp_dir, dry_run,
        datetime_cols=["servertime", "devicetime", "fixtime"],
        key_prefix=key_prefix, deleter=deleter, id_exclusions=id_exclusions,
    )


def archive_events(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool,
                   key_prefix: str = PROD_KEY_PREFIX, deleter=None) -> tuple:
    return archive_table(
        conn, cfg, "tc_events", "eventtime", EVENTS_COLUMNS,
        "events", cutoff, temp_dir, dry_run,
        datetime_cols=["eventtime"],
        key_prefix=key_prefix, deleter=deleter,
    )


# ---------------------------------------------------------------------------
# Snapshot helper (store only -- NEVER delete from DB)
# ---------------------------------------------------------------------------

def snapshot_table(conn, cfg, table_name: str, columns: list,
                   spaces_prefix: str, temp_dir: str,
                   datetime_cols: list = None,
                   key_prefix: str = PROD_KEY_PREFIX) -> int:
    """
    Snapshot all rows to Spaces. DB is NEVER modified.
    Uploads: timestamped copy + latest.parquet (overwritten each run).
    """
    # #9 fix: timezone-aware datetime
    now   = dt.now(timezone.utc).replace(tzinfo=None)
    label = now.strftime("%Y-%m-%dT%H-%M-%S")

    local_ts     = os.path.join(temp_dir, f"{table_name}_{label}.parquet")
    local_latest = os.path.join(temp_dir, f"{table_name}_latest.parquet")
    key_ts       = f"{key_prefix}/{spaces_prefix}/{label}.parquet"
    key_latest   = f"{key_prefix}/{spaces_prefix}/latest.parquet"

    logger.info("  [%s] Reading all rows...", table_name)

    try:
        query = f"SELECT {', '.join(columns)} FROM {table_name} ORDER BY id"
        chunks = list(fetch_chunked(conn, query, ()))

        if not chunks:
            logger.info("  [%s] Empty -- nothing to snapshot.", table_name)
            return 0

        df = pd.concat(chunks, ignore_index=True)
        if datetime_cols:
            for col in datetime_cols:
                if col in df.columns:
                    df[col] = df[col].astype(str)

        write_parquet(df, local_ts)
        write_parquet(df, local_latest)
        logger.info("  [%s] Parquet written (%d rows)", table_name, len(df))

        ok_ts     = do_upload(cfg, local_ts,     key_ts)
        ok_latest = do_upload(cfg, local_latest, key_latest)

        if ok_ts and ok_latest:
            logger.info("  [%s] Snapshot uploaded. DB NOT modified.", table_name)
        else:
            logger.warning("  [%s] One or both uploads failed.", table_name)

        return len(df)

    except Exception as exc:
        logger.error("  [%s] ERROR: %s", table_name, exc)
        return 0

    finally:
        for p in [local_ts, local_latest]:
            if os.path.exists(p):
                os.remove(p)


def snapshot_geofences(conn, cfg, temp_dir, key_prefix=PROD_KEY_PREFIX):
    return snapshot_table(conn, cfg, "tc_geofences",
                          GEOFENCES_COLUMNS, "geofences", temp_dir,
                          key_prefix=key_prefix)

def snapshot_drivers(conn, cfg, temp_dir, key_prefix=PROD_KEY_PREFIX):
    return snapshot_table(conn, cfg, "tc_drivers",
                          DRIVERS_COLUMNS, "drivers", temp_dir,
                          key_prefix=key_prefix)

def snapshot_devices(conn, cfg, temp_dir, key_prefix=PROD_KEY_PREFIX):
    return snapshot_table(conn, cfg, "tc_devices",
                          DEVICES_COLUMNS, "devices", temp_dir,
                          datetime_cols=["lastupdate", "expirationtime"],
                          key_prefix=key_prefix)

def snapshot_device_geofence_segments(conn, cfg, temp_dir, key_prefix=PROD_KEY_PREFIX):
    return snapshot_table(conn, cfg, "tc_device_geofence_segment",
                          DEVICE_GEOFENCE_SEGMENT_COLUMNS,
                          "device_geofence_segments", temp_dir,
                          datetime_cols=["entertime", "exittime"],
                          key_prefix=key_prefix)


# ---------------------------------------------------------------------------
# Run lock -- cron and a manual run must never overlap
# ---------------------------------------------------------------------------

# One fixed lock path -- no config derivation, no fallback. Deriving it from
# archive.temp.dir would let a run with a different --config lock a different
# file; a fallback path has the same flaw (two identities resolving to two
# different files = no mutual exclusion). If the running identity cannot open
# this path, the run fails loudly instead. Tentative pending the L1 host
# answers (which identities run cron vs. hand-runs, and what /var/lock allows).
LOCK_PATH = "/var/lock/traccar-archive.lock"


def _lock_path() -> str:
    """Fixed lock location, deliberately independent of any config value."""
    return LOCK_PATH


def acquire_run_lock():
    """Take an exclusive non-blocking lock; abort loudly if already held.

    Guards the one real overlap on a single-host deployment: the cron firing
    while someone hand-runs the script. Fail-fast by design -- a held lock
    exits non-zero immediately instead of queueing this run behind the other
    one. Only a missing fcntl module (Windows dev box) downgrades to a
    warning; on a real host any lock-file problem is fatal rather than a
    silent run without exclusion. The returned handle must stay referenced
    for the whole run; the lock is released when the process exits.
    """
    if fcntl is None:
        logger.warning("File locking unavailable on this platform -- "
                       "running WITHOUT overlap protection.")
        return None
    lock_path = _lock_path()
    try:
        handle = open(lock_path, "w")
    except OSError as e:
        logger.error("Cannot open lock file %s: %s -- refusing to run "
                     "without overlap protection.", lock_path, e)
        sys.exit(1)
    try:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        handle.close()
        logger.error("Another archiver instance already holds the lock (%s) "
                     "-- aborting this run.", lock_path)
        sys.exit(1)
    handle.write(str(os.getpid()))
    handle.flush()
    return handle


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    args = parse_args()
    key_prefix, archive_only = resolve_run_options(args)

    # First line on the record: where this run writes. A misdirected
    # rehearsal must be obvious from the very top of the log.
    logger.info("KEY PREFIX: %s/  (archive-only=%s, dry-run=%s)",
                key_prefix, archive_only, args.dry_run)

    props = load_config_xml(args.config)
    cfg   = PropsConfig(props)

    logger.info("KEY SPACE: s3://%s/%s/...",
                cfg.get("spaces", "bucket") or "<no bucket configured>",
                key_prefix)

    retention_months = (args.months if args.months is not None
                        else int(props.get("archive.retention.months", 6)))
    temp_dir = ensure_temp_dir(props.get("archive.temp.dir", "/tmp/traccar-archive"))
    dry_run  = args.dry_run

    # Held via the returned handle until the process exits.
    run_lock = acquire_run_lock()  # noqa: F841

    # The only place the delete capability is granted. --archive-only runs
    # never receive it, so no code path in them can delete rows.
    deleter = None if archive_only else batch_delete_by_ids

    # #9 fix: timezone-aware datetime
    now    = dt.now(timezone.utc).replace(tzinfo=None)
    cutoff = (now - relativedelta(months=retention_months)).date()

    logger.info("=" * 60)
    logger.info("  Traccar Cold Storage Archiver")
    logger.info("  Started  : %s UTC", now.strftime("%Y-%m-%d %H:%M:%S"))
    logger.info("  Cutoff   : %s  (retention = %d months)", cutoff, retention_months)
    local_upload_dir = cfg.get("spaces", "local_upload_dir")
    if local_upload_dir:
        logger.info("  Mode     : LOCAL TEST (Upload -> %s)", local_upload_dir)
    else:
        logger.info("  Mode     : DO SPACES (Bucket -> %s)",
                    cfg.get("spaces", "bucket") or "N/A")
    logger.info("  Temp dir : %s", temp_dir)
    logger.info("  Dry run  : %s", dry_run)
    logger.info("  Archive-only : %s", archive_only)
    logger.info("  Key prefix   : %s/", key_prefix)
    logger.info("=" * 60)

    try:
        conn = get_connection(props)
    except Exception as e:
        logger.error("Cannot connect to DB: %s", e)
        sys.exit(1)

    total_failures = 0
    pos_total = evt_total = geo_total = drv_total = dev_total = seg_total = 0

    try:
        protected_ids = set()
        if deleter is not None:
            # D9: fetched once per run; a device's latest position is never
            # handed to the deleter.
            protected_ids = fetch_protected_position_ids(conn)
            logger.info("Protected latest-position ids (D9, excluded from "
                        "deletion): %d", len(protected_ids))

        mode_note = "archive only, DB unchanged" if archive_only else "then delete"
        logger.info("\n--- Archiving POSITIONS (old rows -> Spaces, %s) ---", mode_note)
        pos_total, pf = archive_positions(conn, cfg, cutoff, temp_dir, dry_run,
                                          key_prefix=key_prefix, deleter=deleter,
                                          id_exclusions=protected_ids)
        total_failures += pf

        logger.info("\n--- Archiving EVENTS (old rows -> Spaces, %s) ---", mode_note)
        evt_total, ef = archive_events(conn, cfg, cutoff, temp_dir, dry_run,
                                       key_prefix=key_prefix, deleter=deleter)
        total_failures += ef

        logger.info("\n--- Snapshotting GEOFENCES (store only, DB unchanged) ---")
        geo_total = snapshot_geofences(conn, cfg, temp_dir, key_prefix)

        logger.info("\n--- Snapshotting DRIVERS (store only, DB unchanged) ---")
        drv_total = snapshot_drivers(conn, cfg, temp_dir, key_prefix)

        logger.info("\n--- Snapshotting DEVICES (store only, DB unchanged) ---")
        dev_total = snapshot_devices(conn, cfg, temp_dir, key_prefix)

        logger.info("\n--- Snapshotting DEVICE GEOFENCE SEGMENTS (store only, DB unchanged) ---")
        seg_total = snapshot_device_geofence_segments(conn, cfg, temp_dir, key_prefix)

    finally:
        conn.close()

    logger.info("\n" + "=" * 60)
    logger.info("  Archive complete.")
    row_note = "(DB unchanged -- archive-only)" if archive_only else "(deleted from DB)"
    logger.info("  Positions archived      : %d  %s", pos_total, row_note)
    logger.info("  Events archived         : %d  %s", evt_total, row_note)
    logger.info("  Geofences snapshotted   : %d  (DB unchanged)", geo_total)
    logger.info("  Drivers snapshotted     : %d  (DB unchanged)", drv_total)
    logger.info("  Devices snapshotted     : %d  (DB unchanged)", dev_total)
    logger.info("  Geofence segs snap.     : %d  (DB unchanged)", seg_total)
    if dry_run:
        logger.info("  NOTE: --dry-run -- no rows deleted from DB, and the "
                    "verification uploads were removed again (no artifact left).")
    if archive_only:
        logger.info("  NOTE: --archive-only -- no rows deleted, no markers uploaded.")
    if total_failures > 0:
        logger.warning("  WARNING: %d group(s) failed.", total_failures)
    logger.info("=" * 60)

    # #7 fix: non-zero exit so cron detects failures
    if total_failures > 0:
        sys.exit(1)


if __name__ == "__main__":
    missing = []
    for pkg in ("pymysql", "pandas", "pyarrow", "dateutil"):
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg.replace("dateutil", "python-dateutil"))
    if missing:
        logger.error("Missing packages: %s", ", ".join(missing))
        logger.error("Run: pip install pymysql pandas pyarrow python-dateutil")
        sys.exit(1)
    main()