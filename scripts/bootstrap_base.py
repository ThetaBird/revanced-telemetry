#!/usr/bin/env python3
"""Fetch and verify the pinned signed official Music-compatible base bundle."""
import os
from pathlib import Path
import subprocess
from bootstrap import ROOT, bootstrap, fetch

BASE = ROOT / '.local/base-support/patches-6.0.0.rvp'
SHA256 = '3d3b17720f0a3a40de850e4f41dea8e3628686dfd5aaca5ae76aedd6a0fbb29f'
SIGNATURE_SHA256 = 'd2d543334cab8f64acc54d838acc1b0d6b54bbaef0040c29d721ae55f82cbf84'
MIRROR = 'https://sourceforge.net/projects/revanced.mirror/files/v6.0.0/'


def verify_base(path, java, cli):
    import hashlib
    if hashlib.sha256(path.read_bytes()).hexdigest() != SHA256:
        raise ValueError('Base must match the pinned official v6.0.0 release')
    signature = BASE.with_suffix('.rvp.asc')
    fetch(signature, MIRROR + 'patches-6.0.0.rvp.asc/download', SIGNATURE_SHA256)
    subprocess.run([java, '-cp', str(cli), str(ROOT / 'scripts/VerifyPgp.java'),
                    str(ROOT / 'scripts/keys/revanced-patches.asc'), str(path), str(signature)], check=True)


def main():
    cli = bootstrap()
    fetch(BASE, MIRROR + 'patches-6.0.0.rvp/download', SHA256)
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
    java = str(jdk / 'bin/java') if (jdk / 'bin/java').exists() else 'java'
    verify_base(BASE, java, cli)
    print(BASE.relative_to(ROOT))


if __name__ == '__main__':
    main()
