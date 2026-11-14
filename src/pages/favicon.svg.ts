import type { APIRoute } from 'astro';
import { siteConfig } from '../site.config';

// Same seal mark as the header, so the tab icon follows siteConfig.seal.
export const GET: APIRoute = () => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
  <g transform="rotate(-4 32 32)">
    <rect x="4" y="4" width="56" height="56" rx="11" fill="#2a5a86"/>
    <rect x="9.5" y="9.5" width="45" height="45" rx="7" fill="none" stroke="#f2f6fa" stroke-opacity="0.55"/>
    <text x="32" y="44.5" text-anchor="middle" fill="#f2f6fa" font-size="34" font-weight="700" font-family="'Source Serif 4', 'Noto Serif SC', Georgia, 'Songti SC', serif">${siteConfig.seal}</text>
  </g>
</svg>
`;
  return new Response(svg, {
    headers: { 'Content-Type': 'image/svg+xml; charset=utf-8' },
  });
};
