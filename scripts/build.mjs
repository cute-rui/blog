import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.error) {
    console.error(result.error.message);
    process.exit(1);
  }
  if (result.status !== 0) process.exit(result.status ?? 1);
}

run('cargo', ['+stable', 'build', '--release', '--manifest-path', 'md2wx/Cargo.toml']);
mkdirSync('content/posts', { recursive: true });
run('node', ['scripts/lint-content.mjs']);
run('pnpm', ['exec', 'astro', 'build']);
