import './shortcut-bar.css';

type Shortcut = {
  keys: readonly string[];
  label: string;
  disabled?: boolean;
};

export function ShortcutBar({
  shortcuts,
  onOpenShortcuts,
  ariaLabel = 'Keyboard shortcuts',
}: {
  shortcuts: readonly Shortcut[];
  onOpenShortcuts?: () => void;
  ariaLabel?: string;
}) {
  return (
    <div className="shortcut-bar" role="region" aria-label={ariaLabel}>
      {shortcuts.map(({ keys, label, disabled }) => (
        <span
          key={`${keys.join('+')}:${label}`}
          className="shortcut-bar__item"
          aria-disabled={disabled || undefined}
        >
          {keys.map((key) => (
            <kbd key={key}>{key}</kbd>
          ))}
          <span>{label}</span>
        </span>
      ))}
      {onOpenShortcuts ? (
        <button type="button" className="shortcut-bar__button" onClick={onOpenShortcuts}>
          <kbd>/</kbd>
          <span>Help</span>
        </button>
      ) : null}
    </div>
  );
}
