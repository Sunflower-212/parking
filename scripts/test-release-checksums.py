"""Targeted release checks; Linux CI additionally invokes GNU sha256sum."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_checksums', Path(__file__).with_name('release-checksums.py'))
checksums = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checksums)


class ReleaseChecksumTest(unittest.TestCase):
    def test_manifest_bytes_paths_and_repeated_generation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'nested').mkdir()
            (root / 'nested/file with space.txt').write_bytes(b'content\r\n')
            manifest = checksums.write_manifest(root)
            expected = (checksums.sha256(root / 'nested/file with space.txt') + '  nested/file with space.txt\n').encode('ascii')
            self.assertEqual(manifest.read_bytes(), expected)
            self.assertEqual(checksums.write_manifest(root).read_bytes(), expected)
            self.assertNotIn(b'\r', expected)
            if shutil.which('sha256sum'):
                subprocess.run(['sha256sum', '-c', manifest.name], cwd=str(root), check=True)

    def test_archive_sidecar_has_one_lf_and_no_bom(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'release.tar.gz'
            archive.write_bytes(b'archive')
            sidecar = checksums.write_sidecar(archive)
            self.assertEqual(sidecar.read_bytes(), (checksums.sha256(archive) + '  release.tar.gz\n').encode('ascii'))
            if shutil.which('sha256sum'):
                subprocess.run(['sha256sum', '-c', sidecar.name], cwd=str(archive.parent), check=True)
                archive.write_bytes(b'tampered')
                result = subprocess.run(['sha256sum', '-c', sidecar.name], cwd=str(archive.parent), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertNotEqual(result.returncode, 0)

    def test_rejects_empty_package_and_ambiguous_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                checksums.write_manifest(root)
            for name in ('bad\nname', 'bad\rname', 'nested\\file'):
                with self.assertRaises(ValueError):
                    checksums.checksum_line(root / 'unused', name)


if __name__ == '__main__':
    unittest.main()
