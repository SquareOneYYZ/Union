"""Merge instead of skip/overwrite (C6 / D1).

When a final parquet already exists and the group still has rows (late
arrivals), the fresh export is merged into the existing file: download,
concat, dedupe on id with the archived row winning collisions. The merge is
additive-only and schema-checked, both verified BEFORE the copy — an abort
leaves the existing final key untouched. Deletes are keyed to the newly
exported ids only, never the merged set. A .done marker no longer skips a
group that still has rows (the starvation path).
"""

import pandas as pd
import pytest

import archive_cold_storage as acs
from fakes import FakeConn, make_conn, run_table

TMP = "rehearsal/positions/7/2025-03.parquet.tmp"
FINAL = "rehearsal/positions/7/2025-03.parquet"
MARKER = "rehearsal/positions/7/2025-03.done"

COLS = ["id", "deviceid", "fixtime"]


def seed_existing_final(monkeypatch, old_df):
    """Make download_key deliver a real parquet containing old_df."""
    def fake_download(cfg, spaces_key, local_path):
        acs.write_parquet(old_df, local_path)
        return True
    monkeypatch.setattr(acs, "download_key", fake_download)


@pytest.fixture
def written(monkeypatch):
    """Capture every frame handed to write_parquet (still writes for real)."""
    frames = []
    real = acs.write_parquet

    def spy(df, path):
        frames.append(df.copy())
        real(df, path)
    monkeypatch.setattr(acs, "write_parquet", spy)
    return frames


def test_merge_disjoint_ids_additive(tmp_path, s3, monkeypatch, written):
    s3["final_exists"].add(FINAL)
    old_df = pd.DataFrame({"id": [10, 11], "deviceid": [7, 7],
                           "fixtime": ["2025-03-05 00:00:00",
                                       "2025-03-06 00:00:00"]})[COLS]
    seed_existing_final(monkeypatch, old_df)

    deleted = []

    def deleter(conn, table, ids):
        deleted.append(list(ids))
        return len(ids)

    conn = make_conn()  # exports ids 1, 2
    total, failures = run_table(conn, tmp_path, deleter=deleter)

    assert (total, failures) == (2, 0)
    # The uploaded frame is the merged one: all four ids, none lost.
    merged = written[-1]
    assert sorted(int(i) for i in merged["id"]) == [1, 2, 10, 11]
    # Row-count verification ran against the MERGED count.
    assert ("rowcount", TMP, 4) in s3["events"]
    # Constraint 3: only the newly-exported ids were deleted.
    assert deleted == [[1, 2]]
    assert MARKER in s3["uploads"]


def test_merge_overlap_archived_row_wins(tmp_path, s3, monkeypatch, written):
    s3["final_exists"].add(FINAL)
    # id 2 exists in the archive with a DIFFERENT fixtime than the export's.
    old_df = pd.DataFrame({"id": [2, 10], "deviceid": [7, 7],
                           "fixtime": ["ARCHIVED-VALUE",
                                       "2025-03-05 00:00:00"]})[COLS]
    seed_existing_final(monkeypatch, old_df)

    deleted = []

    def deleter(conn, table, ids):
        deleted.append(list(ids))
        return len(ids)

    conn = make_conn()  # exports ids 1, 2
    total, failures = run_table(conn, tmp_path, deleter=deleter)

    assert (total, failures) == (2, 0)
    merged = written[-1]
    assert sorted(int(i) for i in merged["id"]) == [1, 2, 10]
    row2 = merged[merged["id"] == 2].iloc[0]
    assert row2["fixtime"] == "ARCHIVED-VALUE"  # archive wins the collision
    # Still deletes the exported id 2 (it IS in the DB) — but only export ids.
    assert deleted == [[1, 2]]


def test_schema_mismatch_aborts_naming_columns(tmp_path, s3, monkeypatch,
                                               caplog):
    s3["final_exists"].add(FINAL)
    old_df = pd.DataFrame({"id": [10], "deviceid": [7],
                           "legacycol": ["x"]})  # no fixtime, extra legacycol
    seed_existing_final(monkeypatch, old_df)

    conn = make_conn()
    total, failures = run_table(conn, tmp_path, deleter=lambda c, t, i: len(i))

    assert (total, failures) == (0, 1)
    assert s3["uploads"] == []          # nothing uploaded
    assert s3["copies"] == []           # final key untouched
    assert not any("DELETE" in sql.upper() for sql, _ in conn.executed)
    assert "SCHEMA MISMATCH" in caplog.text
    assert "legacycol" in caplog.text   # only-in-archive named
    assert "fixtime" in caplog.text     # only-in-export named


def test_non_additive_merge_aborts_final_untouched(tmp_path, s3, monkeypatch,
                                                   caplog):
    s3["final_exists"].add(FINAL)
    # Internal duplicate ids in the old file: dedupe would SHRINK it.
    old_df = pd.DataFrame({"id": [10, 10, 10], "deviceid": [7, 7, 7],
                           "fixtime": ["a", "b", "c"]})[COLS]
    seed_existing_final(monkeypatch, old_df)

    conn = FakeConn(groups=[{"deviceid": 7, "yr": 2025, "mo": 3, "cnt": 1}],
                    rows=[{"id": 10, "deviceid": 7,
                           "fixtime": "2025-03-01 00:00:00"}])
    total, failures = run_table(conn, tmp_path, deleter=lambda c, t, i: len(i))

    assert (total, failures) == (0, 1)
    assert s3["uploads"] == []
    assert s3["copies"] == []
    assert "MERGE NOT ADDITIVE" in caplog.text


def test_fresh_group_never_downloads(tmp_path, s3, monkeypatch):
    def explode(cfg, spaces_key, local_path):
        raise AssertionError("download_key called for a fresh group")
    monkeypatch.setattr(acs, "download_key", explode)

    conn = make_conn()
    total, failures = run_table(conn, tmp_path,
                                deleter=lambda c, t, i: len(i))
    assert (total, failures) == (2, 0)


def test_marker_no_longer_starves_late_rows(tmp_path, s3, monkeypatch,
                                            written):
    # The old code skipped on marker and even counted the rows as archived.
    s3["marker_exists"].add(MARKER)
    s3["final_exists"].add(FINAL)
    old_df = pd.DataFrame({"id": [10], "deviceid": [7],
                           "fixtime": ["2025-03-05 00:00:00"]})[COLS]
    seed_existing_final(monkeypatch, old_df)

    deleted = []

    def deleter(conn, table, ids):
        deleted.append(list(ids))
        return len(ids)

    conn = make_conn()  # late rows 1, 2 for an "already archived" month
    total, failures = run_table(conn, tmp_path, deleter=deleter)

    assert (total, failures) == (2, 0)      # actually archived, not skipped
    assert sorted(int(i) for i in written[-1]["id"]) == [1, 2, 10]
    assert deleted == [[1, 2]]
    assert MARKER in s3["uploads"]          # marker re-uploaded after merge
