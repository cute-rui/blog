import { existsSync, readFileSync, appendFileSync } from 'node:fs';
import path from 'node:path';
import { repoRoot } from './lib/content.mjs';
import { renderSummary } from './lib/summary.mjs';

const root = repoRoot();
const indexPath = path.join(root, 'dist', 'wx', 'index.json');
if (!existsSync(indexPath)) {
  console.error('dist/wx/index.json 不存在，站点构建没有生成公众号清单。');
  process.exit(1);
}
const index = JSON.parse(readFileSync(indexPath, 'utf8'));
const planPath = path.join(root, '.publish', 'plan.json');
const plan = existsSync(planPath) ? JSON.parse(readFileSync(planPath, 'utf8')) : null;
const summary = renderSummary(index, plan);
const destination = process.env.GITHUB_STEP_SUMMARY;
if (destination) appendFileSync(destination, `${summary}\n`);
console.log(summary);
