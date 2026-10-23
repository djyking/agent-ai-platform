# 当前 VPS 的生产运维入口

本目录是当前 VPS 部署专用的已审维护源码，不是通用部署模板。固定部署目录为 `/opt/harness/phase6-20261021T011700Z`，服务集合为当前28服务。2026-10-21已安装到 `/opt/harness/production-ops`；运行状态、控制文件、真实备份及验证证据只保存在服务器和本地ignored目录，不进入Git。安装器必须收到最终 Compose 的精确 SHA；之后 Compose 改动会使运维入口拒绝继续，必须先重新审查并绑定控制文件。

## 安装产生的具体变更

1. 新建 `/opt/harness/production-ops/`，保存 `opsctl.py`、0600 控制文件、私有失败日志及安装记录。
2. 原 `start-manually.sh`、`stop-manually.sh`、`compose.sh`、`backup-daily.sh`、`renew-certificates.sh` 的原始字节和模式先保存到新目录 `legacy-<UTC时间>/`，SHA核对通过后才逐个原子替换。
3. 新 start/stop 只操作最终配置中事先捕获的28个现有容器 ID，按依赖顺序启动/逆序停止；不创建、拉取、重建容器。`reranker`、`operations-lab` 不在集合内。
4. 旧 compose 入口仅允许 `config --quiet` 和选定单服务的 `ps --all --quiet`。`up`、`down`、`--force-recreate`、传入另一份配置等操作明确拒绝。配置执行器继续禁用。
5. 安装四个新 systemd 单元并执行 daemon-reload。安装器不启用 timer、不启停任何容器。旧三个 Ops timer 保持禁用。

## 备份范围

固定新目录 `/opt/harness/production-backups/`，权限0700；每次独立目录、文件0600。一次在线 single-transaction 导出六个 Ops 库及 `harness_platform`，拒绝非 InnoDB 表；知识附件、当前绑定配置、私有环境文件和证书卷分别归档。压缩格式、数据库清单、SHA回读与前后容器身份/健康检查通过后才发布 `.complete`；失败留下 `.FAILED` 和部分文件，不冒充成功。

不会删除任何旧备份或新备份；不设置自动 retention。磁盘剩余少于10GiB时拒绝新备份。唯一删除是本次独立目录中压缩校验已通过的原始临时 SQL/tar 文件。

七库SQL为同一事务快照，但附件/配置/证书不与数据库处于同一原子时点。它不备份实时 Redis/RabbitMQ/Nacos 状态、ES/Qdrant数据、监控时间序列或镜像，也不声称已完成恢复演练。本次完整冷备 `/opt/opsagent/backups/phase6-predeploy-20261021T011157Z` 保持独立保护。实际恢复另行停写、审查链接/设备条目并验证；没有自动解压或数据库导入入口。

新每日 timer：02:30 UTC，随机延迟0–15分钟，Persistent=true。首次真实备份验证后再启用。未来磁盘清理必须另行制定保护名单和留存策略。

## Docker证书续期

现有证书位于 Docker 卷 `opsagent_letsencrypt`，ACME Webroot为 `opsagent_acme-webroot`。主机通用certbot.timer不代替这条路径。

使用已存在不可变镜像 `sha256:9626d72120577cf72da4fc7948806e9993598981720a4cbe04340a502468d67b`，临时容器只挂这两个卷，无公网端口，256MiB/0.5CPU/128进程，禁额外capability。精确选择证书 `opsagent.cloud`（SAN含www），仅webroot。强制 `--config /dev/null`、禁目录hooks，复核renewal配置中的路径、域名和hook。12分钟超时仅允许清理本次随机标签匹配的临时容器，不触业务容器。

先执行真实 `cert-dry-run`。生产续期前后运行当前Web的 `nginx -t`；只有证书实际变化才reload同一个Web容器。干跑不reload，也不应改变live证书。当前证书有效期至2026-12-04 14:46:17 UTC。

新续期 timer：00:15、12:15 UTC，随机延迟0–60分钟，Persistent=true；干跑成功后再启用。

## 恢复主机指标

当前Prom仍在保留的 `opsagent_backplane`，不是迁到Harness私网；172.18.0.1:18112是正确网关。故障原因是原host-observer被停用。

`restore-host-observer.py` 只恢复该服务：先验证已审源码SHA、固定linux-host目标、磁盘`/`、接口`eth0`、私网监听地址、Bearer绑定及已有UFW规则。保留原manual-only覆盖和完整服务源码证据，新增 `production.conf` 仅设置 Restart=on-failure/5秒，再enable --now这一服务。它不改Compose、Prom配置、防火墙、Token或configuration-executor。

该采集代码仅用psutil读取本机指标。恢复后验证两次采样产生完整网络指标、无凭据401、未登记目标404，以及Prometheus真实抓取目标up。原有UFW规则只允许backplane网桥的172.18.0.0/16到172.18.0.1:18112，没有公网放行。

## 经主会话确认后的执行顺序

1. 把本目录的已审文件复制到新的远端候选目录，校验逐文件SHA；不覆盖旧候选。
2. 用系统Python执行：`python3 install.py --install --expected-compose-sha256 <最终SHA> --certbot-image sha256:9626d72120577cf72da4fc7948806e9993598981720a4cbe04340a502468d67b`。
3. 执行安装后的 `opsctl.py status`，确认28服务、固定镜像和最终SHA。
4. 执行一次 `systemctl start harness-production-backup.service`，读取私有manifest与完成标记，核对七库、文件SHA、容器身份及旧冷备仍存在。
5. 执行 `python3 /opt/harness/production-ops/opsctl.py cert-dry-run`，核对实际ACME验证、live证书不变、Web未reload。
6. 使用原服务venv执行候选 `restore-host-observer.py` 并核验Prom目标及权限负例。
7. 成功后再 `systemctl enable --now harness-production-backup.timer harness-production-cert-renew.timer`；确认计划与旧timer仍禁用。首次生产renew check可直接调用新service，未到期时不变更证书或reload。

首次安装已获授权，安装器保留旧入口并完成逐文件SHA验证。后续机器或部署路径变化时，必须先重新审查固定路径、服务集合、网络、证书卷及源哈希；不得把本目录原样用于另一台VPS。重装或恢复不能覆盖现有运维目录。

## 安装前权限检查

控制文件及父目录必须root所有且不可被组/其他用户写入。首次安装发现旧scripts目录为0707，保存原权限记录后仅将该目录收紧到0755；原脚本字节和模式另存于legacy备份。备份载荷的既有权限原样保存，不把载荷权限校验混同于可执行控制文件校验。

## 2026-10-21 验证记录

- 九项本地回归通过：固定服务/镜像、退役服务隔离、旧入口拒绝、依赖顺序、并发替换拒绝、停止状态、Certbot卷和配置、hook拒绝、备份失败证据保留。
- 新systemd备份service实际完成首轮七库备份，独立复核全部payload哈希及SQL数据库清单；原五个脚本备份哈希一致，完整冷备25个文件名称/大小不变。未执行数据库恢复演练。
- Docker Certbot实际干跑及新systemd续期service检查均成功，证书未变化、Web没有reload。
- host observer恢复为私网监听，401/404负例和完整第二次主机采样通过；Prometheus 26/26 targets为up。
- 两个新timer已enabled/active，原三个Ops timer与configuration-executor保持disabled。28容器身份和StartedAt在安装、备份、证书与observer接管期间未变，两个退役容器继续停止且restart=no。
- 03:30 UTC 最后一次Agent反馈修复部署后，03:31 UTC 独立核对Compose只有 `services.ops-agent-app.image` 变化，Agent镜像与JAR回读一致，其余27个现役及2个退役容器均保持原状态。旧control和前后Compose原字节保存到独立私有目录后，运维control原子绑定最终Compose SHA `cda33cd6eb819a48b3203193e8ed63005baa3408027e578309c1781b325487c2`；实际 `opsctl.py status` 返回28服务READY。重绑没有启停容器。
- 首轮03:11 UTC在线备份仍对应上一已验证Agent镜像及其Compose时点；最后镜像重绑没有再次运行完整备份，也不改变已有备份的时点或范围。

复现本地回归：`python -m unittest discover -s deploy/production-ops -p test_opsctl.py -v`。这些测试不连接服务器，不启动容器；真实验证证据位于ignored私有目录。
