# Releases

Push a semantic version tag to build a signed release APK and publish it with a
SHA-256 checksum and generated notes on GitHub Releases:

```sh
git tag v1.2.3
git push origin v1.2.3
```

Both `1.2.3` and `v1.2.3` work, including SemVer prerelease and build metadata.
Tags such as `v1.3.0-rc.1` create GitHub prereleases. Other tags do not release;
malformed version tags matching the workflow's tag filter fail validation.
Unit tests, release lint, and the release build must pass before publishing.
Rerunning the workflow replaces the assets of an existing release for that tag.

Set these repository Actions secrets before the first release:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the signing keystore (`base64 -w 0 /path/to/release.keystore` on Linux). |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_ALIAS` | Signing key alias. |
| `ANDROID_KEY_PASSWORD` | Signing key password. |

Keep the same signing key for all releases so installed apps can update. To update
an existing local install, use its current signing key (local release builds
currently default to the local Android debug key). Keep a backup of the key.
The workflow uses the built-in `GITHUB_TOKEN`; no personal access token is needed.

The tag without `v` becomes Android's `versionName`. The workflow run number plus
one becomes `versionCode`, so each new run increases it from the local default of
one. Reruns retain their original code; push newer versions in order.
Local builds retain their defaults unless `-PversionName` / `-PversionCode` or
the `RELEASE_KEYSTORE_PATH`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and
`RELEASE_KEY_PASSWORD` environment variables are supplied.
