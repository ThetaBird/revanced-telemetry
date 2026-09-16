# YouTube Music 8.40.54 validation

## Ratings, repeat and queue clicks — 2026-09-15

Current local artifacts (not a published release):

- APK: `.local/music-8.40.54-controls.apk`
- APK SHA-256: `bd91c5ffa15c667b75f3da49d3e04280674cb4e96ed375f15d326eb4337b8edf`
- Bundle: `patches/build/libs/music-telemetry-0.1.1.rvp`
- Bundle SHA-256: `1f4eb1d1eed605a17dc5083c7285987882f036341bdf2e7e1846287d13b270ef`

| Check | Result |
|---|---|
| Kotlin patch tests | 35 passed, including 11 new repeat/selection tests |
| Exporter/receiver integration tests | 10 passed |
| Patch helper tests | 8 passed |
| Android strict compilation and instrumentation | Passed, including new control/extraction fixtures |
| Actual supplied APK patching and hook audit | All eight telemetry patches plus GmsCore support passed |
| APK signature verification | v2 and v3 verified |
| Focused independent code review | No blockers |

Existing like/dislike/remove-rating callbacks were revalidated with exact request
targets distinct from player context. Repeat tests cover all three modes,
unknown/disabled rejection, matched/stale playlist context and disabled telemetry.
Queue selection tests cover precise 64-bit occurrence IDs, duplicate song IDs,
selected-vs-current targets, rejected click branches, non-queue rows and extraction
failures. HTTP integration verifies these actions are stored privately, retain
metadata and deduplicate upload retries without affecting now-playing.

The receiver's existing `repeatMode` field is numeric: `0` off, `1` one, `2` all.
Readable `repeatModeName` and `repeatScope` fields accompany it. Native enum
ordinals differ and are deliberately not exported as receiver mode codes.
Repeat hooks run after the two native UI control commands return; automatic repeat
changes are excluded. Queue hooks run before dispatch only after the native Up
Next click gate accepts the action; menu/remove/reorder and rejected callbacks
are excluded. Request/command observation does not claim Google acceptance or
audible playback. Signed-in live UI payloads remain unverified.

## Queue and opened-playlist capture — 2026-09-15

Previously validated queue/playlist artifacts:

- APK: `.local/music-8.40.54-queue-playlists.apk`
- APK SHA-256: `673d6b076d7619d12477d6d0dd071048aae723e78d5725ccdccfe39884fe4426`
- Bundle: `patches/build/libs/music-telemetry-0.1.1.rvp`
- Bundle SHA-256: `d61b2c8da4a848407066d6df65830267749f08c08cfd2d5da314b0d24a091e7b`

| Check | Result |
|---|---|
| Kotlin patch tests | 24 passed |
| Exporter/receiver integration tests | 9 passed |
| Python patch helper tests | 8 passed |
| Companion Listen regression suite | 68 passed; SQLite connection ResourceWarnings emitted |
| Strict Android Java compilation | `-Xlint:all -Werror` passed |
| Android instrumentation | Passed; `.local/android-queue-tests/result.txt` |
| Actual supplied APK patching | All six telemetry patches plus GmsCore support succeeded |
| Emitted DEX audit | Queue capture, playlist open/bind, existing telemetry/settings hooks present |
| APK signature verification | v2 and v3 verified |

The Android harness covers native queue extraction beyond the framework's 25-item
window, ordered duplicates and unresolved positions, immutable capture, rich
metadata normalization, chunk byte bounds, SQLite persistence/retries, disabled
capture and destination resets. Playlist tests cover loaded rows, occurrence and
browse IDs, header descriptions/text/artwork, playlist-entry deduplication,
empty-queue reset, reopening, and sanitized extraction-failure diagnostics.
Patch fixtures reject changed anchors and serialize the resulting DEX.

`playback_queue` captures the entire **loaded** native queue. Opened
`playlist_snapshot` events capture the supported playlist detail renderer's loaded
rows and carry `complete: false`; they do not force pagination or claim the full
remote playlist. Unknown/opaque Elements fields are not decoded. The
`playlist_playback_started` action matches a current playlist descriptor to a
loaded track, with `observation: "track_loaded"`, not proof of audible playback.
Reassembly preserves partial-source scope separately from transport completeness.

The patch helper now selects the current 0.1.1 bundle instead of the stale 0.1.0
path. The companion `../listen/server/listen_server.py` changes accept
`playback_queue` privately and exclude it from now-playing. No production receiver
was deployed. Signed-in live playlist UI/queue payloads and playback remain
unverified; actual-app installation/settings evidence below belongs to the prior
settings build. The current APK was built, audited and signature-verified locally.

## Prior settings build — 2026-09-07

Validated locally on 2026-09-07. The current build configures telemetry through
**Settings → Listen telemetry**. No collector URL or write token is embedded.

## Previous release artifacts

- Supplied input: `com.google.android.apps.youtube.music_8.40.54-84054240_minAPI26(arm64-v8a)(nodpi)_apkmirror.com.apk`
- Input SHA-256: `d5b44919a5cd5648b01e392115fe68b9569b1c7847f3cdf65b1ace1302d005d2`
- Release patch bundle: `patches/build/libs/music-telemetry-0.1.1.rvp`
- Published release bundle SHA-256: `e2d1b138eab46116128f4c865c9ff26e313e47d6d3663c7813598e75a2638072`
- Output: `.local/music-8.40.54-listen.apk`
- Output SHA-256: `7c1de7fb6b279d839cbfbdc4330d13f855466930f3c4be405e918c913bbab4ab`
- Installed package: `app.revanced.android.apps.youtube.music`, version 8.40.54.

The release bundle includes all four local telemetry patches and the dependent
native settings entry. The locally validated APK also includes official v6.0.0
`GmsCore support`; it was built with the functionally identical 0.1.0 patch code
before public release metadata was added. Retain one APK signing identity for
updates. APKs, downloads, private keys and screenshots are ignored by Git.

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
