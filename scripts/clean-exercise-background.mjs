import { execFile } from "node:child_process";
import { mkdtemp, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

export const WIDTH = 627;
export const HEIGHT = 314;
const PIXELS = WIDTH * HEIGHT;
const CHARCOAL = [23, 26, 26]; // App charcoal, #171A1A.
const run = promisify(execFile);

function borderColor(pixels) {
  const histograms = Array.from({ length: 3 }, () => new Uint32Array(256));
  let count = 0;
  for (let y = 0; y < HEIGHT; y++) {
    for (let x = 0; x < WIDTH; x++) {
      if (x >= 20 && x < WIDTH - 20 && y >= 12 && y < HEIGHT - 12) continue;
      for (let c = 0; c < 3; c++) histograms[c][pixels[(y * WIDTH + x) * 3 + c]]++;
      count++;
    }
  }
  return histograms.map((histogram) => {
    let accumulated = 0;
    return histogram.findIndex((frequency) => (accumulated += frequency) > count / 2);
  });
}

function isBlueGrey(pixels, index) {
  const [r, g, b] = pixels.subarray(index * 3, index * 3 + 3);
  return r < 51 && g < 51 && b < 51 && b - r >= 2 && b >= g;
}

function removeDistantSpecks(pixels, foreground) {
  const seen = new Uint8Array(PIXELS);
  const queue = new Int32Array(PIXELS);
  for (let start = 0; start < PIXELS; start++) {
    if (!foreground[start] || seen[start]) continue;
    let head = 0;
    let tail = 0;
    let allBlueGrey = true;
    queue[tail++] = start;
    seen[start] = 1;
    while (head < tail) {
      const index = queue[head++];
      const x = index % WIDTH;
      const y = Math.floor(index / WIDTH);
      if (!isBlueGrey(pixels, index)) allBlueGrey = false;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const nx = x + dx;
          const ny = y + dy;
          const next = ny * WIDTH + nx;
          if (nx < 0 || nx >= WIDTH || ny < 0 || ny >= HEIGHT) continue;
          if (foreground[next] && !seen[next]) {
            seen[next] = 1;
            queue[tail++] = next;
          }
        }
      }
    }
    // Dark equipment and thin edges must survive: only discard tiny, isolated specks.
    if (!allBlueGrey || tail > 9) continue;
    let nearSubject = false;
    for (let k = 0; k < tail && !nearSubject; k++) {
      const x = queue[k] % WIDTH;
      const y = Math.floor(queue[k] / WIDTH);
      for (let dy = -8; dy <= 8 && !nearSubject; dy++) {
        for (let dx = -8; dx <= 8; dx++) {
          const nx = x + dx;
          const ny = y + dy;
          if (nx < 0 || nx >= WIDTH || ny < 0 || ny >= HEIGHT) continue;
          if (!isBlueGrey(pixels, ny * WIDTH + nx)) {
            nearSubject = true;
            break;
          }
        }
      }
    }
    if (!nearSubject) for (let k = 0; k < tail; k++) foreground[queue[k]] = 0;
  }
}

export function cleanBackground(pixels, backgroundMask) {
  if (pixels.length !== PIXELS * 3) throw new Error("Expected one 627 x 314 RGB image");
  const foreground = new Uint8Array(PIXELS);
  if (backgroundMask) {
    if (backgroundMask.length !== PIXELS || backgroundMask.some((v) => v !== 0 && v !== 255)) {
      throw new Error("Background mask must be 627 x 314, using only black and white");
    }
    for (let i = 0; i < PIXELS; i++) foreground[i] = backgroundMask[i] === 0 ? 1 : 0;
  } else {
    const background = borderColor(pixels);
    if (Math.max(...background) > 60 || background[2] - background[0] < 2 || background[2] < background[1]) {
      throw new Error("Expected a dark blue-grey background; supply a background mask for this image");
    }
    for (let i = 0; i < PIXELS; i++) {
      const [r, g, b] = pixels.subarray(i * 3, i * 3 + 3);
      const isBackground = r <= background[0] + 4 && g <= background[1] + 4 &&
        b <= background[2] + 4 && b - r >= 2 && b >= g;
      foreground[i] = isBackground ? 0 : 1;
    }
    removeDistantSpecks(pixels, foreground);
  }
  const result = Buffer.from(pixels);
  for (let i = 0; i < PIXELS; i++) if (!foreground[i]) result.set(CHARCOAL, i * 3);
  return result;
}

async function magick(args) {
  const { stdout } = await run("magick", args, { encoding: "buffer", maxBuffer: 2 * 1024 * 1024 });
  return stdout;
}

export async function prepareImage(input, output, mask) {
  const pixels = await magick([
    path.resolve(input), "-auto-orient", "-resize", "627x314^", "-gravity", "center",
    "-extent", "627x314", "-background", "#171A1A", "-alpha", "remove", "-alpha", "off",
    "-colorspace", "sRGB", "-depth", "8", "rgb:-",
  ]);
  let backgroundMask;
  if (mask) {
    const filename = path.resolve(mask);
    const dimensions = await magick(["identify", "-format", "%w %h", filename]);
    if (dimensions.toString() !== `${WIDTH} ${HEIGHT}`) {
      throw new Error("Background mask must match the normalized 627 x 314 image");
    }
    backgroundMask = await magick([
      filename, "-background", "black", "-alpha", "remove", "-alpha", "off",
      "-colorspace", "gray", "-depth", "8", "gray:-",
    ]);
  }
  const cleaned = cleanBackground(pixels, backgroundMask);
  const directory = await mkdtemp(path.join(path.dirname(path.resolve(output)), ".exercise-image-"));
  try {
    const ppm = path.join(directory, "cleaned.ppm");
    const webp = path.join(directory, "cleaned.webp");
    await writeFile(ppm, Buffer.concat([Buffer.from(`P6\n${WIDTH} ${HEIGHT}\n255\n`), cleaned]));
    await magick([ppm, "-strip", "-quality", "82", "-define", "webp:method=6", webp]);
    await rename(webp, path.resolve(output));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [input, output, mask, ...extra] = process.argv.slice(2);
  if (!input || !output || extra.length) {
    console.error("usage: node clean-exercise-background.mjs INPUT OUTPUT [BACKGROUND_MASK]");
    process.exitCode = 2;
  } else {
    try {
      await prepareImage(input, output, mask);
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    }
  }
}
