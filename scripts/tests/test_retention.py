"""Retention validated at the RESOLUTION point (third-review item 1).

The old guard sat on the --months argument — a path the cron never takes.
resolve_retention_months validates whichever source actually wins, so a bad
archive.retention.months in config is a clear fatal error under cron, not an
uncaught ValueError, and never a silent zero/negative retention.
"""

import argparse

import pytest

import archive_cold_storage as acs


def make_args(months=None):
    return argparse.Namespace(months=months)


class TestResolveRetentionMonths:
    def test_cli_override_wins(self):
        assert acs.resolve_retention_months(
            make_args(months=12), {"archive.retention.months": "3"}) == 12

    def test_config_value_used(self):
        assert acs.resolve_retention_months(
            make_args(), {"archive.retention.months": "9"}) == 9

    def test_default_when_unset_or_blank(self):
        assert acs.resolve_retention_months(make_args(), {}) == 6
        assert acs.resolve_retention_months(
            make_args(), {"archive.retention.months": "  "}) == 6

    @pytest.mark.parametrize("source_kwargs,props", [
        # zero: collapses retention, archives recent history early
        (dict(months=0), {}),
        (dict(), {"archive.retention.months": "0"}),
        # negative: cutoff in the future, sweeps the current month
        (dict(months=-3), {}),
        (dict(), {"archive.retention.months": "-3"}),
    ])
    def test_nonpositive_is_fatal_from_either_source(self, source_kwargs,
                                                     props):
        with pytest.raises(SystemExit) as exc:
            acs.resolve_retention_months(make_args(**source_kwargs), props)
        assert exc.value.code == 1

    def test_non_numeric_config_is_a_clear_fatal_not_a_valueerror(self,
                                                                  caplog):
        # The cron path: a typo in config must die loudly, not with an
        # uncaught traceback in archive.log.
        with pytest.raises(SystemExit) as exc:
            acs.resolve_retention_months(
                make_args(), {"archive.retention.months": "six"})
        assert exc.value.code == 1
        assert "archive.retention.months" in caplog.text
