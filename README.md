# Lofit

An Android-native local fitness app.

## Features

* Custom workout routines
* Open workout routines (decided exercises on the fly)
* Predefined execises
* Custom exercises
* History
* Calendar
* Records
* Rudimentary Android Health integration
* Share active workout
* Optional local MCP server (off by default)

## Tech

* Targets Android 16+
  * Personal usecase, simplifies development for me
* Requires no internet

## MCP

Enable **MCP server** in Settings in each app. Debug uses port **8765**; release
uses **8766**, so both apps can run MCP at the same time. The server listens at
`http://127.0.0.1:PORT/mcp` while the app's activity exists, including in the
background. Reopen the app after Android stops it. Turning the switch off or
resetting app data closes the server. The setting persists across app restarts.
To use another port, enter it in Settings and tap **Apply**. The change restarts
the listener and persists across app restarts. Use the new port in your MCP URL,
ADB forward command, and Tailscale rules.

To connect the debug app from a computer over USB or an emulator:

```sh
adb forward tcp:8765 tcp:8765
```

Set your MCP client's Streamable HTTP URL to `http://127.0.0.1:8765/mcp`.
For release, forward `tcp:8766 tcp:8766` and use `http://127.0.0.1:8766/mcp`.
When multiple devices are connected, use `adb -s DEVICE_SERIAL forward ...`.
For direct access over Tailscale, enable Tailscale on both devices and use
`http://PHONE_TAILSCALE_IP:8765/mcp` (IPv6: `http://[PHONE_TAILSCALE_IPV6]:8765/mcp`).
Settings shows the phone's numeric Tailscale URLs while MCP is enabled. Use an IP
address rather than a MagicDNS name. Use port 8766 for release. Tailnet access
rules must permit TCP 8765 for debug and TCP 8766 for release.
The listener handles VPN reconnects without an app restart; reopen Settings to
refresh the displayed addresses after connecting Tailscale.
Android may pause network access while the phone is locked or asleep. Wake and
unlock the phone and reopen Lofit if the endpoint times out.

Only loopback and Tailscale source/destination addresses are accepted; ordinary
LAN and public network requests receive HTTP 403. Tailscale authenticates remote
devices and encrypts traffic; there is no separate MCP login. Devices permitted
by your tailnet rules can read workout data and create routines while MCP is on.
Host and Origin checks accept only localhost, loopback IPs and the phone's current
Tailscale IPs. The Android internet permission permits the listening socket.

The server implements [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
with JSON responses, supporting protocol versions `2025-03-26`, `2025-06-18`
and `2025-11-25`. It has no sessions or SSE stream. Send `initialize`, then
`notifications/initialized`; use `tools/list` for schemas and `tools/call` to run:

| Tool | Action |
| --- | --- |
| `get_routines` | Read saved routines, exercises, notes and planned sets. |
| `get_exercises` | Read the built-in and custom exercise catalog, including IDs. |
| `get_history` | Read saved workouts and logged sets, newest first. |
| `create_routine` | Save a new routine with generated IDs. |

Read tools accept `offset` (default 0) and `limit` (default 100, max 200).
Results include `total` and `nextOffset`; keep reading until `nextOffset` is null.
Weights use kilograms; timestamps use Unix milliseconds. Requests are limited
to 1 MiB. Routine names, exercise IDs, numbers, set types and sides are validated
before the routine is saved.

Example `tools/call` parameters, using an ID from `get_exercises`:

```json
{
  "name": "create_routine",
  "arguments": {
    "name": "Strength",
    "defaultRestSeconds": 120,
    "exercises": [{
      "exerciseId": "bench-press",
      "note": "Slow descent",
      "plannedSets": [{"type": "NORMAL", "weightKg": 40, "reps": 8}]
    }]
  }
}
```

Omitting `plannedSets` creates three normal sets with 8 reps, zero weight and
the exercise's minimum hold duration. Existing routines are never overwritten.

## Development

Kotlin uses ktfmt 0.64 (Kotlin style). Zed formats on save with the same version.
To format or check all Kotlin source and Gradle scripts:

```sh
./gradlew spotlessApply
./gradlew spotlessCheck
```

To measure debug test coverage, with an emulator running:

```sh
./gradlew :app:createDebugUnitTestCoverageReport :app:createDebugAndroidTestCoverageReport
```

Reports are in `app/build/reports/coverage/test/debug/` and
`app/build/reports/coverage/androidTest/debug/connected/`.

This is my first open source project that is PURELY vibecoded. I do NOT read the
code and I do NOT care. It is somewhat of an experiment. UX/UI come first and
there are GUI tests to ensure basic functionality stays stable to a certain
degree.

100% of the code written so far is AI based!

You are free to create issues or contribute though.

