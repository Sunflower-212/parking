"""Enforce the repository's local-only docs policy without reading document contents."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
tracked = subprocess.check_output(['git', 'ls-files', '-z', '--', 'docs'], cwd=root)
if tracked:
    print('BLOCK: docs/ is local-only; remove it from the Git index without deleting local files')
    sys.exit(1)
print('PASS: docs/ is not tracked; local document contents were not read')
