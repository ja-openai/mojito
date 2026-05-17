import './image-upload-optimization-toggle.css';

type Props = {
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  label?: string;
  helperText?: string;
};

export function ImageUploadOptimizationToggle({
  checked,
  disabled = false,
  onChange,
  label = 'Optimize large images before upload',
  helperText = 'Recommended for large files. Video and PDF files are uploaded as-is.',
}: Props) {
  return (
    <label
      className={`image-upload-optimization${disabled ? ' image-upload-optimization--disabled' : ''}`}
    >
      <input
        type="checkbox"
        className="image-upload-optimization__checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span className="image-upload-optimization__copy">
        <span className="image-upload-optimization__label">{label}</span>
        {helperText ? <span className="image-upload-optimization__hint">{helperText}</span> : null}
      </span>
    </label>
  );
}
