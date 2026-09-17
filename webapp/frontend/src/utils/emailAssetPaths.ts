export function emailCompanionPaths(bodyPath: string): string[] {
  if (!bodyPath.endsWith('_body.mdx')) return [];
  return ['subject', 'preheader'].map((part) => bodyPath.replace(/_body\.mdx$/, `_${part}.mdx`));
}
