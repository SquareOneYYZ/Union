"""Snapshot failure accounting (read-pass finding: same fail-silent class as
the marker bug — all four snapshots could fail while the run exited 0 logging
"Archive complete"). snapshot_table now returns (rows, failure_count) and
main adds the failures to the run's exit-code accounting.
"""

import archive_cold_storage as acs
from fakes import FakeConn


ROWS = [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}]


def snap(conn, tmp_path):
    return acs.snapshot_table(conn, acs.PropsConfig({}), "tc_geofences",
                              ["id", "name"], "geofences", str(tmp_path),
                              key_prefix="rehearsal")


def test_happy_snapshot_counts_no_failure(tmp_path, s3):
    rows, failures = snap(FakeConn(rows=ROWS), tmp_path)
    assert (rows, failures) == (2, 0)
    assert sum(1 for k in s3["uploads"]
               if k.startswith("rehearsal/geofences/")) == 2  # ts + latest


def test_upload_failure_counts_as_run_failure(tmp_path, s3):
    s3["fail_upload"].add("rehearsal/geofences/latest.parquet")
    rows, failures = snap(FakeConn(rows=ROWS), tmp_path)
    assert failures == 1


def test_exception_counts_as_run_failure(tmp_path, s3, monkeypatch):
    def explode(conn, query, params, chunk_size=50000):
        raise RuntimeError("db went away")
    monkeypatch.setattr(acs, "fetch_chunked", explode)
    rows, failures = snap(FakeConn(rows=ROWS), tmp_path)
    assert (rows, failures) == (0, 1)


def test_empty_table_is_not_a_failure(tmp_path, s3):
    rows, failures = snap(FakeConn(rows=[]), tmp_path)
    assert (rows, failures) == (0, 0)
