import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import sitemap from '@astrojs/sitemap';
import tailwindcss from '@tailwindcss/vite';
import { siteConfig } from './src/site.config.ts';

export default defineConfig({
  site: siteConfig.url,
  // paginate() emits /posts/2 without this; nav and postUrl already use trailing slashes
  trailingSlash: 'always',
  integrations: [mdx(), sitemap()],
  vite: { plugins: [tailwindcss()] },
  image: {
    // Every <Image> and Markdown image gets a srcset + sizes; covers pass explicit width/height to crop.
    layout: 'constrained',
    responsiveStyles: true,
  },
  markdown: {
    shikiConfig: { themes: { light: 'github-light', dark: 'github-dark' }, defaultColor: false },
  },
});
