import type { APIRoute } from 'astro';
import { siteConfig } from '../site.config';
import { getPublishedPosts, postUrl } from '../lib/posts';

function escapeXml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

export const GET: APIRoute = async ({ site }) => {
  const posts = await getPublishedPosts();
  const origin = site ?? new URL(siteConfig.url);
  const feedUrl = new URL('/atom.xml', origin).href;
  const homeUrl = new URL('/', origin).href;
  const stamp = (date: Date) => date.toISOString();
  const updated = posts.reduce((latest, post) => {
    const at = post.data.updatedDate ?? post.data.pubDate;
    return at > latest ? at : latest;
  }, new Date(0));

  const entries = posts
    .map((post) => {
      const href = new URL(postUrl(post), origin).href;
      const published = post.data.pubDate;
      const updatedAt = post.data.updatedDate ?? published;
      const categories = post.data.tags
        .map((tag) => `    <category term="${escapeXml(tag)}" />`)
        .join('\n');
      return `  <entry>
    <id>${escapeXml(href)}</id>
    <title>${escapeXml(post.data.title)}</title>
    <link href="${escapeXml(href)}" rel="alternate" type="text/html" />
    <published>${stamp(published)}</published>
    <updated>${stamp(updatedAt)}</updated>
    <summary>${escapeXml(post.data.description)}</summary>
    <author>
      <name>${escapeXml(siteConfig.author)}</name>
    </author>${categories ? `\n${categories}` : ''}
  </entry>`;
    })
    .join('\n');

  const xml = `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="${escapeXml(siteConfig.lang)}">
  <id>${escapeXml(homeUrl)}</id>
  <title>${escapeXml(siteConfig.title)}</title>
  <subtitle>${escapeXml(siteConfig.description)}</subtitle>
  <link href="${escapeXml(feedUrl)}" rel="self" type="application/atom+xml" />
  <link href="${escapeXml(homeUrl)}" rel="alternate" type="text/html" />
  <updated>${stamp(posts.length ? updated : new Date())}</updated>
  <author>
    <name>${escapeXml(siteConfig.author)}</name>
  </author>
${entries}
</feed>
`;

  return new Response(xml, {
    headers: {
      'Content-Type': 'application/atom+xml; charset=utf-8',
    },
  });
};
