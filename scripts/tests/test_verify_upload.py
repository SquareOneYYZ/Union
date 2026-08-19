"""Exact-match key verification (C1).

The old check was `spaces_key in result.stdout`, but `s3cmd ls <key>` is a
prefix listing: asking for X.parquet also returns X.parquet.tmp, so the
substring test reported a final key as present when only the tmp upload
existed. These tests pin the exact-match behavior.
"""

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


def patch_s3cmd(monkeypatch, stdout, returncode=0):
    def fake_run(cmd, capture_output=True, text=True):
        return FakeResult(returncode=returncode, stdout=stdout)
    monkeypatch.setattr(acs.subprocess, "run", fake_run)


class TestKeyInListing:
    def test_exact_final_key_found(self):
        assert acs.key_in_listing(LISTING_BOTH, FINAL_DEST)

    def test_exact_tmp_key_found(self):
        assert acs.key_in_listing(LISTING_BOTH, TMP_DEST)

    def test_final_key_not_matched_by_tmp_only_listing(self):
        # The regression the substring check had: "X.parquet" is a substring
        # of "X.parquet.tmp", but the key column never equals the final dest.
        assert not acs.key_in_listing(LISTING_TMP_ONLY, FINAL_DEST)

    def test_empty_listing(self):
        assert not acs.key_in_listing("", FINAL_DEST)

    def test_blank_lines_ignored(self):
        assert acs.key_in_listing("\n\n" + LISTING_FINAL_ONLY + "\n", FINAL_DEST)


class TestVerifyUpload:
    def test_true_when_final_key_listed(self, monkeypatch):
        patch_s3cmd(monkeypatch, LISTING_BOTH)
        assert acs.verify_upload(make_cfg(), FINAL_KEY)

    def test_false_when_only_tmp_listed(self, monkeypatch):
        # Old behavior returned True here — the false positive under test.
        patch_s3cmd(monkeypatch, LISTING_TMP_ONLY)
        assert not acs.verify_upload(make_cfg(), FINAL_KEY)

    def test_false_on_nonzero_exit_even_if_key_in_stdout(self, monkeypatch):
        patch_s3cmd(monkeypatch, LISTING_FINAL_ONLY, returncode=1)
        assert not acs.verify_upload(make_cfg(), FINAL_KEY)


class TestCheckTempKeyExists:
    def test_true_when_tmp_listed(self, monkeypatch):
        patch_s3cmd(monkeypatch, LISTING_TMP_ONLY)
        assert acs.check_temp_key_exists(make_cfg(), TMP_KEY)

    def test_false_when_only_final_listed(self, monkeypatch):
        patch_s3cmd(monkeypatch, LISTING_FINAL_ONLY)
        assert not acs.check_temp_key_exists(make_cfg(), TMP_KEY)
