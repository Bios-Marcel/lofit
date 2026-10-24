# Agent Rules

## Talk

- Use minimum output, no summaries.
- Speak like caveman, as short as possible.
- Use few words. Prefer short words.
- Say result, test status, blocker only, no midway status updates, unless asked.
- No long plans. No filler.

## Testing

- Test on emulator if started.

## Android Device

- Local deploy: [`scripts/deploy.sh`](scripts/deploy.sh)
- Restart: [`scripts/restart.sh`](scripts/restart.sh)
- Stop: [`scripts/stop.sh`](scripts/stop.sh)
- Set `ANDROID_SERIAL` when more than one device is connected.
- Release builds are for GitHub only. Never deploy them to physical phones.

Run:

```sh
./scripts/deploy.sh
./scripts/restart.sh
./scripts/stop.sh
```

## UI / UX

Unless asked otherwise, follow these rules:

* No `back` buttons
* No useless explanation labels
* Keep space between buttons / between buttons and other components

