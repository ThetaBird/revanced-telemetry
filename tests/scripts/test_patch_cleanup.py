"""A rejected or interrupted build must never leave a deliverable APK."""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import patch_apk


class PatchCleanupTests(unittest.TestCase):
    def run_build(self, failure):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.local').mkdir()
            bundle = root / 'patches/build/libs/music-telemetry-0.1.2.rvp'
            bundle.parent.mkdir(parents=True)
            bundle.touch()
            apk = root / 'input.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', 'fixture')
            output = root / 'output.apk'
            calls = []

            def run(command, **kwargs):
                calls.append(command)
                if len(calls) == 1:
                    patch_arguments = Path(command[-1][1:]).read_text()
                    self.assertNotIn('-Oendpoint', patch_arguments)
                    self.assertNotIn('-Otoken', patch_arguments)
                    self.assertIn('Self-hosted Music telemetry', patch_arguments)
                    self.assertIn('Native playback queue telemetry', patch_arguments)
                    self.assertIn('Opened playlist snapshots', patch_arguments)
                    self.assertIn('Repeat mode telemetry', patch_arguments)
                    self.assertIn('Playback queue selection telemetry', patch_arguments)
                output.write_bytes(b'generated but not yet audited')
                if len(calls) == 1:
                    if failure == 'cli_interrupt':
                        raise KeyboardInterrupt()
                    return subprocess.CompletedProcess(command, 0,
                        'SEVERE: individual patch failed\n' if failure == 'severe' else 'patched\n')
                if failure == 'audit_exception':
                    raise OSError('cannot launch audit')
                return subprocess.CompletedProcess(command, 1 if failure == 'audit_failure' else 0, 'audit\n')

            arguments = ['patch_apk.py', str(apk), '--output', str(output)]
            with patch.object(patch_apk, 'ROOT', root), patch.object(patch_apk, 'bootstrap', return_value=root / 'cli.jar'), \
                    patch.object(patch_apk.subprocess, 'run', side_effect=run), patch.object(sys, 'argv', arguments), \
                    contextlib.redirect_stdout(io.StringIO()):
                if failure in ('cli_interrupt', 'audit_exception'):
                    with self.assertRaises(KeyboardInterrupt if failure == 'cli_interrupt' else OSError):
                        patch_apk.main()
                else:
                    self.assertEqual(0 if failure == 'success' else 1, patch_apk.main())
            self.assertEqual(failure == 'success', output.exists())
            self.assertEqual([], list((root / '.local').iterdir()), 'temporary arguments/work must be removed')
            self.assertEqual(1 if failure in ('cli_interrupt', 'severe') else 2, len(calls))

    def test_embedded_credentials_are_no_longer_accepted(self):
        for option in ('--endpoint', '--token-file'):
            with self.subTest(option=option), patch.object(sys, 'argv',
                    ['patch_apk.py', 'input.apk', option, 'value']), \
                    patch.object(patch_apk, 'bootstrap') as bootstrap, \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    patch_apk.main()
                self.assertEqual(2, error.exception.code)
                bootstrap.assert_not_called()

    def test_cli_interrupt_removes_output(self):
        self.run_build('cli_interrupt')

    def test_zero_exit_with_patch_failure_removes_output(self):
        self.run_build('severe')

    def test_audit_exception_removes_output(self):
        self.run_build('audit_exception')

    def test_audit_failure_removes_output(self):
        self.run_build('audit_failure')

    def test_success_retains_only_audited_output(self):
        self.run_build('success')
