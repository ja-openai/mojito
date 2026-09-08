import { AutoTextarea } from '../../components/AutoTextarea';

type DecisionNote = {
  id: string;
  caption: string;
};

type Props = {
  notes: DecisionNote[];
  canEdit: boolean;
  onAdd: () => void;
  onChange: (id: string, caption: string) => void;
  onRemove: (id: string) => void;
};

function getSupportingLinks(text: string): string[] {
  const links = new Set<string>();
  for (const match of text.matchAll(/\bhttps?:\/\/[^\s<>"'`]+/gi)) {
    let candidate = match[0].replace(/[.,;:!?]+$/, '');
    for (const [opening, closing] of [
      ['(', ')'],
      ['[', ']'],
      ['{', '}'],
    ]) {
      while (
        candidate.endsWith(closing) &&
        candidate.split(closing).length > candidate.split(opening).length
      ) {
        candidate = candidate.slice(0, -1);
      }
    }
    try {
      const url = new URL(candidate);
      if (url.protocol === 'http:' || url.protocol === 'https:') {
        links.add(url.href);
      }
    } catch {
      // Leave incomplete URLs as plain note text while the user is typing.
    }
  }
  return [...links];
}

export function GlossaryDecisionNotes({ notes, canEdit, onAdd, onChange, onRemove }: Props) {
  return (
    <section
      className="glossary-term-admin__section glossary-term-admin__decision-notes"
      aria-label="Decision notes"
    >
      <div className="glossary-term-admin__section-header">
        <div>
          <h4 className="glossary-term-admin__section-title">Decision notes</h4>
          <p className="glossary-term-admin__section-description">
            Explain why this term was added or changed. Paste links to supporting documents or Slack
            conversations alongside your notes.
          </p>
        </div>
        {canEdit ? (
          <button type="button" className="settings-button settings-button--ghost" onClick={onAdd}>
            Add decision note
          </button>
        ) : null}
      </div>
      {notes.length === 0 ? (
        <p className="settings-hint">No decision notes yet.</p>
      ) : (
        <div className="glossary-term-admin__reference-list">
          {notes.map((note, index) => {
            const links = getSupportingLinks(note.caption);
            const label = `Decision note ${index + 1}`;
            return (
              <div key={note.id} className="glossary-term-admin__reference-card">
                <div className="glossary-term-admin__reference-header">
                  <label className="settings-field__label" htmlFor={`decision-note-${note.id}`}>
                    {label}
                  </label>
                  {canEdit ? (
                    <button
                      type="button"
                      className="settings-button settings-button--ghost"
                      aria-label={`Remove decision note ${index + 1}`}
                      onClick={() => onRemove(note.id)}
                    >
                      Remove
                    </button>
                  ) : null}
                </div>
                {canEdit ? (
                  <AutoTextarea
                    id={`decision-note-${note.id}`}
                    className="settings-input"
                    value={note.caption}
                    onChange={(event) => onChange(note.id, event.target.value)}
                    placeholder="Why was this term added or changed? Include supporting links."
                    maxLength={1024}
                    minRows={3}
                  />
                ) : (
                  <p className="glossary-term-admin__decision-note-text">{note.caption}</p>
                )}
                {links.length > 0 ? (
                  <div className="glossary-term-admin__decision-note-links">
                    <span className="settings-field__label">Supporting links</span>
                    {links.map((href) => (
                      <a key={href} href={href} target="_blank" rel="noopener noreferrer">
                        {href}
                      </a>
                    ))}
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
