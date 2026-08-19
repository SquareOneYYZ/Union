"""Run-lock contention (C1).

The lock guards a single box against overlapping invocations — the cron
firing while someone hand-runs the script. It must fail fast and loudly when
held (never block and queue), and release cleanly with the process.
"""

import pytest

import archive_cold_storage as acs

posix_only = pytest.mark.skipif(
    acs.fcntl is None, reason="flock requires fcntl (POSIX)"
)


@posix_only
def test_second_acquire_fails_fast_with_nonzero_exit(tmp_path):
    first = acs.acquire_run_lock(str(tmp_path))
    assert first is not None
    with pytest.raises(SystemExit) as exc:
        acs.acquire_run_lock(str(tmp_path))
    assert exc.value.code == 1
    first.close()


@posix_only
def test_reacquire_after_release(tmp_path):
    first = acs.acquire_run_lock(str(tmp_path))
    first.close()
    second = acs.acquire_run_lock(str(tmp_path))
    assert second is not None
    second.close()


def test_fallback_without_fcntl_warns_and_continues(tmp_path, monkeypatch):
    # Windows dev boxes have no fcntl; the run continues without the lock
    # (single-user machine) instead of crashing.
    monkeypatch.setattr(acs, "fcntl", None)
    assert acs.acquire_run_lock(str(tmp_path)) is None
