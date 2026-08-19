"""Shared offline fakes for archive_cold_storage tests: a SQL-recording
connection and a driver that pushes one device-month group through
archive_table."""

from datetime import date

import archive_cold_storage as acs


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn
        self.rowcount = 0

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self.conn.executed.append((sql, params))
        self.rowcount = self.conn.rowcount_for(sql, params)

    def fetchall(self):
        return self.conn.groups

    def fetchmany(self, n):
        if self.conn.rows_served:
            return []
        self.conn.rows_served = True
        return self.conn.rows


class FakeConn:
    def __init__(self, groups=None, rows=None, rowcount_fn=None):
        self.groups = groups or []
        self.rows = rows or []
        self.executed = []
        self.commits = 0
        self.rows_served = False
        self._rowcount_fn = rowcount_fn or (
            lambda sql, params: len(params) if params else 0)

    def rowcount_for(self, sql, params):
        return self._rowcount_fn(sql, params)

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        self.commits += 1


def make_conn():
    """One group (device 7, 2025-03) with two exported rows."""
    return FakeConn(
        groups=[{"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2}],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2025-03-01 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2025-03-02 00:00:00"},
        ],
    )


def run_table(conn, tmp_path, deleter, id_exclusions=None,
              cutoff=date(2026, 2, 1)):
    return acs.archive_table(
        conn, acs.PropsConfig({}), "tc_positions", "fixtime",
        ["id", "deviceid", "fixtime"], "positions",
        cutoff=cutoff, temp_dir=str(tmp_path), dry_run=False,
        datetime_cols=["fixtime"], key_prefix="rehearsal",
        deleter=deleter, id_exclusions=id_exclusions,
    )
