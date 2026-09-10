#!/usr/bin/env python3
"""Emit compact direction data for supported plural locales from pinned CLDR.

Explicit scripts always win. Region overrides are retained when their likely
script differs in direction from the default language. Unknown languages or
scripts remain unknown; callers must keep conservative isolation for them.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination-root', type=Path, required=True)
    parser.add_argument('--plural-data', type=Path, required=True)
    args = parser.parse_args()
    ref = (ROOT / 'cldr/pinned-ref.txt').read_text().strip()
    inputs = {}
    def read(path):
        url = f'https://raw.githubusercontent.com/unicode-org/cldr-json/{ref}/cldr-json/cldr-core/{path}'
        with urllib.request.urlopen(url) as response:
            data = response.read()
        inputs[path] = hashlib.sha256(data).hexdigest()
        return json.loads(data)
    metadata = read('scriptMetadata.json')['scriptMetadata']
    likely = read('supplemental/likelySubtags.json')['supplemental']['likelySubtags']
    plurals = json.loads(args.plural_data.read_text())
    languages = {name.split('-')[0] for kind in ['cardinal', 'ordinal'] for name in plurals[kind]['locales']}
    scripts = {name: ('RTL' if data['rtl'] == 'YES' else 'LTR') for name, data in metadata.items() if data.get('rtl') in {'YES', 'NO'}}
    directions = {lang: scripts[likely[lang].split('-')[1]] for lang in languages if lang in likely and likely[lang].split('-')[1] in scripts}
    overrides = {}
    for key, value in likely.items():
        parts = key.split('-')
        if len(parts) == 2 and parts[0] in directions and (len(parts[1]) == 2 or parts[1].isdigit()):
            direction = scripts.get(value.split('-')[1])
            if direction and direction != directions[parts[0]]:
                overrides[key] = direction
    tables = {}
    for direction in ['LTR', 'RTL']:
        for label, source in [('SCRIPTS', scripts), ('LANGUAGES', directions), ('REGION_OVERRIDES', overrides)]:
            tables[f'{direction}_{label}'] = ' ' + ' '.join(sorted(key for key, val in source.items() if val == direction)) + ' '
    report = {'cldrRef': ref, 'inputSha256': inputs, 'supportedLanguages': sorted(languages), 'tables': tables}
    target = args.destination_root
    def write(path, content):
        output = target / path
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(content)
    write('cldr/generated/bidi/locale_directions.json', json.dumps(report, indent=2, sort_keys=True)+'\n')
    notice = 'Generated from pinned Unicode CLDR by mf2/cldr/update_generated.sh; do not edit by hand.'
    literals = {key: json.dumps(value) for key, value in tables.items()}
    write('python/src/mojito_mf2/_locale_direction_data.py', '# '+notice+'\n'+''.join(f'{key} = {value}\n' for key,value in literals.items()))
    write('javascript/src/locale_direction_data.js', '// '+notice+'\n'+''.join(f'export const {key} = {value};\n' for key,value in literals.items()))
    write('rust/mojito-mf2/src/locale_direction_data.rs', '// '+notice+'\n'+''.join(f'pub(crate) const {key}: &str =\n    {value};\n' for key,value in literals.items()))
    write('swift/MessageFormat2/Sources/MessageFormat2/LocaleDirectionData.swift', '// '+notice+'\nenum LocaleDirectionData {\n'+''.join(f'    static let {key} = {value}\n' for key,value in literals.items())+'}\n')
    write('java/src/main/java/com/box/l10n/mojito/mf2/LocaleDirectionData.java', '// '+notice+'\npackage com.box.l10n.mojito.mf2;\n\nfinal class LocaleDirectionData {\n'+''.join(f'  static final String {key} = {value};\n' for key,value in literals.items())+'}\n')
    write('kotlin/src/main/kotlin/com/box/l10n/mojito/mf2/LocaleDirectionData.kt', '// '+notice+'\npackage com.box.l10n.mojito.mf2\n\ninternal object LocaleDirectionData {\n'+''.join(f'    const val {key} = {value}\n' for key,value in literals.items())+'}\n')
    write('go/locale_direction_data.go', '// '+notice+'\npackage mf2\n\n'+''.join(f'const localeDirection{key} = {value}\n' for key,value in literals.items()))
    write('php/src/LocaleDirectionData.php', '<?php\n// '+notice+'\ndeclare(strict_types=1);\n\nnamespace Mojito\\MessageFormat2\\Internal;\n\nfinal class LocaleDirectionData\n{\n'+''.join(f'    public const {key} = {value};\n' for key,value in literals.items())+'}\n')


if __name__ == '__main__':
    main()
