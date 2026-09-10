#!/usr/bin/env python3
"""Check bounded parser recovery through each runtime's public bridge."""
import argparse
import hashlib
import json

from check_official import run_bridge


def mutation_sources():
    # The exact 42-character nontermination regression, its truncations, and
    # single-character scanner-boundary mutations. Mutations may be valid MF2;
    # the property is termination with a structured result, not rejection.
    seed = '.input {$x :string} .match $x * {{ok}} {x}'
    sources = {seed[:end] for end in range(len(seed) + 1)}
    for index in range(len(seed) + 1):
        for char in '{}|\\.\n*$':
            sources.add(seed[:index] + char + seed[index:])
    sources.update(seed[:index] + seed[index + 1:] for index in range(len(seed)))
    return sorted(sources)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ['--']:
        args.command.pop(0)
    if not args.command:
        parser.error('Pass a production bridge command after --')
    sources = mutation_sources()
    requests = [{'source': source, 'arguments': {'x': 'ok'}, 'locale': 'en',
                 'bidiIsolation': 'none', 'registry': 'portable'} for source in sources]
    run_bridge(args.command, requests, timeout=args.timeout)
    identity = hashlib.sha256(json.dumps(sources, ensure_ascii=True).encode()).hexdigest()
    print(f'{args.runtime}: {len(sources)} parser progress mutations returned within {args.timeout}s; sha256={identity}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
