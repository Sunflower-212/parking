"""Validate effective cloud configuration without displaying credentials or calling providers."""
import argparse
import base64
import binascii
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    'DB_PASSWORD', 'REDIS_PASSWORD', 'CACHE_REDIS_PASSWORD', 'JWT_SECRET',
    'BOOTSTRAP_ADMIN_USERNAME', 'BOOTSTRAP_ADMIN_PASSWORD', 'RABBITMQ_PASSWORD',
    'DEEPSEEK_API_KEY', 'BAIDU_API_KEY', 'BAIDU_SECRET_KEY', 'ALIPAY_APP_ID',
    'ALIPAY_PRIVATE_KEY', 'ALIPAY_PUBLIC_KEY', 'OSS_ACCESS_KEY_ID',
    'OSS_ACCESS_KEY_SECRET', 'OSS_BUCKET', 'OSS_ENDPOINT',
)
FLAGS = ('AI_ENABLED', 'OCR_ENABLED', 'ALIPAY_ENABLED', 'OSS_ENABLED',
         'BACKGROUND_TASKS_ENABLED', 'SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP')
PASSWORDS = ('DB_PASSWORD', 'REDIS_PASSWORD', 'CACHE_REDIS_PASSWORD',
             'RABBITMQ_PASSWORD', 'BOOTSTRAP_ADMIN_PASSWORD')


def issues(config):
    """Return field names and fixed reasons only, never supplied values."""
    failures = []
    env = config['services']['backend']['environment']
    root_password = str(config['services'].get('mysql', {}).get('environment', {}).get('MYSQL_ROOT_PASSWORD') or '')
    if not root_password.strip():
        failures.append('MYSQL_ROOT_PASSWORD: missing')
    for key in REQUIRED:
        value = str(env.get(key) or '').strip()
        if not value or (key not in PASSWORDS and re.match(r'(?i)^(replace-|fill[- ]|your[-_ ]|<|\$\{|填写|粘贴)', value)):
            failures.append(key + ': missing or placeholder')
    for key in FLAGS:
        if str(env.get(key)).lower() != 'true':
            failures.append(key + ': must be enabled')
    if env.get('AI_AUTOCONFIG_EXCLUDE'):
        failures.append('AI_AUTOCONFIG_EXCLUDE: must be empty when AI is enabled')
    if not re.fullmatch(r'\d{16,32}', str(env.get('ALIPAY_APP_ID', ''))):
        failures.append('ALIPAY_APP_ID: invalid application ID format')
    for key, minimum in [('ALIPAY_PRIVATE_KEY', 1000), ('ALIPAY_PUBLIC_KEY', 250)]:
        try:
            raw = base64.b64decode(str(env.get(key, '')), validate=True)
            # Java SDK expects PKCS#8 private keys and X.509 public keys with rsaEncryption OID.
            if len(raw) < minimum or not raw.startswith(b'\x30') or b'\x06\x09\x2a\x86\x48\x86\xf7\x0d\x01\x01\x01' not in raw[:32]:
                raise ValueError()
        except (ValueError, binascii.Error):
            failures.append(key + ': expected single-line Java RSA2 key, without PEM headers')
    for key, path in [('ALIPAY_NOTIFY_URL', '/parking/api/user/rechargeNotify'),
                      ('ALIPAY_RETURN_URL', '/parking/user/paymentResult')]:
        try:
            address = urlsplit(str(env.get(key, '')))
            valid = address.scheme in ('http', 'https') and address.hostname and address.path in ((path, '/parking/user/account') if key == 'ALIPAY_RETURN_URL' else (path,)) and not address.username and not address.password and not address.query and not address.fragment
        except ValueError:
            valid = False
        if not valid:
            failures.append(key + ': invalid public /parking/ URL')
    try:
        endpoint = urlsplit(str(env.get('OSS_ENDPOINT', '')))
        valid = endpoint.scheme == 'https' and endpoint.hostname and endpoint.hostname.startswith('oss-') and endpoint.hostname.endswith('.aliyuncs.com') and endpoint.path in ('', '/') and not endpoint.username and not endpoint.password and not endpoint.query and not endpoint.fragment
    except ValueError:
        valid = False
    if not valid:
        failures.append('OSS_ENDPOINT: expected HTTPS regional OSS service endpoint')
    if len(str(env.get('JWT_SECRET', ''))) < 32:
        failures.append('JWT_SECRET: requires at least 32 characters')
    # Match the login interface so a newly created administrator can actually sign in.
    if not re.fullmatch(r'\S{3,16}', str(env.get('BOOTSTRAP_ADMIN_PASSWORD', ''))):
        failures.append('BOOTSTRAP_ADMIN_PASSWORD: login format requires 3-16 non-whitespace characters')
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', default='.env')
    args = parser.parse_args()
    if not (ROOT / args.env_file).is_file():
        print('BLOCK: environment file missing; run python3 scripts/init-env.py --cloud')
        return 1
    try:
        result = subprocess.run(['docker', 'compose', '--env-file', args.env_file,
                                 '-f', 'compose.cloud.yml', 'config', '--format', 'json'],
                                cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                universal_newlines=True, encoding='utf-8', timeout=30)
        if result.returncode:
            missing = sorted(set(re.findall(r'required variable ([A-Z][A-Z0-9_]*)', result.stderr)))
            print('BLOCK: fill required configuration: ' + ', '.join(missing) if missing else 'BLOCK: Compose validation failed; check file syntax and required environment fields')
            return 1
        failures = issues(json.loads(result.stdout))
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError):
        print('BLOCK: could not validate cloud configuration; confirm Docker Compose is installed')
        return 1
    for failure in failures:
        print('BLOCK: ' + failure)
    if failures:
        return 1
    print('PASS: configuration format checked; provider credentials, key rotation and public callbacks are NOT verified')
    return 0


if __name__ == '__main__':
    sys.exit(main())
