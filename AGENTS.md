# Agent Rules

## Talk

- Minimum output, no summaries
- Speak like caveman, shortest possible
- Use few short words
- Say result, test status, blocker only, no midway status updates, unless asked
- No long plans. No filler

## Testing

- Test on emulator if started.
- Default to two emulators for parallel tests, including releases: `./scripts/test-sharded.py --shards 2`. Use another count only when requested.

### Test speed

- Use `replaceText()` for text input, not `typeText()`. For keyboard tests, explicitly tap the field and await keyboard visibility.
- Do not append `closeSoftKeyboard()` to `replaceText()` by habit. Keep it only when the flow can open the keyboard and needs it closed, such as after field taps or in input dialogs.
- Seed prerequisite exercises, sets, and workouts through the repository before activity launch. Avoid repeated UI taps just to build fixture data.
- Keep UI actions and assertions for the behavior under test: adding/removing items, history-derived defaults, unsaved drafts, gestures, keyboard handling, and lifecycle changes. Do not bypass these with seeded results.
- Keep test-speed changes in test code. Do not add production hooks or use reflection into private UI state to skip setup.
- Await observable conditions with bounded timeouts instead of fixed sleeps. Do not shorten timeouts or remove synchronization merely to make tests faster.
- Prefer JVM tests for pure logic; use emulator tests where Android or UI behavior matters.
- Measure speed changes with the same suite, two shards, and profiling mode. Report single-run differences as observed, not guaranteed. Espresso wait samples include intentional synchronization, not just wasted time.

## Formatting

- After file changes, run `./gradlew spotlessApply` as the final work step before replying.
- No test rerun after format

## Android Device

- Set `ANDROID_SERIAL` when more than one device is connected.
- Start emulator: [`scripts/start-emulator.sh`](scripts/start-emulator.sh). It selects the sole AVD, or takes an AVD name as its argument. It stays running after the script exits and waits until Android boots.
- Local deploy: [`scripts/deploy.sh`](scripts/deploy.sh). When targeting emulator, it starts one if needed and waits for boot.
- Phone debug deploy: [`scripts/deploy-phone.sh`](scripts/deploy-phone.sh). It uses serial `RFCTC0T2CYP` (SM-S911B). Run it directly; do not look up the device first. If it fails, ask me to replug the phone and stop.
- Restart: [`scripts/restart.sh`](scripts/restart.sh)
- Stop: [`scripts/stop.sh`](scripts/stop.sh)
- If `adb` cannot open its local socket in the sandbox, run emulator and ADB commands outside the sandbox. A background `emulator ... &` can stop when its shell exits; use the start script.
- Release builds are for GitHub only. Never deploy them to physical phones.

Run:

```sh
./scripts/start-emulator.sh
./scripts/deploy.sh
./scripts/deploy-phone.sh
./scripts/restart.sh
./scripts/stop.sh
```

## UI / UX

Unless asked otherwise, follow these rules:

* No `back` buttons
* No useless explanation labels
* Keep space between buttons / between buttons and other components

## Code Guidelines

- When changing logic that leaves legacy logic, make a `// LEGACY:` marker with explanation

## Git Tags

- Format `v0.major.minor`, use same as tag title (first line)
- Don't list technical changes, unless they have a major impact on UX (such as performance)
