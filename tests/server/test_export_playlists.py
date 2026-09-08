import unittest
from unittest.mock import Mock
from server.export_playlists import endpoint_url, export, make_snapshot, snapshot_events, reassemble_snapshot


class ExportTests(unittest.TestCase):
    def test_all_tracks_order_duplicates_and_library_liked(self):
        client = Mock()
        client.get_library_playlists.return_value = [{'playlistId': 'PL1'}]
        tracks = [{'videoId': 'repeat', 'setVideoId': str(i)} for i in range(150)]
        client.get_playlist.return_value = {'title': 'Large', 'trackCount': 150, 'tracks': tracks}
        uploader = Mock()
        succeeded, failed = export(client, 'https://server/v1/playlists', 'secret', uploader=uploader)
        self.assertEqual(succeeded, ['PL1', 'LM'])
        self.assertEqual(failed, [])
        client.get_library_playlists.assert_called_once_with(limit=None)
        self.assertEqual(client.get_playlist.call_args_list[0].kwargs, {'limit': None})
        snapshot = uploader.call_args_list[0].args[2]
        self.assertEqual(len(snapshot['tracks']), 150)
        self.assertEqual(snapshot['tracks'][149]['setVideoId'], '149')
        self.assertEqual(snapshot['tracks'][149]['position'], 149)

    def test_explicit_ids_and_failures_do_not_upload(self):
        client = Mock()
        client.get_playlist.side_effect = [RuntimeError('private auth detail'), {'tracks': []}]
        uploader = Mock()
        self.assertEqual(export(client, 'https://server', 'secret', ['bad', 'good'], False, uploader), (['good'], ['bad']))
        client.get_library_playlists.assert_not_called()
        uploader.assert_called_once()
        self.assertEqual(uploader.call_args.args[2]['playlistId'], 'good')

    def test_partial_or_malformed_snapshot_rejected(self):
        for playlist in ({}, {'tracks': [], 'trackCount': 12}, {'tracks': [], 'continuation': 'more'}):
            with self.assertRaises(ValueError):
                make_snapshot('PL1', playlist)

    def test_chunking_and_complete_reassembly(self):
        import json
        snapshot = make_snapshot('PL1', {'tracks': [
            {'videoId': 'repeat', 'setVideoId': str(i), 'title': 'é' * 200}
            for i in range(350)]})
        events = snapshot_events(snapshot, 'server')
        self.assertGreater(len(events), 1)
        self.assertTrue(all(len(json.dumps(e, ensure_ascii=False).encode()) < 65536 for e in events))
        self.assertIsNone(reassemble_snapshot(events[:-1]))
        restored = reassemble_snapshot(list(reversed(events)) + [events[0]])
        self.assertEqual(restored['tracks'], snapshot['tracks'])
        altered = dict(events[0], tracks=[])
        self.assertIsNone(reassemble_snapshot(events + [altered]))

    def test_untrusted_reassembly_and_empty_snapshot(self):
        events = snapshot_events(make_snapshot('PL1', {'title': 'Empty', 'tracks': []}), 'server')
        self.assertEqual(reassemble_snapshot(events)['tracks'], [])
        for value in (None, {}, 'bad', [None], [dict(events[0], partCount=50001)],
                      [dict(events[0], partCount=True)], [dict(events[0], totalTracks=True)],
                      [dict(events[0], tracks=[{'position': 0}])],
                      [dict(events[0], partCount=2)], [dict(events[0], title=None)]):
            self.assertIsNone(reassemble_snapshot(value))
        self.assertIsNone(reassemble_snapshot(events + [dict(events[0], title='Changed')]))
        with self.assertRaises(ValueError):
            snapshot_events(make_snapshot('PL1', {'title': 'é' * 40000, 'tracks': []}), 'server')
        single = snapshot_events(make_snapshot('PL1', {'tracks': [{'videoId': 'a'}]}), 'server')
        malformed = dict(single[0], tracks=[dict(single[0]['tracks'][0], position=False)])
        self.assertIsNone(reassemble_snapshot([malformed]))

    def test_cli_sanitized_failure_stages(self):
        import contextlib
        import io
        import os
        import sys
        from types import SimpleNamespace
        from urllib.error import HTTPError
        from unittest.mock import patch
        from server.export_playlists import main
        secret = 'PRIVATE_BEARER_SECRET'
        client = Mock()
        client.get_library_playlists.return_value = [{'playlistId': 'PL1'}]
        argv = ['export', '--auth', 'auth.json', '--endpoint', 'https://server/api/events', '--no-liked']
        cases = [
            ('retrieval', HTTPError('https://private', 401, secret, {}, None), None),
            ('packing', None, {}),
            ('upload', None, {'tracks': []}),
        ]
        for stage, retrieval_error, playlist in cases:
            client.get_playlist.side_effect = retrieval_error
            client.get_playlist.return_value = playlist
            output = io.StringIO()
            with patch.object(sys, 'argv', argv), patch.dict(os.environ, {'LISTEN_API_KEY': secret}), \
                    patch.dict(sys.modules, {'ytmusicapi': SimpleNamespace(YTMusic=lambda _: client)}), \
                    patch('server.export_playlists.build_opener') as opener, \
                    contextlib.redirect_stderr(output), contextlib.redirect_stdout(io.StringIO()):
                upload_error = HTTPError('https://private', 503, secret, {}, None)
                opener.return_value.open.side_effect = upload_error
                self.assertEqual(main(), 1)
                upload_error.close()
            self.assertIn(f'stage={stage}', output.getvalue())
            self.assertNotIn(secret, output.getvalue())
            self.assertNotIn('https://private', output.getvalue())
            if stage in ('retrieval', 'upload'):
                self.assertIn('exception=HTTPError', output.getvalue())
                self.assertIn('http_status=' + ('401' if stage == 'retrieval' else '503'), output.getvalue())
        client.get_library_playlists.side_effect = RuntimeError(secret)
        output = io.StringIO()
        with patch.object(sys, 'argv', argv), patch.dict(os.environ, {'LISTEN_API_KEY': secret}), \
                patch.dict(sys.modules, {'ytmusicapi': SimpleNamespace(YTMusic=lambda _: client)}), \
                contextlib.redirect_stderr(output):
            self.assertEqual(main(), 1)
        self.assertIn('stage=discovery exception=RuntimeError', output.getvalue())
        self.assertNotIn(secret, output.getvalue())

    def test_transport_policy(self):
        self.assertEqual(endpoint_url('https://server/api/events/'), 'https://server/api/events')
        self.assertEqual(endpoint_url('http://127.0.0.1:8765/api/events'), 'http://127.0.0.1:8765/api/events')
        for url in ('http://example.com', 'https://user:secret@example.com', 'https://example.com/?secret=x', 'file:///tmp/receiver'):
            with self.assertRaises(ValueError):
                endpoint_url(url)
        self.assertEqual(endpoint_url('http://devbox/api/events', True), 'http://devbox/api/events')


if __name__ == '__main__':
    unittest.main()
