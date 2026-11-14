import { siteConfig } from '../site.config';

export interface WordCount {
  value: string;
  unit: string;
}

/** 3214 -> { value: "3,214", unit: "字" }, 12345 -> { value: "1.2", unit: "万字" } */
export function formatWords(words: number): WordCount {
  if (words >= 10000) {
    return { value: (words / 10000).toFixed(1).replace(/\.0$/, ''), unit: '万字' };
  }
  return { value: words.toLocaleString(siteConfig.lang), unit: '字' };
}

/** FNV-1a; stable across builds so generated covers do not shuffle between deploys. */
export function hashString(input: string): number {
  let hash = 2166136261;
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}
