"""One-shot, authenticated playlist snapshot export; schedule externally."""
import argparse
from datetime import datetime, timezone
import ipaddress
import json
import os
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
MAX_BODY = 64 * 1024
PART_BUDGET = 60 * 1024


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def endpoint_url(base, allow_http=False):
    parsed = urlsplit(base)
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('endpoint requires a host and no credentials, query, or fragment')
    try:
        loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = parsed.hostname == 'localhost'
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and (loopback or allow_http)):
        raise ValueError('HTTPS required (or explicit --allow-http for development)')
    if not parsed.path.rstrip('/').endswith('/api/events'):
        raise ValueError('endpoint must be the full Listen /api/events URL')
    return base.rstrip('/')


def make_snapshot(playlist_id, playlist):
    if not isinstance(playlist, dict) or not isinstance(playlist.get('tracks'), list):
        raise ValueError('playlist response lacks tracks')
    tracks = playlist['tracks']
    # Fail closed when upstream supplies evidence of incomplete pagination.
    count = playlist.get('trackCount')
    if isinstance(count, int) and count > len(tracks):
        raise ValueError('playlist response is incomplete')
    if playlist.get('continuations') or playlist.get('continuation'):
        raise ValueError('playlist response still has continuations')
    snapshot = {
        'playlistId': playlist_id,
        'title': playlist.get('title') or playlist_id,
        'observedAt': datetime.now(timezone.utc).isoformat(),
        'tracks': [{
            'position': position, 'videoId': track.get('videoId'),
            'setVideoId': track.get('setVideoId'), 'title': track.get('title'),
            'artists': track.get('artists', []), 'album': track.get('album'),
            'durationSeconds': track.get('duration_seconds'),
            'isAvailable': track.get('isAvailable'), 'likeStatus': track.get('likeStatus'),
        } for position, track in enumerate(tracks)],
    }
    return snapshot


def snapshot_events(snapshot, device_id):
    snapshot_id = str(uuid.uuid4())
    base = {"event": "playlist_snapshot", "snapshotId": snapshot_id,
            "playlistId": snapshot["playlistId"], "title": snapshot["title"],
            "deviceId": device_id, "sourcePackage": "com.google.android.apps.youtube.music",
            "observedAt": snapshot["observedAt"], "totalTracks": len(snapshot["tracks"])}
    # Reserve worst-case part counters and IDs while packing by UTF-8 byte size.
    base_size = len(json.dumps(dict(base, id=snapshot_id + ":50000", partIndex=50000,
                                    partCount=50000, tracks=[]), ensure_ascii=False).encode())
    if base_size > PART_BUDGET:
        raise ValueError("playlist metadata exceeds the event size limit")
    parts, current, current_size = [], [], base_size
    for track in snapshot["tracks"]:
        size = len(json.dumps(track, ensure_ascii=False).encode()) + 2
        if base_size + size > PART_BUDGET:
            raise ValueError("a track exceeds the event size limit")
        if current and current_size + size > PART_BUDGET:
            parts.append(current)
            current, current_size = [], base_size
        current.append(track)
        current_size += size
    parts.append(current)
    if len(snapshot["tracks"]) > 50000:
        raise ValueError("playlist exceeds track limit")
    return [dict(base, id=f"{snapshot_id}:{index}", partIndex=index,
                 partCount=len(parts), tracks=tracks) for index, tracks in enumerate(parts)]


def reassemble_snapshot(events):
    """Return a complete snapshot or None; callers keep their prior snapshot on None."""
    if not isinstance(events, list) or not events:
        return None
    first = events[0]
    if not isinstance(first, dict) or type(first.get("partCount")) is not int or not 1 <= first["partCount"] <= 50000:
        return None
    if type(first.get("totalTracks")) is not int or not 0 <= first["totalTracks"] <= 50000:
        return None
    for field in ("snapshotId", "playlistId", "deviceId", "observedAt", "title"):
        if not isinstance(first.get(field), str):
            return None
    if first["totalTracks"] == 0 and first["partCount"] != 1:
        return None
    keys = ("snapshotId", "playlistId", "partCount", "totalTracks", "deviceId", "observedAt", "title")
    parts = {}
    for event in events:
        if not isinstance(event, dict) or not isinstance(event.get("tracks"), list):
            return None
        if event.get("event") != "playlist_snapshot" or any(event.get(k) != first.get(k) for k in keys):
            return None
        if type(event.get("partCount")) is not int or type(event.get("totalTracks")) is not int:
            return None
        if first["totalTracks"] and not event["tracks"]:
            return None
        index = event.get("partIndex")
        if type(index) is not int or index < 0 or index >= first["partCount"]:
            return None
        if index in parts and parts[index]["tracks"] != event["tracks"]:
            return None
        parts[index] = event
    if len(parts) != first["partCount"]:
        return None
    tracks = [track for i in range(first["partCount"]) for track in parts[i]["tracks"]]
    if any(not isinstance(t, dict) or type(t.get("position")) is not int for t in tracks):
        return None
    if len(tracks) != first["totalTracks"] or [t.get("position") for t in tracks] != list(range(len(tracks))):
        return None
    return {"snapshotId": first["snapshotId"], "playlistId": first["playlistId"],
            "observedAt": first["observedAt"], "title": first.get("title", ""), "tracks": tracks}


def upload(url, token, event):
    data = json.dumps(event, allow_nan=False, ensure_ascii=False).encode()
    if len(data) > MAX_BODY:
        raise ValueError('event exceeds receiver payload limit')
    request = Request(url, data=data, headers={
        'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token,
    }, method='POST')
    # Never forward a bearer token through a redirect.
    with build_opener(NoRedirects()).open(request, timeout=30) as response:
        if not 200 <= response.status < 300:
            raise ValueError('receiver did not acknowledge event')


def report_failure(stage, error):
    """CLI diagnostic using only fixed labels and numeric HTTP status, never messages."""
    safe_types = (HTTPError, URLError, TimeoutError, ConnectionError, ValueError,
                  TypeError, KeyError, ImportError, OSError, RuntimeError)
    kind = next((kind.__name__ for kind in safe_types if isinstance(error, kind)), "Exception")
    status = getattr(error, "code", None)
    if type(status) is not int:
        status = getattr(getattr(error, "response", None), "status_code", None)
    suffix = f" http_status={status}" if type(status) is int and 100 <= status <= 599 else ""
    print(f"Export failure: stage={stage} exception={kind}{suffix}", file=sys.stderr)
    if isinstance(error, HTTPError):
        error.close()


def export(client, url, token, playlist_ids=None, include_liked=True, uploader=upload,
           device_id="playlist-exporter", on_failure=None):
    ids = list(playlist_ids) if playlist_ids else [p['playlistId'] for p in client.get_library_playlists(limit=None)]
    if include_liked:
        ids.append('LM')
    succeeded, failed = [], []
    for playlist_id in dict.fromkeys(ids):
        stage = "retrieval"
        try:
            playlist = client.get_playlist(playlist_id, limit=None)
            stage = "packing"
            snapshot = make_snapshot(playlist_id, playlist)
            events = snapshot_events(snapshot, device_id)
            stage = "upload"
            for event in events:
                uploader(url, token, event)
            succeeded.append(playlist_id)
        except Exception as error:
            if on_failure is not None:
                on_failure(stage, error)
            if isinstance(error, HTTPError):
                error.close()
            failed.append(playlist_id)
    return succeeded, failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth', required=True, help='ytmusicapi browser authentication JSON path')
    parser.add_argument('--endpoint', required=True, help='full Listen /api/events URL')
    parser.add_argument('--device-id', default='playlist-exporter')
    parser.add_argument('--playlist', action='append', help='explicit playlist ID, repeatable; otherwise export library')
    parser.add_argument('--no-liked', action='store_true', help='omit automatic LM export')
    parser.add_argument('--allow-http', action='store_true', help='allow non-loopback HTTP for development')
    args = parser.parse_args()
    token = os.environ.get('LISTEN_API_KEY', '')
    if not token or '\n' in token or '\r' in token:
        parser.error('set a single-line LISTEN_API_KEY')
    try:
        url = endpoint_url(args.endpoint, args.allow_http)
    except ValueError as error:
        parser.error(str(error))
    try:
        from ytmusicapi import YTMusic
        client = YTMusic(args.auth)
        succeeded, failed = export(client, url, token, args.playlist, not args.no_liked, device_id=args.device_id,
                                   on_failure=report_failure)
    except Exception as error:
        report_failure("discovery", error)
        return 1
    print(f'Exported {len(succeeded)} playlist(s); {len(failed)} failed.')
    if failed:
        print('Failed playlists retain their previous snapshots; retry the export.', file=sys.stderr)
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
