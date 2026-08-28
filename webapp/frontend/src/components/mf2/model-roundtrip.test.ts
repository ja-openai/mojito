import { parseToModel } from '@mojito-mf2/core';
import { describe, expect, it } from 'vitest';

import { cloneModel, parseMf2, printModel } from './model';

type SourceToModelFixture = {
  name: string;
  source: string;
};

const fixtureModules = import.meta.glob<SourceToModelFixture>(
  '../../../../../mf2/conformance/fixtures/source-to-model/*.json',
  { eager: true, import: 'default' },
);
const sourceToModelFixtures = Object.values(fixtureModules).sort((left, right) =>
  left.name.localeCompare(right.name),
);

function expectSemanticRoundTrip(source: string) {
  const original = parseToModel(source);
  expect(original.diagnostics).toEqual([]);
  expect(original.model).toBeTruthy();

  const editorModel = parseMf2(source, {}, 'en', {
    includeRuntimeDiagnostics: false,
  }).model;
  expect(editorModel).toBeTruthy();
  if (!editorModel) throw new Error('Expected a valid editor model');

  const printed = printModel(cloneModel(editorModel));
  const reparsed = parseToModel(printed);
  expect(reparsed.diagnostics).toEqual([]);
  expect(reparsed.model).toEqual(original.model);
  return printed;
}

describe('MF2 editor model semantic round trips', () => {
  it('preserves a quoted literal star separately from a wildcard key', () => {
    const printed = expectSemanticRoundTrip(`.input {$status :string}
.match $status
|active| {{Quoted active}}
|*| {{Literal star}}
* {{Other}}`);

    expect(printed).toContain('|*| {{Literal star}}');
    expect(printed).toContain('* {{Other}}');
  });

  it('preserves boolean attributes without converting them to empty literals', () => {
    const printed = expectSemanticRoundTrip('Hello, {$name @title=|user| @private}!');

    expect(printed).toContain('@private');
    expect(printed).not.toContain('@private=');
  });

  it('preserves options on closing markup', () => {
    const printed = expectSemanticRoundTrip(
      'Tap {#link href=$profileUrl}profile{/link href=|profile|}.',
    );

    expect(printed).toContain('{/link href=|profile|}');
  });

  it.each(sourceToModelFixtures)('round-trips $name without semantic changes', ({ source }) => {
    expectSemanticRoundTrip(source);
  });
});
