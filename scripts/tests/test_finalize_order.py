"""Finalize before delete (C4 / D3).

New ordering per group: upload tmp -> verify tmp -> row-count check ->
copy tmp->final -> verify final -> delete tmp -> DB delete by ids -> marker.
No DB delete may ever be issued before a successful finalize; a finalize
failure fails the group with the DB untouched and the tmp preserved.
"""

from fakes import make_conn, run_table

TMP = "rehearsal/positions/7/2025-03.parquet.tmp"
FINAL = "rehearsal/positions/7/2025-03.parquet"
MARKER = "rehearsal/positions/7/2025-03.done"


def deleter_recording_into(events):
    def deleter(conn, table, ids):
        events.append(("db_delete", table, tuple(ids)))
        return len(ids)
    return deleter


def test_happy_path_ordering(tmp_path, s3):
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording_into(s3["events"]))
    assert (total, failures) == (2, 0)

    ev = s3["events"]

    def idx(item, last=False):
        matches = [i for i, e in enumerate(ev) if e[:len(item)] == item]
        assert matches, f"event {item} not found in {ev}"
        return matches[-1] if last else matches[0]

    upload_tmp = idx(("upload", TMP))
    verify_tmp = idx(("verify", TMP))
    rowcount = idx(("rowcount", TMP))
    copy = idx(("copy", TMP, FINAL))
    # last=True: the first ("verify", FINAL) is C6's existence probe before
    # the upload; the one that matters for ordering is the post-copy verify.
    verify_final = idx(("verify", FINAL), last=True)
    del_tmp = idx(("delete", TMP))
    db_delete = idx(("db_delete",))
    marker = idx(("upload", MARKER))

    assert upload_tmp < verify_tmp < rowcount < copy < verify_final < del_tmp
    # The heart of D3: no DB delete before a fully successful finalize.
    assert db_delete > del_tmp
    # Marker last: it means "archived AND deleted".
    assert marker > db_delete


def test_no_db_delete_when_finalize_copy_fails(tmp_path, s3):
    s3["fail_copy"] = True
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording_into(s3["events"]))

    assert failures == 1 and total == 0
    assert not any(e[0] == "db_delete" for e in s3["events"])
    assert not any("DELETE" in sql.upper() for sql, _ in conn.executed)
    # Tmp preserved: never deleted from the bucket.
    assert TMP not in s3["spaces_deletes"]
    assert MARKER not in s3["uploads"]


def test_no_db_delete_when_final_verify_fails(tmp_path, s3):
    s3["fail_verify"].add(FINAL)
    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=deleter_recording_into(s3["events"]))

    assert failures == 1 and total == 0
    assert not any(e[0] == "db_delete" for e in s3["events"])
    assert TMP not in s3["spaces_deletes"]      # tmp preserved
    assert MARKER not in s3["uploads"]
