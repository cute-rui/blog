import { preparePublish } from './lib/prepare-publish.mjs';
import { repoRoot } from './lib/content.mjs';

const plan = preparePublish({
  repoRoot: repoRoot(),
  eventName: process.env.GITHUB_EVENT_NAME ?? '',
  before: process.env.CONTENT_BEFORE ?? '',
  after: process.env.CONTENT_AFTER ?? '',
  targetsInput: process.env.DISPATCH_TARGETS ?? '',
  slugsInput: process.env.DISPATCH_SLUGS ?? '',
});
console.log(JSON.stringify(plan));
