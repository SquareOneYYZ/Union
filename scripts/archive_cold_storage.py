#!/usr/bin/env python3
"""
archive_cold_storage.py
=======================
Archives old tc_positions and tc_events rows from MySQL to DigitalOcean Spaces
in Parquet format using s3cmd.

Each table is archived INDEPENDENTLY (no joins).
Data is grouped by deviceid × year × month — one Parquet file per group.

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
    s3cmd --configure   # enter DO Spaces access key, secret key,
                        # endpoint = nyc3.digitaloceanspaces.com (adjust region)
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
    """Return base s3cmd command list, optionally with --config flag."""
    cmd = ["s3cmd"]
    if s3cmd_config and s3cmd_config.strip() and s3cmd_config.strip() != "~/.s3cfg":
        expanded = os.path.expanduser(s3cmd_config.strip())
        cmd += [f"--config={expanded}"]
    return cmd


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
# Archive positions
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
        # Find distinct (deviceid, year, month) groups to archive
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
        # First day of next month
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
            # --- Read rows ---
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

            # --- Write Parquet ---
            df = pd.DataFrame(rows)
            # Convert datetime objects to ISO strings for safe Parquet serialization
            for col in ["servertime", "devicetime", "fixtime"]:
                if col in df.columns:
                    df[col] = df[col].astype(str)
            write_parquet(df, local_path)
            print(f"  [positions] Parquet written: {local_path} ({len(df)} rows)")

            # --- Upload to Spaces (or local for testing) ---
            if local_upload_dir:
                success = local_upload(local_path, local_upload_dir, spaces_key)
            else:
                if not bucket:
                    print(f"  [positions] ERROR: bucket not configured and local_upload_dir is empty.")
                    continue
                success = s3cmd_upload(s3cmd_config, local_path, bucket, spaces_key)

            if not success:
                print(f"  [positions] Storage failed — skipping deletion for safety.")
                continue

            # --- Delete from DB (only if not dry-run and upload succeeded) ---
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
            # Safe: skip deletion; don't propagate — continue with next group

        finally:
            # Clean up local temp file
            if os.path.exists(local_path):
                os.remove(local_path)

    return total


# ---------------------------------------------------------------------------
# Archive events
# ---------------------------------------------------------------------------

EVENTS_COLUMNS = [
    "id", "deviceid", "type", "eventtime",
    "positionid", "geofenceid", "maintenanceid", "attributes",
]


def archive_events(conn, cfg, cutoff: date, temp_dir: str, dry_run: bool) -> int:
    """Archive tc_events older than cutoff. Returns total rows archived."""
    bucket = cfg.get("spaces", "bucket", fallback="")
    s3cmd_config = cfg.get("spaces", "s3cmd_config", fallback="")
    local_upload_dir = cfg.get("spaces", "local_upload_dir", fallback="")
    total = 0

    with conn.cursor() as cur:
        # Find distinct (deviceid, year, month) groups to archive
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
            # --- Read rows ---
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

            # --- Write Parquet ---
            df = pd.DataFrame(rows)
            for col in ["eventtime"]:
                if col in df.columns:
                    df[col] = df[col].astype(str)
            write_parquet(df, local_path)
            print(f"  [events] Parquet written: {local_path} ({len(df)} rows)")

            # --- Upload to Spaces (or local for testing) ---
            if local_upload_dir:
                success = local_upload(local_path, local_upload_dir, spaces_key)
            else:
                if not bucket:
                    print(f"  [events] ERROR: bucket not configured and local_upload_dir is empty.")
                    continue
                success = s3cmd_upload(s3cmd_config, local_path, bucket, spaces_key)

            if not success:
                print(f"  [events] Storage failed — skipping deletion for safety.")
                continue

            # --- Delete from DB ---
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
        print("\n--- Archiving POSITIONS ---")
        pos_total = archive_positions(conn, cfg, cutoff, temp_dir, dry_run)

        print("\n--- Archiving EVENTS ---")
        evt_total = archive_events(conn, cfg, cutoff, temp_dir, dry_run)
    finally:
        conn.close()

    print("\n" + "=" * 60)
    print(f"  Archive complete.")
    print(f"  Positions archived : {pos_total}")
    print(f"  Events archived    : {evt_total}")
    if dry_run:
        print(f"  NOTE: --dry-run mode — no rows were deleted from the DB.")
    print("=" * 60)


if __name__ == "__main__":
    # Check required packages
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
