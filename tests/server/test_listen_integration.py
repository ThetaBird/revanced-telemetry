"""Exercise exported events against the real sibling Listen implementation."""
import importlib.util
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from server.export_playlists import export, reassemble_snapshot

LISTEN = Path(__file__).resolve().parents[3] / 'listen/server/listen_server.py'


@unittest.skipUnless(LISTEN.exists(), 'sibling Listen checkout is required')
class ListenIntegrationTests(unittest.TestCase):
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
