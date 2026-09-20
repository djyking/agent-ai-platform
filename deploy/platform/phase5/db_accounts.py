"""Provision migration/runtime/backup accounts from a private JSON file via stdin."""
import argparse
import json
import re
import subprocess
from pathlib import Path


def account_sql(config):
    db = config["database"]
    host = config["accountHost"]
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", db):
        raise ValueError("Invalid database")
    if not re.fullmatch(r"[A-Za-z0-9.%_:/-]{1,255}", host):
        raise ValueError("Invalid account host")
    sql = [f"CREATE DATABASE IF NOT EXISTS `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"]
    grants = {
        "runtime": "SELECT, INSERT, UPDATE, DELETE",
        "migration": "SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES",
        "backup": "SELECT, SHOW VIEW, TRIGGER",
    }
    for role, permissions in grants.items():
        value = config[role]
        name, password = value["user"], value["password"]
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,31}", name):
            raise ValueError("Invalid account name")
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", password):
            raise ValueError("Use a 32+ character random alphanumeric/base64url password")
        if password.startswith(("REPLACE_", "ANOTHER_", "32_OR_MORE_")):
            raise ValueError("Example account passwords must be replaced before provisioning")
        account = f"'{name}'@'{host}'"
        # No IF NOT EXISTS: an existing account must be reviewed, not silently re-used.
        sql += [f"CREATE USER {account} IDENTIFIED BY '{password}' REQUIRE SSL;",
                f"GRANT {permissions} ON `{db}`.* TO {account};"]
        if role == "backup":
            sql += [f"GRANT RELOAD, REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO {account};"]
    return "\n".join(sql) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-config", type=Path, required=True)
    parser.add_argument("--admin-defaults", type=Path, required=True)
    args = parser.parse_args()
    sql = account_sql(json.loads(args.private_config.read_text(encoding="utf-8")))
    result = subprocess.run(["mysql", "--defaults-extra-file=" + str(args.admin_defaults.resolve()),
                             "--batch"], input=sql.encode(), stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise SystemExit("Account provisioning failed; review account existence and server audit privately")
    print("Created separate DML runtime, schema migration, and read/replication backup accounts.")


if __name__ == "__main__":
    main()
