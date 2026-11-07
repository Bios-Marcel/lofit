# Exercise image seed

Use [`style-seed.png`](style-seed.png) as the first reference image for each
new exercise image. It fixes the athlete, clothes, palette, background, and
rendering style. An old exercise photo may be added as a second reference for
pose and equipment only. Image generation is not pixel-deterministic; this
seed makes separate generations visually consistent.

Use this prompt and replace only the four `{{...}}` fields:

```text
Use case: stylized-concept
Asset type: Lofit exercise card illustration
Input image: strict reference for character identity, anatomy, outfit, palette,
background, lighting, linework, and rendering style.

Exercise: {{EXERCISE_NAME}}
Pose: {{ONE PRECISE SENTENCE DESCRIBING THE KEY POSITION}}
Equipment: {{EQUIPMENT OR "none"}}
View: {{FRONT, SIDE, REAR, OR THREE-QUARTER}}

Create one polished instructional illustration of the reference athlete doing
the exercise. Keep his face, hair, build, off-white T-shirt, deep green shorts,
white socks, and gray shoes unchanged. Use the same near-black charcoal card
background and softly cel-shaded 2D finish as the reference. Show the whole
body and all equipment, centered with generous padding, on a wide 2:1 canvas.
Choose a clear, anatomically correct key position.

No text, labels, logos, watermark, scenery, panel borders, motion trail,
cropped body parts, extra limbs, or extra equipment. Do not change the outfit,
character, colors, or art style.
```

Keep the seed and prompt unchanged between images. Change only exercise, pose,
equipment, and view. Always process the original generated PNG with the prep
script, for both new images and regenerated replacements:

```sh
./scripts/prepare-exercise-image.sh generated.png exercise_lateral_raise
```

The script requires ImageMagick and Node.js. It normalizes to 627 x 314, estimates
the blue-grey background from the image border, replaces background pixels with
app charcoal (`#171A1A`), and removes tiny isolated background specks. It then
encodes WebP at quality 82 with compression method 6 and strips metadata. Pixels
outside the cleanup mask are preserved before WebP encoding. Use the original
PNG each time; reprocessing a WebP adds another lossy compression pass.

The result is written to `app/src/main/res/drawable-nodpi/<name>.webp`. A failed
conversion leaves an existing resource untouched.

Inspect every result at native size and zoomed in, especially dark plates,
cables, benches, walls, and the athlete's outline. Background cleanup is a
colour-based mask, so those details can need a manually corrected mask. For such
images, supply a black-and-white PNG aligned to the normalized 627 x 314 image:
white replaces a pixel with charcoal; black preserves its original colour.
This mask replaces automatic detection, allowing floor shadows to be cleared
while retaining a wall or equipment. Do not reuse a mask from a different pose
or generation. A background outside the supported dark blue-grey palette also
requires a mask.

```sh
./scripts/prepare-exercise-image.sh generated.png exercise_wall_sit background-mask.png
```

The mask must already be exactly 627 x 314 and contain only black and white;
the script does not resize it. WebP can introduce small colour variations near
edges; the background is exactly `#171A1A` before encoding.

Check the prep workflow with:

```sh
node --test scripts/tests/clean-exercise-background.test.mjs
```

## Female variants

Every bundled male exercise resource has a sibling `<name>_female` resource.
Some resource names share a retained WebP through
[`exercise_image_aliases.xml`](../../app/src/main/res/values/exercise_image_aliases.xml).
Male and female sharing decisions are independent. The original resource is
the male variant. `ExercisePictures.bundledResource` selects the
variant using the saved biological gender, for both resource IDs and image
names. User-uploaded pictures keep priority over bundled illustrations.

Use `style-seed.png` as the first reference, `style-seed-female.png` as the
female character reference, and the original exercise illustration as the
pose and equipment reference. Preserve the exercise, view, outfit, background,
and rendering style. The female athlete has dark brown hair in a ponytail,
an off-white T-shirt, deep green shorts, white socks, and gray shoes.

`female-variants.json` records every source, destination, exercise name, and
full prompt from the built-in `image_gen` generation run. Normalize female
outputs with the same script, passing `<original_name>_female` as the name.

For an aliased image, follow its alias to find the current reference bitmap.
To regenerate only that resource, remove its alias entry before writing the
replacement WebP; leave the other variant's alias unchanged.

[`applied-deduplication.json`](applied-deduplication.json) records approved
sharing decisions and later regeneration overrides.
