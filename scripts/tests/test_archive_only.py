"""--archive-only (C2): structurally incapable of deleting.

The delete capability is injected into archive_table as `deleter`; an
archive-only run passes None, so the deleting branch holds no reference to
any delete code. These tests drive a full group through archive_table with a
SQL-recording fake connection and fake s3 helpers, and assert that in
archive-only mode: batch_delete is never reached (it is monkeypatched to
explode), no DELETE SQL ever hits the connection, no .done marker is
uploaded, and the parquet still finalizes tmp -> final.
"""

from datetime import date

import pytest

import archive_cold_storage as acs


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self.conn.executed.append(sql)

    def fetchall(self):
        return self.conn.groups

    def fetchmany(self, n):
        if self.conn.rows_served:
            return []
        self.conn.rows_served = True
        return self.conn.rows


class FakeConn:
    def __init__(self, groups, rows):
        self.groups = groups
        self.rows = rows
        self.executed = []
        self.rows_served = False

    def cursor(self):
        return FakeCursor(self)

    def commit(self):
        pass


@pytest.fixture
def s3(monkeypatch):
    """Fake the s3 helpers; record every key that gets uploaded/copied/deleted."""
    calls = {"uploads": [], "copies": [], "spaces_deletes": []}

    monkeypatch.setattr(acs, "do_upload",
                        lambda cfg, path, key: calls["uploads"].append(key) or True)
    # Marker check must miss, temp-key check must miss, temp verify must hit.
    monkeypatch.setattr(acs, "verify_upload",
                        lambda cfg, key: not key.endswith(".done"))
    monkeypatch.setattr(acs, "check_temp_key_exists", lambda cfg, key: False)
    monkeypatch.setattr(acs, "verify_row_count", lambda cfg, key, n: True)
    monkeypatch.setattr(acs, "copy_spaces_key",
                        lambda cfg, src, dst: calls["copies"].append((src, dst)) or True)
    monkeypatch.setattr(acs, "delete_spaces_key",
                        lambda cfg, key: calls["spaces_deletes"].append(key))
    return calls


def make_conn():
    return FakeConn(
        groups=[{"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2}],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2025-03-01 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2025-03-02 00:00:00"},
        ],
    )


def run_table(conn, tmp_path, deleter):
    return acs.archive_table(
        conn, acs.PropsConfig({}), "tc_positions", "fixtime",
        ["id", "deviceid", "fixtime"], "positions",
        cutoff=date(2026, 2, 1), temp_dir=str(tmp_path), dry_run=False,
        datetime_cols=["fixtime"], key_prefix="rehearsal", deleter=deleter,
    )


def test_archive_only_cannot_reach_any_delete(tmp_path, monkeypatch, s3):
    # If any code path still referenced batch_delete, this makes it explode.
    monkeypatch.setattr(acs, "batch_delete",
                        lambda *a, **k: (_ for _ in ()).throw(
                            AssertionError("delete path reached in archive-only mode")))
    conn = make_conn()

    total, failures = run_table(conn, tmp_path, deleter=None)

    assert (total, failures) == (2, 0)
    # No DELETE ever hit the connection — only discovery + export SELECTs.
    assert conn.executed, "expected SQL to have been issued"
    assert not any("DELETE" in sql.upper() for sql in conn.executed)
    # No marker uploaded; only the tmp upload happened.
    assert s3["uploads"] == ["rehearsal/positions/7/2025-03.parquet.tmp"]
    # Finalize still ran: tmp copied to final, tmp removed.
    assert s3["copies"] == [("rehearsal/positions/7/2025-03.parquet.tmp",
                             "rehearsal/positions/7/2025-03.parquet")]
    assert s3["spaces_deletes"] == ["rehearsal/positions/7/2025-03.parquet.tmp"]


def test_destructive_mode_still_deletes_and_marks(tmp_path, s3):
    deleted_calls = []

    def fake_deleter(conn, table, time_col, device_id, period_start, period_end):
        deleted_calls.append((table, device_id, period_start, period_end))
        return 2

    conn = make_conn()
    total, failures = run_table(conn, tmp_path, deleter=fake_deleter)

    assert (total, failures) == (2, 0)
    assert deleted_calls == [("tc_positions", 7, date(2025, 3, 1), date(2025, 4, 1))]
    assert "rehearsal/positions/7/2025-03.done" in s3["uploads"]
