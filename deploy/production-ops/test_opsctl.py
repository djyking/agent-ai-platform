import copy
import gzip
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

if sys.platform == 'win32':
    sys.modules['fcntl'] = types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=lambda *args: None)
import opsctl as ops


class OperationsTest(unittest.TestCase):
    def control(self):
        return {'format': 'harness-production-ops-v1', 'composePath': str(ops.COMPOSE), 'composeSha256': 'a' * 64, 'certbotImage': 'sha256:' + 'b' * 64}

    def compose(self):
        return {'name': 'opsagent', 'services': {name: {'image': 'sha256:' + 'c' * 64, 'restart': 'unless-stopped'} for name in ops.SERVICES}, 'volumes': {'letsencrypt': {'name': 'opsagent_letsencrypt'}, 'acme-webroot': {'name': 'opsagent_acme-webroot'}, 'knowledge-uploads': {'name': 'opsagent_knowledge-uploads'}}}

    def containers(self):
        result = []
        for index, name in enumerate(ops.SERVICES):
            result.append({'Id': str(index).zfill(64), 'Image': 'sha256:' + 'c' * 64,
                'Config': {'Labels': {'com.docker.compose.project': 'opsagent', 'com.docker.compose.service': name, 'com.docker.compose.project.config_files': str(ops.COMPOSE)}},
                'State': {'Running': True, 'StartedAt': '2026-10-21T02:00:00Z', 'Health': {'Status': 'healthy'}}, 'Mounts': []})
        return result

    def test_exact_services_and_immutable_images(self):
        self.assertEqual(28, len(ops.SERVICES))
        ops.validate_control(self.control(), self.compose())
        for change in ['retired', 'missing', 'tagged', 'wrong-volume', 'wrong-compose']:
            compose, control = self.compose(), self.control()
            if change == 'retired': compose['services']['reranker'] = {}
            elif change == 'missing': compose['services'].pop('support')
            elif change == 'tagged': compose['services']['support']['image'] = 'mutable:latest'
            elif change == 'wrong-volume': compose['volumes']['letsencrypt']['name'] = 'other-certificate'
            else: control['composePath'] = '/opt/opsagent/deploy/public/compose.yaml'
            with self.subTest(change=change), self.assertRaises(ValueError): ops.validate_control(control, compose)

    def test_inventory_excludes_stopped_retired_and_rejects_drift(self):
        infos = self.containers()
        retired = copy.deepcopy(infos[0]); retired['Config']['Labels']['com.docker.compose.service'] = 'reranker'; retired['State']['Running'] = False
        self.assertEqual(28, len(ops.select_containers(infos + [retired], self.compose())))
        for change in ['retired-running', 'missing', 'duplicate', 'wrong-image', 'old-origin', 'unhealthy']:
            altered = copy.deepcopy(infos)
            if change == 'retired-running':
                retired['State']['Running'] = True; altered.append(retired)
            elif change == 'missing': altered.pop()
            elif change == 'duplicate': altered.append(altered[0])
            elif change == 'wrong-image': altered[0]['Image'] = 'sha256:' + 'd' * 64
            elif change == 'old-origin': altered[0]['Config']['Labels']['com.docker.compose.project.config_files'] = '/opt/opsagent/deploy/public/compose.yaml'
            else: altered[0]['State']['Health']['Status'] = 'unhealthy'
            with self.subTest(change=change), self.assertRaises(ValueError): ops.select_containers(altered, self.compose())

    def test_legacy_wrapper_rejects_all_lifecycle_and_arbitrary_flags(self):
        self.assertEqual(['config', '--quiet'], ops.wrapper_arguments(['config', '--quiet']))
        self.assertEqual(['ps', '--all', '--quiet', 'mysql'], ops.wrapper_arguments(['ps', '--all', '--quiet', 'mysql']))
        for args in [['up', '--force-recreate', 'mysql'], ['start'], ['stop'], ['down'], ['ps', '--all', '--quiet', 'reranker'], ['config'], ['-f', '/tmp/other', 'config', '--quiet']]:
            with self.subTest(args=args), self.assertRaises(ValueError): ops.wrapper_arguments(args)

    def test_dependency_order_and_cycles(self):
        c = self.compose(); c['services']['harness-api']['depends_on'] = {'mysql': {'condition': 'service_healthy'}}
        levels = ops.dependency_levels(c)
        self.assertLess(next(i for i, level in enumerate(levels) if 'mysql' in level), next(i for i, level in enumerate(levels) if 'harness-api' in level))
        c['services']['mysql']['depends_on'] = {'harness-api': {}}
        with self.assertRaises(ValueError): ops.dependency_levels(c)
        c = self.compose(); c['services']['mysql']['depends_on'] = {'reranker': {}}
        with self.assertRaises(ValueError): ops.dependency_levels(c)

    def test_lifecycle_only_captured_ids_and_rejects_concurrent_replacement(self):
        c = self.compose(); before = ops.select_containers(self.containers(), c)
        after = copy.deepcopy(before); after['mysql']['Id'] = 'f' * 64
        calls = []
        def fake_docker(*args, **kwargs):
            calls.append(args)
            return json.dumps(list(before.values())) if args[0] == 'inspect' else ''
        with patch.object(ops, 'inventory', side_effect=[before, after]), patch.object(ops, 'digest', return_value='a' * 64), patch.object(ops, 'docker', side_effect=fake_docker):
            with self.assertRaisesRegex(ValueError, 'changed'): ops.lifecycle(self.control(), c, 'start')
        start = [x for x in calls if x[0] == 'start'][0]
        self.assertEqual(set(i['Id'] for i in before.values()), set(start[1:]))
        self.assertNotIn('f' * 64, start)

    def test_stop_checks_final_running_state(self):
        c = self.compose(); before = ops.select_containers(self.containers(), c)
        with patch.object(ops, 'inventory', return_value=before), patch.object(ops, 'digest', return_value='a' * 64), patch.object(ops, 'docker', return_value=''):
            with self.assertRaisesRegex(ValueError, 'still running'): ops.lifecycle(self.control(), c, 'stop')

    def test_certbot_uses_only_bound_volumes_and_no_project_recreate(self):
        command = ops.certbot_command(self.control()['certbotImage'], 'harness-cert-renew-fixture', 'fixture', True)
        self.assertIn('--dry-run', command)
        self.assertIn('--no-directory-hooks', command)
        self.assertIn('--config', command)
        self.assertEqual('/dev/null', command[command.index('--config') + 1])
        self.assertIn('opsagent.cloud', command)
        self.assertIn('type=volume,src=opsagent_letsencrypt,dst=/etc/letsencrypt', command)
        self.assertNotIn('compose', command)
        self.assertNotIn('--privileged', command)
        self.assertNotIn('-p', command)

    def test_renewal_rejects_hooks_and_unreviewed_domains(self):
        text = 'archive_dir = /etc/letsencrypt/archive/opsagent.cloud\n' + ''.join(key + ' = /etc/letsencrypt/live/opsagent.cloud/' + key + '.pem\n' for key in ('cert', 'privkey', 'chain', 'fullchain')) + '[renewalparams]\nauthenticator = webroot\nserver = https://acme-v02.api.letsencrypt.org/directory\nwebroot_path = /var/www/certbot,\n[[webroot_map]]\nopsagent.cloud = /var/www/certbot\nwww.opsagent.cloud = /var/www/certbot\n'
        ops.validate_renewal(text, [])
        for modified, hooks in [(text + 'evil.example = /tmp\n', []), (text.replace('authenticator = webroot', 'authenticator = manual'), []), ('pre_hook = touch /tmp/unsafe\n' + text, []), (text, ['hook.sh'])]:
            with self.assertRaises(ValueError): ops.validate_renewal(modified, hooks)

    def test_failed_backup_preserves_partial_and_existing_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); backups = root / 'new-backups'; backups.mkdir()
            historical = root / 'phase6-predeploy-cold'; historical.mkdir(); (historical / 'payload').write_bytes(b'protected')
            infos = ops.select_containers(self.containers(), self.compose())
            calls = iter(['0\n', RuntimeError('synthetic dump failure')])
            def fake_docker(*args, **kwargs):
                result = next(calls)
                if isinstance(result, Exception): raise result
                return result
            with patch.object(ops, 'BACKUPS', backups), patch.object(ops, 'inventory', return_value=infos), patch.object(ops, 'validate_mount'), patch.object(ops, 'secure_path'), patch.object(ops.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=20 * 1024**3)), patch.object(ops, 'docker', side_effect=fake_docker):
                with self.assertRaises(RuntimeError): ops.backup(self.control(), self.compose())
            created = list(backups.iterdir()); self.assertEqual(1, len(created))
            self.assertTrue((created[0] / '.FAILED').is_file())
            self.assertTrue((created[0] / 'databases.sql.partial').is_file())
            self.assertFalse((created[0] / '.complete').exists())
            self.assertEqual(b'protected', (historical / 'payload').read_bytes())


if __name__ == '__main__': unittest.main()
