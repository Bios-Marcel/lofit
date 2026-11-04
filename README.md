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

See [MCP documentation](docs/MCP.md) for setup, tools, and picture uploads.

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

