import { describe, expect, it } from 'vitest';

import {
  buildMf2TermBindingManifest,
  extractMf2TermRequirements,
  mf2RuntimeVariableNamesForUsage,
  requirementsForTermOptions,
  uniqueMf2TermArguments,
} from './mf2TermRequirements';

const ROMANCE_COMPACT_BASE_REQUIREMENTS = [
  'partOfSpeech=noun',
  'gender',
  'number',
  'forms.bare.singular',
  'forms.bare.plural',
];

describe('mf2TermRequirements', () => {
  it('extracts term usages in source order with spans and quoted values', () => {
    const message =
      'Deleted {$item :term article=definite count=$count} from {$place :term article="indefinite"}.';

    const usages = extractMf2TermRequirements(message, 'fr');

    expect(usages).toHaveLength(2);
    expect(usages[0]).toMatchObject({
      argument: 'item',
      expression: '{$item :term article=definite count=$count}',
      options: { article: 'definite', count: '$count' },
      start: 8,
      end: 51,
    });
    expect(usages[1]?.argument).toBe('place');
    expect(usages[1]?.options).toEqual({ article: 'indefinite' });
  });

  it('expands French article plus count requirements', () => {
    expect(requirementsForTermOptions({ article: 'definite', count: '$count' }, 'fr-FR')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'elision',
      'forms.definite.singular',
      'forms.definite.plural',
      'forms.count.one',
      'forms.count.other',
    ]);
  });

  it('uses closed-world form requirements for German article and case selection', () => {
    expect(
      requirementsForTermOptions(
        { article: 'definite', case: 'accusative', count: '$count' },
        'de',
      ),
    ).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'forms.definite.accusative.singular',
      'forms.definite.accusative.plural',
      'forms.count.one',
      'forms.count.other',
    ]);
    expect(
      requirementsForTermOptions(
        { article: 'indefinite', case: 'nominative', number: 'singular' },
        'de',
      ),
    ).toEqual(['partOfSpeech=noun', 'gender', 'number', 'forms.indefinite.nominative.singular']);
  });

  it('uses closed-world form requirements for Slavic case selection', () => {
    expect(requirementsForTermOptions({ case: 'accusative', count: '$count' }, 'sr')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'forms.accusative.singular',
      'forms.accusative.plural',
      'forms.count.one',
      'forms.count.other',
    ]);
    expect(requirementsForTermOptions({ case: 'prepositional', count: '$count' }, 'ru-RU')).toEqual(
      [
        'partOfSpeech=noun',
        'gender',
        'number',
        'forms.prepositional.singular',
        'forms.prepositional.plural',
        'forms.count.one',
        'forms.count.other',
      ],
    );
  });

  it('uses explicit form requirements for Semitic and Indic selection', () => {
    expect(
      requirementsForTermOptions(
        { definiteness: 'construct', case: 'genitive', number: 'dual' },
        'ar',
      ),
    ).toEqual(['partOfSpeech=noun', 'gender', 'number', 'forms.construct.genitive.dual']);
    expect(
      requirementsForTermOptions({ definiteness: 'construct', number: 'plural' }, 'he'),
    ).toEqual(['partOfSpeech=noun', 'gender', 'number', 'forms.construct.plural']);
    expect(requirementsForTermOptions({ case: 'sociative', count: '$count' }, 'ml')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'forms.sociative.singular',
      'forms.sociative.plural',
      'forms.count.one',
      'forms.count.other',
    ]);
    expect(requirementsForTermOptions({ case: 'vocative', number: 'plural' }, 'ml-IN')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'forms.vocative.plural',
    ]);
  });

  it('uses explicit form requirements for Nordic genitive and definiteness selection', () => {
    expect(
      requirementsForTermOptions(
        { definiteness: 'definite', case: 'genitive', count: '$count' },
        'sv',
      ),
    ).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'forms.definite.genitive.singular',
      'forms.definite.genitive.plural',
      'forms.count.one',
      'forms.count.other',
    ]);
    expect(
      requirementsForTermOptions(
        { definiteness: 'indefinite', case: 'nominative', number: 'plural' },
        'da-DK',
      ),
    ).toEqual(['partOfSpeech=noun', 'gender', 'number', 'forms.indefinite.nominative.plural']);
  });

  it('uses compact metadata requirements for Spanish article composition', () => {
    expect(requirementsForTermOptions({ article: 'definite', count: '$count' }, 'es')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'stress',
      'forms.bare.singular',
      'forms.bare.plural',
    ]);
  });

  it('uses compact metadata requirements for Italian article-class composition', () => {
    expect(requirementsForTermOptions({ article: 'definite', count: '$count' }, 'it')).toEqual([
      'partOfSpeech=noun',
      'gender',
      'number',
      'articleClass',
      'forms.bare.singular',
      'forms.bare.plural',
    ]);
  });

  it('uses compact metadata requirements for Portuguese article and preposition composition', () => {
    const portugueseOptions: Record<string, string>[] = [
      { article: 'definite', count: '$count' },
      { article: 'indefinite', count: '$count' },
      { preposition: 'de', article: 'definite', count: '$count' },
      { preposition: 'em', article: 'definite', count: '$count' },
      { preposition: 'em', article: 'indefinite', count: '$count' },
      { preposition: 'por', article: 'definite', count: '$count' },
    ];
    for (const options of portugueseOptions) {
      expect(requirementsForTermOptions(options, 'pt-BR')).toEqual(
        ROMANCE_COMPACT_BASE_REQUIREMENTS,
      );
    }
  });

  it('uses Turkish suffix metadata instead of gender and count forms', () => {
    expect(requirementsForTermOptions({ case: 'accusative', count: '$count' }, 'tr')).toEqual([
      'partOfSpeech=noun',
      'number',
      'turkishSuffix.vowelEnd',
      'turkishSuffix.frontVowel',
      'turkishSuffix.roundedVowel',
      'turkishSuffix.hardConsonant',
      'forms.bare.singular',
    ]);
  });

  it('flags runtime term forms for profile-only and metadata-only locales', () => {
    expect(
      requirementsForTermOptions({ definiteness: 'definite', count: '$count' }, 'nb-NO'),
    ).toEqual(['unsupported-locale-runtime-term-inflection']);

    expect(
      extractMf2TermRequirements('Deleted {$item :term number=plural}.', 'zh-Hant')[0]
        ?.requirements,
    ).toEqual(['unsupported-locale-runtime-term-inflection']);

    expect(requirementsForTermOptions({}, 'ja')).not.toContain(
      'unsupported-locale-runtime-term-inflection',
    );
  });

  it('maps Hindi pronoun agreement to the related term argument', () => {
    const message =
      '{$owner :term person=first case=genitive count=$ownerCount agreeWith=$item agreeWithCount=$itemCount} {$item :term case=direct count=$itemCount}.';

    const usages = extractMf2TermRequirements(message, 'hi');

    expect(usages[0]).toMatchObject({
      argument: 'owner',
      bindingArguments: ['item'],
      relatedArgument: 'item',
      requirements: [
        'hindiPronoun.person',
        'hindiPronoun.case',
        'hindiPronoun.number',
        'agreeWith.gender',
        'agreeWith.count',
      ],
    });
    expect(usages[1]).toMatchObject({
      argument: 'item',
      bindingArguments: ['item'],
    });
    expect(uniqueMf2TermArguments(usages)).toEqual(['item']);
    expect(mf2RuntimeVariableNamesForUsage(usages[0])).toEqual(['ownerCount', 'itemCount']);

    expect(
      buildMf2TermBindingManifest('inventory.owner', 'hi', [{ label: 'Target', message, usages }])
        .argumentTerms,
    ).toEqual({
      'inventory.owner.target': {
        item: [],
      },
    });
  });

  it('rejects unsupported and conflicting term options', () => {
    expect(() => extractMf2TermRequirements('{$item :term role=source}', 'fr')).toThrow(
      'Unsupported term option: role',
    );
    expect(() =>
      extractMf2TermRequirements('{$item :term article=definite article=indefinite}', 'fr'),
    ).toThrow('Duplicate term option: article');
    expect(() =>
      extractMf2TermRequirements('{$item :term number=dual count=$count}', 'ar'),
    ).toThrow('Number option cannot be combined with count option');
    expect(() =>
      extractMf2TermRequirements('{$item :term preposition=por article=indefinite}', 'pt'),
    ).toThrow('Unsupported preposition/article combination: por + indefinite');
    expect(() =>
      extractMf2TermRequirements('{$owner :term person=fourth case=genitive}', 'hi'),
    ).toThrow('Unsupported Hindi pronoun person option: fourth');
    expect(() =>
      extractMf2TermRequirements('{$owner :term person=first case=genitive agreeWith=item}', 'hi'),
    ).toThrow('Hindi pronoun agreeWith option must reference a variable: item');
  });

  it('builds an unresolved term binding manifest with stable source and target message ids', () => {
    const source = 'Deleted {$item :term article=definite count=$count}.';
    const target = 'Supprimé {$item :term article=definite count=$count}.';

    expect(
      buildMf2TermBindingManifest('checkout.pay', 'fr-FR', [
        { label: 'Source', message: source, usages: extractMf2TermRequirements(source, 'fr-FR') },
        { label: 'Target', message: target, usages: extractMf2TermRequirements(target, 'fr-FR') },
      ]),
    ).toEqual({
      schema: 'mojito-mf2-inflection/message-term-binding-manifest/v0',
      locale: 'fr-FR',
      messages: {
        'checkout.pay.source': source,
        'checkout.pay.target': target,
      },
      argumentTerms: {
        'checkout.pay.source': { item: [] },
        'checkout.pay.target': { item: [] },
      },
    });
  });

  it('applies explicit term ids to all matching arguments and de-duplicates repeated usages', () => {
    const message =
      'Moved {$item :term article=definite} from {$place :term article=indefinite} to {$item :term article=definite}.';

    const manifest = buildMf2TermBindingManifest(
      'inventory.move',
      'fr-FR',
      [{ label: 'Source', message, usages: extractMf2TermRequirements(message, 'fr-FR') }],
      {
        termIdsByArgument: {
          item: ['item.iron_sword', 'item.iron_sword'],
          place: ['place.armory'],
        },
      },
    );

    expect(manifest.argumentTerms).toEqual({
      'inventory.move.source': {
        item: ['item.iron_sword'],
        place: ['place.armory'],
      },
    });
  });

  it('lets message-specific term ids override shared argument term ids', () => {
    const message = 'Deleted {$item :term article=definite}.';

    const manifest = buildMf2TermBindingManifest(
      'checkout.pay',
      'fr-FR',
      [{ label: 'Target', message, usages: extractMf2TermRequirements(message, 'fr-FR') }],
      {
        termIdsByArgument: { item: ['item.shared'] },
        termIdsByMessageId: {
          'checkout.pay.target': { item: ['item.target'] },
        },
      },
    );

    expect(manifest.argumentTerms['checkout.pay.target']?.item).toEqual(['item.target']);
  });

  it('keeps unique term arguments in first-use order', () => {
    const usages = extractMf2TermRequirements(
      '{$place :term article=definite} contains {$item :term article=indefinite} and {$place :term article=definite}.',
      'fr-FR',
    );

    expect(uniqueMf2TermArguments(usages)).toEqual(['place', 'item']);
  });

  it('rejects invalid binding manifest inputs before calling the backend', () => {
    const usages = extractMf2TermRequirements('{$item :term article=definite}.', 'fr-FR');

    expect(() => buildMf2TermBindingManifest(' ', 'fr-FR', [])).toThrow(
      'MF2 term binding manifest message id must not be blank',
    );
    expect(() =>
      buildMf2TermBindingManifest('checkout.pay', 'fr-FR', [
        { label: 'Current target', message: '', usages },
      ]),
    ).toThrow('Unsupported MF2 term binding manifest group suffix: current target');
    expect(() =>
      buildMf2TermBindingManifest(
        'checkout.pay',
        'fr-FR',
        [{ label: 'Source', message: '', usages }],
        { termIdsByArgument: { item: [''] } },
      ),
    ).toThrow('MF2 term binding manifest term id must not be blank');
  });
});
