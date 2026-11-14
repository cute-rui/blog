import { siteConfig } from '../site.config';

export function formatDate(date: Date, lang = siteConfig.lang): string {
  return new Intl.DateTimeFormat(lang, {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(date);
}

export function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** "09.10" — compact month.day for archive rows. */
export function monthDay(date: Date): string {
  return isoDate(date).slice(5).replace('-', '.');
}
