import { describe, expect, it } from 'vitest';

import { formatJsonPayload, getJsonPayloadInstructions } from './jsonPayloadFormatting';

describe('formatJsonPayload', () => {
  it('unpacks JSON objects in input and output text content', () => {
    const payload = JSON.stringify({
      input: [
        {
          role: 'user',
          content: [
            {
              text: JSON.stringify({
                locale: 'fr-CA',
                textUnitsToTranslate: [{ tmTextUnitId: 422200, source: 'No actions found.' }],
              }),
              type: 'input_text',
            },
          ],
        },
      ],
      output: [
        {
          content: [
            {
              type: 'output_text',
              text: JSON.stringify({
                targets: [{ tmTextUnitId: 422200, target: 'Aucune action trouvée.' }],
              }),
            },
          ],
        },
      ],
    });

    expect(formatJsonPayload(payload)).toBe(
      JSON.stringify(
        {
          input: [
            {
              role: 'user',
              content: [
                {
                  text: {
                    locale: 'fr-CA',
                    textUnitsToTranslate: [{ tmTextUnitId: 422200, source: 'No actions found.' }],
                  },
                  type: 'input_text',
                },
              ],
            },
          ],
          output: [
            {
              content: [
                {
                  type: 'output_text',
                  text: {
                    targets: [{ tmTextUnitId: 422200, target: 'Aucune action trouvée.' }],
                  },
                },
              ],
            },
          ],
        },
        null,
        2,
      ),
    );
  });

  it('unpacks JSON arrays in structured text content', () => {
    const payload = JSON.stringify({
      content: [{ type: 'output_text', text: '[{"target":"Enregistrer"}]' }],
    });

    expect(formatJsonPayload(payload)).toBe(
      JSON.stringify(
        { content: [{ type: 'output_text', text: [{ target: 'Enregistrer' }] }] },
        null,
        2,
      ),
    );
  });

  it('leaves JSON-looking strings in other fields encoded', () => {
    const payload = JSON.stringify({
      source: '{"example":"literal UI copy"}',
      content: [{ type: 'tool_result', text: '{"result":"keep as text"}' }],
    });

    expect(formatJsonPayload(payload)).toBe(
      JSON.stringify(
        {
          source: '{"example":"literal UI copy"}',
          content: [{ type: 'tool_result', text: '{"result":"keep as text"}' }],
        },
        null,
        2,
      ),
    );
  });

  it('leaves non-container and invalid structured text unchanged', () => {
    const payload = JSON.stringify({
      content: [
        { type: 'input_text', text: '"plain string"' },
        { type: 'output_text', text: '{not valid JSON}' },
      ],
    });

    expect(formatJsonPayload(payload)).toBe(
      JSON.stringify(
        {
          content: [
            { type: 'input_text', text: '"plain string"' },
            { type: 'output_text', text: '{not valid JSON}' },
          ],
        },
        null,
        2,
      ),
    );
  });

  it('returns a non-JSON payload unchanged', () => {
    expect(formatJsonPayload('provider returned plain text')).toBe('provider returned plain text');
  });
});

describe('getJsonPayloadInstructions', () => {
  it('returns top-level instructions with their line breaks intact', () => {
    const instructions = 'You are a professional translator.\n\n**Input:**\nJSON source text.';

    expect(getJsonPayloadInstructions(JSON.stringify({ instructions, input: [] }))).toBe(
      instructions,
    );
  });

  it('ignores nested, empty, and invalid instructions payloads', () => {
    expect(
      getJsonPayloadInstructions(JSON.stringify({ request: { instructions: 'nested' } })),
    ).toBe(null);
    expect(getJsonPayloadInstructions(JSON.stringify({ instructions: '   ' }))).toBe(null);
    expect(getJsonPayloadInstructions('not JSON')).toBe(null);
  });
});
