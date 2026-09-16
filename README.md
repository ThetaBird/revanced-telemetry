# Music telemetry for Listen

A custom ReVanced patch bundle and playlist exporter for **YouTube Music
8.40.54**, using the event protocol from the sibling `../listen` project.
The Android extension is DEX code and can be injected into an arm64-v8a APK.

The supplied **8.40.54 (84054240), arm64-v8a** APK has been patched with all eight
telemetry patches, an in-app settings page and official GmsCore support. The latest
signed APK passes the injected-hook audit and signature checks. The prior settings
build installed and opened **Settings → Listen telemetry** on an ARM64 emulator.
URL and token start empty; telemetry is off until configured and enabled.
Signed-in playback, live carousel coverage and production delivery remain unverified.

## Implemented coverage

| Observation | Implementation | Boundary |
|---|---|---|
| Track loaded | Music track-ID callback | Loading is not proof of audible playback; repeated identical IDs are deduplicated. |
| Playback position | Music time callback, throttled to 15 seconds | Position observation, not play/pause or a skip inference. |
| Like/dislike/remove rating | Rating-request serialization with exact target ID | `videoId` comes from the request; `contextVideoId` is the current player. Request construction is not Google acceptance or a unique button press; retries may repeat it. |
| Media-session next/previous/play/pause | Optional framework callback patch | Dispatch through Android media sessions only. Reports which methods match; does not establish the originating UI/headset. |
| Playback queue | Native queue before the media-session 25-song window | Emits `playback_queue` for changes to the entire loaded queue, preserving order, duplicates, video/queue/playlist IDs and available display metadata. Unloaded recommendations are outside the snapshot. |
| Opened playlists | Playlist detail header and loaded adapter | Emits `playlist_snapshot` on open and loaded-content changes, including off-screen loaded songs. `complete: false` distinguishes these observations from full saved-playlist exports. |
| Playlist playback | Current queue descriptor matched to a loaded track | Emits `music_action` / `playlist_playback_started` when entering a playlist; retains its ID/index. This observes a track load, not audible playback. |
| Saved playlists and Liked Songs | Authenticated, paginated server-side exporter | Preserves ordering and duplicate entries; does not export a live radio/Quick Play queue. |
| Repeat song/queue/off | Native repeat-button paths after the command returns | Emits `repeat_mode_changed` with `repeatModeName: "one"`, `"all"`, or `"off"`. Repeat all affects the current queue, including a playing playlist; automatic state changes are excluded. |
| Queue song click | Accepted Up Next row command path | Emits `queue_song_selected` with exact song/queue occurrence and source playlist IDs/index when available. Overflow, reorder and rejected clicks are excluded. |
| In-app Skip/Previous | Accepted command paths in both player layouts | Emits `skip_requested` / `previous_requested`, with `origin: "player_controls"`; does not prove a track transition. |
| Carousel song selection | Optional two-row carousel callback and watch-endpoint target | Emits `carousel_song_selected` with the visible `sourceTitle`. Covers this renderer's primary song tap, not every Quick Play layout or gesture. |

## Build

Requirements: JDK 17 or 21, Python 3.10+, Android SDK platform 36 and build-tools
36.1.0. Set `JAVA_HOME` and `ANDROID_HOME` when needed. On this Mac the build
script discovers Android Studio's JBR and the usual Android SDK location.

```sh
./scripts/build.sh
```

Output: `patches/build/libs/music-telemetry-0.1.2.rvp`.

The build downloads ReVanced CLI **6.0.0** and R8 **9.4.17**, verifying pinned
SHA-256 checksums. It compiles against the CLI's bundled **Patcher 22** API with
Kotlin **2.3.10** and packages the extension with D8. Plain Gradle Java/Kotlin
projects avoid private Gradle plugins and GitHub Packages credentials. The Gradle
wrapper distribution is checksum-verified too.

## ReVanced Manager

Use ReVanced Manager **2.6.0 or newer**. Add this URL under
**Patches → Edit → Add patches → Enter URL**:

```text
https://raw.githubusercontent.com/ThetaBird/revanced-telemetry/main/patches.json
```

The URL is a small update descriptor. Manager downloads the versioned `.rvp`
release from GitHub and loads its patches. This remote-source path also avoids a
Manager 2.6.0 local-picker bug: Manager asks Android for
`application/octet-stream`, while many document providers correctly identify an
RVP as `application/zip` and disable it in the picker.

Choose the original YouTube Music **8.40.54** APK from storage. The core
**Self-hosted Music telemetry** patch includes the in-app configuration page.
Select **In-app Music player action telemetry** for player next/previous,
**Native playback queue telemetry** and **Opened playlist snapshots** for queue/playlist capture,
**Repeat mode telemetry** and **Playback queue selection telemetry** for repeat and Up Next clicks,
**Media-session action telemetry** for media controls, and **Carousel selection
telemetry** for the supported two-row carousel renderer. For a non-root install,
also select the official **GmsCore support** patch and install ReVanced GmsCore.

Manager does not currently verify patch-bundle signatures. Release artifacts are
built by this repository's workflow and include a SHA-256 checksum for independent
verification. Updating the source downloads a newer patch bundle; applying that
update still requires repatching the original Music APK.

## Patch a local APK

First update the Listen receiver with the companion changes in
`../listen/server/listen_server.py`. Existing deployments reject the new event
names until that code is deployed. No production deployment is performed here.

```sh
python3 scripts/patch_apk.py /private/path/youtube-music-8.40.54.apk \
  --output .local/music-telemetry.apk
```

After installing, open Music's **Settings → Listen telemetry** page. Enter your
full HTTPS collector URL (for example, `https://your-listen-host/api/events`)
and your **Listen write token**, then enable telemetry. The URL and token start
empty, and capture and upload remain disabled until valid configuration is saved
and telemetry is enabled. Change configuration from the same page without
rebuilding the APK. The token is an app setting, not a Google credential; it is
masked in the settings UI and kept in private storage excluded from Android backup.
Turning telemetry off pauses capture and uploads; an in-flight request may finish.
Changing the URL clears the previous destination's queued events. Changing only
its token retains queued events and their retry IDs.

The helper embeds no collector URL or token and accepts no credential arguments.
It checks that required host-to-extension calls and the settings entry exist in
the resulting DEX before reporting success. This audit does not replace device
testing.

For optional media-session commands, add `--media-session-actions`. The patch
fails if no proven callback overrides exist or an override hierarchy is
ambiguous. Missing individual actions are reported; an enabled patch does not
imply all four actions are available.

For carousel selections, add `--carousel-selections`. Events contain the observed
heading, such as `Quick picks`, only when the bound item and its visible carousel
header can be associated. Other grids, renderers, and double-tap behavior are not
covered. Media-session and carousel hooks are optional; track, position, rating,
in-app next/previous, native queue, opened-playlist, repeat and queue-selection hooks
are included by default.

For a non-root installation, fetch the pinned official **6.0.0** base bundle and
select its GmsCore support patch:

```sh
python3 scripts/bootstrap_base.py
python3 scripts/patch_apk.py /private/path/youtube-music-8.40.54.apk \
  --media-session-actions --carousel-selections \
  --base-patches .local/base-support/patches-6.0.0.rvp \
  --enable-base-patch 'GmsCore support' \
  --output .local/music-telemetry-with-base.apk
```

The bootstrap downloads the official release from its SourceForge mirror, checks
pinned artifact/signature hashes, and verifies the detached PGP signature and
signing-subkey binding against the bundled pinned ReVanced key. The patch helper
repeats this verification before using the base. GitHub build attestation was
unavailable for the mirrored release; this workflow does not claim to verify it.
Other base bundles are rejected rather than assumed compatible.

Install [ReVanced GmsCore](https://github.com/ReVanced/GmsCore/releases/tag/v0.3.13.2.250932)
for non-root sign-in. The combined APK uses package
`app.revanced.android.apps.youtube.music`. Background playback, stream spoofing,
and ad patches are separate base-patch choices and were not included in the
validated build. Repeat `--enable-base-patch` to select additional base patches;
those combinations require their own validation.

A newly signed APK cannot update an installation with a different signing key.
The helper retains its signing keystore under `.local/`, does not uninstall
anything, and does not install onto a connected device. It rejects CLI patch
errors even if the CLI returns success, audits injected calls, and removes failed
outputs. Collector settings are stored on the device and are not part of the APK.

## Listen integration

Uploads use the exact configured `/api/events` URL, an individual JSON object,
`Authorization: Bearer ...`, and any 2xx response as acknowledgment, matching
Listen's Android uploader. Redirects are rejected. The extension sends no
requests to RYD and never forwards Google authentication headers.

Example rating request observation:

```json
{
  "id": "46b865b0-efbf-43c3-a998-92506a33cd03",
  "event": "music_action",
  "action": "like",
  "observedAt": "2026-09-07T12:00:00.000Z",
  "sourcePackage": "com.google.android.apps.youtube.music",
  "deviceId": "installation-uuid",
  "sequence": 42,
  "videoId": "lmnopqrstuv",
  "contextVideoId": "abcdefghijk",
  "videoIdBasis": "rating_request",
  "observation": "request_built",
  "rating": 1,
  "positionMs": 12345,
  "origin": null
}
```

The receiver stores `music_action`, `playback_queue` and `playlist_snapshot` privately and excludes
them from playback resolution and public now-playing, including after restart.
Existing Listen playback observations continue to drive that feed. If an upstream
worker also validates event names, its allowlist must accept all three names;
no separately deployed worker code was present in the inspected Listen checkout.

Queue and opened-playlist snapshots use the existing collector and settings. They
contain the IDs and metadata that the supported native models expose; unknown fields
are omitted. They do not upload Google credentials, request tokens, or raw protobufs.
The native queue includes video IDs, queue IDs, source playlist IDs/indexes, titles,
subtitles and thumbnails when available. Opened playlists include video IDs,
occurrence IDs, linked browse IDs, display text and mapped artwork. Playlist header
metadata includes descriptions, supplementary text and linked IDs where exposed by
the supported renderer. Opaque Elements payloads and unmapped fields are omitted. Pagination is passive: scrolling
loads more songs and produces another snapshot of the accumulated loaded rows.
No claim is made that every server-side song has loaded.

Deploy the companion receiver change in `../listen/server/listen_server.py` before
sending `playback_queue`; older receivers reject that event name. Local changes
have not been deployed.

See [playlist export setup and scheduling](server/README.md) for authenticated
full-library exports, chunked snapshots, and complete-snapshot reassembly.

## Delivery guarantees and diagnostics

- Database and HTTP work run on a bounded background executor. Capture is
  asynchronous; a process crash before SQLite commit can lose pending callbacks.
- SQLite preserves event IDs, a device ID, sequence, and queued events across
  process restarts. Delivery is at least once for retained committed events;
  Listen deduplicates retries by `id`.
- The queue retains up to 10,000 events, evicting oldest entries with a durable
  `droppedEvents` count. The 1,024-callback memory limit exposes `droppedCallbacks`.
- Failures retain queued events and retry with backoff while Music's process is
  alive. Force-stop prevents upload until the next application initialization.
- Inspect `adb logcat -s MusicTelemetry` for safe exception categories and HTTP
  status. For example, 401 indicates a credential rejection and 422 usually
  indicates receiver/schema incompatibility. Raw errors and tokens are not logged.
- Keep `PARQUET_INCLUDE_RAW_PAYLOAD=true` in Listen's archiver to preserve action
  and playlist-specific fields after SQLite retention cleanup.

## Verification

See [validation evidence and artifact checksums](docs/validation.md) for the
recorded results and remaining acceptance gaps.

```sh
./scripts/build.sh
python3 -m unittest discover -s tests/server -v
python3 -m unittest discover -s tests/scripts -v
./scripts/test-android.sh
```

The Android script uses a local emulator (default AVD
`Medium_Phone_API_36.1`) and refuses physical-device serials. It tests real SQLite
and emitted callback payloads, including exact rating targets, player-control
origins, and carousel heading/target association with negative cases. This harness
is separate from the actual Music installation smoke test. If it launches
an emulator it reports its serial and leaves it available for further tests.

The companion Listen regression suite can be run with its existing dependencies:

```sh
python3 -m venv .venv-test
.venv-test/bin/pip install -r ../listen/server/archiver/requirements.txt
.venv-test/bin/python -m unittest discover -s ../listen/server -p 'test_*.py'
```

The current local artifact is `.local/music-8.40.54-controls.apk`. It contains no
embedded collector configuration. In **Settings → Listen telemetry**, enter your
collector URL and Listen write token, enable telemetry, and save. Updating either
setting requires no restart or rebuild.

The 35 Kotlin patch tests, 10 exporter/integration tests, 8 helper tests and Android
runtime harness pass.
The harness exercises the real form, invalid input, persistence, disabled capture,
live endpoint/token changes, retry identity and preserved playback context. Its
HTTPS transport is an in-memory test substitute. The actual patched Music app's
native settings entry and form were separately checked on the emulator.
See [validation evidence](docs/validation.md) for artifact checksums and limits.

Still unverified: signed-in action payloads from real Music UI interactions,
which live home-feed sections use the instrumented carousel renderer,
authenticated playlist retrieval, and production receiver/worker deployment.
Playlist export requires its separate browser-auth file.

## Source references

GPL-3.0; see [LICENSE](LICENSE). Fingerprint anchors are adapted and attributed
in source from [inotia00/revanced-patches at 54ce1d4](https://github.com/inotia00/revanced-patches/tree/54ce1d4808b12903602a1a0d9a721ee835093c38).
The project structure originated from the [official patch template](https://github.com/ReVanced/revanced-patches-template/tree/bd4b564fc0fc8f7ade8dcd41eb4aaaf59adad6ae).
The pinned CLI's [dependency catalog](https://github.com/ReVanced/revanced-cli/blob/v6.0.0/gradle/libs.versions.toml)
documents the upstream API dependencies. The official base is
[ReVanced patches v6.0.0](https://gitlab.com/ReVanced/revanced-patches/-/releases/v6.0.0),
available from its [release mirror](https://sourceforge.net/projects/revanced.mirror/files/v6.0.0/).
The pinned key fingerprint is corroborated by the ReVanced-owned
[release workflow log](https://github.com/ReVanced/revanced-manager/actions/runs/27447613839).
