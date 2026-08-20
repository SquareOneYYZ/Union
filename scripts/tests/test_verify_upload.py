"""Three-state probe and strict verification (C1 + second-review fix).

`s3cmd ls <key>` is a prefix listing, so exact-match comparison is required
(X.parquet vs X.parquet.tmp). And per the measurement-validity rule, a
failed probe (non-zero exit, timeout) must never read as absence:
probe_key returns None on failure, and verify_upload treats anything but a
confirmed True as not-verified (fail-closed).
"""

import subprocess

import archive_cold_storage as acs


BUCKET = "testbucket"
FINAL_KEY = "archive/positions/7/2026-01.parquet"
TMP_KEY = FINAL_KEY + ".tmp"
FINAL_DEST = f"s3://{BUCKET}/{FINAL_KEY}"
TMP_DEST = f"s3://{BUCKET}/{TMP_KEY}"

LISTING_BOTH = (
    f"2026-03-04 09:00      1234  {FINAL_DEST}\n"
    f"2026-03-04 09:00      1234  {TMP_DEST}\n"
)
LISTING_TMP_ONLY = f"2026-03-04 09:00      1234  {TMP_DEST}\n"
LISTING_FINAL_ONLY = f"2026-03-04 09:00      1234  {FINAL_DEST}\n"


def make_cfg():
    return acs.PropsConfig({
        "archive.spaces.bucket": BUCKET,
        "archive.python.exe": "python",
        "archive.s3cmd.script": "s3cmd",
        "archive.s3cmd.configFile": "s3.cfg",
    })


class FakeResult:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def patch_run_s3cmd(monkeypatch, stdout="", returncode=0, result="normal"):
    def fake(cmd):
        if result == "none":       # timeout / failed launch
            return None
        return FakeResult(returncode=returncode, stdout=stdout)
    monkeypatch.setattr(acs, "run_s3cmd", fake)


class TestKeyInListing:
    def test_exact_final_key_found(self):
        assert acs.key_in_listing(LISTING_BOTH, FINAL_DEST)

    def test_final_key_not_matched_by_tmp_only_listing(self):
        # "X.parquet" is a substring of "X.parquet.tmp"; the key column never
        # equals the final dest.
        assert not acs.key_in_listing(LISTING_TMP_ONLY, FINAL_DEST)

    def test_empty_listing(self):
        assert not acs.key_in_listing("", FINAL_DEST)


class TestProbeKey:
    def test_present(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, LISTING_FINAL_ONLY)
        assert acs.probe_key(make_cfg(), FINAL_KEY) is True

    def test_absent(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, "")
        assert acs.probe_key(make_cfg(), FINAL_KEY) is False

    def test_prefix_cousin_is_not_presence(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, LISTING_TMP_ONLY)
        assert acs.probe_key(make_cfg(), FINAL_KEY) is False

    def test_nonzero_exit_is_error_not_absence(self, monkeypatch):
        # Even with the key visible in stdout: a failed command is a failed
        # measurement, never a fact about the bucket.
        patch_run_s3cmd(monkeypatch, LISTING_FINAL_ONLY, returncode=1)
        assert acs.probe_key(make_cfg(), FINAL_KEY) is None

    def test_timeout_is_error_not_absence(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, result="none")
        assert acs.probe_key(make_cfg(), FINAL_KEY) is None


class TestVerifyUploadFailClosed:
    def test_true_only_when_confirmed_present(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, LISTING_FINAL_ONLY)
        assert acs.verify_upload(make_cfg(), FINAL_KEY)

    def test_absent_fails(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, LISTING_TMP_ONLY)
        assert not acs.verify_upload(make_cfg(), FINAL_KEY)

    def test_probe_error_fails(self, monkeypatch):
        patch_run_s3cmd(monkeypatch, LISTING_FINAL_ONLY, returncode=1)
        assert not acs.verify_upload(make_cfg(), FINAL_KEY)


class TestRunS3cmdTimeout:
    def test_timeout_returns_none(self, monkeypatch):
        def raise_timeout(cmd, capture_output=True, text=True, timeout=None):
            raise subprocess.TimeoutExpired(cmd, timeout)
        monkeypatch.setattr(acs.subprocess, "run", raise_timeout)
        assert acs.run_s3cmd(["s3cmd", "ls", "s3://x"]) is None

    def test_configure_from_props(self):
        assert acs.configure_s3_timeout({"archive.s3cmd.timeout": "42"}) == 42
        assert acs.S3_TIMEOUT["seconds"] == 42
        assert (acs.configure_s3_timeout({})
                == acs.DEFAULT_S3_TIMEOUT_SECONDS)

    def test_invalid_timeout_is_fatal(self):
        import pytest
        with pytest.raises(SystemExit):
            acs.configure_s3_timeout({"archive.s3cmd.timeout": "soon"})
        with pytest.raises(SystemExit):
            acs.configure_s3_timeout({"archive.s3cmd.timeout": "0"})
        acs.configure_s3_timeout({})  # restore default for other tests
