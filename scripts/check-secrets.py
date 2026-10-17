"""Scan the Git index without printing private values. Run after git add, before commit."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z'], cwd=root).decode().split('\0')
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
for path in filter(None, paths):
    if re.search(r'(^|/)(application\.ya?ml|\.env|deepseek-api_key\.txt)$', path) or re.search(r'\.(pem|key|p12|pfx|zip|jar)$', path) or '/.idea/' in '/' + path:
        issues.append((path, 'private or generated file staged'))
    contents = subprocess.check_output(['git', 'show', ':' + path], cwd=root)
    if any(value in contents for value in known):
        issues.append((path, 'matches a private local configuration value'))
    if re.search(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{20,}', contents):
        issues.append((path, 'credential-like literal'))
if issues:
    for path, reason in issues:
        print(f'BLOCK: {path}: {reason}')
    sys.exit(1)
print(f'PASS: inspected {sum(bool(p) for p in paths)} staged files; no local secret values or private configuration files found')
