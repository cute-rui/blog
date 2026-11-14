import type { MarkdownHeading } from 'astro';

// GFM injects <h2 id="footnote-label">Footnotes</h2> into render().headings.
export function tocHeadings(headings: MarkdownHeading[]): MarkdownHeading[] {
  return headings.filter(
    (heading) =>
      (heading.depth === 2 || heading.depth === 3) &&
      heading.slug !== 'footnote-label' &&
      heading.text !== 'Footnotes' &&
      heading.text !== '脚注',
  );
}
