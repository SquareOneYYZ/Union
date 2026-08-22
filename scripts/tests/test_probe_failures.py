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


def test_marker_upload_failure_is_warned_not_silent_success(tmp_path, s3,
                                                            caplog):
    # do_upload returns False on failure, it does not raise -- the old code
    # logged "Done marker uploaded" regardless. Warn-only stays (a missing
    # marker is harmless under the merge semantics), silent success does not.
    s3["fail_upload"].add(MARKER)
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=lambda c, t, i: len(i))

    assert (total, failures) == (2, 0)          # warn-only: not a failure
    assert MARKER not in s3["uploads"]
    assert "Done marker upload FAILED" in caplog.text
    assert "Done marker uploaded" not in caplog.text


def test_dry_run_checks_its_own_tmp_delete(tmp_path, s3):
    # Not a regression: the delete_spaces_key contract (return checked) was
    # established by the probe fix and this call site was missed by it.
    s3["fail_delete"].add(TMP)
    conn = make_conn()
    total, failures = run_table(conn, tmp_path, deleter=None, dry_run=True)
    assert failures == 1                      # leftover tmp is not silent
    assert s3["copies"] == []                 # dry-run never finalizes
    assert MARKER not in s3["uploads"]


def test_every_delete_spaces_key_call_site_checks_the_return():
    # The contract, enforced structurally: no call site may ignore the
    # return — including ones added later.
    import inspect
    import re
    import archive_cold_storage as acs
    src = inspect.getsource(acs)
    for m in re.finditer(r"^\s*(.+delete_spaces_key\()", src, re.M):
        stmt = m.group(1).strip()
        if stmt.startswith("def delete_spaces_key("):
            continue
        assert (stmt.startswith("if not delete_spaces_key(")
                or re.match(r"^\w+\s*=\s*delete_spaces_key\(", stmt)), (
            f"unchecked delete_spaces_key call: {stmt!r}")


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
