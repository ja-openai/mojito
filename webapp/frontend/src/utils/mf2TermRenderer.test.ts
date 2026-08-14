import { describe, expect, it } from 'vitest';

import arApprovedPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.json';
import arPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.json';
import daPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.json';
import dePack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.json';
import esPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json';
import hePack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.json';
import hiPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json';
import itPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.json';
import mlApprovedPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.json';
import mlPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.json';
import ptPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.json';
import ruPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json';
import srPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.json';
import svPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json';
import trPack from '../../../../common/src/test/resources/com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.json';
import { Mf2CompiledTermRenderer } from './mf2TermRenderer';

type FixtureRenderCase = {
  count?: string;
  expected: string;
  message: string;
  termId: string;
};

function expectRenderedCases(renderer: Mf2CompiledTermRenderer, cases: FixtureRenderCase[]) {
  for (const renderCase of cases) {
    const variables: Record<string, string> =
      renderCase.count == null ? {} : { count: renderCase.count };
    expect(renderer.renderMessage(renderCase.message, { item: renderCase.termId }, variables)).toBe(
      renderCase.expected,
    );
  }
}

describe('Mf2CompiledTermRenderer', () => {
  it('renders generated Serbian case forms from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(srPack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Obrisano je mačku.',
        message: 'Obrisano je {$item :term case=accusative count=$count}.',
        termId: 'sr.case.mačka',
      },
      {
        count: '2',
        expected: 'Obrisano je mačke.',
        message: 'Obrisano je {$item :term case=accusative count=$count}.',
        termId: 'sr.case.mačka',
      },
      {
        expected: 'Daj mački.',
        message: 'Daj {$item :term case=dative}.',
        termId: 'sr.case.mačka',
      },
      {
        count: '2',
        expected: 'Nema mačaka.',
        message: 'Nema {$item :term case=genitive count=$count}.',
        termId: 'sr.case.mačka',
      },
      {
        expected: 'Zdravo mačko.',
        message: 'Zdravo {$item :term case=vocative}.',
        termId: 'sr.case.mačka',
      },
    ]);
  });

  it('renders generated Russian case forms from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(ruPack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Удалено кошку.',
        message: 'Удалено {$item :term case=accusative count=$count}.',
        termId: 'ru.case.кошка',
      },
      {
        count: '2',
        expected: 'Удалено кошек.',
        message: 'Удалено {$item :term case=accusative count=$count}.',
        termId: 'ru.case.кошка',
      },
      {
        count: '2',
        expected: 'Нет ресторанов.',
        message: 'Нет {$item :term case=genitive count=$count}.',
        termId: 'ru.case.ресторан',
      },
      {
        count: '1',
        expected: 'В аббатстве.',
        message: 'В {$item :term case=prepositional count=$count}.',
        termId: 'ru.case.аббатство',
      },
      {
        count: '2',
        expected: 'В аббатствах.',
        message: 'В {$item :term case=prepositional count=$count}.',
        termId: 'ru.case.аббатство',
      },
    ]);
  });

  it('renders generated Hindi case forms from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(hiPack);

    expect(
      renderer.renderMessage(
        'हटा दिया {$item :term case=direct count=$count}.',
        { item: 'hi.case.अंगारा' },
        { count: '2' },
      ),
    ).toBe('हटा दिया अंगारे.');
    expect(
      renderer.renderMessage(
        'में {$item :term case=oblique count=$count}.',
        { item: 'hi.case.आँख' },
        { count: '2' },
      ),
    ).toBe('में आँखों.');
  });

  it('renders Arabic explicit forms and reports missing cells from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(arPack);
    const approvedRenderer = new Mf2CompiledTermRenderer(arApprovedPack);

    expect(
      renderer.renderMessage(
        'حُذفت {$item :term definiteness=indefinite case=nominative}.',
        { item: 'ar.explicit.mother' },
        {},
      ),
    ).toBe('حُذفت أُمٌّ.');
    expect(
      renderer.renderMessage(
        'اختيرت {$item :term definiteness=construct case=nominative number=dual}.',
        { item: 'ar.explicit.mother' },
        {},
      ),
    ).toBe('اختيرت أُمَّا.');
    expect(() =>
      renderer.renderMessage(
        'مع {$item :term definiteness=construct case=genitive number=dual}.',
        { item: 'ar.explicit.mother' },
        {},
      ),
    ).toThrow('Missing form construct.genitive.dual for term ar.explicit.mother');
    expect(
      approvedRenderer.renderMessage(
        'اختيرت {$item :term definiteness=construct case=genitive number=dual}.',
        { item: 'ar.explicit.message' },
        {},
      ),
    ).toBe('اختيرت رسالتي.');
  });

  it('renders Hebrew construct-state forms and reports missing cells from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(hePack);

    expect(
      renderer.renderMessage(
        'נבחר {$item :term number=plural}.',
        { item: 'he.construct.house' },
        {},
      ),
    ).toBe('נבחר בתים.');
    expect(
      renderer.renderMessage(
        'נבחר {$item :term definiteness=construct number=plural}.',
        { item: 'he.construct.house' },
        {},
      ),
    ).toBe('נבחר בתי.');
    expect(() =>
      renderer.renderMessage(
        'נבחר {$item :term definiteness=construct number=dual}.',
        { item: 'he.construct.house' },
        {},
      ),
    ).toThrow('Missing form construct.dual for term he.construct.house');
  });

  it('renders Malayalam explicit case forms and reports missing cells from Java fixtures', () => {
    const renderer = new Mf2CompiledTermRenderer(mlPack);
    const approvedRenderer = new Mf2CompiledTermRenderer(mlApprovedPack);

    expect(
      renderer.renderMessage(
        'കൂടെ {$item :term case=sociative count=$count}.',
        { item: 'ml.case.disciple' },
        { count: '1' },
      ),
    ).toBe('കൂടെ ശിഷ്യനോട്.');
    expect(
      renderer.renderMessage(
        'കൂടെ {$item :term case=sociative count=$count}.',
        { item: 'ml.case.disciple' },
        { count: '2' },
      ),
    ).toBe('കൂടെ ശിഷ്യന്മാരോട്.');
    expect(() =>
      renderer.renderMessage(
        'വിളി {$item :term case=vocative number=singular}.',
        { item: 'ml.case.disciple' },
        {},
      ),
    ).toThrow('Missing form vocative.singular for term ml.case.disciple');
    expect(
      approvedRenderer.renderMessage(
        'വിളി {$item :term case=vocative number=plural}.',
        { item: 'ml.case.father' },
        {},
      ),
    ).toBe('വിളി പിതാക്കന്മാരേ.');
  });

  it('renders Nordic genitive and definiteness forms from Java fixtures', () => {
    const swedishRenderer = new Mf2CompiledTermRenderer(svPack);
    const danishRenderer = new Mf2CompiledTermRenderer(daPack);

    expectRenderedCases(swedishRenderer, [
      {
        count: '1',
        expected: 'Vald bostaden.',
        message: 'Vald {$item :term definiteness=definite case=nominative count=$count}.',
        termId: 'sv.definiteness.bostad',
      },
      {
        count: '2',
        expected: 'Vald bostädernas.',
        message: 'Vald {$item :term definiteness=definite case=genitive count=$count}.',
        termId: 'sv.definiteness.bostad',
      },
      {
        count: '1',
        expected: 'Vald chassis.',
        message: 'Vald {$item :term definiteness=indefinite case=genitive count=$count}.',
        termId: 'sv.definiteness.chassi',
      },
    ]);
    expectRenderedCases(danishRenderer, [
      {
        count: '1',
        expected: 'Valgt franskmanden.',
        message: 'Valgt {$item :term definiteness=definite case=nominative count=$count}.',
        termId: 'da.definiteness.franskmand',
      },
      {
        count: '2',
        expected: 'Valgt franskmændenes.',
        message: 'Valgt {$item :term definiteness=definite case=genitive count=$count}.',
        termId: 'da.definiteness.franskmand',
      },
      {
        count: '2',
        expected: 'Valgt børnebørns.',
        message: 'Valgt {$item :term definiteness=indefinite case=genitive count=$count}.',
        termId: 'da.definiteness.barnebarn',
      },
    ]);
  });

  it('renders German explicit article/case forms from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(dePack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Gelöscht: die Katze.',
        message: 'Gelöscht: {$item :term article=definite case=accusative count=$count}.',
        termId: 'de.article_case.katze',
      },
      {
        count: '2',
        expected: 'Gelöscht: die Katzen.',
        message: 'Gelöscht: {$item :term article=definite case=accusative count=$count}.',
        termId: 'de.article_case.katze',
      },
      {
        count: '2',
        expected: 'Mit den Mädchen.',
        message: 'Mit {$item :term article=definite case=dative count=$count}.',
        termId: 'de.article_case.maedchen',
      },
      {
        count: '1',
        expected: 'Erstellt: ein 1-Euro-Job.',
        message: 'Erstellt: {$item :term article=indefinite case=nominative count=$count}.',
        termId: 'de.article_case.1_euro_job',
      },
      {
        count: '2',
        expected: 'Erstellt: Mädchen.',
        message: 'Erstellt: {$item :term article=indefinite case=nominative count=$count}.',
        termId: 'de.article_case.maedchen',
      },
    ]);
  });

  it('renders Spanish compact article composition from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(esPack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Has eliminado el agua.',
        message: 'Has eliminado {$item :term article=definite count=$count}.',
        termId: 'item.water',
      },
      {
        count: '2',
        expected: 'Has eliminado las aguas.',
        message: 'Has eliminado {$item :term article=definite count=$count}.',
        termId: 'item.water',
      },
      {
        expected: 'Has encontrado un agua.',
        message: 'Has encontrado {$item :term article=indefinite}.',
        termId: 'item.water',
      },
      {
        count: '1',
        expected: 'Has eliminado la abeja.',
        message: 'Has eliminado {$item :term article=definite count=$count}.',
        termId: 'item.bee',
      },
      {
        count: '2',
        expected: 'Has encontrado unas abejas.',
        message: 'Has encontrado {$item :term article=indefinite count=$count}.',
        termId: 'item.bee',
      },
      {
        count: '1',
        expected: 'Has eliminado el ababol.',
        message: 'Has eliminado {$item :term article=definite count=$count}.',
        termId: 'item.poppy',
      },
      {
        count: '2',
        expected: 'Has encontrado unos ababoles.',
        message: 'Has encontrado {$item :term article=indefinite count=$count}.',
        termId: 'item.poppy',
      },
    ]);
  });

  it('renders Italian compact article composition from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(itPack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Hai eliminato lo gnomo.',
        message: 'Hai eliminato {$item :term article=definite count=$count}.',
        termId: 'item.gnome',
      },
      {
        count: '2',
        expected: 'Hai eliminato gli gnomi.',
        message: 'Hai eliminato {$item :term article=definite count=$count}.',
        termId: 'item.gnome',
      },
      {
        count: '1',
        expected: 'Hai eliminato il libro.',
        message: 'Hai eliminato {$item :term article=definite count=$count}.',
        termId: 'item.book',
      },
      {
        count: '1',
        expected: "Hai trovato un'acqua.",
        message: 'Hai trovato {$item :term article=indefinite count=$count}.',
        termId: 'item.water',
      },
      {
        count: '1',
        expected: "Hai trovato un'ape.",
        message: 'Hai trovato {$item :term article=indefinite count=$count}.',
        termId: 'item.bee',
      },
    ]);
  });

  it('renders Portuguese compact article and preposition composition from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(ptPack);

    expectRenderedCases(renderer, [
      {
        count: '1',
        expected: 'Removido o campo.',
        message: 'Removido {$item :term article=definite count=$count}.',
        termId: 'item.field',
      },
      {
        count: '2',
        expected: 'Removido os campos.',
        message: 'Removido {$item :term article=definite count=$count}.',
        termId: 'item.field',
      },
      {
        count: '2',
        expected: 'Encontrado umas casas.',
        message: 'Encontrado {$item :term article=indefinite count=$count}.',
        termId: 'item.house',
      },
      {
        count: '2',
        expected: 'Removido das casas.',
        message: 'Removido {$item :term preposition=de article=definite count=$count}.',
        termId: 'item.house',
      },
      {
        count: '1',
        expected: 'Disponível num campo.',
        message: 'Disponível {$item :term preposition=em article=indefinite count=$count}.',
        termId: 'item.field',
      },
      {
        count: '2',
        expected: 'Filtrado pelos campos.',
        message: 'Filtrado {$item :term preposition=por article=definite count=$count}.',
        termId: 'item.field',
      },
    ]);
  });

  it('renders Turkish compact suffix composition from the Java fixture', () => {
    const renderer = new Mf2CompiledTermRenderer(trPack);

    expect(
      renderer.renderMessage(
        'Silindi {$item :term case=accusative count=$count}.',
        { item: 'item.car' },
        { count: '1' },
      ),
    ).toBe('Silindi arabayı.');
    expect(
      renderer.renderMessage(
        'Bulundu {$item :term case=locative count=$count}.',
        { item: 'item.school' },
        { count: '2' },
      ),
    ).toBe('Bulundu okullarda.');
    expect(
      renderer.renderMessage(
        'Listelendi {$item :term count=$count}.',
        { item: 'item.rose' },
        { count: '2' },
      ),
    ).toBe('Listelendi güller.');
  });
});
