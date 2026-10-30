"""Small self-check for secret-safe configuration errors and cloud initialization."""
import base64
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cloud_config', ROOT / 'scripts/check-cloud-config.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CloudConfigTest(unittest.TestCase):
    def config(self):
        env = {key: 'unit-test-private-value-not-to-be-printed' for key in module.REQUIRED}
        env.update({key: 'true' for key in module.FLAGS})
        oid = b'\x06\x09\x2a\x86\x48\x86\xf7\x0d\x01\x01\x01'
        for key in ('ALIPAY_PRIVATE_KEY', 'ALIPAY_PUBLIC_KEY'):
            env[key] = base64.b64encode(b'\x30' + oid + b'\0' * 1200).decode()
        env.update(ALIPAY_APP_ID='1234567890123456', AI_AUTOCONFIG_EXCLUDE='',
                   BOOTSTRAP_ADMIN_PASSWORD='test-password', OSS_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com',
                   ALIPAY_NOTIFY_URL='http://example.invalid/parking/api/user/rechargeNotify',
                   ALIPAY_RETURN_URL='http://example.invalid/parking/user/paymentResult')
        return {'services': {'backend': {'environment': env}, 'mysql': {'environment': {'MYSQL_ROOT_PASSWORD': 'unit-test-root-password'}}}}

    def test_complete_format_passes_without_network_calls(self):
        self.assertEqual([], module.issues(self.config()))

    def test_errors_do_not_include_submitted_credentials(self):
        config = self.config()
        env = config['services']['backend']['environment']
        env.update(ALIPAY_PRIVATE_KEY='sensitive-malformed-value', OSS_ENDPOINT='http://sensitive-malformed-value',
                   ALIPAY_NOTIFY_URL='http://example.invalid/wrong', AI_ENABLED='false')
        failures = module.issues(config)
        self.assertGreaterEqual(len(failures), 4)
        self.assertNotIn('sensitive-malformed-value', '\n'.join(failures))

    def test_placeholder_and_excluded_ai_are_rejected(self):
        config = self.config()
        config['services']['backend']['environment'].update(DEEPSEEK_API_KEY='replace-key', AI_AUTOCONFIG_EXCLUDE='disabled')
        self.assertTrue(any('DEEPSEEK_API_KEY' in message for message in module.issues(config)))
        self.assertTrue(any('AI_AUTOCONFIG_EXCLUDE' in message for message in module.issues(config)))

    def test_short_custom_service_passwords_are_accepted(self):
        config = self.config()
        config['services']['mysql']['environment']['MYSQL_ROOT_PASSWORD'] = '1'
        for key in module.PASSWORDS:
            config['services']['backend']['environment'][key] = '123456' if key == 'BOOTSTRAP_ADMIN_PASSWORD' else '1'
        self.assertEqual([], module.issues(config))

    def test_empty_passwords_remain_required_and_admin_matches_login_format(self):
        config = self.config()
        config['services']['mysql']['environment']['MYSQL_ROOT_PASSWORD'] = ''
        config['services']['backend']['environment'].update(DB_PASSWORD='', BOOTSTRAP_ADMIN_PASSWORD='12')
        failures = module.issues(config)
        self.assertTrue(any(message.startswith('MYSQL_ROOT_PASSWORD:') for message in failures))
        self.assertTrue(any(message.startswith('DB_PASSWORD:') for message in failures))
        self.assertTrue(any(message.startswith('BOOTSTRAP_ADMIN_PASSWORD:') for message in failures))

    def test_cloud_init_preserves_existing_credentials(self):
        (ROOT / '.work').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / '.work') as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'scripts/init-env.py').write_bytes((ROOT / 'scripts/init-env.py').read_bytes())
            (root / '.env.cloud.example').write_bytes((ROOT / '.env.cloud.example').read_bytes())
            command = [sys.executable, str(root / 'scripts/init-env.py'), '--cloud']
            subprocess.run(command, check=True, capture_output=True)
            first = (root / '.env').read_bytes()
            self.assertNotIn(b'replace-with-strong-', first)
            self.assertIn(b'AI_ENABLED=true', first)
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(first, (root / '.env').read_bytes())


if __name__ == '__main__':
    unittest.main()
