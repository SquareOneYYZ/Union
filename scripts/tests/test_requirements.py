"""requirements.txt (C8): the pins are staging's measured working set.

The file must pin exactly the four runtime dependencies with == (no ranges,
no extras), and package.sh must ship it next to the script — the P4 artifact
verifier enforces byte-identity in the built installers automatically now
that the file exists.
"""

import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REQ = os.path.join(HERE, "..", "requirements.txt")
PACKAGE_SH = os.path.join(HERE, "..", "..", "setup", "package.sh")

EXPECTED = {
    "PyMySQL": "1.1.2",
    "pandas": "3.0.1",
    "pyarrow": "23.0.1",
    "python-dateutil": "2.9.0",
}


def parse_requirements():
    pins = {}
    with open(REQ, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = re.fullmatch(r"([A-Za-z0-9._-]+)==([A-Za-z0-9.]+)", line)
            assert m, f"not an exact pin: {line!r}"
            pins[m.group(1)] = m.group(2)
    return pins


def test_exact_pins_match_the_measured_set():
    assert parse_requirements() == EXPECTED


def test_package_sh_ships_requirements_next_to_the_script():
    with open(PACKAGE_SH, encoding="utf-8") as f:
        content = f.read()
    assert "cp ../scripts/requirements.txt out/scripts" in content


SETUP_SH = os.path.join(HERE, "..", "..", "setup", "setup.sh")


def test_setup_sh_installs_pins_for_the_cron_interpreter():
    # Must be the exact interpreter the cron line invokes, with the PEP 668
    # override that matches how the existing working host was provisioned.
    with open(SETUP_SH, encoding="utf-8") as f:
        content = f.read()
    assert ("/usr/bin/python3 -m pip install --break-system-packages "
            "-r /opt/traccar/scripts/requirements.txt") in content


def test_setup_sh_prints_the_selfcheck_command():
    with open(SETUP_SH, encoding="utf-8") as f:
        content = f.read()
    assert "--selfcheck" in content


def test_setup_sh_gates_cron_install_on_selfcheck():
    # The deploy equivalent of a fail-open probe: the cron must never be
    # armed on a host that cannot pass the selfcheck.
    with open(SETUP_SH, encoding="utf-8") as f:
        content = f.read()
    gate = content.index(
        "if /usr/bin/python3 /opt/traccar/scripts/archive_cold_storage.py "
        "--config /opt/traccar/conf/traccar.xml --selfcheck; then")
    cron = content.index("0 4 1 * *")
    assert gate < cron


def test_package_sh_removes_scripts_dir_before_other_zip():
    # out/scripts surviving package_linux leaked the archiver into the
    # "other" zip built afterwards from out/*.
    with open(PACKAGE_SH, encoding="utf-8") as f:
        content = f.read()
    assert "rm -r out/scripts" in content


def test_ci_installs_the_pinned_requirements():
    workflow = os.path.join(HERE, "..", "..", ".github", "workflows",
                            "python-tests.yml")
    with open(workflow, encoding="utf-8") as f:
        content = f.read()
    assert "-r scripts/requirements.txt" in content
