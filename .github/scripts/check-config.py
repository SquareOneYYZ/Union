#!/usr/bin/env python3
"""Check a traccar.xml against config-rules.txt before a deploy.

    check-config.py CONFIG RULES ENV [KEYS_OUT]

Fails (exit 1) on: XML that does not parse, a key set more than once with
different values (traccar silently keeps the last one), and any broken rule.
A key repeated with the same value is only a warning. Only key names are
ever printed, never values. KEYS_OUT, if given, receives the sorted key names
so environments can be compared.

Stdlib only: runs under the host's /usr/bin/python3.
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def load_entries(path):
    values = defaultdict(list)
    for entry in ET.parse(path).getroot().iter('entry'):
        values[entry.get('key')].append((entry.text or '').strip())
    return values


def load_rules(path, env):
    rules = []
    with open(path) as f:
        for number, line in enumerate(f, 1):
            parts = line.split('#', 1)[0].split()
            if not parts:
                continue
            kind, args = parts[0], parts[1:]
            arity = {'required': 1, 'forbidden': 1, 'when': 2}.get(kind)
            if arity is None or len(args) not in (arity, arity + 1):
                raise SystemExit(f'{path}:{number}: cannot parse rule: {line.strip()}')
            if len(args) > arity and env not in args[arity].split(','):
                continue
            rules.append((kind, args[:arity]))
    return rules


def main():
    if len(sys.argv) not in (4, 5):
        raise SystemExit(__doc__)
    config, rules_path, env = sys.argv[1:4]

    try:
        values = load_entries(config)
    except (ET.ParseError, OSError) as e:
        print(f'FAIL {config} cannot be read as XML: {e}')
        return 1

    current = {key: vals[-1] for key, vals in values.items()}
    errors, warnings = [], []

    for key, vals in sorted(values.items()):
        if len(vals) > 1:
            if len(set(vals)) > 1:
                errors.append(f'{key} is set {len(vals)} times with DIFFERENT values (the last one wins)')
            else:
                warnings.append(f'{key} is set {len(vals)} times (same value)')

    for kind, args in load_rules(rules_path, env):
        if kind == 'required':
            if not current.get(args[0]):
                errors.append(f'{args[0]} is required but missing or empty')
        elif kind == 'when':
            condition, needed = args
            key, _, expected = condition.partition('=')
            if current.get(key, '').lower() == expected.lower() and not current.get(needed):
                errors.append(f'{needed} is required because {key}={expected}, but missing or empty')
        elif kind == 'forbidden':
            pattern = args[0]
            if pattern.endswith('*'):
                found = [k for k in current if k.startswith(pattern[:-1])]
            else:
                found = [pattern] if pattern in current else []
            for k in sorted(found):
                errors.append(f'{k} must not be set on {env}')

    for w in warnings:
        print(f'WARN {w}')
    for e in errors:
        print(f'FAIL {e}')
    print(f'{config}: {len(current)} keys, {len(errors)} problem(s), {len(warnings)} warning(s)')

    if len(sys.argv) == 5:
        with open(sys.argv[4], 'w') as f:
            f.write(''.join(f'{k}\n' for k in sorted(current)))

    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
