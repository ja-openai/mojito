import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ContentCmsSettingsDirectoryPreview } from './ContentCmsSettingsDirectoryPreview';

const copyAuthoringTechnicalInternals =
  /Admin tools|CMS|Mojito|schema|mapping|variant|publish|repository|package|snapshot|JSON/i;

function getCopyAuthoringRegion() {
  const copyAuthoringRegion = screen
    .getByRole('heading', { name: 'Copy authoring' })
    .closest('section');
  if (!(copyAuthoringRegion instanceof HTMLElement)) {
    throw new Error('Expected Copy authoring settings region');
  }
  return copyAuthoringRegion;
}

function getSettingsSections() {
  return Array.from(document.querySelectorAll('.settings-page > .settings-card')).filter(
    (section): section is HTMLElement => section instanceof HTMLElement,
  );
}

function getAccessibleCopy(container: HTMLElement) {
  return Array.from(container.querySelectorAll('[aria-label], [title], [aria-describedby]'))
    .flatMap((element) => [
      element.getAttribute('aria-label'),
      element.getAttribute('title'),
      ...(element.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((describedById) => document.getElementById(describedById)?.textContent),
    ])
    .filter((value): value is string => value != null);
}

describe('ContentCmsSettingsDirectoryPreview', () => {
  it('keeps the Product copy settings directory author-facing', () => {
    render(<ContentCmsSettingsDirectoryPreview />);

    expect(screen.getByRole('heading', { name: 'Copy authoring' })).toBeVisible();
    const copyAuthoringRegion = getCopyAuthoringRegion();
    expect(getSettingsSections()[0]).toBe(copyAuthoringRegion);
    const productCopyEntry = screen.getByRole('button', { name: /Product copy/ });
    expect(productCopyEntry).toBeVisible();
    expect(productCopyEntry).toHaveTextContent(
      'Write product copy, translate it, and release approved copy.',
    );
    expect(productCopyEntry).not.toHaveTextContent('hand source text to Mojito');
    expect(copyAuthoringRegion).not.toHaveTextContent(copyAuthoringTechnicalInternals);
    expect(getAccessibleCopy(copyAuthoringRegion)).not.toEqual(
      expect.arrayContaining([expect.stringMatching(copyAuthoringTechnicalInternals)]),
    );
    expect(screen.queryByRole('button', { name: /Admin tools/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Content CMS' })).not.toBeInTheDocument();
  });
});
