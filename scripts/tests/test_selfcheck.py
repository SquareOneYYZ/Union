"""--selfcheck (Phase 4): read-only post-install verification.

Checks imports, config parse, temp dir, lock path, s3cmd reachability, and
DB connect. Must be strictly read-only: the only s3cmd subcommand it may run
is `ls`, and the only SQL is a SELECT.
"""

import argparse

import archive_cold_storage as acs


def write_cfg(tmp_path, drop=(), **overrides):
    entries = {
        "database.url": "jdbc:mysql://dbhost:3306/traccar",
        "database.user": "u",
        "database.password": "p",
        "archive.spaces.bucket": "testbucket",
        "archive.s3cmd.configFile": "s3.cfg",
        "archive.python.exe": "python",
        "archive.s3cmd.script": "s3cmd",
        "archive.temp.dir": str(tmp_path),
    }
    entries.update(overrides)
    body = "\n".join(f"    <entry key='{k}'>{v}</entry>"
                     for k, v in entries.items() if k not in drop)
    path = tmp_path / "traccar-test.xml"
    path.write_text(f"<properties>\n{body}\n</properties>", encoding="utf-8")
    return str(path)


class FakeResult:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class SelfcheckConn:
    def __init__(self):
        self.executed = []

    def cursor(self):
        conn = self

        class Cur:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def execute(self, sql, params=None):
                conn.executed.append(sql)

            def fetchone(self):
                return {"one": 1, "tz": "+00:00"}

        return Cur()

    def close(self):
        pass


def run_selfcheck(tmp_path, monkeypatch, s3_exit=0, db_raises=False, **cfg_kw):
    cfg_path = write_cfg(tmp_path, **cfg_kw)
    seen = {"cmds": [], "conn": None}

    def fake_run(cmd, capture_output=True, text=True, timeout=None):
        seen["cmds"].append(cmd)
        return FakeResult(returncode=s3_exit)

    def fake_connect(**kwargs):
        if db_raises:
            raise RuntimeError("no db")
        seen["conn"] = SelfcheckConn()
        return seen["conn"]

    monkeypatch.setattr(acs.subprocess, "run", fake_run)
    monkeypatch.setattr(acs.pymysql, "connect", fake_connect)
    args = argparse.Namespace(config=cfg_path, selfcheck=True)
    return acs.run_selfcheck(args), seen


def test_all_green_returns_zero(tmp_path, monkeypatch):
    code, seen = run_selfcheck(tmp_path, monkeypatch)
    assert code == 0
    # Strictly read-only: s3cmd only ever ran `ls`, SQL only ever a SELECT.
    for cmd in seen["cmds"]:
        assert "ls" in cmd
        for verb in ("put", "del", "rm", "cp", "mv", "sync", "setacl"):
            assert verb not in cmd
    assert all(sql.strip().upper().startswith("SELECT")
               for sql in seen["conn"].executed)


def test_missing_config_key_fails(tmp_path, monkeypatch, caplog):
    code, _ = run_selfcheck(tmp_path, monkeypatch,
                            drop=("archive.spaces.bucket",))
    assert code == 1
    assert "archive.spaces.bucket" in caplog.text


def test_s3cmd_failure_fails(tmp_path, monkeypatch):
    code, _ = run_selfcheck(tmp_path, monkeypatch, s3_exit=1)
    assert code == 1


def test_db_failure_fails(tmp_path, monkeypatch):
    code, _ = run_selfcheck(tmp_path, monkeypatch, db_raises=True)
    assert code == 1


def test_missing_config_file_fails(tmp_path, monkeypatch):
    args = argparse.Namespace(config=str(tmp_path / "nope.xml"),
                              selfcheck=True)
    assert acs.run_selfcheck(args) == 1


def test_invalid_timeout_reports_and_every_other_check_still_runs(
        tmp_path, monkeypatch, caplog):
    # Report-and-fail, never sys.exit: run_selfcheck is built to run every
    # check before returning.
    code, seen = run_selfcheck(tmp_path, monkeypatch,
                               **{"archive.s3cmd.timeout": "soon"})
    assert code == 1
    assert "archive.s3cmd.timeout" in caplog.text
    assert seen["conn"] is not None          # the DB check still ran
    assert seen["cmds"]                      # the s3cmd checks still ran
    acs.configure_s3_timeout({})             # restore default


def test_probe_premise_violation_fails(tmp_path, monkeypatch):
    # The absence premise: ls of an absent key exits 0 with an EMPTY listing.
    # An s3cmd that echoes the key back for an absent target violates it.
    cfg_path = write_cfg(tmp_path)
    seen = {"conn": None}

    def fake_run(cmd, capture_output=True, text=True, timeout=None):
        dest = cmd[-1]
        if "selfcheck-premise" in dest:
            return FakeResult(returncode=0, stdout=f"2026-01-01 00:00  1 {dest}\n")
        return FakeResult(returncode=0)

    def fake_connect(**kwargs):
        seen["conn"] = SelfcheckConn()
        return seen["conn"]

    monkeypatch.setattr(acs.subprocess, "run", fake_run)
    monkeypatch.setattr(acs.pymysql, "connect", fake_connect)
    args = argparse.Namespace(config=cfg_path, selfcheck=True)
    assert acs.run_selfcheck(args) == 1
