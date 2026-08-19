import os
import sys

# Make scripts/archive_cold_storage.py (and the shared fakes) importable.
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

import pytest  # noqa: E402


@pytest.fixture
def s3(monkeypatch):
    """Fake the s3 helpers; record every key uploaded/copied/deleted.

    Marker checks miss, temp-key checks miss, temp verifies hit -- the happy
    path for a fresh group.
    """
    import archive_cold_storage as acs

    calls = {"uploads": [], "copies": [], "spaces_deletes": []}

    monkeypatch.setattr(acs, "do_upload",
                        lambda cfg, path, key: calls["uploads"].append(key) or True)
    monkeypatch.setattr(acs, "verify_upload",
                        lambda cfg, key: not key.endswith(".done"))
    monkeypatch.setattr(acs, "check_temp_key_exists", lambda cfg, key: False)
    monkeypatch.setattr(acs, "verify_row_count", lambda cfg, key, n: True)
    monkeypatch.setattr(acs, "copy_spaces_key",
                        lambda cfg, src, dst: calls["copies"].append((src, dst)) or True)
    monkeypatch.setattr(acs, "delete_spaces_key",
                        lambda cfg, key: calls["spaces_deletes"].append(key))
    return calls
