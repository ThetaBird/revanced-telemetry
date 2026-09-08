#!/usr/bin/env python3
"""Patch a supplied Music APK. Configure telemetry inside Music settings after installation."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import re
import shutil

from bootstrap import ROOT, bootstrap
from bootstrap_base import verify_base


def argument_file_line(value):
    if '\n' in value or '\r' in value:
        raise ValueError('Newlines are not allowed in patch arguments')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / '.local/music-telemetry.apk')
    parser.add_argument('--base-patches', type=Path, help='Optional verified official Patcher-22 base RVP')
    parser.add_argument('--enable-base-patch', action='append', default=[])
    parser.add_argument('--media-session-actions', action='store_true',
                        help='Require optional exact Android media-session command hooks')
    parser.add_argument('--carousel-selections', action='store_true',
                        help='Require optional two-row carousel selection and observed heading hooks')
    parser.add_argument('--aapt2', type=Path, help='Optional apktool-compatible aapt2 override')
    args = parser.parse_args()
    apk = args.apk.resolve()
    if not apk.is_file() or not zipfile.is_zipfile(apk):
        parser.error('supply a local APK ZIP file')
    if args.output.exists():
        parser.error('output already exists; choose a new output path')
    if args.enable_base_patch and not args.base_patches:
        parser.error('--enable-base-patch requires --base-patches')
    cli = bootstrap()
    bundle = ROOT / 'patches/build/libs/music-telemetry-0.1.0.rvp'
    if not bundle.exists():
        parser.error('run scripts/build.sh first')
    java_home = os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')
    java = str(Path(java_home) / 'bin/java') if (Path(java_home) / 'bin/java').exists() else 'java'
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    # This bundle was built from our local sources and has no remote build attestation.
    command = ['patch', '--exclusive', '-b', '-p', str(bundle), '-e', 'Self-hosted Music telemetry']
    command += ['-e', 'In-app Music player action telemetry']
    if args.media_session_actions:
        command += ['-e', 'Media-session action telemetry']
    if args.carousel_selections:
        command += ['-e', 'Carousel selection telemetry']
    if args.base_patches:
        verify_base(args.base_patches.resolve(), java, cli)
        # Detached PGP and pinned content are verified above. GitHub Sigstore attestation
        # is unavailable for this mirrored release; do not claim native provenance verification.
        command += ['-b', '-p', str(args.base_patches.resolve())]
        for name in args.enable_base_patch:
            command += ['-e', name]
    if args.aapt2:
        command += ['--custom-aapt2-binary', str(args.aapt2.resolve())]
    # Keep signing identity and temporary files inside the ignored local directory.
    work = Path(tempfile.mkdtemp(prefix='patch-work-', dir=ROOT / '.local'))
    command += ['--keystore', str(ROOT / '.local/music-telemetry.keystore'),
                '-t', str(work), '-o', str(output), str(apk)]
    succeeded = False
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=ROOT / '.local', suffix='.args', delete=False) as file:
            argument_path = Path(file.name)
            try:
                file.writelines(argument_file_line(item) for item in command)
                file.flush()
                os.chmod(argument_path, 0o600)
                # Argument files preserve spaces and special characters in local paths.
                result = subprocess.run([java, '-jar', str(cli), '@' + str(argument_path)],
                                        cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                print(result.stdout, end='')
            finally:
                argument_path.unlink(missing_ok=True)
        # CLI can sign and return zero even when an individual patch fails.
        if result.returncode or re.search(r'(?m)^SEVERE:', result.stdout):
            output.unlink(missing_ok=True)
            print('Patching failed; rejected output removed.')
            return result.returncode or 1
        if not output.is_file():
            raise SystemExit('CLI did not produce the expected APK; inspect the patch errors above.')
        audit = [java, '-cp', str(cli), str(ROOT / 'scripts/AuditApk.java'), str(output)]
        if args.media_session_actions:
            audit.append('media')
        if args.carousel_selections:
            audit.append('carousel')
        checked = subprocess.run(audit, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        print(checked.stdout, end='')
        if checked.returncode:
            output.unlink(missing_ok=True)
            print('Post-patch audit failed; rejected output removed.')
            return checked.returncode
        print(f'Patched APK: {output}')
        succeeded = True
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)
        if not succeeded:
            output.unlink(missing_ok=True)



if __name__ == '__main__':
    raise SystemExit(main())
