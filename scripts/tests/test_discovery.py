"""Device-iterated group discovery (performance-defect fix, Option A).

The old discovery filtered on the time column alone — a full scan of a
~730M-row table at the top of every run, since the only time-bearing index is
led by deviceid. Discovery now enumerates devices from tc_devices and asks an
index-backed per-device GROUP BY for each. The orphan-positions gap
(deviceid absent from tc_devices) is documented behavior handled by a one-off
sweep in the runbook, and each run ends with a per-device group-count summary
so a disappearing device is visible.
"""

import logging
from datetime import date

import archive_cold_storage as acs
from fakes import FakeConn, make_conn, run_table

CUTOFF = date(2026, 2, 1)


def test_devices_enumerated_from_tc_devices_then_per_device_queries():
    conn = FakeConn(
        groups=[{"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2}],
        devices=[7, 8],  # device 8 exists but has no archivable groups
    )

    groups = acs.discover_groups(conn, "tc_positions", "fixtime", CUTOFF)

    sql0, params0 = conn.executed[0]
    assert sql0 == "SELECT id FROM tc_devices ORDER BY id"
    assert params0 is None
    # One per-device query per enumerated device, index-matching shape.
    assert len(conn.executed) == 3
    sql1, params1 = conn.executed[1]
    assert "WHERE deviceid = %s AND fixtime < %s" in sql1
    assert "GROUP BY YEAR(fixtime), MONTH(fixtime)" in sql1
    assert params1 == (7, CUTOFF)
    assert groups == [{"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2}]


def test_no_time_only_query_remains(tmp_path, s3):
    conn = make_conn()
    run_table(conn, tmp_path, deleter=lambda c, t, i: len(i))

    # Every query that filters the table by time also pins a device — the
    # index-unusable time-only shape must be gone.
    for sql, _params in conn.executed:
        if "FROM tc_positions" in sql and "fixtime <" in sql:
            assert "deviceid = %s" in sql


def test_ordering_is_device_major_then_month():
    conn = FakeConn(
        groups=[
            {"deviceid": 8, "yr": 2025, "mo": 1, "cnt": 1},
            {"deviceid": 7, "yr": 2025, "mo": 4, "cnt": 1},
            {"deviceid": 7, "yr": 2025, "mo": 2, "cnt": 1},
        ],
        devices=[7, 8],
    )
    groups = acs.discover_groups(conn, "tc_positions", "fixtime", CUTOFF)
    assert [(g["deviceid"], g["yr"], g["mo"]) for g in groups] == [
        (7, 2025, 2), (7, 2025, 4), (8, 2025, 1)]


def test_orphan_devices_are_the_documented_gap():
    # Device 99 has groups in the table but is gone from tc_devices: the
    # per-run loop does not see it — by decision; the one-off runbook sweep
    # is the mechanism that finds it.
    conn = FakeConn(
        groups=[
            {"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2},
            {"deviceid": 99, "yr": 2025, "mo": 3, "cnt": 5},
        ],
        devices=[7],
    )
    groups = acs.discover_groups(conn, "tc_positions", "fixtime", CUTOFF)
    assert all(g["deviceid"] == 7 for g in groups)


def test_discovery_and_end_of_run_summaries_logged(tmp_path, s3, caplog):
    caplog.set_level(logging.INFO)
    conn = make_conn()
    run_table(conn, tmp_path, deleter=lambda c, t, i: len(i))

    assert "Discovery: 1 device(s) enumerated from tc_devices" in caplog.text
    assert "End-of-run device summary" in caplog.text
    assert "7:1" in caplog.text  # device 7 had 1 group this run
