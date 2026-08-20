"""--max-groups (bulk-migration session bounding).

Approved requirements: stop only at a group boundary, never mid-group; log
that the run stopped on the limit rather than exhausting the work; a clean
limit-stop is not a failure (exit stays 0 via the failure count staying 0).
"""

import argparse
import logging

import pytest

import archive_cold_storage as acs
from fakes import FakeConn, run_table


def two_group_conn():
    return FakeConn(
        groups=[
            {"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 2},
            {"deviceid": 7, "yr": 2025, "mo": 4, "cnt": 1},
        ],
        rows=[
            {"id": 1, "deviceid": 7, "fixtime": "2025-03-01 00:00:00"},
            {"id": 2, "deviceid": 7, "fixtime": "2025-03-02 00:00:00"},
        ],
    )


def test_limit_stops_at_group_boundary_without_failure(tmp_path, s3, caplog):
    caplog.set_level(logging.INFO)
    budget = acs.GroupBudget(1)
    conn = two_group_conn()

    total, failures = run_table(conn, tmp_path,
                                deleter=lambda c, t, i: len(i), budget=budget)

    # First group completed END-TO-END (finalized + marker); second untouched.
    assert (total, failures) == (2, 0)
    assert "rehearsal/positions/7/2025-03.done" in s3["uploads"]
    assert not any("2025-04" in k for k in s3["uploads"])
    assert budget.exhausted_hit
    assert "--max-groups limit (1) reached" in caplog.text
    assert "clean stop, not a failure" in caplog.text


def test_no_limit_processes_everything(tmp_path, s3):
    budget = acs.GroupBudget(None)
    conn = two_group_conn()
    run_table(conn, tmp_path, deleter=lambda c, t, i: len(i), budget=budget)
    assert not budget.exhausted_hit
    assert budget.used == 0            # unlimited budget never counts


def test_limit_larger_than_work_is_not_a_limit_stop(tmp_path, s3):
    budget = acs.GroupBudget(10)
    conn = two_group_conn()
    run_table(conn, tmp_path, deleter=lambda c, t, i: len(i), budget=budget)
    assert budget.used == 2
    assert not budget.exhausted_hit


def test_cutoff_skips_do_not_consume_budget(tmp_path, s3):
    from datetime import date
    budget = acs.GroupBudget(1)
    conn = FakeConn(
        groups=[{"deviceid": 7, "yr": 2026, "mo": 2, "cnt": 5}],
        rows=[{"id": 1, "deviceid": 7, "fixtime": "2026-02-01 00:00:00"}],
    )
    run_table(conn, tmp_path, deleter=lambda c, t, i: len(i),
              cutoff=date(2026, 2, 18), budget=budget)
    assert budget.used == 0            # the C7 skip cost nothing
    assert not budget.exhausted_hit


def test_budget_is_shared_across_tables():
    budget = acs.GroupBudget(3)
    assert [budget.take() for _ in range(5)] == [True, True, True, False, False]
    assert budget.used == 3
    assert budget.exhausted_hit


def test_nonpositive_max_groups_refused():
    args = argparse.Namespace(archive_only=False, dry_run=False, prefix=None,
                              max_groups=0)
    with pytest.raises(SystemExit) as exc:
        acs.resolve_run_options(args)
    assert exc.value.code == 2
