import { repoRoot } from './lib/content.mjs';
import { syncWechatDrafts } from './lib/wechat-sync.mjs';

await syncWechatDrafts({ repoRoot: repoRoot() });
