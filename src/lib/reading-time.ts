export interface ReadingTime {
  minutes: number;
  words: number;
}

const CJK =
  /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF\u3040-\u309F\u30A0-\u30FF\uAC00-\uD7AF\u1100-\u11FF]/g;
const LATIN = /[A-Za-z0-9]+/g;

export function readingTime(body: string | undefined): ReadingTime {
  const text = body ?? '';
  const cjk = text.match(CJK)?.length ?? 0;
  const latin = text.match(LATIN)?.length ?? 0;
  return {
    words: cjk + latin,
    minutes: Math.max(1, Math.ceil(cjk / 300 + latin / 200)),
  };
}
