# 内部 TLS 材料准备

`prepare-internal-tls.py` 只在指定的全新目录生成材料，不连接服务、不改已有 MySQL 证书、不修改源 JRE。依赖 Python 3.12、OpenSSL 1.1.1 或 3.x 和目标 Java 17 的 `keytool`。输出目录的父目录必须已存在；即使目标为空目录也拒绝覆盖。失败目录保留 `failed.json`，不得用于部署或再次生成。

先从将要运行的实际 Java 镜像取得 `$JAVA_HOME/lib/security/cacerts`，核对镜像来源及摘要。不同 JRE 的公共根可能不同；使用不同基础镜像时分别准备/核对其信任库，不能拿只有私有 CA 的空库替代公共根。脚本复制源库，加入新 CA，逐张核对所有原证书的 SHA-256、数量及类型保持不变，避免破坏 DeepSeek/OpenAI 等公网 HTTPS。

```sh
python3 prepare-internal-tls.py \
  --output-dir /opt/harness/private/internal-tls-NEW-REVISION \
  --jre-cacerts /opt/harness/private/runtime-jre-cacerts \
  --keytool /path/to/actual-jdk/bin/keytool
```

源库与输出库默认使用 JRE 标准完整性密码 `changeit`。此库只含公开证书，密码不是应用凭据。非默认密码通过 `--truststore-password-file /private/password-file` 提供（UTF-8 单行），同步配置各 Java 进程的 trustStorePassword；不要把实际密码写进源码、命令行或日志。脚本不改变源库密码，不接受含私钥/密钥条目的源库。

| 输出 | 使用方与边界 |
| --- | --- |
| `internal-tls/server-chain.pem`、`server-key.pem` | 私有 TLS 网关。链包含网关叶证书和 CA；DNS SAN 为 `harness-identity.internal`、`harness-rag.internal`、`harness-platform.internal`。 |
| `mysql-tls/server-cert.pem`、`server-key.pem`、`ca.pem` | 仅 MySQL，使用独立私钥；DNS SAN 为 `mysql`、`harness-mysql`。 |
| `trust/cacerts` | Java 客户端；`manifest.json` 的 `trustStoreType` 为真实 JKS 或 PKCS12。包含源 JRE 公共根和新私有 CA。 |
| `ca/public/ca.crt` | 用于核对指纹或向其他经批准的客户端导入。 |
| `ca/private/ca.key` | CA 签名私钥，只存离线私有管理目录，绝不挂入任何服务。 |
| `started.json`、`manifest.json`、`requests/` | 生成记录、完整性证据及签发请求；不是部署验收记录。 |

Compose 的三个服务挂载源只可分别选择 `internal-tls/`、`mysql-tls/`、`trust/`，不能挂载整个输出目录或 `ca/`。按部署生成器的 `runtimeDirectory/private/` 约定安装到同名子目录；先核对 manifest 和文件摘要，再以拒绝覆盖的方式安装。已有 revision、证书和证据必须保留，轮换使用新 revision。

生成目录及 CA 私钥目录为 0700，服务证书目录为 0755，私钥为 0600，公开证书与公开信任库为 0644。在 Linux 安装时根据实际镜像确认 UID/GID，把 MySQL 私钥属主设为该 MySQL 进程的 UID，并以该 UID 核验可读。不要通过把私钥改为全员可读来解决权限问题。Windows 文件模式不是 NTFS ACL 隔离保证；本机生成仅适合测试，正式材料应在私有 Linux 管理路径中生成。

保留 MySQL 数据目录原有自动证书。显式挂载新 `mysql-tls/` 后配置：

```text
--ssl-ca=/run/harness-mysql-tls/ca.pem
--ssl-cert=/run/harness-mysql-tls/server-cert.pem
--ssl-key=/run/harness-mysql-tls/server-key.pem
```

JDBC 保持 `sslMode=VERIFY_IDENTITY`，连接名必须是两个 MySQL SAN 之一；不能通过关闭主机名验证兼容原自动证书。目标部署仍需验证实际容器读取权限、证书链、DNS、TLS 握手、数据库账号 `REQUIRE SSL` 和应用连接。网关没有宿主或公网端口，且必须限定上游和允许路径；证书签发不代替应用认证/授权。

默认 CA 有效期 3650 天、服务证书 397 天，可通过 `--ca-days`/`--leaf-days` 缩短；叶证书期限必须小于 CA。后续轮换需重新准备新的私有 revision、按服务维护计划切换和保留回滚材料；本脚本不自动重启服务、不替换旧证书，也不设置计划任务。
