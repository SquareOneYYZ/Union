"""Delete by exported ids (C3 / D2 / D9).

The DELETE is keyed by the exported id list in 10k chunks — never by a time
window — so a row the export never saw can never be deleted. A device's
latest position (tc_devices.positionid) is filtered out in Python before the
deleter is called, keeping the SQL a plain id IN (...). A deleted-vs-expected
count mismatch fails the group loudly, leaves the archive intact, and
attempts no repair.
"""

import archive_cold_storage as acs
from fakes import FakeConn, make_conn, run_table


class TestBatchDeleteByIds:
    def test_chunking_and_exact_sql(self):
        conn = FakeConn()
        ids = list(range(1, 25001))

        total = acs.batch_delete_by_ids(conn, "tc_positions", ids)

        assert total == 25000
        assert len(conn.executed) == 3
        sql0, params0 = conn.executed[0]
        assert sql0 == ("DELETE FROM tc_positions WHERE id IN ("
                        + ", ".join(["%s"] * 10000) + ")")
        assert params0 == tuple(range(1, 10001))
        sql2, params2 = conn.executed[2]
        assert sql2.count("%s") == 5000
        assert params2 == tuple(range(20001, 25001))
        # Committed once per chunk, keeping table locks short.
        assert conn.commits == 3

    def test_never_a_time_window(self):
        conn = FakeConn()
        acs.batch_delete_by_ids(conn, "tc_positions", [1, 2, 3])
        (sql, _params), = conn.executed
        assert "WHERE id IN" in sql
        for fragment in ("fixtime", "eventtime", "deviceid", ">=", "<"):
            assert fragment not in sql

    def test_empty_id_list_issues_no_sql(self):
        conn = FakeConn()
        assert acs.batch_delete_by_ids(conn, "tc_positions", []) == 0
        assert conn.executed == []
        assert conn.commits == 0


class TestCountMismatch:
    def test_mismatch_fails_group_loudly_no_repair(self, tmp_path, s3):
        calls = []

        def bad_deleter(conn, table, ids):
            calls.append(list(ids))
            return len(ids) - 1  # one row short

        conn = make_conn()
        total, failures = run_table(conn, tmp_path, deleter=bad_deleter)

        assert failures == 1
        assert total == 0
        assert len(calls) == 1                    # no retry, no repair
        assert s3["copies"] == []                 # no finalize
        assert not any(k.endswith(".done") for k in s3["uploads"])
        assert s3["spaces_deletes"] == []         # tmp preserved: archive intact


class TestLatestPositionExclusion:
    def test_protected_ids_never_reach_the_deleter(self, tmp_path, s3):
        received = []

        def deleter(conn, table, ids):
            received.append(list(ids))
            return len(ids)

        conn = make_conn()
        total, failures = run_table(conn, tmp_path, deleter=deleter,
                                    id_exclusions={2})

        assert (total, failures) == (2, 0)        # both rows still archived
        assert received == [[1]]                  # only the unprotected id deleted
        assert "rehearsal/positions/7/2025-03.done" in s3["uploads"]

    def test_fetch_protected_position_ids(self):
        conn = FakeConn(groups=[{"positionid": 5}, {"positionid": 9}])
        assert acs.fetch_protected_position_ids(conn) == {5, 9}
        (sql, _), = conn.executed
        assert "FROM tc_devices" in sql
        assert "positionid IS NOT NULL" in sql
