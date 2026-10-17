"""Create local deployment credentials once, without printing secrets."""
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
target = root / '.env'
if target.exists():
    contents=target.read_text(encoding='utf-8')
    if 'CACHE_REDIS_PASSWORD=' not in contents:
        with target.open('a',encoding='utf-8') as output: output.write('\nCACHE_REDIS_PASSWORD='+secrets.token_hex(24)+'\n')
    print('.env preserved; missing cache credential added if needed')
else:
    contents = (root / '.env.example').read_text(encoding='utf-8')
    for placeholder in ('replace-with-strong-root-password', 'replace-with-strong-database-password',
                        'replace-with-strong-redis-password', 'replace-with-strong-cache-password', 'replace-with-at-least-32-random-characters'):
        contents = contents.replace(placeholder, secrets.token_hex(24))
    contents = contents.replace('BOOTSTRAP_ADMIN_PASSWORD=replace-me', 'BOOTSTRAP_ADMIN_PASSWORD=' + secrets.token_hex(8))
    target.write_text(contents, encoding='utf-8')
    print('Created .env with unique credentials; view the local file to sign in as the bootstrap administrator')
