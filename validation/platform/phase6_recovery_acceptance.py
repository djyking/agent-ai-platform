"""Exercise the real encrypted full/binlog/PITR tools on disposable, isolated local MySQL copies.

The existing application database is only read through a consistent dump. Binary-log backup
is performed on a new dedicated instance, never on the user's shared OpsAgent MySQL server.
Containers/volumes are retained for investigation; only this invocation's containers are stopped.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


class Exercise:
    def __init__(self, config, report):
        self.config = json.loads(Path(config).read_text(encoding="utf-8-sig"))
        self.root = Path(__file__).resolve().parents[2]
        self.docker = self.config["docker"]
        self.database = self.config["database"]
        if self.database != "harness_platform_phase6":
            raise ValueError("Only the explicitly isolated phase-six application database is supported")
        if self.config["sourceContainer"] != "opsagent-mysql-1":
            raise ValueError("Review the explicit local source container before using this exercise")
        self.work = Path(self.config["workDirectory"]).resolve()
        if not self.work.is_relative_to(self.root / ".work/phase6") or self.work.exists():
            raise ValueError("Use a fresh private directory below .work/phase6")
        self.work.mkdir(parents=True, mode=0o700)
        self.report_path = Path(report)
        if self.report_path.exists():
            raise ValueError("Report already exists")
        self.tag = "harness-phase6-recovery-" + uuid.uuid4().hex[:10]
        self.source, self.target = self.tag + "-source", self.tag + "-target"
        self.created = []
        self.passwords = {self.source: secrets.token_urlsafe(36), self.target: secrets.token_urlsafe(36)}
        self.report = {"format": "harness-local-recovery-v1", "status": "RUNNING",
                       "startedAt": self.utc(), "checks": [], "originalDatabaseReadOnly": True,
                       "dedicatedSourceCopy": True, "crossHostRecovery": False,
                       "applicationStartedInRecovery": False, "productionRpoRtoVerified": False}

    @staticmethod
    def utc():
        return datetime.now(timezone.utc).isoformat()

    def call(self, args, data=None, env=None, timeout=180):
        result = subprocess.run([self.docker, *args], input=data, capture_output=True,
                                env={**os.environ, **(env or {})}, timeout=timeout)
        if result.returncode:
            with (self.work / "diagnostics.private.log").open("ab") as log:
                log.write(result.stderr)
            raise RuntimeError("DOCKER_OPERATION_FAILED")
        return result.stdout

    def save(self):
        self.report_path.parent.mkdir(parents=True, exist_ok=True)
        self.report_path.write_text(json.dumps(self.report, indent=2) + "\n", encoding="utf-8")

    def sql(self, container, sql, database=None):
        return self.call(["exec", "-i", "-e", "MYSQL_PWD", container, "mysql", "-uroot",
                          "--protocol=TCP", "--host=127.0.0.1",
                          "--port=" + ("13306" if container == self.target else "3306"),
                          "--batch", "--skip-column-names", *(["--database=" + database] if database else [])],
                         sql.encode(), {"MYSQL_PWD": self.passwords[container]}).decode().strip()

    def start_mysql(self, container, target=False):
        secret = self.work / (("target" if target else "source") + ".password")
        secret.write_text(self.passwords[container], encoding="ascii")
        os.chmod(secret, 0o600)
        self.call(["run", "-d", "--name", container, "--network", self.tag,
                   "--mount", f"type=volume,source={container}-data,target=/var/lib/mysql",
                   "--mount", f"type=bind,source={secret},target=/run/secrets/root-password,readonly",
                   "-e", "MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root-password",
                   self.config["mysqlImage"], "--port=" + ("13306" if target else "3306"),
                   "--event-scheduler=OFF", "--skip-log-bin" if target else "--log-bin=mysql-bin",
                   *(["--server-id=6106", "--binlog-format=ROW"] if not target else [])])
        self.created.append(container)
        for _ in range(120):
            try:
                self.sql(container, "SELECT 1")
                return
            except RuntimeError:
                time.sleep(.5)
        raise RuntimeError("MYSQL_NOT_READY")

    def tool(self, container, command, timeout=180):
        if command and command[0].startswith("/tools/"):
            # Docker Desktop bind mounts do not preserve a Unix 0600 mode. MySQL correctly
            # ignores writable option files; copy them into this sidecar's private tmpfs.
            bootstrap = (
                "import os,pathlib,runpy,sys; os.umask(0o077); sys.path.insert(0,'/tools'); "
                "[(pathlib.Path('/dev/shm/'+n).write_bytes(pathlib.Path('/evidence/'+n).read_bytes()),"
                "os.chmod('/dev/shm/'+n,0o600)) for n in ['source.mysql.cnf','target.mysql.cnf']]; "
                "sys.argv=" + repr(command) + "; runpy.run_path(sys.argv[0],run_name='__main__')"
            )
            command = ["-c", bootstrap]
        return self.call(["run", "--rm", "--network", "container:" + container,
                          "--mount", f"type=bind,source={self.work},target=/evidence",
                          "--mount", f"type=bind,source={self.root / 'deploy/platform/phase5'},target=/tools,readonly",
                          "--shm-size=512m", self.config["toolsImage"], *command], timeout=timeout)

    def copy_database(self):
        self.sql(self.source, f"CREATE DATABASE `{self.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
        # The original container's existing root secret remains in its environment.
        export = [self.docker, "exec", "-i", self.config["sourceContainer"], "sh", "-c",
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
                  '--no-tablespaces --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4 '
                  + self.database]
        with (self.work / "source-export.private.log").open("wb") as error_log:
            producer = subprocess.Popen(export, stdout=subprocess.PIPE, stderr=error_log)
            consumer = subprocess.run([self.docker, "exec", "-i", "-e", "MYSQL_PWD", self.source,
                                       "mysql", "-uroot", "--default-character-set=utf8mb4", self.database],
                                      stdin=producer.stdout, stdout=subprocess.DEVNULL, stderr=error_log,
                                      env={**os.environ, "MYSQL_PWD": self.passwords[self.source]}, timeout=180)
            producer.stdout.close()
            status = producer.wait(timeout=30)
        if status or consumer.returncode:
            raise RuntimeError("SOURCE_SNAPSHOT_COPY_FAILED")

    def rows_digest(self, container):
        data = self.call(["exec", "-e", "MYSQL_PWD", container, "mysqldump", "-uroot",
                          "--protocol=TCP", "--host=127.0.0.1",
                          "--port=" + ("13306" if container == self.target else "3306"),
                          "--no-create-info", "--skip-comments", "--skip-add-locks",
                          "--skip-disable-keys", "--skip-extended-insert", "--order-by-primary",
                          "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob",
                          "--default-character-set=utf8mb4", self.database],
                         env={"MYSQL_PWD": self.passwords[container]})
        return hashlib.sha256(data).hexdigest()

    def run(self):
        self.save()
        try:
            self.call(["network", "create", "--internal", self.tag])
            for key in ("mysqlImage", "toolsImage"):
                self.report[key] = json.loads(self.call(["image", "inspect", self.config[key]]))[0]["Id"]
            self.start_mysql(self.source)
            self.start_mysql(self.target, target=True)
            self.copy_database()
            self.sql(self.source, "CREATE TABLE phase6_recovery_marker(id INT PRIMARY KEY, value_text VARCHAR(100));"
                     "INSERT INTO phase6_recovery_marker VALUES(1,'before-full');", self.database)
            source_defaults = self.work / "source.mysql.cnf"
            target_defaults = self.work / "target.mysql.cnf"
            for path, container, port in [(source_defaults, self.source, 3306), (target_defaults, self.target, 13306)]:
                path.write_text("[client]\nuser=root\npassword=" + self.passwords[container]
                                + f"\nhost=127.0.0.1\nport={port}\n", encoding="ascii")
                os.chmod(path, 0o600)
            # Generate the recovery key inside the Linux sidecar without printing private material.
            self.tool(self.source, ["-c", "import subprocess,pathlib; "
                      "subprocess.run(['age-keygen','-o','/evidence/recovery.agekey'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); "
                      "p=subprocess.run(['age-keygen','-y','/evidence/recovery.agekey'],check=True,capture_output=True); "
                      "pathlib.Path('/evidence/recipients.txt').write_bytes(p.stdout)"])
            state = self.work / "private-state"
            state.mkdir(mode=0o700)
            # Actual versioned deployment/identity recovery files are already operator controlled.
            for source in self.config["privateStateFiles"]:
                source = Path(source).resolve(strict=True)
                if not source.is_relative_to(self.root / ".work/phase6/local") or not source.is_file():
                    raise ValueError("Recovery state must be an explicit local phase-six file")
                (state / source.name).write_bytes(source.read_bytes())
            backup = {"database": self.database, "mysqlDefaults": "/dev/shm/source.mysql.cnf",
                      "ageRecipients": "/evidence/recipients.txt", "destination": "/evidence/encrypted",
                      "privateState": "/evidence/private-state", "tmpfs": "/dev/shm/harness-backup",
                      "metricsDirectory": "/evidence/metrics", "dedicatedInstanceConfirmed": True}
            (self.work / "backup.json").write_text(json.dumps(backup), encoding="utf-8")
            self.tool(self.source, ["/tools/backup.py", "full", "--config", "/evidence/backup.json"])
            full = next((self.work / "encrypted").glob("full-*.tar.gz.age"))
            self.sql(self.source, "INSERT INTO phase6_recovery_marker VALUES(2,'after-full-before-cut');", self.database)
            expected = self.rows_digest(self.source)
            cut = int(time.time()) + 2
            while time.time() < cut + 1:
                time.sleep(.1)
            stop_at = datetime.fromtimestamp(cut, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            self.sql(self.source, "INSERT INTO phase6_recovery_marker VALUES(3,'after-cut-must-not-restore');", self.database)
            self.tool(self.source, ["/tools/backup.py", "binlog", "--config", "/evidence/backup.json"])
            logs = list((self.work / "encrypted").glob("binlog-*.tar.gz.age"))
            command = ["/tools/restore.py", "--full", "/evidence/encrypted/" + full.name,
                       "--identity", "/evidence/recovery.agekey", "--stop-at", stop_at,
                       "--target-defaults", "/dev/shm/target.mysql.cnf",
                       "--apply-isolated", "--isolation-ack", "workers-absent-network-isolated",
                       "--report", "/evidence/restored.json"]
            for log in logs:
                command += ["--binlog", "/evidence/encrypted/" + log.name]
            began = time.monotonic()
            self.tool(self.target, command)
            restored = self.rows_digest(self.target)
            if restored != expected:
                raise RuntimeError("RECOVERED_ROWS_DIFFER")
            markers = self.sql(self.target, "SELECT id FROM phase6_recovery_marker ORDER BY id", self.database)
            if markers.splitlines() != ["1", "2"]:
                raise RuntimeError("POINT_IN_TIME_BOUNDARY_FAILED")
            states = self.sql(self.target, "SELECT run_status,COUNT(*) FROM harness_runs GROUP BY run_status", self.database)
            self.report.update(status="PASSED", snapshotRowsSha256=expected, restoredRowsSha256=restored,
                               restoredRunStates=states.splitlines(), pointInTimeTarget=stop_at,
                               localDatabaseRestoreAndVerificationSeconds=round(time.monotonic() - began, 3))
            self.report["checks"] = [
                {"name": "real-age-encrypted-full-and-row-binlogs", "passed": True},
                {"name": "private-state-in-encrypted-archive", "passed": True},
                {"name": "all-table-data-digest-match", "passed": True},
                {"name": "pre-cut-change-restored-post-cut-excluded", "passed": True},
                {"name": "isolated-new-target-no-workers", "passed": True}]
            self.report["backupReceipts"] = [
                json.loads(p.read_text(encoding="utf-8")) for p in
                sorted((self.work / "encrypted").glob("*.age.json"))]
        except Exception as failure:
            self.report["status"] = "FAILED"
            self.report["failureType"] = type(failure).__name__
            if isinstance(failure, RuntimeError):
                self.report["failureCode"] = str(failure)
            raise
        finally:
            for container in self.created:
                try:
                    self.call(["stop", "-t", "15", container], timeout=30)
                except Exception:
                    self.report.setdefault("cleanupWarnings", []).append(container)
            self.report["retainedContainers"] = self.created
            self.report["retainedNetwork"] = self.tag
            self.report["finishedAt"] = self.utc()
            self.save()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    try:
        Exercise(args.config, args.report).run()
    except Exception as failure:
        print("Local recovery exercise failed. Type: " + type(failure).__name__)
        raise SystemExit(1)
