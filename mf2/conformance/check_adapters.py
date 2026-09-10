#!/usr/bin/env python3
"""Exercise the reference-derived 47-case corpus through real Mojito registries."""
import argparse
import json
from pathlib import Path
import sys

from check_official import ROOT, digest, run_bridge


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--registry', choices=['portable', 'platform'], default='platform')
    parser.add_argument('--dispositions', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ['--']:
        args.command.pop(0)
    if not args.command:
        parser.error('Pass a bridge command after --')
    root = ROOT / 'reference/fixtures'
    paths = sorted([*(root / 'selection-operands').glob('*/*.json'), *(root / 'resolved-values/adapters').glob('*.json')])
    cases = []
    for path in paths:
        fixture = json.loads(path.read_text())
        for case in fixture['formatCases']:
            cases.append((f"{path.relative_to(root)}#{case['name']}", {
                'source': fixture['source'], 'arguments': case['arguments'], 'locale': case['locale'],
                'bidiIsolation': 'none', 'registry': args.registry, 'expected': case['expected'],
            }))
    if len(cases) != 47:
        raise ValueError(f'Expected the complete 47-case adapter corpus, got {len(cases)}')
    responses = run_bridge(args.command, [request for _, request in cases])
    dispositions = json.loads(args.dispositions.read_text()) if args.dispositions else {}
    unused = set(dispositions)
    differences = []
    passed = failed = known = 0
    for (key, request), response in zip(cases, responses):
        actual = {'value': response.get('value'), 'errors': response.get('errors', []), 'diagnostics': response.get('diagnostics', [])}
        expected = {'value': request['expected'], 'errors': [], 'diagnostics': []}
        if actual == expected:
            passed += 1
            continue
        row = {'id': key, 'testSha256': digest(request), 'expected': expected, 'actual': actual}
        disposition = dispositions.get(key)
        if disposition and disposition.get('reason') and disposition.get('testSha256') == row['testSha256'] and disposition.get('actual') == actual:
            known += 1
            unused.discard(key)
            row['reason'] = disposition['reason']
        else:
            failed += 1
        differences.append(row)
    report = {'runtime': args.runtime, 'registry': args.registry, 'cases': len(cases), 'passed': passed,
              'knownDifference': known, 'failed': failed, 'differences': differences, 'staleDispositions': sorted(unused)}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True)+'\n')
    print(f'{args.runtime}/{args.registry} adapter corpus: {passed} passed, {known} known differences, {failed} failed, {len(unused)} stale dispositions')
    for row in differences:
        print(f"  {row['id']}: {row['actual']}")
    return int(bool(failed or unused))


if __name__ == '__main__':
    sys.exit(main())
