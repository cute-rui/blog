import { writeFileSync } from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';

const BYTE_LIMIT = 1_000_000;
const MAX_EDGE = 1600;

function longest(width, height) {
  return Math.max(width || 0, height || 0);
}

async function encode(input, { format, quality, edge }) {
  let pipeline = sharp(input, { animated: false, density: 144 });
  if (edge > 0 && edge < MAX_EDGE) {
    pipeline = pipeline.resize({ width: Math.round(edge), height: Math.round(edge), fit: 'inside', withoutEnlargement: true });
  } else if (longestEdgeNeeded(edge)) {
    pipeline = pipeline.resize({ width: MAX_EDGE, height: MAX_EDGE, fit: 'inside', withoutEnlargement: true });
  }
  if (format === 'jpeg') return pipeline.jpeg({ quality, mozjpeg: true }).toBuffer();
  return pipeline.png({ compressionLevel: 9 }).toBuffer();
}

function longestEdgeNeeded(edge) {
  return edge === 0 || edge > MAX_EDGE;
}

export async function transcodeBodyImage(input) {
  const ext = path.extname(input).toLowerCase();
  const meta = await sharp(input, { animated: false, density: 144 }).metadata();
  const hasAlpha = Boolean(meta.hasAlpha);
  const format = hasAlpha || ['.png', '.svg', '.webp', '.gif'].includes(ext) ? 'png' : 'jpeg';
  let quality = 82;
  let edge = longest(meta.width, meta.height);
  let buffer = null;
  for (let attempt = 0; attempt < 12; attempt += 1) {
    buffer = await encode(input, { format, quality, edge });
    if (buffer.length < BYTE_LIMIT) break;
    if (format === 'jpeg' && quality > 50) quality -= 8;
    else edge = Math.max(320, Math.round((edge || MAX_EDGE) * 0.85));
  }
  if (!buffer || buffer.length >= BYTE_LIMIT) {
    throw new Error(`${path.basename(input)} 压到 ${buffer ? buffer.length : 0} 字节，仍然不小于 1 MB`);
  }
  return {
    buffer,
    extension: format === 'jpeg' ? '.jpg' : '.png',
    gif: ext === '.gif',
  };
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

export async function writeCover(input, dest, crop) {
  const meta = await sharp(input, { animated: false, density: 144 }).metadata();
  const width = meta.width || 1;
  const height = meta.height || 1;
  let left;
  let top;
  let cropWidth;
  let cropHeight;
  if (crop) {
    left = Math.round(crop.x1 * width);
    top = Math.round(crop.y1 * height);
    cropWidth = Math.round((crop.x2 - crop.x1) * width);
    cropHeight = Math.round((crop.y2 - crop.y1) * height);
  } else if (width / height > 2.35) {
    cropHeight = height;
    cropWidth = Math.round(height * 2.35);
    left = Math.round((width - cropWidth) / 2);
    top = 0;
  } else {
    cropWidth = width;
    cropHeight = Math.round(width / 2.35);
    left = 0;
    top = Math.round((height - cropHeight) / 2);
  }
  left = clamp(left, 0, width - 1);
  top = clamp(top, 0, height - 1);
  cropWidth = Math.max(1, Math.min(cropWidth, width - left));
  cropHeight = Math.max(1, Math.min(cropHeight, height - top));

  let quality = 82;
  let buffer = null;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    let pipeline = sharp(input, { animated: false, density: 144 }).extract({
      left,
      top,
      width: cropWidth,
      height: cropHeight,
    });
    if (cropWidth > MAX_EDGE) pipeline = pipeline.resize({ width: MAX_EDGE });
    buffer = await pipeline.jpeg({ quality, mozjpeg: true }).toBuffer();
    if (buffer.length < BYTE_LIMIT) break;
    quality = Math.max(40, quality - 8);
  }
  if (!buffer || buffer.length >= BYTE_LIMIT) {
    throw new Error(`封面压到 ${buffer ? buffer.length : 0} 字节，仍然不小于 1 MB`);
  }
  writeFileSync(dest, buffer);
}
