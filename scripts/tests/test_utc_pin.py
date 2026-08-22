"""UTC session pinning on the pymysql connection (C1 / D8)."""

import archive_cold_storage as acs


def test_connection_pins_utc(monkeypatch):
    captured = {}

    def fake_connect(**kwargs):
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(acs.pymysql, "connect", fake_connect)
    acs.get_connection({
        "database.url": "jdbc:mysql://dbhost:3306/traccar",
        "database.user": "u",
        "database.password": "p",
    })

    assert captured["init_command"] == "SET time_zone = '+00:00'"
    assert captured["host"] == "dbhost"
    assert captured["port"] == 3306
    assert captured["database"] == "traccar"


def test_hyphenated_db_name_with_params_parses_fully(monkeypatch):
    # (\w+) used to truncate "toll-db" to "toll", silently feeding the
    # localhost-fallback hazard's cousin: connecting to the wrong database.
    captured = {}
    monkeypatch.setattr(acs.pymysql, "connect",
                        lambda **kw: captured.update(kw) or object())
    acs.get_connection({
        "database.url": "jdbc:mysql://dbhost:3306/toll-db?useSSL=false&x=1",
    })
    assert captured["database"] == "toll-db"
