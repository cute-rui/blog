import type { AstroIntegration } from 'astro';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildWxPages } from '../../scripts/lib/wx-pages.mjs';

function repoRoot(): string {
  const fromSource = fileURLToPath(new URL('../..', import.meta.url));
  if (existsSync(path.join(fromSource, 'package.json'))) return fromSource;
  return process.cwd();
}

export function wxPages(): AstroIntegration {
  return {
    name: 'wx-pages',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        await buildWxPages({
          repoRoot: repoRoot(),
          outDir: fileURLToPath(dir),
          log: (message) => logger.info(message),
          warn: (message) => logger.warn(message),
        });
      },
    },
  };
}
