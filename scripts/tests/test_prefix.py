"""--prefix validation (C2).

A rehearsal must never be able to write into the production 'archive/' key
space, and --prefix without --archive-only (a destructive run aimed at a
scratch prefix) must be refused outright.
"""

import argparse

import pytest

import archive_cold_storage as acs


def make_args(archive_only=False, dry_run=False, prefix=None):
    return argparse.Namespace(
        archive_only=archive_only, dry_run=dry_run, prefix=prefix)


class TestValidatePrefix:
    @pytest.mark.parametrize("prefix,expected", [
        ("rehearsal-2026", "rehearsal-2026"),
        ("scratch/aug", "scratch/aug"),
        ("a.b_c-d", "a.b_c-d"),
        ("/leading-slash-stripped/", "leading-slash-stripped"),
        ("  padded  ", "padded"),
    ])
    def test_valid(self, prefix, expected):
        assert acs.validate_prefix(prefix) == expected

    @pytest.mark.parametrize("prefix", [
        "archive",            # the production key space itself
        "archive/scratch",    # resolves inside it
        "archive/",           # normalizes to it
        "/archive",           # normalizes to it
        "archive2",           # startswith: sits beside the production space
        "archive-mine",       # startswith: collides with the -quarantine sibling pattern
        "archives/x",         # startswith in the first segment
        "",                   # empty
        "/",                  # empty after normalization
        "..",                 # traversal
        "a/../b",             # traversal segment
        "a//b",               # empty segment
        "a b",                # unsupported characters
        "sc:ratch",           # unsupported characters
    ])
    def test_rejected(self, prefix):
        with pytest.raises(ValueError):
            acs.validate_prefix(prefix)


class TestResolveRunOptions:
    def test_default_is_production_prefix_destructive(self):
        assert acs.resolve_run_options(make_args()) == (acs.PROD_KEY_PREFIX, False)

    def test_archive_only_default_prefix(self):
        assert acs.resolve_run_options(make_args(archive_only=True)) == (
            acs.PROD_KEY_PREFIX, True)

    def test_archive_only_with_scratch_prefix(self):
        assert acs.resolve_run_options(
            make_args(archive_only=True, prefix="rehearsal-2026")) == (
            "rehearsal-2026", True)

    def test_prefix_without_archive_only_refused(self):
        with pytest.raises(SystemExit) as exc:
            acs.resolve_run_options(make_args(prefix="rehearsal-2026"))
        assert exc.value.code == 2

    def test_prefix_into_production_key_space_refused(self):
        with pytest.raises(SystemExit) as exc:
            acs.resolve_run_options(make_args(archive_only=True, prefix="archive"))
        assert exc.value.code == 2

    def test_archive_only_and_dry_run_mutually_exclusive(self):
        with pytest.raises(SystemExit) as exc:
            acs.resolve_run_options(make_args(archive_only=True, dry_run=True))
        assert exc.value.code == 2
