"""Scan the Git index without printing private values. Run after git add, before commit."""
from pathlib import Path
import argparse
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--range', dest='revision_range', help='Scan every changed blob in pending commits, e.g. origin/main..HEAD')
args = parser.parse_args()
if args.revision_range:
    commits = subprocess.check_output(['git', 'rev-list', args.revision_range], cwd=root).decode().splitlines()
    blobs = [(commit, path) for commit in commits for path in subprocess.check_output(
        ['git', 'diff-tree', '--root', '--no-commit-id', '-r', '--name-only', '--diff-filter=ACMR', '-z', commit], cwd=root
    ).decode().split('\0') if path]
else:
    blobs = [('', path) for path in subprocess.check_output(
        ['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z'], cwd=root
    ).decode().split('\0') if path]
known = set()
for config in [root / '.env', root / 'parking/parking/src/main/resources/application.yml']:
    if not config.exists():
        continue
    for line in config.read_text(encoding='utf-8-sig').splitlines():
        if re.search(r'(?i)(password|jwt.?secret|api.?key|access.?key|secret.?key|private.?key)', line):
            match = re.match(r'\s*[\w.\-]+\s*[:=]\s*(.*?)\s*$', line)
            if match:
                value = match[1].strip('"\'')
                if len(value) >= 12 and not value.startswith('${'):
                    known.add(value.encode())
key_file = root / 'parking/deepseek-api_key.txt'
if key_file.exists():
    known.update(part.encode() for part in re.findall(r'sk-[A-Za-z0-9_-]{16,}', key_file.read_text(encoding='utf-8-sig')))

issues = []
for revision, path in blobs:
    if re.search(r'(^|/)(application\.ya?ml|\.env|deepseek-api_key\.txt)$', path) or re.search(r'\.(pem|key|p12|pfx|zip|jar)$', path) or '/.idea/' in '/' + path:
        issues.append((path, 'private or generated file staged'))
    if '/__pycache__/' in '/' + path or path.endswith(('.pyc', '.pyo')):
        issues.append((path, 'generated Python bytecode'))
    contents = subprocess.check_output(['git', 'show', revision + ':' + path], cwd=root)
    if any(value in contents for value in known):
        issues.append((path, 'matches a private local configuration value'))
    if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{20,}', contents):
        issues.append((path, 'credential-like literal'))
if issues:
    for path, reason in issues:
        print(f'BLOCK: {path}: {reason}')
    sys.exit(1)
print(f'PASS: inspected {len(blobs)} file revisions; no local secret values or private configuration files found')
