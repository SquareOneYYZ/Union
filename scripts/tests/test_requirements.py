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
