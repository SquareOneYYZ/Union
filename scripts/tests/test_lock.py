"""Run-lock contention (C1).

The lock guards a single box against overlapping invocations — the cron
firing while someone hand-runs the script. It must: use a path independent of
any config value (a different --config must not escape exclusion), fail fast
and loudly when held (never block and queue), fail loudly on any lock-file
problem on a real host, and downgrade to a warning ONLY when fcntl itself is
absent (Windows dev boxes).
"""

import os

import pytest

import archive_cold_storage as acs

posix_only = pytest.mark.skipif(
    acs.fcntl is None, reason="flock requires fcntl (POSIX)"
)


def test_lock_path_is_config_independent():
    # Only two possible locations, neither influenced by any config value:
    # the host-wide /var/lock, or the script's own directory.
    p = acs._lock_path()
    assert p in (
        "/var/lock/traccar-archive.lock",
        os.path.join(os.path.dirname(os.path.abspath(acs.__file__)),
                     "archive_cold_storage.lock"),
    )


@posix_only
def test_second_acquire_fails_fast_with_nonzero_exit(tmp_path, monkeypatch):
    monkeypatch.setattr(acs, "_lock_path", lambda: str(tmp_path / "t.lock"))
    first = acs.acquire_run_lock()
    assert first is not None
    with pytest.raises(SystemExit) as exc:
        acs.acquire_run_lock()
    assert exc.value.code == 1
    first.close()


@posix_only
def test_reacquire_after_release(tmp_path, monkeypatch):
    monkeypatch.setattr(acs, "_lock_path", lambda: str(tmp_path / "t.lock"))
    first = acs.acquire_run_lock()
    first.close()
    second = acs.acquire_run_lock()
    assert second is not None
    second.close()


@posix_only
def test_unopenable_lock_path_fails_loudly(tmp_path, monkeypatch):
    # A missing/unwritable lock directory on a real host must exit non-zero,
    # not fall through to warn-and-continue.
    monkeypatch.setattr(
        acs, "_lock_path", lambda: str(tmp_path / "no-such-dir" / "t.lock"))
    with pytest.raises(SystemExit) as exc:
        acs.acquire_run_lock()
    assert exc.value.code == 1


def test_fallback_without_fcntl_warns_and_continues(monkeypatch):
    # The ONLY downgrade path: fcntl itself is absent (Windows dev box).
    monkeypatch.setattr(acs, "fcntl", None)
    assert acs.acquire_run_lock() is None
