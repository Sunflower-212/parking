"""Create local deployment credentials once, without printing secrets."""
from pathlib import Path
import argparse
import os
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cloud', action='store_true', help='Use the all-services cloud template for a new .env')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
target = root / '.env'
if target.exists():
    contents=target.read_text(encoding='utf-8')
    for name in ('CACHE_REDIS_PASSWORD','RABBITMQ_PASSWORD'):
        if name+'=' not in contents:
            with target.open('a',encoding='utf-8') as output: output.write('\n'+name+'='+secrets.token_hex(24)+'\n')
    print('.env preserved; missing middleware credentials added if needed')
else:
    contents = (root / ('.env.cloud.example' if args.cloud else '.env.example')).read_text(encoding='utf-8')
    for placeholder in ('replace-with-strong-root-password', 'replace-with-strong-database-password',
                        'replace-with-strong-redis-password', 'replace-with-strong-cache-password', 'replace-with-at-least-32-random-characters', 'replace-with-a-random-password'):
        contents = contents.replace(placeholder, secrets.token_hex(24))
    contents = contents.replace('BOOTSTRAP_ADMIN_PASSWORD=replace-me', 'BOOTSTRAP_ADMIN_PASSWORD=' + secrets.token_hex(8))
    target.write_text(contents, encoding='utf-8')
    print('Created .env with unique credentials; view the local file to sign in as the bootstrap administrator')
if args.cloud and os.name != 'nt':
    target.chmod(0o600)
