import { getCollection, type CollectionEntry } from 'astro:content';
import { listedOnBlog } from './publish';

export type Post = CollectionEntry<'posts'>;

export interface TagCount {
  tag: string;
  count: number;
}

export async function getPublishedPosts(): Promise<Post[]> {
  const posts = await getCollection('posts', ({ data }) => listedOnBlog(data, import.meta.env.PROD));
  return posts.sort((a, b) => {
    const byDate = b.data.pubDate.valueOf() - a.data.pubDate.valueOf();
    if (byDate !== 0) return byDate;
    return a.data.title.localeCompare(b.data.title);
  });
}

export function getAdjacentPosts(posts: Post[], id: string): { prev?: Post; next?: Post } {
  const index = posts.findIndex((post) => post.id === id);
  if (index === -1) return {};
  return {
    prev: posts[index + 1],
    next: posts[index - 1],
  };
}

export function countTags(posts: Post[]): TagCount[] {
  const counts = new Map<string, number>();
  for (const post of posts) {
    for (const tag of post.data.tags) {
      counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }
  }
  return [...counts.entries()]
    .map(([tag, count]) => ({ tag, count }))
    .sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
}

export interface YearGroup {
  year: number;
  posts: Post[];
}

/** Groups already-sorted posts by publication year, newest year first. */
export function groupByYear(posts: Post[]): YearGroup[] {
  const groups = new Map<number, Post[]>();
  for (const post of posts) {
    const year = post.data.pubDate.getUTCFullYear();
    groups.set(year, [...(groups.get(year) ?? []), post]);
  }
  return [...groups.entries()]
    .map(([year, posts]) => ({ year, posts }))
    .sort((a, b) => b.year - a.year);
}

export function postUrl(post: Post): string {
  return `/posts/${post.id}/`;
}

export function tagUrl(tag: string): string {
  return `/tags/${encodeURIComponent(tag)}/`;
}
