import os
import sys

# Make scripts/archive_cold_storage.py (and the shared fakes) importable.
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

import pytest  # noqa: E402


@pytest.fixture
def s3(monkeypatch):
    """Fake the s3 helpers; record every call in order.

    Defaults are the happy path for a fresh group: marker checks miss,
    temp-key checks miss, verifies hit. Tests can inject failures via
    calls["fail_copy"] = True or calls["fail_verify"].add(<key>). Every call
    also lands in calls["events"] in invocation order, so ordering tests can
    assert what happened before what.
    """
    import archive_cold_storage as acs

    calls = {
        "uploads": [], "copies": [], "spaces_deletes": [], "events": [],
        "fail_copy": False, "fail_verify": set(), "tmp_exists": set(),
        "final_exists": set(), "marker_exists": set(),
    }

    def do_upload(cfg, path, key):
        calls["uploads"].append(key)
        calls["events"].append(("upload", key))
        return True

    def verify_upload(cfg, key):
        calls["events"].append(("verify", key))
        if key in calls["fail_verify"]:
            return False
        if key.endswith(".done"):
            return key in calls["marker_exists"]
        if key.endswith(".parquet.tmp"):
            return True
        # Final .parquet existence: pre-seeded by the test, or created by a
        # copy earlier in this run (C4 verifies the final key after copy).
        return (key in calls["final_exists"]
                or any(dst == key for _src, dst in calls["copies"]))

    def check_temp_key_exists(cfg, key):
        calls["events"].append(("check_tmp", key))
        return key in calls["tmp_exists"]

    def verify_row_count(cfg, key, n):
        calls["events"].append(("rowcount", key, n))
        return True

    def copy_spaces_key(cfg, src, dst):
        calls["events"].append(("copy", src, dst))
        if calls["fail_copy"]:
            return False
        calls["copies"].append((src, dst))
        return True

    def delete_spaces_key(cfg, key):
        calls["spaces_deletes"].append(key)
        calls["events"].append(("delete", key))

    monkeypatch.setattr(acs, "do_upload", do_upload)
    monkeypatch.setattr(acs, "verify_upload", verify_upload)
    monkeypatch.setattr(acs, "check_temp_key_exists", check_temp_key_exists)
    monkeypatch.setattr(acs, "verify_row_count", verify_row_count)
    monkeypatch.setattr(acs, "copy_spaces_key", copy_spaces_key)
    monkeypatch.setattr(acs, "delete_spaces_key", delete_spaces_key)
    return calls
