import path from 'node:path';
import { repoRoot } from './lib/content.mjs';
import { lintPosts } from './lib/lint.mjs';

const root = repoRoot();
const postsDir = path.join(root, 'content', 'posts');
const result = lintPosts(postsDir, { repoRoot: root });
for (const warning of result.warnings) console.warn(`警告 ${warning.message}`);
if (result.missing) {
  console.log('content/posts 不存在，没有文章可校验。内容仓库检出到 content/ 之后会在这里检查。');
  process.exit(0);
}
if (result.errors.length) {
  for (const error of result.errors) console.error(error.message);
  process.exit(1);
}
console.log(`内容校验通过，${result.posts.length} 篇。`);
