#!/usr/bin/env python3
"""Fetch the pinned public CLI/API artifact without GitHub Packages credentials."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / '.local/toolchain/revanced-cli-6.0.0-all.jar'
URL = 'https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar'
SHA256 = 'c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958'
R8 = ROOT / '.local/toolchain/r8-9.4.17.jar'
R8_URL = 'https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.4.17/r8-9.4.17.jar'
R8_SHA256 = '2100511344497f041644a4d63fb7be8a516ce9bace30b7d17ab27cc93a0e58d4'


def fetch(path, url, sha256):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if hashlib.sha256(path.read_bytes()).hexdigest() != sha256:
            raise SystemExit(f'{path.name} checksum mismatch; remove the local artifact before retrying.')
        return path
    temporary = path.with_suffix('.download')
    try:
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != sha256:
            raise SystemExit(f'Downloaded {path.name} checksum mismatch.')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)
    return path


def bootstrap():
    fetch(CLI, URL, SHA256)
    fetch(R8, R8_URL, R8_SHA256)
    return CLI


if __name__ == '__main__':
    print(bootstrap().relative_to(ROOT))
