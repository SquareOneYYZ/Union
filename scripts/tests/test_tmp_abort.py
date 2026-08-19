"""Leftover tmp keys abort their group (C5 / D3).

A leftover .parquet.tmp is evidence, not garbage: the old code auto-deleted
it ("cleaning up and restarting this group"), which was the audit's #1-ranked
data-loss path — under old semantics the tmp can be the only copy of
already-deleted rows. Now the group aborts loudly, the object is left exactly
as found, other groups continue, and the run exits non-zero via the failure
count.
"""

from fakes import FakeConn, make_conn, run_table

TMP_7 = "rehearsal/positions/7/2025-03.parquet.tmp"


def test_leftover_tmp_aborts_group_and_is_never_deleted(tmp_path, s3, caplog):
    s3["tmp_exists"].add(TMP_7)
    conn = make_conn()

    total, failures = run_table(conn, tmp_path, deleter=None)

    assert (total, failures) == (0, 1)
    # The tmp was not touched, nothing was uploaded, nothing was finalized.
    assert s3["spaces_deletes"] == []
    assert s3["uploads"] == []
    assert s3["copies"] == []
    # The group aborted before the export SELECT: only discovery SQL ran.
    assert len(conn.executed) == 1
    # The 4am reader gets both readings and the pointer to the procedure.
    log = caplog.text
    assert "ABORTING GROUP" in log
    assert "redundant residue" in log            # new-semantics reading
    assert "ONLY copy" in log                    # old-semantics reading
    assert "Leftover tmp keys" in log            # runbook section pointer


def test_one_aborted_group_does_not_stop_the_others(tmp_path, s3):
    # Device 7 has a stale tmp; device 8 must still archive end-to-end.
    s3["tmp_exists"].add(TMP_7)
    conn = FakeConn(
        groups=[
            {"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2},
            {"deviceid": 8, "yr": 2025, "mo": 4, "cnt": 2},
        ],
        rows=[
            {"id": 3, "deviceid": 8, "fixtime": "2025-04-01 00:00:00"},
            {"id": 4, "deviceid": 8, "fixtime": "2025-04-02 00:00:00"},
        ],
    )

    def deleter(c, table, ids):
        return len(ids)

    total, failures = run_table(conn, tmp_path, deleter=deleter)

    # Group 7 aborted (1 failure), group 8 completed fully (2 rows, marker).
    assert (total, failures) == (2, 1)
    assert "rehearsal/positions/8/2025-04.done" in s3["uploads"]
    assert ("rehearsal/positions/8/2025-04.parquet.tmp",
            "rehearsal/positions/8/2025-04.parquet") in s3["copies"]
    # And device 7's tmp still untouched.
    assert TMP_7 not in s3["spaces_deletes"]
