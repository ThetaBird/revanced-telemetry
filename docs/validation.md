# YouTube Music 8.40.54 validation

Validated locally on 2026-09-07. The current build configures telemetry through
**Settings → Listen telemetry**. No collector URL or write token is embedded.

## Current artifacts

- Supplied input: `com.google.android.apps.youtube.music_8.40.54-84054240_minAPI26(arm64-v8a)(nodpi)_apkmirror.com.apk`
- Input SHA-256: `d5b44919a5cd5648b01e392115fe68b9569b1c7847f3cdf65b1ace1302d005d2`
- Patch bundle: `patches/build/libs/music-telemetry-0.1.0.rvp`
- Bundle SHA-256: `4a57c2f3c39890210c62645fe132cde34cc91aa987c8f9c47ddb2e906137caf0`
- Output: `.local/music-8.40.54-listen.apk`
- Output SHA-256: `7c1de7fb6b279d839cbfbdc4330d13f855466930f3c4be405e918c913bbab4ab`
- Installed package: `app.revanced.android.apps.youtube.music`, version 8.40.54.

Includes all four local telemetry patches, the dependent native settings entry,
and official v6.0.0 `GmsCore support`. Retains the local signing identity for
updates. Earlier `nonroot-validation` and `settings` APKs are superseded by this
artifact. APKs, downloads, keys and screenshots are ignored by Git.

## Settings validation

| Check | Result |
|---|---|
| Kotlin injection and settings-resource tests | 17 passed |
| Python patch helper tests | 8 passed; credential flags rejected |
| Android UI/runtime harness | Passed |
| Actual APK patching and DEX audit | All selected patches and required settings hook passed |
| APK signing | v2 and v3 verified |
| ARM64 emulator update installation | Success |
| Native Music settings integration | Account → Settings → Listen telemetry opens the form without sign-in |
| Default state | Empty URL/token, masked token field, telemetry disabled |
| ART bytecode verification | Success |
| Former embedded configuration scan | Previous collector host, localhost URL and disposable token absent from all APK DEX |

The Android harness exercises the actual settings form and configuration files,
with an in-memory HTTPS transport substituted for networking. It verifies:

- Invalid URL/header input is rejected before persistence.
- Saved configuration drives the uploader without rebuilding or restarting.
- A rejected request retries with a rotated token and the same event ID.
- Token rotation preserves the current song/session for subsequent progress.
- Disabled telemetry does not capture or upload events.
- Changing destinations deletes the previous server's backlog.
- Runtime initialization restores saved configuration, and credentials can be cleared.
- Existing SQLite durability, queue bounds, callback payload, rating, player and
  carousel checks continue to pass.

Harness evidence: `.local/android-queue-tests/result.txt`. Actual Music settings
entry screenshot: `.local/music-settings-entry.png`; final form accessibility
evidence: `.local/music-settings-form.xml`. The form prevents screen capture and
masks the token. Configuration lives in the app's private no-backup directory.

The 8 exporter/integration and 62 companion Listen regression tests passed before
this settings change; their source was not changed or retested for this update.
No production token was used, no Google account was signed in, and no receiver
was deployed during these checks.

## Evidence boundaries

- Rating hooks read the request's actual target video ID, independently of the
  current player ID. They observe request serialization, not Google's acceptance;
  retries may cause repeated observations with distinct event IDs.
- In-app next/previous hooks occur immediately before accepted player commands in
  both traced player-control implementations. They do not infer skips from track changes.
- Carousel hooks capture a supported two-row item callback, its WatchEndpoint
  video ID and the visible section heading. Fixtures include `Quick picks`; a
  signed-in server-provided Quick picks feed has not been exercised. Other
  renderers and selection gestures may need additional hooks.
- Media-session hooks observe framework dispatch, not a proven headset or UI source.
- Authentication and live playback, real rating requests, account playlist
  pagination, production HTTP delivery and deployment remain untested.
- Automated playlist export is a separate authenticated server-side command;
  there is no claim of complete live radio/Quick Play queue export from the app.

## Base bundle provenance

The official v6.0.0 bundle has SHA-256
`3d3b17720f0a3a40de850e4f41dea8e3628686dfd5aaca5ae76aedd6a0fbb29f`.
`scripts/bootstrap_base.py` verifies the pinned checksum and detached signature,
including the signing subkey's binding to pinned ReVanced root
`A7835DFCACA14BDA3CD6EDD3633C6920FBE6A2FF`. The signature verifies locally.
The mirrored release's GitHub Sigstore build attestation was unavailable; this
build does not claim that attestation was verified. Source and transport links
are in the README and bootstrap script.
