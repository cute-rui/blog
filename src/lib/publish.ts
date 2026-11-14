/** A post stays off the site when `publish.blog` is false. Drafts stay visible in dev. */
export function listedOnBlog(
  data: { draft: boolean; publish?: { blog: boolean } },
  prod: boolean,
): boolean {
  if (data.publish?.blog === false) return false;
  if (prod && data.draft) return false;
  return true;
}
