"""Fail-closed probes at every archive_table call site (second-review item 1).

The old code conflated "s3cmd failed" with "key absent" at three existence
probes: the marker check, the leftover-tmp check (a C5 bypass), and the
final-exists check (a C6 bypass enabling a blind overwrite). A failed probe
now fails the group — counted, nothing uploaded or deleted for it, other
groups continue — never reads as absence.
"""

from fakes import make_conn, run_table

TMP = "rehearsal/positions/7/2025-03.parquet.tmp"
FINAL = "rehearsal/positions/7/2025-03.parquet"
MARKER = "rehearsal/positions/7/2025-03.done"


def deleter_recording(calls):
    def deleter(conn, table, ids):
        calls.append(list(ids))
        return len(ids)
    return deleter


def assert_group_failed_clean(s3, conn, total, failures, deleted):
    assert (total, failures) == (0, 1)
    assert deleted == []                                  # no DB delete
    assert not any("DELETE" in sql.upper() for sql, _ in conn.executed)
    assert s3["uploads"] == []                            # nothing uploaded
    assert s3["copies"] == []                             # nothing finalized
    assert s3["spaces_deletes"] == []                     # nothing removed


def test_marker_probe_error_fails_group(tmp_path, s3, caplog):
    s3["probe_errors"].add(MARKER)
    deleted = []
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording(deleted))
    assert_group_failed_clean(s3, conn, total, failures, deleted)
    assert "Marker probe FAILED" in caplog.text


def test_tmp_probe_error_fails_group_not_read_as_no_tmp(tmp_path, s3, caplog):
    # The C5 bypass: an errored listing must not mean "no leftover tmp".
    s3["probe_errors"].add(TMP)
    deleted = []
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording(deleted))
    assert_group_failed_clean(s3, conn, total, failures, deleted)
    assert "Leftover-tmp probe FAILED" in caplog.text


def test_final_probe_error_fails_group_before_upload(tmp_path, s3, caplog):
    # The C6 bypass: an errored existence probe must not mean "no final
    # exists" — that path blind-overwrites an unknown final.
    s3["probe_errors"].add(FINAL)
    deleted = []
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording(deleted))
    assert_group_failed_clean(s3, conn, total, failures, deleted)
    assert "Final-key probe FAILED" in caplog.text


def test_tmp_delete_failure_after_finalize_fails_group_before_db_delete(
        tmp_path, s3):
    s3["fail_delete"].add(TMP)
    deleted = []
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording(deleted))
    assert (total, failures) == (0, 1)
    assert deleted == []                       # no rows deleted
    # Finalize copied and verified the final before the tmp-delete failed:
    # the safe state is final-in-place, tmp preserved, DB untouched.
    assert s3["copies"] == [(TMP, FINAL)]
    assert MARKER not in s3["uploads"]
