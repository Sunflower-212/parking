"""Generate GNU sha256sum manifests as LF-only UTF-8 bytes on every platform.

Usage: python scripts/release-checksums.py DIRECTORY
       python scripts/release-checksums.py --file ARCHIVE
Finalize package files before generating a manifest; hash the archive after packing.
"""
import argparse
import hashlib
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def checksum_line(path, relative_name):
    if any(char in relative_name for char in ('\r', '\n', '\\')):
        raise ValueError('Checksum filenames must use POSIX paths without line breaks')
    return '{}  {}\n'.format(sha256(path), relative_name)


def write_manifest(directory):
    directory = Path(directory).resolve(strict=True)
    manifest = directory / 'SHA256SUMS.txt'
    lines = []
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError('Release manifests do not allow symlinks')
        if path.is_file() and path != manifest:
            lines.append(checksum_line(path, path.relative_to(directory).as_posix()))
    if not lines:
        raise ValueError('Package contains no files')
    # write_text with platform defaults turns LF into CRLF on Windows.
    manifest.write_bytes(''.join(lines).encode('utf-8'))
    return manifest


def write_sidecar(archive):
    archive = Path(archive)
    if archive.is_symlink() or not archive.is_file():
        raise ValueError('Archive must be a regular file')
    archive = archive.resolve(strict=True)
    sidecar = archive.with_name(archive.name + '.sha256')
    sidecar.write_bytes(checksum_line(archive, archive.name).encode('utf-8'))
    return sidecar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('directory', nargs='?')
    group.add_argument('--file', type=Path)
    args = parser.parse_args()
    print(write_sidecar(args.file) if args.file else write_manifest(args.directory))


if __name__ == '__main__':
    main()
