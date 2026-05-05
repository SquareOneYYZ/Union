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
                        help="Upload but skip DB deletion")
    parser.add_argument("--months",  type=int, default=None,
                        help="Override retention_months from config")
    return parser.parse_args()


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


def verify_upload(cfg, spaces_key: str) -> bool:
    """Verify file landed in Spaces after upload. (#4 fix: no delete without verify)"""
    bucket = cfg.get("spaces", "bucket")
    dest   = f"s3://{bucket}/{spaces_key}"
    cmd    = build_s3cmd_base(cfg) + ["ls", dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    exists = result.returncode == 0 and spaces_key in result.stdout
    if not exists:
        logger.warning("Verification failed -- file not found at %s", dest)
    return exists

def check_temp_key_exists(cfg, temp_spaces_key: str) -> bool:
    """Check if a previous run left a temp upload behind."""
    bucket = cfg.get("spaces", "bucket")
    dest   = f"s3://{bucket}/{temp_spaces_key}"
    cmd    = build_s3cmd_base(cfg) + ["ls", dest]
    result = subprocess.run(cmd, capture_output=True, text=True)
    return result.returncode == 0 and temp_spaces_key in result.stdout


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
# Batched DELETE -- avoids long table locks (#5 fix)
# ---------------------------------------------------------------------------

def batch_delete(conn, table: str, time_col: str, device_id: int,
                 period_start: date, period_end: date,
                 batch_size: int = 10000) -> int:
    """Delete in small batches to prevent long table locks."""
    total_deleted = 0
    while True:
        with conn.cursor() as cur:
            cur.execute(
                f"DELETE FROM {table} "
                f"WHERE deviceid = %s AND {time_col} >= %s AND {time_col} < %s "
                f"LIMIT %s",
                (device_id, period_start, period_end, batch_size),
            )
            deleted = cur.rowcount
        conn.commit()
        total_deleted += deleted
        logger.info("    Deleted batch of %d rows...", deleted)
        if deleted < batch_size:
            break
    return total_deleted


# ---------------------------------------------------------------------------
# Generic archive function -- replaces duplicated archive_positions/events (#8 fix)
# ---------------------------------------------------------------------------

def archive_table(conn, cfg, table: str, time_col: str, columns: list,
                  spaces_prefix: str, cutoff: date, temp_dir: str,
                  dry_run: bool, datetime_cols: list = None) -> tuple:
    """
    Archive rows older than cutoff for any time-series table.
    Returns (total_rows_archived, failure_count).
    """
    total    = 0
    failures = 0

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
        spaces_key   = f"archive/{spaces_prefix}/{device_id}/{label}.parquet"
        temp_key     = f"archive/{spaces_prefix}/{device_id}/{label}.parquet.tmp"
        marker_key   = f"archive/{spaces_prefix}/{device_id}/{label}.done"

        logger.info("  [%s] device=%d period=%s rows=%d", table, device_id, label, g["cnt"])

        if verify_upload(cfg, marker_key):
            logger.info("  [%s] Already archived (found .done marker) -- skipping.", table)
            total += g["cnt"]
            continue

        if check_temp_key_exists(cfg, temp_key):
            logger.warning(
                "  [%s] Found leftover temp key %s — previous run was killed mid-delete. "
                "Cleaning up and restarting this group.", table, temp_key
            )
            delete_spaces_key(cfg, temp_key)

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

            write_parquet(df, local_path)
            logger.info("  [%s] Parquet written: %s (%d rows)", table, local_path, len(df))

            if not do_upload(cfg, local_path, temp_key):
                logger.error("  [%s] Upload to temp key failed -- skipping.", table)
                failures += 1
                continue

            if not verify_upload(cfg, temp_key):
                logger.error("  [%s] Temp key verification failed -- skipping.", table)
                failures += 1
                continue

            if not verify_row_count(cfg, temp_key, len(df)):
                logger.error("  [%s] Row count mismatch on temp key -- skipping.", table)
                failures += 1
                continue

            if dry_run:
                logger.info("  [%s] --dry-run: skipping DB deletion and finalization.", table)
                delete_spaces_key(cfg, temp_key)
            else:
                deleted = batch_delete(conn, table, time_col,
                                       device_id, period_start, period_end)
                logger.info("  [%s] Deleted %d rows in batches.", table, deleted)

                if not copy_spaces_key(cfg, temp_key, spaces_key):
                    logger.error(
                        "  [%s] CRITICAL: DB deleted but could not finalize parquet key. "
                        "Temp key still exists at %s — recover manually.", table, temp_key
                    )
                    failures += 1
                    continue

                delete_spaces_key(cfg, temp_key)

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

def archive_positions(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool) -> tuple:
    return archive_table(
        conn, cfg, "tc_positions", "fixtime", POSITIONS_COLUMNS,
        "positions", cutoff, temp_dir, dry_run,
        datetime_cols=["servertime", "devicetime", "fixtime"],
    )


def archive_events(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool) -> tuple:
    return archive_table(
        conn, cfg, "tc_events", "eventtime", EVENTS_COLUMNS,
        "events", cutoff, temp_dir, dry_run,
        datetime_cols=["eventtime"],
    )


# ---------------------------------------------------------------------------
# Snapshot helper (store only -- NEVER delete from DB)
# ---------------------------------------------------------------------------

def snapshot_table(conn, cfg, table_name: str, columns: list,
                   spaces_prefix: str, temp_dir: str,
                   datetime_cols: list = None) -> int:
    """
    Snapshot all rows to Spaces. DB is NEVER modified.
    Uploads: timestamped copy + latest.parquet (overwritten each run).
    """
    # #9 fix: timezone-aware datetime
    now   = dt.now(timezone.utc).replace(tzinfo=None)
    label = now.strftime("%Y-%m-%dT%H-%M-%S")

    local_ts     = os.path.join(temp_dir, f"{table_name}_{label}.parquet")
    local_latest = os.path.join(temp_dir, f"{table_name}_latest.parquet")
    key_ts       = f"archive/{spaces_prefix}/{label}.parquet"
    key_latest   = f"archive/{spaces_prefix}/latest.parquet"

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


def snapshot_geofences(conn, cfg, temp_dir):
    return snapshot_table(conn, cfg, "tc_geofences",
                          GEOFENCES_COLUMNS, "geofences", temp_dir)

def snapshot_drivers(conn, cfg, temp_dir):
    return snapshot_table(conn, cfg, "tc_drivers",
                          DRIVERS_COLUMNS, "drivers", temp_dir)

def snapshot_devices(conn, cfg, temp_dir):
    return snapshot_table(conn, cfg, "tc_devices",
                          DEVICES_COLUMNS, "devices", temp_dir,
                          datetime_cols=["lastupdate", "expirationtime"])

def snapshot_device_geofence_segments(conn, cfg, temp_dir):
    return snapshot_table(conn, cfg, "tc_device_geofence_segment",
                          DEVICE_GEOFENCE_SEGMENT_COLUMNS,
                          "device_geofence_segments", temp_dir,
                          datetime_cols=["entertime", "exittime"])


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    args  = parse_args()
    props = load_config_xml(args.config)
    cfg   = PropsConfig(props)

    retention_months = (args.months if args.months is not None
                        else int(props.get("archive.retention.months", 6)))
    temp_dir = ensure_temp_dir(props.get("archive.temp.dir", "/tmp/traccar-archive"))
    dry_run  = args.dry_run

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
    logger.info("=" * 60)

    try:
        conn = get_connection(props)
    except Exception as e:
        logger.error("Cannot connect to DB: %s", e)
        sys.exit(1)

    total_failures = 0
    pos_total = evt_total = geo_total = drv_total = dev_total = seg_total = 0

    try:
        logger.info("\n--- Archiving POSITIONS (old rows -> Spaces, then delete) ---")
        pos_total, pf = archive_positions(conn, cfg, cutoff, temp_dir, dry_run)
        total_failures += pf

        logger.info("\n--- Archiving EVENTS (old rows -> Spaces, then delete) ---")
        evt_total, ef = archive_events(conn, cfg, cutoff, temp_dir, dry_run)
        total_failures += ef

        logger.info("\n--- Snapshotting GEOFENCES (store only, DB unchanged) ---")
        geo_total = snapshot_geofences(conn, cfg, temp_dir)

        logger.info("\n--- Snapshotting DRIVERS (store only, DB unchanged) ---")
        drv_total = snapshot_drivers(conn, cfg, temp_dir)

        logger.info("\n--- Snapshotting DEVICES (store only, DB unchanged) ---")
        dev_total = snapshot_devices(conn, cfg, temp_dir)

        logger.info("\n--- Snapshotting DEVICE GEOFENCE SEGMENTS (store only, DB unchanged) ---")
        seg_total = snapshot_device_geofence_segments(conn, cfg, temp_dir)

    finally:
        conn.close()

    logger.info("\n" + "=" * 60)
    logger.info("  Archive complete.")
    logger.info("  Positions archived      : %d  (deleted from DB)", pos_total)
    logger.info("  Events archived         : %d  (deleted from DB)", evt_total)
    logger.info("  Geofences snapshotted   : %d  (DB unchanged)", geo_total)
    logger.info("  Drivers snapshotted     : %d  (DB unchanged)", drv_total)
    logger.info("  Devices snapshotted     : %d  (DB unchanged)", dev_total)
    logger.info("  Geofence segs snap.     : %d  (DB unchanged)", seg_total)
    if dry_run:
        logger.info("  NOTE: --dry-run -- no rows deleted from DB.")
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