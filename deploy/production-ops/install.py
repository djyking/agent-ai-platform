#!/usr/bin/env python3
"""Explicit reviewed installation only. Never enables timers or starts/stops containers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from datetime import datetime, timezone
import opsctl as ops

WRAPPERS = {
    'start-manually.sh': 'exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py start "$@"\n',
    'stop-manually.sh': 'exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py stop "$@"\n',
    'compose.sh': 'exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py compose-wrapper "$@"\n',
    'backup-daily.sh': 'exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py backup "$@"\n',
    'renew-certificates.sh': 'if [[ $# -eq 0 ]]; then\n  exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py cert-renew\nelif [[ $# -eq 1 && "$1" == --dry-run ]]; then\n  exec /usr/bin/python3 /opt/harness/production-ops/opsctl.py cert-dry-run\nelse\n  echo "Usage: renew-certificates.sh [--dry-run]" >&2\n  exit 2\nfi\n',
}
UNITS = ['harness-production-backup.service', 'harness-production-backup.timer',
         'harness-production-cert-renew.service', 'harness-production-cert-renew.timer']
LEGACY_TIMERS = ['opsagent-backup.timer', 'opsagent-cert-renew.timer', 'opsagent-ip-history.timer']
LEGACY_HELPERS = ['opsagent-configuration-executor.service']


def atomic(path, data, mode):
    temporary = path.with_name(path.name + '.installing')
    with temporary.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(mode)
    os.replace(temporary, path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--install', action='store_true', required=True)
    parser.add_argument('--expected-compose-sha256', required=True)
    parser.add_argument('--certbot-image', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Root installation required')
    os.umask(0o077)
    candidate = Path(__file__).resolve().parent
    ops.secure_path(candidate, directory=True)
    if ops.ROOT.exists():
        raise ValueError('Existing production operations installation must not be overwritten')
    for name in LEGACY_TIMERS + LEGACY_HELPERS:
        result = ops.run(['systemctl', 'show', name, '--property=ActiveState,UnitFileState'])
        properties = dict(line.split('=', 1) for line in result.splitlines() if '=' in line)
        if properties.get('ActiveState') != 'inactive' or properties.get('UnitFileState') != 'disabled':
            raise ValueError('Legacy startup/maintenance units must remain disabled before replacement')
    ops.secure_path(ops.COMPOSE)
    if ops.digest(ops.COMPOSE) != args.expected_compose_sha256:
        raise ValueError('Final Compose changed after review')
    control = {'format': 'harness-production-ops-v1', 'composePath': str(ops.COMPOSE),
               'composeSha256': args.expected_compose_sha256, 'certbotImage': args.certbot_image}
    compose = json.loads(ops.COMPOSE.read_bytes())
    ops.validate_control(control, compose)
    containers = ops.inventory(compose)
    ops.validate_mount(containers['ops-web-app'], '/etc/letsencrypt', ops.VOLUMES['certificate'], readonly=True)
    ops.validate_mount(containers['ops-web-app'], '/var/www/certbot', ops.VOLUMES['challenge'], readonly=True)
    image = json.loads(ops.docker('image', 'inspect', args.certbot_image))
    if len(image) != 1 or image[0]['Id'] != args.certbot_image:
        raise ValueError('Reviewed Certbot image is absent')
    scripts = ops.OPSDIR / 'scripts'
    originals = {}
    for name in WRAPPERS:
        path = ops.secure_path(scripts / name)
        originals[name] = {'bytes': path.read_bytes(), 'mode': path.stat().st_mode & 0o777}
    for name in UNITS:
        ops.secure_path(candidate / name)
        if (Path('/etc/systemd/system') / name).exists():
            raise ValueError('Existing new unit must not be overwritten')
    ops.run(['systemd-analyze', 'verify', *[str(candidate / name) for name in UNITS]])
    ops.ROOT.mkdir(mode=0o700)
    (ops.ROOT / 'failures').mkdir(mode=0o700)
    legacy = ops.ROOT / ('legacy-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    legacy.mkdir(mode=0o700)
    manifest = {}
    for name, record in originals.items():
        with (legacy / name).open('xb') as stream:
            stream.write(record['bytes'])
        manifest[name] = {'sha256': hashlib.sha256(record['bytes']).hexdigest(), 'originalMode': oct(record['mode'])}
    ops.private_write(legacy / 'manifest.private.json', manifest)
    if any(ops.digest(legacy / name) != info['sha256'] for name, info in manifest.items()):
        raise ValueError('Legacy script preservation failed; no wrapper replaced')
    atomic(ops.ROOT / 'opsctl.py', (candidate / 'opsctl.py').read_bytes(), 0o700)
    atomic(ops.CONTROL, (json.dumps(control, indent=2) + '\n').encode(), 0o600)
    if not ops.BACKUPS.exists():
        ops.BACKUPS.mkdir(mode=0o700)
    ops.secure_path(ops.BACKUPS, directory=True)
    for name, body in WRAPPERS.items():
        atomic(scripts / name, ('#!/usr/bin/env bash\nset -euo pipefail\n# Reviewed 28-service entry; preserved original is under /opt/harness/production-ops/legacy-*\n' + body).encode(), 0o750)
    for name in UNITS:
        atomic(Path('/etc/systemd/system') / name, (candidate / name).read_bytes(), 0o644)
    ops.run(['systemctl', 'daemon-reload'])
    installed_control, installed_compose = ops.load_control()
    actual = ops.inventory(installed_compose)
    if installed_control != control:
        raise ValueError('Installed control changed during installation')
    result = {'status': 'READY', 'serviceCount': len(actual), 'composeSha256': control['composeSha256']}
    ops.private_write(ops.ROOT / 'installation.private.json', {'installedAt': ops.now(), 'legacyBackup': str(legacy), 'control': control, 'status': result, 'timersEnabled': False, 'servicesRestarted': False})
    print(json.dumps({'status': 'INSTALLED_NOT_ENABLED', 'legacyBackup': str(legacy), 'composeSha256': args.expected_compose_sha256, 'timersEnabled': False, 'servicesRestarted': False}))


def locked_main():
    if os.geteuid() != 0 or ops.LOCK.is_symlink():
        raise ValueError('Root and a safe operations lock are required')
    with os.fdopen(os.open(ops.LOCK, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'w') as lock:
        info = os.fstat(lock.fileno())
        if info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1:
            raise ValueError('Operations lock must be a private root-owned regular file')
        ops.fcntl.flock(lock.fileno(), ops.fcntl.LOCK_EX | ops.fcntl.LOCK_NB)
        main()


if __name__ == '__main__':
    try:
        locked_main()
    except Exception as error:
        print(json.dumps({'status': 'INSTALLATION_FAILED', 'errorType': type(error).__name__, 'reason': str(error) if isinstance(error, ValueError) else 'Installation stopped; preserve partial installation for review'}))
        raise SystemExit(1)
