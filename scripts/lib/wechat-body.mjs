export function applyWechatBody(shared, overrides) {
  const list = Array.isArray(overrides) ? overrides : [];
  if (list.length === 0) return { text: shared, stale: [] };
  if (list.length === 1 && list[0] && list[0].find === '') {
    return { text: String(list[0].replace ?? ''), stale: [] };
  }
  const hits = [];
  const stale = [];
  let cursor = 0;
  for (const item of list) {
    const find = item && typeof item.find === 'string' ? item.find : null;
    if (find == null || find === '') {
      stale.push(item);
      continue;
    }
    const at = shared.indexOf(find, cursor);
    if (at < 0) {
      stale.push(item);
      continue;
    }
    const end = at + find.length;
    if (hits.length > 0 && at < hits[hits.length - 1].end) {
      stale.push(item);
      continue;
    }
    hits.push({ start: at, end, replace: String(item.replace ?? '') });
    cursor = end;
  }
  let text = '';
  let position = 0;
  for (const hit of hits) {
    text += shared.slice(position, hit.start);
    text += hit.replace;
    position = hit.end;
  }
  text += shared.slice(position);
  return { text, stale };
}
