import { Pill } from './Pill';

export function AiReviewConfidence({ value }: { value?: number }) {
  if (value == null || !Number.isInteger(value) || value < 0 || value > 100) return null;

  return (
    <Pill
      className="ai-chat-review__confidence"
      aria-label={`Model confidence: ${value} out of 100`}
    >
      {value}
    </Pill>
  );
}
