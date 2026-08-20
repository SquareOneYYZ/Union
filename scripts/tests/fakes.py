"""Shared offline fakes for archive_cold_storage tests: a SQL-recording
connection and a driver that pushes one device-month group through
archive_table."""

from datetime import date

import archive_cold_storage as acs


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn
        self.rowcount = 0
        self._sql = ""
        self._params = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self.conn.executed.append((sql, params))
        self._sql, self._params = sql, params
        self.rowcount = self.conn.rowcount_for(sql, params)

    def fetchall(self):
        return self.conn.fetchall_for(self._sql, self._params)

    def fetchmany(self, n):
        if self.conn.rows_served:
            return []
        self.conn.rows_served = True
        return self.conn.rows


class FakeConn:
    def __init__(self, groups=None, rows=None, rowcount_fn=None, devices=None):
        self.groups = groups or []
        self.rows = rows or []
        # Device-iterated discovery enumerates tc_devices; default to every
        # device that has a group, so existing fixtures keep working.
        self.devices = (devices if devices is not None
                        else sorted({g["deviceid"] for g in self.groups
                                     if "deviceid" in g}))
        self.executed = []
        self.commits = 0
        self.rows_served = False
        self._rowcount_fn = rowcount_fn or (
            lambda sql, params: len(params) if params else 0)

    def rowcount_for(self, sql, params):
        return self._rowcount_fn(sql, params)

    def fetchall_for(self, sql, params):
        if "positionid" in sql:
            return self.groups          # fetch_protected_position_ids tests
        if "FROM tc_devices" in sql:
            return [{"id": d} for d in self.devices]
        if "GROUP BY" in sql:
            dev = params[0]
            rows = [{"yr": g["yr"], "mo": g["mo"], "cnt": g["cnt"]}
                    for g in self.groups if g["deviceid"] == dev]
            return sorted(rows, key=lambda r: (r["yr"], r["mo"]))  # ORDER BY yr, mo
        return self.groups

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
              cutoff=date(2026, 2, 1), budget=None, quarantine_floor=None):
    return acs.archive_table(
        conn, acs.PropsConfig({}), "tc_positions", "fixtime",
        ["id", "deviceid", "fixtime"], "positions",
        cutoff=cutoff, temp_dir=str(tmp_path), dry_run=False,
        datetime_cols=["fixtime"], key_prefix="rehearsal",
        deleter=deleter, id_exclusions=id_exclusions, budget=budget,
        quarantine_floor=quarantine_floor,
    )
