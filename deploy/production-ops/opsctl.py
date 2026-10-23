#!/usr/bin/env python3
"""Pinned 28-service operations. No image recreation, historical replay, or retention deletion."""
import argparse
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import uuid
from datetime import datetime, timezone

ROOT = Path('/opt/harness/production-ops')
CONTROL = ROOT / 'control.json'
BACKUPS = Path('/opt/harness/production-backups')
LOCK = Path('/run/lock/harness-production-ops.lock')
DEPLOYMENT = Path('/opt/harness/phase6-20261021T011700Z')
COMPOSE = DEPLOYMENT / 'compose.full-ui.pinned.private.json'
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']
ENV = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C.UTF-8'}
SERVICES = tuple(sorted('mysql redis rabbitmq nacos sentinel elasticsearch qdrant ops-auth-app ops-ticket-app ops-knowledge-app ops-rag-app ops-platform-app ops-gateway-app prometheus alertmanager grafana ops-web-app ops-agent-app ops-demo-order-app demo-redis demo-rabbitmq observation-collector otel-collector tempo harness-api harness-worker-1 support harness-internal-tls'.split()))
DATABASES = ('ops_auth', 'ops_ticket', 'ops_knowledge', 'ops_rag', 'ops_platform', 'ops_ai', 'harness_platform')
VOLUMES = {'certificate': 'opsagent_letsencrypt', 'challenge': 'opsagent_acme-webroot', 'attachments': 'opsagent_knowledge-uploads'}
OPSDIR = Path('/opt/opsagent/deploy/public')


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def secure_path(path, directory=False):
    path = Path(path)
    if not path.is_absolute():
        raise ValueError('Absolute path required')
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('Control paths cannot traverse symlinks')
        info = part.stat()
        if info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Control paths must be root-owned and not group/other writable')
    if (not path.is_dir()) if directory else (not path.is_file()):
        raise ValueError('Unexpected control path type')
    return path


def validate_control(control, compose):
    expected_keys = {'format', 'composePath', 'composeSha256', 'certbotImage'}
    if set(control) != expected_keys or control['format'] != 'harness-production-ops-v1':
        raise ValueError('Unexpected operations control format')
    if control['composePath'] != str(COMPOSE) or not re.fullmatch(r'[a-f0-9]{64}', control['composeSha256']):
        raise ValueError('Unexpected canonical Compose binding')
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', control['certbotImage']):
        raise ValueError('Immutable local Certbot image required')
    if compose.get('name') != 'opsagent' or set(compose.get('services', {})) != set(SERVICES):
        raise ValueError('Canonical Compose must contain exactly the reviewed 28 services')
    for service, value in compose['services'].items():
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', value.get('image', '')):
            raise ValueError('Every service must use its verified local immutable image ID')
        if value.get('restart') not in {'no', 'unless-stopped'}:
            raise ValueError('Unsupported restart policy')
    for key, expected in [('letsencrypt', VOLUMES['certificate']), ('acme-webroot', VOLUMES['challenge']), ('knowledge-uploads', VOLUMES['attachments'])]:
        if compose.get('volumes', {}).get(key, {}).get('name') != expected:
            raise ValueError('Unexpected persistent volume binding')


def load_control():
    secure_path(CONTROL)
    secure_path(COMPOSE)
    control = json.loads(CONTROL.read_bytes())
    raw = COMPOSE.read_bytes()
    if control.get('composeSha256') != hashlib.sha256(raw).hexdigest():
        raise ValueError('Canonical Compose changed; review and rebind operations control first')
    compose = json.loads(raw)
    validate_control(control, compose)
    return control, compose


def run(arguments, timeout=30, output=None):
    try:
        result = subprocess.run(arguments, env=ENV, stdout=output or subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except subprocess.TimeoutExpired as failure:
        save_failure(failure.stderr or b'Checked subprocess timed out')
        raise
    if result.returncode:
        # Command output can contain secrets. Never emit it to journald or the caller.
        save_failure(result.stderr)
        raise RuntimeError('Checked subprocess failed with exit code ' + str(result.returncode))
    return result.stdout.decode() if output is None else None


def save_failure(content):
    directory = ROOT / 'failures'
    if directory.is_dir() and not directory.is_symlink():
        secure_path(directory, directory=True)
        with (directory / (uuid.uuid4().hex + '.private.log')).open('xb') as output:
            output.write(content)


def docker(*args, **kwargs):
    return run(DOCKER + list(args), **kwargs)


def compose_command(*args):
    return DOCKER + ['compose', '--project-name', 'opsagent', '--project-directory', str(DEPLOYMENT), '-f', str(COMPOSE), *args]


def select_containers(infos, compose, healthy=True):
    selected = {}
    for info in infos:
        labels = info['Config'].get('Labels') or {}
        if labels.get('com.docker.compose.project') != 'opsagent':
            raise ValueError('Unexpected Docker project')
        service = labels.get('com.docker.compose.service')
        if service not in SERVICES:
            if info['State']['Running']:
                raise ValueError('Unreviewed service is running in the project')
            continue
        if service in selected or info['Image'] != compose['services'][service]['image']:
            raise ValueError('Duplicate service or container image differs from canonical Compose')
        origins = set(labels.get('com.docker.compose.project.config_files', '').split(','))
        allowed = {str(COMPOSE), str(DEPLOYMENT / 'compose.full-ui.target.private.json')}
        if not origins or not origins <= allowed:
            raise ValueError('Container was not created from the reviewed deployment')
        state = info['State']
        if healthy and (not state['Running'] or state.get('Paused') or state.get('Restarting') or state.get('OOMKilled') or state.get('Health', {}).get('Status', 'healthy') != 'healthy'):
            raise ValueError('A selected service is not ready: ' + service)
        selected[service] = info
    if set(selected) != set(SERVICES):
        raise ValueError('All 28 existing containers must be present; this entry never creates them')
    return selected


def inventory(compose, healthy=True):
    ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=opsagent').split()
    if not ids:
        raise ValueError('No existing project containers')
    return select_containers(json.loads(docker('inspect', *ids)), compose, healthy)


def validate_mount(info, destination, name, readonly=None):
    found = [m for m in info['Mounts'] if m['Destination'] == destination]
    if len(found) != 1 or found[0].get('Type') != 'volume' or found[0].get('Name') != name:
        raise ValueError('Unexpected named volume attachment')
    if readonly is not None and found[0]['RW'] == readonly:
        raise ValueError('Unexpected volume access mode')


def volume_path(name):
    infos = json.loads(docker('volume', 'inspect', name))
    expected = Path('/var/lib/docker/volumes') / name / '_data'
    if len(infos) != 1 or infos[0]['Name'] != name or Path(infos[0]['Mountpoint']) != expected:
        raise ValueError('Unexpected local volume location')
    return secure_path(expected, directory=True)


def snapshot(infos):
    return {name: {'id': value['Id'], 'image': value['Image'], 'startedAt': value['State']['StartedAt']} for name, value in sorted(infos.items())}


def private_write(path, data):
    with Path(path).open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(data, indent=2) + '\n')


def compress_owned(raw, destination):
    with raw.open('rb') as source, gzip.open(destination, 'xb', compresslevel=1) as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    run(['gzip', '-t', str(destination)], timeout=120)
    raw.unlink()  # Only this invocation's exact newly created raw temporary file.


def checked_tar(path):
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive:
            if member.name.startswith('/') or '..' in Path(member.name).parts:
                raise ValueError('Unexpected archive member')


def configuration_paths(infos):
    paths = {COMPOSE, CONTROL, ROOT / 'opsctl.py', OPSDIR / 'secret.env'}
    for info in infos.values():
        for mount in info['Mounts']:
            if mount['Type'] != 'bind':
                continue
            path = Path(mount['Source'])
            if not (path.is_relative_to(DEPLOYMENT) or path.is_relative_to(OPSDIR)):
                raise ValueError('Unreviewed bind mount cannot be included in configuration backup')
            # These are backup payloads, not executable control files. Preserve
            # existing ownership/modes, but never traverse a redirected source.
            if any(part.is_symlink() for part in (path, *path.parents)) or not (path.is_file() or path.is_dir()):
                raise ValueError('Backup source must be an existing non-redirected regular path')
            paths.add(path)
    # Keep whole current deployment secret/config directory, including env files
    # consumed only at container creation, rather than only mounted fragments.
    paths.add(secure_path(DEPLOYMENT / 'private', directory=True))
    return sorted(path for path in paths if not any(other != path and other in path.parents for other in paths))


def backup(control, compose):
    before = inventory(compose)
    validate_mount(before['ops-knowledge-app'], '/app/data/uploads', VOLUMES['attachments'])
    validate_mount(before['ops-web-app'], '/etc/letsencrypt', VOLUMES['certificate'], readonly=True)
    if not BACKUPS.exists():
        BACKUPS.mkdir(mode=0o700)
    secure_path(BACKUPS, directory=True)
    if shutil.disk_usage(BACKUPS).free < 10 * 1024**3:
        raise ValueError('Less than 10 GiB free; no backup or retention deletion was attempted')
    target = BACKUPS / ('harness-production-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:12])
    target.mkdir(mode=0o700)
    private_write(target / '.owner', {'format': 'harness-production-backup-v1', 'createdAt': now()})
    try:
        # Explicit database list; no data changes, global locks, or source-data/GTID privilege requests.
        sql = 'SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA IN (' + ','.join("'" + db + "'" for db in DATABASES) + ") AND TABLE_TYPE='BASE TABLE' AND ENGINE <> 'InnoDB';"
        query = ['exec', before['mysql']['Id'], 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names -e "$1"', 'sh', sql]
        if docker(*query).strip() != '0':
            raise ValueError('Non-InnoDB tables require a separately reviewed consistency procedure')
        dump_command = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF --column-statistics=0 --default-character-set=utf8mb4 --databases ' + ' '.join(DATABASES)
        sql_started = now()
        raw_sql = target / 'databases.sql.partial'
        with raw_sql.open('xb') as output:
            docker('exec', before['mysql']['Id'], 'sh', '-c', dump_command, timeout=900, output=output)
        compress_owned(raw_sql, target / 'databases.sql.gz')
        with gzip.open(target / 'databases.sql.gz', 'rt', errors='strict') as source:
            db_markers = {m.group(1) for line in source for m in [re.match(r'-- Current Database: `([^`]+)`', line)] if m}
        if db_markers != set(DATABASES):
            raise ValueError('The verified dump does not contain exactly seven expected databases')
        sql_finished = now()
        raw_tar = target / 'knowledge-uploads.tar.partial'
        with raw_tar.open('xb') as output:
            docker('exec', before['ops-knowledge-app']['Id'], 'tar', '--numeric-owner', '-cf', '-', '-C', '/app/data/uploads', '.', timeout=600, output=output)
        compress_owned(raw_tar, target / 'knowledge-uploads.tgz')
        checked_tar(target / 'knowledge-uploads.tgz')
        paths = configuration_paths(before)
        run(['tar', '--numeric-owner', '-C', '/', '-czf', str(target / 'configuration.private.tgz'), '--', *[str(p).lstrip('/') for p in paths]], timeout=300)
        checked_tar(target / 'configuration.private.tgz')
        certroot = volume_path(VOLUMES['certificate'])
        run(['tar', '--numeric-owner', '-C', str(certroot), '-czf', str(target / 'certificates.private.tgz'), '.'], timeout=120)
        checked_tar(target / 'certificates.private.tgz')
        after = inventory(compose)
        if snapshot(before) != snapshot(after) or control['composeSha256'] != digest(COMPOSE):
            raise ValueError('Deployment changed during backup; incomplete evidence retained')
        files = {p.name: {'sha256': digest(p), 'bytes': p.stat().st_size} for p in sorted(target.iterdir()) if p.is_file()}
        report = {'format': 'harness-production-backup-v1', 'completedAt': now(), 'databases': DATABASES,
                  'sqlStartedAt': sql_started, 'sqlFinishedAt': sql_finished, 'files': files, 'containers': snapshot(after),
                  'composeSha256': control['composeSha256'], 'retentionDeletion': False, 'restoreTestPerformed': False,
                  'consistency': 'Seven InnoDB databases in one online transaction; attachments/configuration/certificates captured separately. Not a cross-store atomic snapshot.',
                  'excluded': ['live Redis/RabbitMQ/Nacos state', 'Elasticsearch/Qdrant data', 'monitoring time series', 'container images'],
                  'recoveryBoundary': 'The protected predeployment cold backup remains separate. Restoring this online backup requires a separately reviewed stop-write/recovery procedure.'}
        private_write(target / 'manifest.private.json', report)
        # Hash every final payload again; completion is published only after read-back.
        if any(digest(target / name) != item['sha256'] for name, item in files.items()):
            raise ValueError('Backup read-back digest mismatch')
        private_write(target / '.complete', {'completedAt': now(), 'manifestSha256': digest(target / 'manifest.private.json')})
        return {'status': 'BACKUP_VERIFIED', 'backup': str(target), 'manifestSha256': digest(target / 'manifest.private.json'), 'databases': DATABASES, 'restoreTestPerformed': False, 'retentionDeletion': False}
    except BaseException:
        private_write(target / '.FAILED', {'failedAt': now(), 'complete': False, 'partialFilesRetained': True})
        raise


def validate_renewal(text, hooks):
    if hooks or re.search(r'(?mi)^\s*(?:pre|post|renew|deploy)[_-]hook\s*=', text):
        raise ValueError('Unreviewed Certbot hook')
    expected = {'authenticator': 'webroot', 'server': 'https://acme-v02.api.letsencrypt.org/directory',
                'archive_dir': '/etc/letsencrypt/archive/opsagent.cloud', 'webroot_path': '/var/www/certbot,'}
    expected.update({key: '/etc/letsencrypt/live/opsagent.cloud/' + key + '.pem' for key in ('cert', 'privkey', 'chain', 'fullchain')})
    for key, value in expected.items():
        matches = re.findall(r'(?m)^\s*' + re.escape(key) + r'\s*=\s*(.*?)\s*$', text)
        if matches != [value]:
            raise ValueError('Unexpected certificate renewal configuration')
    if '[[webroot_map]]' not in text:
        raise ValueError('Missing reviewed webroot map')
    mapping = text.split('[[webroot_map]]', 1)[1]
    lines = [line.strip() for line in mapping.splitlines() if line.strip() and not line.lstrip().startswith('#')]
    if set(lines) != {'opsagent.cloud = /var/www/certbot', 'www.opsagent.cloud = /var/www/certbot'}:
        raise ValueError('Unexpected certificate names or webroot')


def certbot_command(image, name, run_id, dry_run):
    args = DOCKER + ['run', '--rm', '--pull', 'never', '--name', name, '--label', 'harness.production-ops.run=' + run_id,
                    '--network', 'bridge', '--memory', '256m', '--cpus', '0.5', '--pids-limit', '128',
                    '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                    '--mount', 'type=volume,src=' + VOLUMES['certificate'] + ',dst=/etc/letsencrypt',
                    '--mount', 'type=volume,src=' + VOLUMES['challenge'] + ',dst=/var/www/certbot',
                    image, 'renew', '--config', '/dev/null', '--cert-name', 'opsagent.cloud', '--webroot', '-w', '/var/www/certbot',
                    '--non-interactive', '--quiet', '--no-random-sleep-on-renew', '--no-directory-hooks']
    return args + (['--dry-run'] if dry_run else [])


def renew(control, compose, dry_run):
    infos = inventory(compose, healthy=False)
    web = infos['ops-web-app']
    if not web['State']['Running'] or web['State'].get('Health', {}).get('Status') != 'healthy':
        raise ValueError('Current public Web must be healthy')
    validate_mount(web, '/etc/letsencrypt', VOLUMES['certificate'], readonly=True)
    validate_mount(web, '/var/www/certbot', VOLUMES['challenge'], readonly=True)
    certroot = volume_path(VOLUMES['certificate'])
    volume_path(VOLUMES['challenge'])
    renewal = certroot / 'renewal/opsagent.cloud.conf'
    secure_path(renewal)
    hookroot = certroot / 'renewal-hooks'
    hooks = [p for p in hookroot.rglob('*') if p.is_file() or p.is_symlink()] if hookroot.exists() else []
    validate_renewal(renewal.read_text(), hooks)
    certificate = certroot / 'live/opsagent.cloud/fullchain.pem'
    if not certificate.resolve(strict=True).is_relative_to(certroot):
        raise ValueError('Certificate link escapes its reviewed volume')
    certificate_before = digest(certificate)
    actual = json.loads(docker('image', 'inspect', control['certbotImage']))
    if len(actual) != 1 or actual[0]['Id'] != control['certbotImage']:
        raise ValueError('Certbot image identity mismatch')
    docker('exec', web['Id'], 'nginx', '-t')
    run_id = uuid.uuid4().hex
    name = 'harness-cert-renew-' + run_id
    try:
        run(certbot_command(control['certbotImage'], name, run_id, dry_run), timeout=720)
    except subprocess.TimeoutExpired:
        # Only a matching transient container created by this exact invocation is eligible.
        existing = json.loads(docker('inspect', name))
        if len(existing) == 1 and existing[0]['Config'].get('Labels', {}).get('harness.production-ops.run') == run_id:
            docker('rm', '-f', existing[0]['Id'])
        raise
    current = inventory(compose, healthy=False)['ops-web-app']
    if current['Id'] != web['Id'] or control['composeSha256'] != digest(COMPOSE):
        raise ValueError('Web deployment changed during renewal; no reload performed')
    docker('exec', web['Id'], 'nginx', '-t')
    changed = digest(certificate) != certificate_before
    if dry_run and changed:
        raise ValueError('Dry-run changed the live certificate; no reload performed')
    if not dry_run and changed:
        docker('exec', web['Id'], 'nginx', '-s', 'reload')
    cert = run(['openssl', 'x509', '-in', str(certificate), '-noout', '-dates'])
    return {'status': 'CERT_DRY_RUN_PASSED' if dry_run else 'CERT_RENEWAL_CHECK_PASSED', 'nginxReloaded': not dry_run and changed, 'certificateChanged': changed, 'certificateValidity': cert.strip().splitlines()}


def wrapper_arguments(arguments):
    if arguments == ['config', '--quiet']:
        return arguments
    if len(arguments) == 4 and arguments[:3] == ['ps', '--all', '--quiet'] and arguments[3] in SERVICES:
        return arguments
    raise ValueError('Legacy Compose mutations are disabled; use the reviewed production entry')


def dependency_levels(compose):
    remaining = set(SERVICES)
    complete, levels = set(), []
    while remaining:
        ready = sorted(name for name in remaining if set(compose['services'][name].get('depends_on', {})) <= complete)
        if not ready:
            raise ValueError('Dependency cycle or dependency outside the selected services')
        levels.append(ready)
        remaining.difference_update(ready)
        complete.update(ready)
    return levels


def lifecycle(control, compose, action):
    before = inventory(compose, healthy=False)
    identities = {name: info['Id'] for name, info in before.items()}
    levels = dependency_levels(compose)
    if action == 'stop':
        levels.reverse()
    deadline = time.monotonic() + 240
    for level in levels:
        if digest(COMPOSE) != control['composeSha256']:
            raise ValueError('Compose changed during lifecycle operation; further actions stopped')
        # Address only the captured container IDs. A concurrent replacement is never started/stopped.
        ids = [identities[name] for name in level]
        if action == 'stop':
            docker('stop', '--time', '30', *ids, timeout=45)
        else:
            docker('start', *ids, timeout=30)
            while True:
                infos = json.loads(docker('inspect', *ids))
                ready = all(i['State']['Running'] and not i['State'].get('Paused') and not i['State'].get('Restarting') and i['State'].get('Health', {}).get('Status', 'healthy') == 'healthy' for i in infos)
                if ready:
                    break
                if time.monotonic() > deadline:
                    raise ValueError('Selected service readiness deadline exceeded')
                time.sleep(1)
    actual = inventory(compose, healthy=action == 'start')
    if {name: info['Id'] for name, info in actual.items()} != identities or digest(COMPOSE) != control['composeSha256']:
        raise ValueError('Deployment changed during lifecycle operation; success is not claimed')
    if action == 'stop' and any(info['State']['Running'] for info in actual.values()):
        raise ValueError('Some selected services are still running')
    return {'status': 'STARTED' if action == 'start' else 'STOPPED', 'serviceCount': len(SERVICES), 'containersRecreated': False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['status', 'start', 'stop', 'backup', 'cert-renew', 'cert-dry-run', 'compose-wrapper'])
    parser.add_argument('legacy_arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Root-owned production operations require root')
    os.umask(0o077)
    if args.action != 'compose-wrapper' and args.legacy_arguments:
        raise ValueError('Unexpected operation arguments')
    if LOCK.is_symlink():
        raise ValueError('Operations lock cannot be a symlink')
    with os.fdopen(os.open(LOCK, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600), 'w') as lock:
        info = os.fstat(lock.fileno())
        if info.st_uid != 0 or info.st_mode & 0o077 or info.st_nlink != 1:
            raise ValueError('Operations lock must be a private root-owned regular file')
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        control, compose = load_control()
        if args.action == 'compose-wrapper':
            safe = wrapper_arguments(args.legacy_arguments)
            inventory(compose, healthy=False)
            sys.stdout.write(run(compose_command(*safe)))
            return
        if args.action == 'backup':
            result = backup(control, compose)
        elif args.action.startswith('cert-'):
            result = renew(control, compose, args.action == 'cert-dry-run')
        elif args.action in {'start', 'stop'}:
            result = lifecycle(control, compose, args.action)
        else:
            actual = inventory(compose)
            result = {'status': 'READY', 'serviceCount': len(actual), 'composeSha256': control['composeSha256'], 'services': list(SERVICES)}
        print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Do not disclose subprocess output, command arguments, or secret paths.
        print(json.dumps({'status': 'FAILED', 'errorType': type(error).__name__, 'reason': str(error) if isinstance(error, ValueError) else 'Operation failed; no completion marker was issued'}), file=sys.stderr)
        raise SystemExit(1)
