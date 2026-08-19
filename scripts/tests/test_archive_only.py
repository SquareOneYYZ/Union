"""--archive-only (C2): structurally incapable of deleting.

The delete capability is injected into archive_table as `deleter`; an
archive-only run passes None, so the deleting branch holds no reference to
any delete code. These tests drive a full group through archive_table with a
SQL-recording fake connection and fake s3 helpers, and assert that in
archive-only mode: the delete function is never reached (it is monkeypatched
to explode), no DELETE SQL ever hits the connection, no .done marker is
uploaded, and the parquet still finalizes tmp -> final.
"""

from datetime import date

import archive_cold_storage as acs
from fakes import make_conn, run_table


def test_archive_only_cannot_reach_any_delete(tmp_path, monkeypatch, s3):
    # If any code path still referenced the delete function, this explodes.
    monkeypatch.setattr(acs, "batch_delete_by_ids",
                        lambda *a, **k: (_ for _ in ()).throw(
                            AssertionError("delete path reached in archive-only mode")))
    conn = make_conn()

    total, failures = run_table(conn, tmp_path, deleter=None)

    assert (total, failures) == (2, 0)
    # No DELETE ever hit the connection — only discovery + export SELECTs.
    assert conn.executed, "expected SQL to have been issued"
    assert not any("DELETE" in sql.upper() for sql, _ in conn.executed)
    # No marker uploaded; only the tmp upload happened.
    assert s3["uploads"] == ["rehearsal/positions/7/2025-03.parquet.tmp"]
    # Finalize still ran: tmp copied to final, tmp removed.
    assert s3["copies"] == [("rehearsal/positions/7/2025-03.parquet.tmp",
                             "rehearsal/positions/7/2025-03.parquet")]
    assert s3["spaces_deletes"] == ["rehearsal/positions/7/2025-03.parquet.tmp"]


def test_destructive_mode_still_deletes_and_marks(tmp_path, s3):
    deleted_calls = []

    def fake_deleter(conn, table, ids):
        deleted_calls.append((table, list(ids)))
        return len(ids)

    conn = make_conn()
    total, failures = run_table(conn, tmp_path, deleter=fake_deleter)

    assert (total, failures) == (2, 0)
    assert deleted_calls == [("tc_positions", [1, 2])]
    assert "rehearsal/positions/7/2025-03.done" in s3["uploads"]
