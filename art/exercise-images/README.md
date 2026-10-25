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
equipment, and view. Then normalize the chosen output:

```sh
./scripts/prepare-exercise-image.sh generated.png exercise_lateral_raise
```

The result is written to `app/src/main/res/drawable-nodpi/<name>.webp` at the
same 627 x 314 size as the original Lofit illustrations.

## Female variants

Every bundled `exercise_*.webp` has a sibling `<name>_female.webp`. The original
resource is the male variant. `ExercisePictures.bundledResource` selects the
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
