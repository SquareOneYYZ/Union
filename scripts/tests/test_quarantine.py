"""Clock-garbage quarantine (decision: option c).

Groups whose month ends on or before archive.quarantine.floor are archived
under '<prefix>-quarantine/' — a sibling key space the Java read path never
serves — and deleted from the live table like any other group. The floor is
config, never a literal; quarantined groups are counted separately so they
never silently blend into the normal totals.
"""

import logging
from datetime import date

import pytest

import archive_cold_storage as acs
from fakes import FakeConn, run_table

FLOOR = date(2024, 1, 1)


def garbage_conn(yr=2000, mo=1):
    return FakeConn(
        groups=[{"deviceid": 7, "yr": yr, "mo": mo, "cnt": 2}],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": f"{yr}-{mo:02d}-01 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": f"{yr}-{mo:02d}-02 00:00:00"},
        ],
    )


def test_garbage_group_goes_to_quarantine_prefix_and_is_deleted(
        tmp_path, s3, caplog):
    caplog.set_level(logging.INFO)
    deleted = []

    def deleter(conn, table, ids):
        deleted.append(list(ids))
        return len(ids)

    total, failures = run_table(garbage_conn(), tmp_path, deleter=deleter,
                                quarantine_floor=FLOOR)

    assert (total, failures) == (2, 0)
    # Every key — tmp, final, marker — lives under the quarantine sibling.
    assert s3["uploads"] == [
        "rehearsal-quarantine/positions/7/2000-01.parquet.tmp",
        "rehearsal-quarantine/positions/7/2000-01.done",
    ]
    assert s3["copies"] == [(
        "rehearsal-quarantine/positions/7/2000-01.parquet.tmp",
        "rehearsal-quarantine/positions/7/2000-01.parquet")]
    # Deleted from the live table like any other group.
    assert deleted == [[1, 2]]
    assert "QUARANTINE: device=7 2000-01" in caplog.text
    assert "Quarantined groups this run: 1 (2 rows)" in caplog.text


def test_normal_group_unaffected_by_floor(tmp_path, s3, caplog):
    caplog.set_level(logging.INFO)
    total, failures = run_table(garbage_conn(yr=2025, mo=3), tmp_path,
                                deleter=lambda c, t, i: len(i),
                                quarantine_floor=FLOOR)

    assert (total, failures) == (2, 0)
    assert all(k.startswith("rehearsal/") for k in s3["uploads"])
    assert "Quarantined groups this run: 0 (0 rows)" in caplog.text


@pytest.mark.parametrize("yr,mo,quarantined", [
    (2023, 12, True),    # month ends 2024-01-01 == floor -> quarantined
    (2024, 1, False),    # month ends 2024-02-01 > floor  -> normal
])
def test_floor_boundary(tmp_path, s3, yr, mo, quarantined):
    run_table(garbage_conn(yr=yr, mo=mo), tmp_path,
              deleter=lambda c, t, i: len(i), quarantine_floor=FLOOR)
    prefix = "rehearsal-quarantine/" if quarantined else "rehearsal/"
    assert all(k.startswith(prefix) for k in s3["uploads"])


def test_no_floor_means_no_quarantine(tmp_path, s3, caplog):
    caplog.set_level(logging.INFO)
    run_table(garbage_conn(), tmp_path, deleter=lambda c, t, i: len(i),
              quarantine_floor=None)
    assert all(k.startswith("rehearsal/") for k in s3["uploads"])
    assert "Quarantined groups" not in caplog.text


class TestParseQuarantineFloor:
    def test_valid(self):
        assert acs.parse_quarantine_floor(
            {"archive.quarantine.floor": "2024-01-01"}) == date(2024, 1, 1)

    def test_unset_means_disabled(self):
        assert acs.parse_quarantine_floor({}) is None
        assert acs.parse_quarantine_floor(
            {"archive.quarantine.floor": "  "}) is None

    def test_malformed_is_fatal(self):
        with pytest.raises(SystemExit) as exc:
            acs.parse_quarantine_floor(
                {"archive.quarantine.floor": "January 2024"})
        assert exc.value.code == 1
