#!/usr/bin/env python3
"""Restore the verified, private, read-only host observer only; no Compose/firewall edits."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.request
import urllib.error
import opsctl as ops

EXPECTED_SOURCES = {
    'collector.py': 'a576bc5cd47b5e9ea62b00b910144823ef6092802510ade9cb401a8898d547ee',
    'linux_host.py': 'fdd8669c17da9f9d50400c4015ca0542ae70c225d722c869b96aef5c53e921d7',
    'windows_host.py': '44f30dba536f0ae3d1a8643b7e4d88f5d88a5035c9feaca26a5e595a08ec3578',
}
CONFIG_SHA = 'a8ecc5df8d7b227052d4fface2c8d19265dc709ee68934c6b75277fd7e3e14bd'
HELPER = Path('/opt/opsagent/runtime-helpers/observation-collector')
CONFIG = Path('/opt/opsagent/runtime-helpers/host-collector.json')
UNIT = 'opsagent-host-observer.service'
UNIT_SHA = 'aedf2c5151ff64c43533fe292394debc9dca784d40bbb2b10a408747d8b51ad7'
MANUAL_SHA = '9b6943ff77b31e063152214a3aa57a6f73782b14434700170a0f30ca70ef2524'


def get(url, token=None):
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token} if token else {})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=5) as response:
            return response.status, response.read(1024 * 1024).decode()
    except urllib.error.HTTPError as error:
        return error.code, ''


def main():
    if os.geteuid() != 0:
        raise ValueError('Root required')
    os.umask(0o077)
    control, compose = ops.load_control()
    infos = ops.inventory(compose)
    for name, expected in EXPECTED_SOURCES.items():
        ops.secure_path(HELPER / name)
        if ops.digest(HELPER / name) != expected:
            raise ValueError('Observer code changed after the read-only review')
    ops.secure_path(CONFIG)
    if ops.digest(CONFIG) != CONFIG_SHA:
        raise ValueError('Observer configuration changed after review')
    config = json.loads(CONFIG.read_bytes())
    if config['listen_host'] != '172.18.0.1' or config['listen_port'] != 18112 or config['targets'] != {'linux-host': {'kind': 'linux-host', 'host_id': 'opsagent-cloud-host', 'disks': ['/'], 'interfaces': ['eth0']}}:
        raise ValueError('Unexpected host observer scope')
    token = (ops.OPSDIR / 'runtime/host-collector.token').read_text().strip()
    if config['bearer_token'] != token:
        raise ValueError('Prometheus bearer binding differs')
    network = infos['prometheus']['NetworkSettings']['Networks']['opsagent_backplane']
    if network['Gateway'] != '172.18.0.1' or not network['IPAddress'].startswith('172.18.'):
        raise ValueError('Current Prometheus is not on the reviewed private backplane')
    rules = ops.run(['ufw', 'status', 'numbered'])
    required = '172.18.0.1 18112/tcp on br-9b58c2fca104'
    if not any(required in line and '172.18.0.0/16' in line and 'ALLOW IN' in line for line in rules.splitlines()):
        raise ValueError('Existing private firewall rule no longer matches review')
    dropin = Path('/etc/systemd/system/opsagent-host-observer.service.d')
    ops.secure_path(dropin, directory=True)
    unitfile = Path('/etc/systemd/system') / UNIT
    ops.secure_path(unitfile)
    original = dropin / 'manual-only.conf'
    ops.secure_path(original)
    if ops.digest(unitfile) != UNIT_SHA or ops.digest(original) != MANUAL_SHA or {path.name for path in dropin.iterdir()} != {'manual-only.conf'}:
        raise ValueError('Observer service or override changed after review')
    destination = dropin / 'production.conf'
    if destination.exists():
        raise ValueError('Existing production observer override must not be overwritten')
    snapshot = ops.ROOT / 'host-observer-before.private.json'
    ops.private_write(snapshot, {'recordedAt': ops.now(), 'unitSource': ops.run(['systemctl', 'cat', UNIT, '--no-pager']), 'manualOnlySha256': ops.digest(original), 'manualOnlyContent': original.read_text(), 'configSha256': CONFIG_SHA, 'sourceHashes': EXPECTED_SOURCES})
    with destination.open('x') as output:
        output.write('[Service]\nRestart=on-failure\nRestartSec=5\n')
    destination.chmod(0o644)
    ops.run(['systemctl', 'daemon-reload'])
    ops.run(['systemctl', 'enable', '--now', UNIT])
    url = 'http://172.18.0.1:18112/metrics/linux-host'
    deadline = time.monotonic() + 30
    while True:
        try:
            code, body = get(url, token)
            if code == 200 and 'opsagent_infra_read_success 1' in body:
                break
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise ValueError('Observer did not become available')
        time.sleep(1)
    time.sleep(1)
    code, body = get(url, token)
    if code != 200 or 'opsagent_infra_host_complete 1' not in body:
        raise ValueError('Observer has not produced a complete second host sample')
    if get(url)[0] != 401 or get('http://172.18.0.1:18112/metrics/not-registered', token)[0] != 404:
        raise ValueError('Observer negative access checks failed')
    prom = 'http://' + network['IPAddress'] + ':9090/api/v1/targets?state=active'
    deadline = time.monotonic() + 75
    while True:
        status, text = get(prom)
        targets = json.loads(text)['data']['activeTargets'] if status == 200 else []
        selected = [target for target in targets if target.get('labels', {}).get('job') == 'opsagent-linux-host']
        if len(selected) == 1 and selected[0].get('health') == 'up':
            break
        if time.monotonic() > deadline:
            raise ValueError('Prometheus did not confirm the real private host scrape')
        time.sleep(3)
    result = {'status': 'HOST_OBSERVER_RESTORED', 'recordedAt': ops.now(), 'privateBind': '172.18.0.1:18112', 'prometheusTargetUp': True, 'unauthenticatedStatus': 401, 'unregisteredTargetStatus': 404, 'hostSampleComplete': True, 'firewallChanged': False, 'composeChanged': False, 'configurationExecutorChanged': False}
    ops.private_write(ops.ROOT / 'host-observer-restored.private.json', result)
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'status': 'HOST_OBSERVER_RESTORE_FAILED', 'errorType': type(error).__name__, 'reason': str(error) if isinstance(error, ValueError) else 'Restore stopped; inspect preserved pre-change evidence'}))
        raise SystemExit(1)
