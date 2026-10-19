# Agent Rules

## Talk

- Use minimum output, no summaries.
- Speak like caveman.
- Use few words. Short words.
- Say result, test status, blocker only.
- No long plans. No filler.

## Testing

- Test on emulator if started.

## Android Device

- Deploy: [`scripts/deploy.sh`](scripts/deploy.sh)
- Restart: [`scripts/restart.sh`](scripts/restart.sh)
- Stop: [`scripts/stop.sh`](scripts/stop.sh)
- Set `ANDROID_SERIAL` when more than one device is connected.
- For physical device, always deploy prod build, never debug build

Run:

```sh
./scripts/deploy.sh
./scripts/restart.sh
./scripts/stop.sh
```
