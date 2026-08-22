"""Malformed (zero/garbage-date) groups fail themselves, not the run.

Prod's permissive sql_mode permits zero dates, so discovery can emit groups
like year=0 month=0 (or NULL-derived values). date() rejecting them must
cost exactly one group failure — counted, logged with the device and month
named, run continues, exit non-zero at the end via the failure count — never
a whole-run crash partway through an 11,345-group migration.
"""

import pytest

from fakes import FakeConn, run_table


def test_zero_date_group_fails_itself_and_the_run_continues(tmp_path, s3,
                                                            caplog):
    conn = FakeConn(
        groups=[
            {"deviceid": 7, "yr": 0, "mo": 0, "cnt": 3},      # zero-date garbage
            {"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2},   # healthy
        ],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2025-03-01 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2025-03-02 00:00:00"},
        ],
    )

    total, failures = run_table(conn, tmp_path,
                                deleter=lambda c, t, i: len(i))

    # The healthy group archived end-to-end; the malformed one is a counted
    # failure (=> non-zero exit at the end of the run), not a crash.
    assert (total, failures) == (2, 1)
    assert "rehearsal/positions/7/2025-03.done" in s3["uploads"]
    assert "MALFORMED GROUP device=7 year=0 month=0" in caplog.text


@pytest.mark.parametrize("yr,mo", [
    (0, 0),          # zero date
    (2024, 13),      # impossible month
    (2024, 0),       # zero month, valid year
    (None, None),    # NULL-derived
])
def test_each_malformed_shape_is_one_group_failure(tmp_path, s3, yr, mo,
                                                   caplog):
    conn = FakeConn(groups=[{"deviceid": 9, "yr": yr, "mo": mo, "cnt": 1}])

    total, failures = run_table(conn, tmp_path,
                                deleter=lambda c, t, i: len(i))

    assert (total, failures) == (0, 1)
    assert s3["uploads"] == []            # nothing attempted for the group
    assert "MALFORMED GROUP device=9" in caplog.text
