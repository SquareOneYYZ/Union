"""Cutoff/month-boundary skip (C7).

A group qualifies for discovery via any row older than the cutoff, but export
and delete take the full calendar month — so a mid-month manual run (cutoff
2026-02-18) would otherwise archive-and-delete the Feb 18–28 rows, which are
younger than the retention period. Any group whose month window extends past
the cutoff's month start is skipped; only whole months strictly before the
cutoff month are processed.
"""

from datetime import date

from fakes import FakeConn, run_table


def deleter(conn, table, ids):
    return len(ids)


def test_mid_month_cutoff_skips_cutoff_month_processes_older(tmp_path, s3):
    # Cutoff 2026-02-18: January (window ends 2026-02-01) is processed;
    # February (window ends 2026-03-01, past the cutoff month start) is not.
    conn = FakeConn(
        groups=[
            {"deviceid": 7, "yr": 2026, "mo": 1, "cnt": 2},
            {"deviceid": 7, "yr": 2026, "mo": 2, "cnt": 5},
        ],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2026-01-10 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2026-01-11 00:00:00"},
        ],
    )

    total, failures = run_table(conn, tmp_path, deleter=deleter,
                                cutoff=date(2026, 2, 18))

    # January archived fully; February skipped without counting as a failure.
    assert (total, failures) == (2, 0)
    assert "rehearsal/positions/7/2026-01.done" in s3["uploads"]
    assert not any("2026-02" in k for k in s3["uploads"])
    assert not any("2026-02" in dst for _src, dst in s3["copies"])


def test_skip_happens_before_any_bucket_or_export_work(tmp_path, s3):
    conn = FakeConn(
        groups=[{"deviceid": 7, "yr": 2026, "mo": 2, "cnt": 5}],
        rows=[{"id": 1, "deviceid": 7, "fixtime": "2026-02-01 00:00:00"}],
    )

    total, failures = run_table(conn, tmp_path, deleter=deleter,
                                cutoff=date(2026, 2, 18))

    assert (total, failures) == (0, 0)      # a skip is not a failure
    # Discovery only (device enumeration + per-device query), no export SELECT.
    assert len(conn.executed) == 2
    assert not any("ORDER BY fixtime" in sql for sql, _ in conn.executed)
    assert s3["uploads"] == []
    assert s3["events"] == []               # not even a marker/tmp s3 probe


def test_day_one_cutoff_processes_previous_month(tmp_path, s3):
    # Cron fires on day 1: cutoff 2026-03-01 -> month start 2026-03-01;
    # February's window ends exactly there and IS processed (boundary is
    # exclusive: period_end > month start skips, equality does not).
    conn = FakeConn(
        groups=[{"deviceid": 7, "yr": 2026, "mo": 2, "cnt": 2}],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2026-02-10 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2026-02-11 00:00:00"},
        ],
    )

    total, failures = run_table(conn, tmp_path, deleter=deleter,
                                cutoff=date(2026, 3, 1))

    assert (total, failures) == (2, 0)
    assert "rehearsal/positions/7/2026-02.done" in s3["uploads"]
