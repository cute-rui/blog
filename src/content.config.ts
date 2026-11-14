import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { z } from 'astro/zod';

const publishSchema = z
  .object({
    blog: z.boolean().default(true),
    wechat: z.boolean().default(false),
  })
  .default({ blog: true, wechat: false });

const wechatSchema = z
  .object({
    title: z.string().max(32).optional(),
    author: z.string().max(16).optional(),
    digest: z.string().max(120).optional(),
    cover: z.string().optional(),
    coverCrop: z
      .object({
        x1: z.number().min(0).max(1),
        y1: z.number().min(0).max(1),
        x2: z.number().min(0).max(1),
        y2: z.number().min(0).max(1),
      })
      .refine((crop) => crop.x2 > crop.x1 && crop.y2 > crop.y1, {
        message: 'coverCrop 需要 x1 < x2 且 y1 < y2',
      })
      .optional(),
    openComment: z.boolean().optional(),
    fansOnlyComment: z.boolean().optional(),
    sourceUrl: z.string().optional(),
  })
  .optional();

const posts = defineCollection({
  loader: glob({
    base: './content/posts',
    pattern: '*/index.{md,mdx}',
    generateId: ({ entry }) => entry.replaceAll('\\', '/').replace(/\/index\.(md|mdx)$/i, ''),
  }),
  schema: ({ image }) =>
    z.object({
      title: z.string(),
      description: z.string(),
      pubDate: z.coerce.date(),
      updatedDate: z.coerce.date().optional(),
      tags: z.array(z.string()).default([]),
      draft: z.boolean().default(false),
      heroImage: image().optional(),
      heroAlt: z.string().optional(),
      publish: publishSchema,
      wechat: wechatSchema,
    }),
});

export const collections = { posts };
