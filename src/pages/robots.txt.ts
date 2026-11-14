import type { APIRoute } from 'astro';
import { siteConfig } from '../site.config';

export const GET: APIRoute = () =>
  new Response(`User-agent: *\nAllow: /\nDisallow: /wx/\n\nSitemap: ${siteConfig.url}/sitemap-index.xml\n`, {
    headers: { 'Content-Type': 'text/plain' },
  });
