# Playlist exports to Listen

Use the existing receiver in `../listen/server/listen_server.py`. This directory
contains the exporter, not a second receiver. Python 3.10+ is required.

The receiver changes in the sibling Listen checkout must be deployed before
sending new events. Existing deployments reject the new event names until updated.
No production endpoint or credentials are configured in this repository.

```sh
# From the revanced repository, start the existing receiver locally.
export LISTEN_API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
YTMUSIC_RESOLVER_ENABLED=false python3 ../listen/server/listen_server.py \
  --host 127.0.0.1 --port 8765 --database /tmp/listen-test.sqlite3
```

Retain the existing server's authentication, reverse proxy, storage, archive and
public now-playing deployment. Both the extension and exporter use its full
HTTPS `/api/events` endpoint, `Authorization: Bearer <LISTEN_API_KEY>`, and
`Content-Type: application/json`. Each request sends one event object; any 2xx
response acknowledges delivery. Existing id-based deduplication applies. The
worker must forward these new event types unchanged to the updated receiver.

## Export playlists

```sh
python3 -m venv .venv
.venv/bin/pip install -r server/requirements.txt
# Configure browser credentials using ytmusicapi's interactive browser setup.
.venv/bin/ytmusicapi browser --file /private/path/browser.json
# Set LISTEN_API_KEY to the same write key used by your Listen deployment.
.venv/bin/python -m server.export_playlists \
  --auth /private/path/browser.json \
  --endpoint https://your-listen-host/api/events \
  --device-id playlist-exporter
```

[Official authentication instructions](https://ytmusicapi.readthedocs.io/en/stable/setup/browser.html).
Keep the authentication file private and outside version control. The exporter
uses browser authentication separately from the Android app and never uploads
Google authentication headers. Its one-shot command exports the library and
Liked Songs (`LM`), requesting `limit=None` for library and track pagination.
Repeat `--playlist PL_ID` to select explicit playlists; add `--no-liked` to omit
LM. Run the command from this repository using cron or a systemd timer with a
private environment file for `LISTEN_API_KEY`. A nonzero exit means setup,
retrieval, or upload failed. Logs show counts, failure stage, a safe exception category, and
numeric HTTP status when available. They never include credentials, upstream response bodies, or exception messages.

HTTP is accepted for loopback testing; non-loopback HTTP requires `--allow-http`
for development. HTTPS redirects are rejected so credentials cannot be forwarded.

## Event contract

Music hooks send `event: "music_action"` and an `action` such as `track_loaded`,
`like`, `dislike`, `skip_requested`, or `carousel_song_selected`. Required fields
are `id`, `observedAt`, `deviceId`, `sourcePackage`, and `action`. Additional camelCase fields include
`videoId`, `positionMs`, `sequence`, `origin`, and `playbackSessionId`.
These observations never replace Listen's public now-playing state.

Ratings carry the request's target in `videoId`, with
`videoIdBasis: "rating_request"` and `observation: "request_built"`; `contextVideoId`
separately
identifies the current player. Repeated request construction may produce repeated
observations. In-app next/previous commands use `origin: "player_controls"`;
framework media callbacks use `origin: "media_session"`. Optional carousel events
use `origin: "music_carousel"`, the selected watch-endpoint `videoId`, and the
visible heading in `sourceTitle`. A heading is observed UI text, not a canonical
Quick Play classification. See the [coverage boundaries](../README.md#implemented-coverage).

Playlist events use the same envelope:

```json
{
  "id": "snapshot-uuid:0",
  "event": "playlist_snapshot",
  "observedAt": "2026-09-07T12:00:00Z",
  "deviceId": "playlist-exporter",
  "sourcePackage": "com.google.android.apps.youtube.music",
  "snapshotId": "snapshot-uuid",
  "playlistId": "PL_example",
  "title": "Example playlist",
  "partIndex": 0,
  "partCount": 1,
  "totalTracks": 1,
  "tracks": [{"position": 0, "videoId": "abcdefghijk", "setVideoId": "item-id"}]
}
```

Parts stay below 60 KiB to fit Listen's 64 KiB single-event cap. Positions are
zero-based across the entire playlist. Duplicate songs remain separate entries;
`setVideoId` identifies their playlist occurrences. Missing video IDs are retained
as null for unavailable entries. Selected track metadata uses camelCase names;
Google request/feedback tokens are excluded.

The receiver stores parts as ordinary private events. It does **not** expose a
public playlist endpoint or promote individual parts into a current snapshot.
Group stored events by `snapshotId` and pass each group to
`server.export_playlists.reassemble_snapshot(events)`. It returns a snapshot only
when every part and every contiguous track position are present; otherwise it
returns `None`. Select the latest **complete** group for a playlist. Failed or
interrupted exports leave earlier complete snapshots available. Re-running an
export creates a fresh group. Historical exports are not deleted automatically.
An export is a point-in-time API read, not a transaction against playlist changes
made concurrently in YouTube Music. Declared incomplete pagination is rejected.

## Verification

```sh
python3 -m unittest discover -s tests/server -v
python3 -m unittest discover -s ../listen/server -p 'test_*.py'
```

Tests use synthetic playlists and local HTTP, including playlists over 100 tracks,
duplicate entries, chunk reassembly, failed exports, and the real Listen validator.
Live Google authentication and production worker deployment require your configured
account and endpoint.

Listen's Parquet archive preserves action fields and playlist parts in the default
`raw_payload` column (`PARQUET_INCLUDE_RAW_PAYLOAD=true`). Keep it enabled: setting
it to false discards these extra fields from archived data; they are not separate
Parquet columns. Reassembly can read the original event objects from this column.
