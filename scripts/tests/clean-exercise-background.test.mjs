import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { cleanBackground, WIDTH, HEIGHT } from "../clean-exercise-background.mjs";

const exec = promisify(execFile);
const size = WIDTH * HEIGHT;
const charcoal = [23, 26, 26];
const solid = (color) => Buffer.alloc(size * 3).fill(Buffer.from(color));
const pixel = (pixels, x, y) => [...pixels.subarray((y * WIDTH + x) * 3, (y * WIDTH + x) * 3 + 3)];

function rectangle(pixels, x, y, width, height, color) {
  for (let row = y; row < y + height; row++) {
    for (let col = x; col < x + width; col++) pixels.set(color, (row * WIDTH + col) * 3);
  }
}

test("flattens noisy blue-grey background and shadows while preserving artwork", () => {
  const pixels = solid([26, 30, 34]);
  for (let i = 0; i < size; i++) {
    const noise = i % 5 - 2;
    pixels.set([26 + noise, 30 + noise, 34 + noise], i * 3);
  }
  const details = [
    [220, 70, 55, 90, [237, 179, 137]], // Athlete.
    [220, 160, 55, 30, [42, 72, 63]], // Green shorts.
    [160, 80, 40, 40, [29, 27, 26]], // Dark, warm weight plate.
    [360, 50, 8, 150, [38, 41, 43]], // Cool grey equipment.
  ];
  for (const detail of details) rectangle(pixels, ...detail);
  rectangle(pixels, 190, 240, 180, 12, [18, 22, 26]);
  const original = Buffer.from(pixels);
  const result = cleanBackground(pixels);
  assert.deepEqual(pixels, original, "Must not mutate the source");
  for (let y = 0; y < HEIGHT; y++) {
    for (let x = 0; x < WIDTH; x++) {
      const detail = details.find(([left, top, width, height]) =>
        x >= left && x < left + width && y >= top && y < top + height);
      assert.deepEqual(pixel(result, x, y), detail ? detail[4] : charcoal);
    }
  }
});

test("removes distant specks without erasing thin equipment or nearby edges", () => {
  const pixels = solid([26, 30, 34]);
  const blueGrey = [33, 36, 40];
  rectangle(pixels, 80, 80, 2, 2, blueGrey);
  rectangle(pixels, 220, 100, 50, 60, [237, 179, 137]);
  rectangle(pixels, 216, 110, 2, 2, blueGrey);
  rectangle(pixels, 450, 100, 1, 40, blueGrey);
  const result = cleanBackground(pixels);
  assert.deepEqual(pixel(result, 80, 80), charcoal);
  assert.deepEqual(pixel(result, 216, 110), blueGrey);
  for (let y = 100; y < 140; y++) assert.deepEqual(pixel(result, 450, y), blueGrey);
});

test("an explicit mask preserves a dark wall and clears a brighter floor", () => {
  const pixels = solid([26, 30, 34]);
  const wall = [30, 34, 38];
  rectangle(pixels, 400, 0, 40, HEIGHT, wall);
  rectangle(pixels, 0, 260, 400, 54, [37, 41, 45]);
  const mask = Buffer.alloc(size, 255);
  for (let y = 0; y < HEIGHT; y++) mask.fill(0, y * WIDTH + 400, y * WIDTH + 440);
  const result = cleanBackground(pixels, mask);
  assert.deepEqual(pixel(result, 420, 180), wall);
  assert.deepEqual(pixel(result, 200, 280), charcoal);
  assert.deepEqual(pixel(result, 20, 20), charcoal);
});

test("rejects misaligned masks and unsupported palettes instead of guessing", () => {
  assert.throws(() => cleanBackground(Buffer.alloc(3)), /627 x 314/);
  assert.throws(() => cleanBackground(solid([26, 30, 34]), Buffer.alloc(1)), /mask/);
  assert.throws(() => cleanBackground(solid([26, 30, 34]), Buffer.alloc(size, 128)), /black and white/);
  const light = solid([200, 200, 200]);
  assert.throws(() => cleanBackground(light), /supply a background mask/);
  assert.deepEqual(cleanBackground(light, Buffer.alloc(size)), light);
});

test("prep command normalizes, cleans and encodes; failures preserve the existing file", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "lofit-image-prep-"));
  const scripts = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  try {
    await mkdir(path.join(directory, "scripts"));
    const destination = path.join(directory, "app/src/main/res/drawable-nodpi");
    await mkdir(destination, { recursive: true });
    for (const name of ["prepare-exercise-image.sh", "clean-exercise-background.mjs"]) {
      await copyFile(path.join(scripts, name), path.join(directory, "scripts", name));
    }
    const ppm = path.join(directory, "source.ppm");
    const input = path.join(directory, "generated source.png");
    const pixels = solid([26, 30, 34]);
    rectangle(pixels, 250, 100, 80, 100, [237, 179, 137]);
    await writeFile(ppm, Buffer.concat([Buffer.from(`P6\n${WIDTH} ${HEIGHT}\n255\n`), pixels]));
    await exec("magick", [ppm, "-resize", "200%", input]);
    const command = path.join(directory, "scripts/prepare-exercise-image.sh");
    const output = path.join(destination, "exercise_fixture.webp");
    const result = await exec("bash", [command, input, "exercise_fixture"]);
    assert.equal(result.stdout.trim(), output);
    const dimensions = await exec("magick", ["identify", "-format", "%w %h", output]);
    assert.equal(dimensions.stdout, "627 314");
    const { stdout: decoded } = await exec("magick", [output, "-depth", "8", "rgb:-"], {
      encoding: "buffer", maxBuffer: size * 3 + 1024,
    });
    for (const [actual, expected] of [
      [pixel(decoded, 0, 0), charcoal],
      [pixel(decoded, 290, 150), [237, 179, 137]],
    ]) actual.forEach((channel, i) => assert.ok(Math.abs(channel - expected[i]) <= 3));

    const approved = await readFile(output);
    const mask = path.join(directory, "background mask.png");
    // The same pixel count in the wrong orientation must also be rejected.
    await exec("magick", ["-size", "314x627", "xc:white", mask]);
    await assert.rejects(exec("bash", [command, input, "exercise_fixture", mask]), /627 x 314/);
    assert.deepEqual(await readFile(output), approved);

    // A supplied mask overrides detection all the way through the shell command.
    await exec("magick", ["-size", "627x314", "xc:white", mask]);
    await exec("bash", [command, input, "exercise_fixture", mask]);
    const flat = await exec("magick", [output, "-format", "%[pixel:p{290,150}]", "info:"]);
    assert.match(flat.stdout, /srgb\(2[0-9],2[0-9],2[0-9]\)/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
