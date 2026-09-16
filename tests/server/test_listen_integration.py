"""Exercise exported events against the real sibling Listen implementation."""
import importlib.util
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from server.export_playlists import export, reassemble_snapshot, upload

LISTEN = Path(__file__).resolve().parents[3] / 'listen/server/listen_server.py'


@unittest.skipUnless(LISTEN.exists(), 'sibling Listen checkout is required')
class ListenIntegrationTests(unittest.TestCase):
    def test_rating_repeat_and_queue_actions_remain_private_and_preserve_targets(self):
        spec = importlib.util.spec_from_file_location('listen_controls_integration', LISTEN)
        listen = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(listen)
        actions = [
            dict(action='like', rating=1), dict(action='dislike', rating=-1),
            dict(action='remove_rating', rating=0),
            dict(action='repeat_mode_changed', repeatMode=1, repeatModeName='one', repeatScope='song', playlistId='PL_current'),
            dict(action='repeat_mode_changed', repeatMode=2, repeatModeName='all', repeatScope='queue', playlistId='PL_current'),
            dict(action='repeat_mode_changed', repeatMode=0, repeatModeName='off', repeatScope='off'),
            dict(action='queue_song_selected', queueId='9223372036854775807', playlistId='PL_target',
                 playlistIndex=7, contextVideoId='context1234', origin='playback_queue'),
        ]
        events = [dict(id=f'control-{i}', event='music_action', observedAt='2026-09-15T00:00:00Z',
                       deviceId='phone', sourcePackage='com.google.android.apps.youtube.music',
                       videoId='abcdefghijk', **action) for i, action in enumerate(actions)]
        class Resolver:
            def enrich(self, event):
                raise AssertionError('Control events must bypass playback resolution')
        with tempfile.TemporaryDirectory() as temp:
            repository = listen.EventRepository(Path(temp) / 'listen.db')
            server = ThreadingHTTPServer(('127.0.0.1', 0), listen.make_handler(repository, 'secret', resolver=Resolver()))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                for event in events + [events[-1]]:
                    upload(f'http://127.0.0.1:{server.server_port}/api/events', 'secret', event)
                stored = {event['id']: event for event in repository.recent(100)}
                self.assertEqual(len(stored), len(events), 'retry created a second click')
                for event in events:
                    for key, value in event.items():
                        self.assertEqual(stored[event['id']][key], value)
                self.assertEqual(repository.recent_for_sources(listen.YOUTUBE_MUSIC_PACKAGES, 1), [])
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def test_chunked_export_through_real_http_and_database(self):
        spec = importlib.util.spec_from_file_location('listen_receiver_integration', LISTEN)
        listen = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(listen)
        class Client:
            def get_playlist(self, playlist_id, limit):
                assert limit is None
                return {'title': 'Playlist', 'tracks': [
                    {'videoId': 'abcdefghijk', 'setVideoId': str(i), 'title': 'Long title ' * 30}
                    for i in range(350)]}
        class Resolver:
            def enrich(self, event):
                raise AssertionError('Playlist must bypass now-playing resolver')
        with tempfile.TemporaryDirectory() as temp:
            repository = listen.EventRepository(Path(temp) / 'listen.db')
            server = ThreadingHTTPServer(('127.0.0.1', 0), listen.make_handler(repository, 'secret', resolver=Resolver()))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                result = export(Client(), f'http://127.0.0.1:{server.server_port}/api/events', 'secret', ['PL1'], False)
                self.assertEqual(result, (['PL1'], []))
                stored = repository.recent(100)
                self.assertGreater(len(stored), 1)
                restored = reassemble_snapshot(stored)
                self.assertEqual(len(restored['tracks']), 350)
                self.assertEqual(restored['tracks'][-1]['setVideoId'], '349')
                self.assertEqual(repository.recent_for_sources(listen.YOUTUBE_MUSIC_PACKAGES, 1), [])
            finally:
                server.shutdown()
                server.server_close()
                thread.join()
