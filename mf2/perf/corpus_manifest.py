"""Print immutable input identity alongside a benchmark run."""
import hashlib
import json
from pathlib import Path
import sys

iterations, warmup = int(sys.argv[3]), int(sys.argv[4])
if iterations <= 0 or warmup < 0:
    raise SystemExit('Iterations must be positive and warmup must be non-negative')

root = Path(sys.argv[1]).resolve()
files = sorted(root.glob('*.json'))
if not files:
    raise SystemExit('No benchmark fixture files')
corpus = hashlib.sha256()
cases = 0
for path in files:
    data = path.read_bytes()
    corpus.update(path.name.encode() + b'\0' + data + b'\0')
    cases += len(json.loads(data).get('formatCases', []))
print(json.dumps({'fixtureRoot': str(root), 'corpusSha256': corpus.hexdigest(),
                  'sourceCount': len(files), 'formatCaseCount': cases,
                  'mode': sys.argv[2], 'iterations': iterations, 'warmup': warmup,
                  'registry': 'Mojito portable; ICU reference registries are separate comparisons',
                  'measurement': 'process RSS includes startup and warmup; hot-loop timings exclude both'}))
