#!/usr/bin/env python3
"""
archive_cold_storage.py
=======================
Archives old tc_positions and tc_events rows from MySQL to DigitalOcean Spaces
in Parquet format using s3cmd.

Each table is archived INDEPENDENTLY (no joins).
Data is grouped by deviceid × year × month — one Parquet file per group.

SNAPSHOT TABLES (store only, NEVER deleted from DB):
  - tc_geofences
  - tc_drivers
  - tc_devices
  - tc_device_geofence_segment
  These are snapshotted on every run into a single file each.

Usage (from Git Bash):
    python archive_cold_storage.py [--config archive.config] [--dry-run] [--months 6]

Flags:
    --config FILE   Path to config file (default: archive.config in script dir)
    --dry-run       Upload to Spaces but SKIP deletion from DB (safe test mode)
    --months N      Override retention_months from config

Requirements:
    pip install pymysql pandas pyarrow

s3cmd must be installed and configured:
    pip install s3cmd
    s3cmd --configure
"""

import argparse
import configparser
import os
import subprocess
import sys
import shutil
from datetime import datetime, date
from dateutil.relativedelta import relativedelta
import pandas as pd
import pymysql
import pymysql.cursors


# ---------------------------------------------------------------------------
# CLI argument parsing
# ---------------------------------------------------------------------------

def parse_args():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(
        description="Archive old Traccar positions & events to DigitalOcean Spaces"
    )
    parser.add_argument(
        "--config",
        default=os.path.join(script_dir, "archive.config"),
        help="Path to the config file (default: archive.config next to the script)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Upload Parquet files to Spaces but do NOT delete rows from DB",
    )
    parser.add_argument(
        "--months",
        type=int,
        default=None,
        help="Override retention_months from config",
    )
    return parser.parse_args()


# ---------------------------------------------------------------------------
# Config loading
# ---------------------------------------------------------------------------

def load_config(config_path: str) -> configparser.ConfigParser:
    if not os.path.exists(config_path):
        print(f"[ERROR] Config file not found: {config_path}")
        sys.exit(1)
    cfg = configparser.ConfigParser()
    cfg.read(config_path)
    return cfg


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

def get_connection(cfg: configparser.ConfigParser):
    return pymysql.connect(
        host=cfg.get("database", "host"),
        port=cfg.getint("database", "port"),
        user=cfg.get("database", "user"),
        password=cfg.get("database", "password"),
        database=cfg.get("database", "name"),
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )


# ---------------------------------------------------------------------------
# s3cmd helpers
# ---------------------------------------------------------------------------

def build_s3cmd_base(s3cmd_config: str) -> list:
    return [
        r"C:\Python311\python.exe",
        r"C:\Python311\Scripts\s3cmd"
    ]


def s3cmd_upload(s3cmd_config: str, local_file: str, bucket: str, key: str) -> bool:
    """Upload a local file to Spaces. Returns True on success."""
    dest = f"s3://{bucket}/{key}"
    cmd = build_s3cmd_base(s3cmd_config) + ["put", "--acl-private", local_file, dest]
    print(f"  [S3] Uploading {os.path.basename(local_file)} → {dest}")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"  [S3 ERROR] {result.stderr.strip()}")
        return False
    return True


def local_upload(local_file: str, upload_dir: str, key: str) -> bool:
    """Copy a local file to a local "upload" directory. Returns True on success."""
    dest = os.path.join(upload_dir, key.replace("/", os.sep))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"  [LOCAL] Copying {os.path.basename(local_file)} → {dest}")
    try:
        shutil.copy2(local_file, dest)
        return True
    except Exception as e:
        print(f"  [LOCAL ERROR] {e}")
        return False


def do_upload(cfg, local_path: str, spaces_key: str) -> bool:
    """Route upload to either local dir or DO Spaces based on config."""
    bucket = cfg.get("spaces", "bucket", fallback="")
    s3cmd_config = cfg.get("spaces", "s3cmd_config", fallback="")
    local_upload_dir = cfg.get("spaces", "local_upload_dir", fallback="")

    if local_upload_dir:
        return local_upload(local_path, local_upload_dir, spaces_key)
    else:
        if not bucket:
            print(f"  [ERROR] bucket not configured and local_upload_dir is empty.")
            return False
        return s3cmd_upload(s3cmd_config, local_path, bucket, spaces_key)


# ---------------------------------------------------------------------------
# Parquet helpers
# ---------------------------------------------------------------------------

def ensure_temp_dir(temp_dir: str) -> str:
    expanded = os.path.expanduser(temp_dir)
    os.makedirs(expanded, exist_ok=True)
    return expanded


def write_parquet(df: pd.DataFrame, path: str):
    """Write DataFrame to Parquet using pyarrow engine."""
    df.to_parquet(path, engine="pyarrow", index=False, compression="snappy")


# ---------------------------------------------------------------------------
# Generic snapshot helper
# (store ALL rows in one file, NEVER delete from DB)
# ---------------------------------------------------------------------------

def snapshot_table(conn, cfg, table_name: str, columns: list,
                   spaces_prefix: str, temp_dir: str,
                   datetime_cols: list = None) -> int:
    """
    Reads ALL rows from table_name and uploads a single Parquet snapshot to Spaces.
    Data is NEVER deleted from the database — this is a read-only snapshot.

    File is stored at:  archive/{spaces_prefix}/latest.parquet
    A timestamped copy is also stored at:
                        archive/{spaces_prefix}/YYYY-MM-DDTHH-MM-SS.parquet
    so you have a full history of snapshots.

    Returns number of rows snapshotted (0 on error or empty table).
    """
    label = datetime.utcnow().strftime("%Y-%m-%dT%H-%M-%S")
    filename_ts    = f"{table_name}_{label}.parquet"
    filename_latest = f"{table_name}_latest.parquet"

    local_path_ts     = os.path.join(temp_dir, filename_ts)
    local_path_latest = os.path.join(temp_dir, filename_latest)

    spaces_key_ts     = f"archive/{spaces_prefix}/{label}.parquet"
    spaces_key_latest = f"archive/{spaces_prefix}/latest.parquet"

    print(f"\n  [{table_name}] Reading all rows...")

    try:
        with conn.cursor() as cur:
            cols_sql = ", ".join(columns)
            cur.execute(f"SELECT {cols_sql} FROM {table_name} ORDER BY id")
            rows = cur.fetchall()

        if not rows:
            print(f"  [{table_name}] Table is empty — nothing to snapshot.")
            return 0

        df = pd.DataFrame(rows)

        # Convert any datetime columns to strings for safe Parquet serialisation
        if datetime_cols:
            for col in datetime_cols:
                if col in df.columns:
                    df[col] = df[col].astype(str)

        # Write two copies: timestamped + latest
        write_parquet(df, local_path_ts)
        write_parquet(df, local_path_latest)
        print(f"  [{table_name}] Parquet written ({len(df)} rows)")

        # Upload timestamped snapshot (history)
        ok_ts = do_upload(cfg, local_path_ts, spaces_key_ts)
        # Upload latest snapshot (overwrite)
        ok_latest = do_upload(cfg, local_path_latest, spaces_key_latest)

        if ok_ts and ok_latest:
            print(f"  [{table_name}] Snapshot uploaded successfully. DB data NOT deleted.")
        else:
            print(f"  [{table_name}] WARNING: one or both uploads failed.")

        return len(df)

    except Exception as exc:
        print(f"  [{table_name}] ERROR: {exc}")
        return 0

    finally:
        for p in [local_path_ts, local_path_latest]:
            if os.path.exists(p):
                os.remove(p)


# ---------------------------------------------------------------------------
# Archive positions  (store + DELETE from DB after archival)
# ---------------------------------------------------------------------------

POSITIONS_COLUMNS = [
    "id", "deviceid", "servertime", "devicetime", "fixtime",
    "valid", "latitude", "longitude", "altitude", "speed",
    "course", "address", "accuracy", "network", "attributes",
]


def archive_positions(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool) -> int:
    """Archive tc_positions older than cutoff. Returns total rows archived."""
    bucket = cfg.get("spaces", "bucket", fallback="")
    s3cmd_config = cfg.get("spaces", "s3cmd_config", fallback="")
    local_upload_dir = cfg.get("spaces", "local_upload_dir", fallback="")
    total = 0

    with conn.cursor() as cur:
        cur.execute("""
            SELECT deviceid,
                   YEAR(fixtime)  AS yr,
                   MONTH(fixtime) AS mo,
                   COUNT(*)       AS cnt
            FROM tc_positions
            WHERE fixtime < %s
            GROUP BY deviceid, YEAR(fixtime), MONTH(fixtime)
            ORDER BY deviceid, yr, mo
        """, (cutoff,))
        groups = cur.fetchall()

    if not groups:
        print("[positions] Nothing to archive.")
        return 0

    print(f"[positions] Found {len(groups)} device/month group(s) to archive.")

    for g in groups:
        device_id = g["deviceid"]
        yr = g["yr"]
        mo = g["mo"]
        cnt = g["cnt"]

        period_start = date(yr, mo, 1)
        if mo == 12:
            period_end = date(yr + 1, 1, 1)
        else:
            period_end = date(yr, mo + 1, 1)

        label = f"{yr}-{mo:02d}"
        filename = f"positions_{device_id}_{label}.parquet"
        local_path = os.path.join(temp_dir, filename)
        spaces_key = f"archive/positions/{device_id}/{label}.parquet"

        print(f"\n  [positions] device={device_id} period={label} rows={cnt}")

        try:
            with conn.cursor() as cur:
                cols = ", ".join(POSITIONS_COLUMNS)
                cur.execute(
                    f"SELECT {cols} FROM tc_positions "
                    f"WHERE deviceid = %s AND fixtime >= %s AND fixtime < %s "
                    f"ORDER BY fixtime",
                    (device_id, period_start, period_end),
                )
                rows = cur.fetchall()

            if not rows:
                print(f"  [positions] No rows found (skipping).")
                continue

            df = pd.DataFrame(rows)
            for col in ["servertime", "devicetime", "fixtime"]:
                if col in df.columns:
                    df[col] = df[col].astype(str)
            write_parquet(df, local_path)
            print(f"  [positions] Parquet written: {local_path} ({len(df)} rows)")

            success = do_upload(cfg, local_path, spaces_key)

            if not success:
                print(f"  [positions] Storage failed — skipping deletion for safety.")
                continue

            if dry_run:
                print(f"  [positions] --dry-run: skipping DB deletion.")
            else:
                with conn.cursor() as cur:
                    cur.execute(
                        "DELETE FROM tc_positions "
                        "WHERE deviceid = %s AND fixtime >= %s AND fixtime < %s",
                        (device_id, period_start, period_end),
                    )
                conn.commit()
                print(f"  [positions] Deleted {cur.rowcount} rows from tc_positions.")

            total += len(df)

        except Exception as exc:
            print(f"  [positions] ERROR for device={device_id} {label}: {exc}")

        finally:
            if os.path.exists(local_path):
                os.remove(local_path)

    return total


# ---------------------------------------------------------------------------
# Archive events  (store + DELETE from DB after archival)
# ---------------------------------------------------------------------------

EVENTS_COLUMNS = [
    "id", "deviceid", "type", "eventtime",
    "positionid", "geofenceid", "maintenanceid", "attributes",
]


def archive_events(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool) -> int:
    """Archive tc_events older than cutoff. Returns total rows archived."""
    total = 0

    with conn.cursor() as cur:
        cur.execute("""
            SELECT deviceid,
                   YEAR(eventtime)  AS yr,
                   MONTH(eventtime) AS mo,
                   COUNT(*)         AS cnt
            FROM tc_events
            WHERE eventtime < %s
            GROUP BY deviceid, YEAR(eventtime), MONTH(eventtime)
            ORDER BY deviceid, yr, mo
        """, (cutoff,))
        groups = cur.fetchall()

    if not groups:
        print("[events] Nothing to archive.")
        return 0

    print(f"[events] Found {len(groups)} device/month group(s) to archive.")

    for g in groups:
        device_id = g["deviceid"]
        yr = g["yr"]
        mo = g["mo"]
        cnt = g["cnt"]

        period_start = date(yr, mo, 1)
        if mo == 12:
            period_end = date(yr + 1, 1, 1)
        else:
            period_end = date(yr, mo + 1, 1)

        label = f"{yr}-{mo:02d}"
        filename = f"events_{device_id}_{label}.parquet"
        local_path = os.path.join(temp_dir, filename)
        spaces_key = f"archive/events/{device_id}/{label}.parquet"

        print(f"\n  [events] device={device_id} period={label} rows={cnt}")

        try:
            with conn.cursor() as cur:
                cols = ", ".join(EVENTS_COLUMNS)
                cur.execute(
                    f"SELECT {cols} FROM tc_events "
                    f"WHERE deviceid = %s AND eventtime >= %s AND eventtime < %s "
                    f"ORDER BY eventtime",
                    (device_id, period_start, period_end),
                )
                rows = cur.fetchall()

            if not rows:
                print(f"  [events] No rows found (skipping).")
                continue

            df = pd.DataFrame(rows)
            for col in ["eventtime"]:
                if col in df.columns:
                    df[col] = df[col].astype(str)
            write_parquet(df, local_path)
            print(f"  [events] Parquet written: {local_path} ({len(df)} rows)")

            success = do_upload(cfg, local_path, spaces_key)

            if not success:
                print(f"  [events] Storage failed — skipping deletion for safety.")
                continue

            if dry_run:
                print(f"  [events] --dry-run: skipping DB deletion.")
            else:
                with conn.cursor() as cur:
                    cur.execute(
                        "DELETE FROM tc_events "
                        "WHERE deviceid = %s AND eventtime >= %s AND eventtime < %s",
                        (device_id, period_start, period_end),
                    )
                conn.commit()
                print(f"  [events] Deleted {cur.rowcount} rows from tc_events.")

            total += len(df)

        except Exception as exc:
            print(f"  [events] ERROR for device={device_id} {label}: {exc}")

        finally:
            if os.path.exists(local_path):
                os.remove(local_path)

    return total


# ---------------------------------------------------------------------------
# SNAPSHOT TABLES  (store only — NEVER delete from DB)
# ---------------------------------------------------------------------------

# tc_geofences
GEOFENCES_COLUMNS = [
    "id", "name", "description", "area", "calendarid", "attributes",
]

# tc_drivers
DRIVERS_COLUMNS = [
    "id", "name", "uniqueid", "attributes",
]

# tc_devices
DEVICES_COLUMNS = [
    "id", "name", "uniqueid", "status", "lastupdate", "positionid",
    "groupid", "phone", "model", "contact", "category", "disabled",
    "expirationtime", "calendarid", "attributes",
]

# tc_device_geofence_segment
DEVICE_GEOFENCE_SEGMENT_COLUMNS = [
    "id", "deviceid", "geofenceid", "type",
    "enterpositionid", "exitpositionid",
    "entertime", "exittime",
    "odostart", "odoend", "distance", "open",
]


def snapshot_geofences(conn, cfg, temp_dir: str) -> int:
    """Snapshot tc_geofences → store only, no DB delete."""
    return snapshot_table(
        conn, cfg,
        table_name="tc_geofences",
        columns=GEOFENCES_COLUMNS,
        spaces_prefix="geofences",
        temp_dir=temp_dir,
    )


def snapshot_drivers(conn, cfg, temp_dir: str) -> int:
    """Snapshot tc_drivers → store only, no DB delete."""
    return snapshot_table(
        conn, cfg,
        table_name="tc_drivers",
        columns=DRIVERS_COLUMNS,
        spaces_prefix="drivers",
        temp_dir=temp_dir,
    )


def snapshot_devices(conn, cfg, temp_dir: str) -> int:
    """Snapshot tc_devices → store only, no DB delete."""
    return snapshot_table(
        conn, cfg,
        table_name="tc_devices",
        columns=DEVICES_COLUMNS,
        spaces_prefix="devices",
        temp_dir=temp_dir,
        datetime_cols=["lastupdate", "expirationtime"],
    )


def snapshot_device_geofence_segments(conn, cfg, temp_dir: str) -> int:
    """Snapshot tc_device_geofence_segment → store only, no DB delete."""
    return snapshot_table(
        conn, cfg,
        table_name="tc_device_geofence_segment",
        columns=DEVICE_GEOFENCE_SEGMENT_COLUMNS,
        spaces_prefix="device_geofence_segments",
        temp_dir=temp_dir,
        datetime_cols=["entertime", "exittime"],
    )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    args = parse_args()
    cfg = load_config(args.config)

    retention_months = args.months if args.months is not None else cfg.getint("archive", "retention_months")
    temp_dir = ensure_temp_dir(cfg.get("spaces", "temp_dir", fallback="/tmp/traccar-archive"))
    dry_run = args.dry_run

    cutoff = (datetime.utcnow() - relativedelta(months=retention_months)).date()

    print("=" * 60)
    print(f"  Traccar Cold Storage Archiver")
    print(f"  Started  : {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')} UTC")
    print(f"  Cutoff   : {cutoff}  (retention = {retention_months} months)")

    local_upload_dir = cfg.get("spaces", "local_upload_dir", fallback="")
    if local_upload_dir:
        print(f"  Mode     : LOCAL TEST (Upload → {local_upload_dir})")
    else:
        print(f"  Mode     : DO SPACES (Bucket → {cfg.get('spaces', 'bucket', fallback='N/A')})")

    print(f"  Temp dir : {temp_dir}")
    print(f"  Dry run  : {dry_run}")
    print("=" * 60)

    try:
        conn = get_connection(cfg)
    except Exception as e:
        print(f"[ERROR] Cannot connect to DB: {e}")
        sys.exit(1)

    try:
        # --- Time-series tables: archive old rows + delete from DB ---
        print("\n--- Archiving POSITIONS (old rows → Spaces, then delete from DB) ---")
        pos_total = archive_positions(conn, cfg, cutoff, temp_dir, dry_run)

        print("\n--- Archiving EVENTS (old rows → Spaces, then delete from DB) ---")
        evt_total = archive_events(conn, cfg, cutoff, temp_dir, dry_run)

        # --- Snapshot tables: copy to Spaces, NEVER delete from DB ---
        print("\n--- Snapshotting GEOFENCES (store only, DB unchanged) ---")
        geo_total = snapshot_geofences(conn, cfg, temp_dir)

        print("\n--- Snapshotting DRIVERS (store only, DB unchanged) ---")
        drv_total = snapshot_drivers(conn, cfg, temp_dir)

        print("\n--- Snapshotting DEVICES (store only, DB unchanged) ---")
        dev_total = snapshot_devices(conn, cfg, temp_dir)

        print("\n--- Snapshotting DEVICE GEOFENCE SEGMENTS (store only, DB unchanged) ---")
        seg_total = snapshot_device_geofence_segments(conn, cfg, temp_dir)

    finally:
        conn.close()

    print("\n" + "=" * 60)
    print(f"  Archive complete.")
    print(f"  Positions archived         : {pos_total}  (deleted from DB)")
    print(f"  Events archived            : {evt_total}  (deleted from DB)")
    print(f"  Geofences snapshotted      : {geo_total}  (DB unchanged)")
    print(f"  Drivers snapshotted        : {drv_total}  (DB unchanged)")
    print(f"  Devices snapshotted        : {dev_total}  (DB unchanged)")
    print(f"  Geofence segments snap.    : {seg_total}  (DB unchanged)")
    if dry_run:
        print(f"  NOTE: --dry-run mode — no rows were deleted from the DB.")
    print("=" * 60)


if __name__ == "__main__":
    missing = []
    for pkg in ("pymysql", "pandas", "pyarrow", "dateutil"):
        try:
            __import__(pkg)
        except ImportError:
            missing.append(pkg.replace("dateutil", "python-dateutil"))
    if missing:
        print(f"[ERROR] Missing Python packages: {', '.join(missing)}")
        print(f"        Run: pip install pymysql pandas pyarrow python-dateutil")
        sys.exit(1)
    main()