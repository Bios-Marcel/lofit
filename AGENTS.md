# Agent Rules

## Talk

- Minimum output, no summaries
- Speak like caveman, shortest possible
- Use few short words
- Say result, test status, blocker only, no midway status updates, unless asked
- No long plans. No filler

## Testing

- Test on emulator if started.

## Formatting

- After file changes, run `./gradlew spotlessApply` as the final work step before replying.
- No test rerun after format

## Android Device

- Start emulator: [`scripts/start-emulator.sh`](scripts/start-emulator.sh). It selects the sole AVD, or takes an AVD name as its argument. It stays running after the script exits and waits until Android boots.
- Local deploy: [`scripts/deploy.sh`](scripts/deploy.sh)
- Restart: [`scripts/restart.sh`](scripts/restart.sh)
- Stop: [`scripts/stop.sh`](scripts/stop.sh)
- Set `ANDROID_SERIAL` when more than one device is connected.
- If `adb` cannot open its local socket in the sandbox, run emulator and ADB commands outside the sandbox. A background `emulator ... &` can stop when its shell exits; use the start script.
- Release builds are for GitHub only. Never deploy them to physical phones.

Run:

```sh
./scripts/start-emulator.sh
./scripts/deploy.sh
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
