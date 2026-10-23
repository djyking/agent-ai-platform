# 合成检索与真实回答质量基线

2026-09-18，固定 `quality-synthetic-v1` 数据集与 `grounded-json-v1` prompt 的首轮真实评估 **10/10 PASSED**。运行前生成 [`live-quality-plan-v1.json`](live-quality-plan-v1.json) 冻结金标、阈值和模型配置；[`live-quality-v1.json`](live-quality-v1.json) 保留逐例合成上下文、回答、引用及评分。首轮后没有修改金标或阈值，也没有重试模型调用。

| 项目 | 本次结果 |
|---|---|
| 场景 | 运维 5 例、研发 5 例；6 可答、4 拒答 |
| 真实模型 | `deepseek-v4-pro`，由当前服务端模型目录核实；temperature=0、JSON 输出、每次最多512输出token |
| 调用与结算 | 10 次真实模型调用、10 次实际 RAG 检索；5038 已知 token，逐例与 Harness 结算一致 |
| 检索金标 micro recall | 7/7 必需文档，100% |
| 越权检索与合成 marker 泄露 | 0 / 0 |
| 引用规则有效性 | 10/10，100% |
| 可答场景的必需事实 | 6/6，100% |
| 可答性/拒答准确性 | 10/10，100% |
| 所有预定规则同时通过 | 10/10，100% |

源资料全部为版本化英文合成资料，URI 使用 `synthetic.invalid`。内容包括当前/已废弃规范冲突、备份数值、发布检查、API重试、双来源迁移、跨租户请求、未知资料和来源中的提示注入。真实调用仅发送这些合成资料，没有发送 OpsAgent 内部文档。凭据仅在进程中注入，没有写入报告或仓库。

已逐例审阅输出：Amber 回答选取当前 2%/5分钟/0% 规则；双来源迁移回答引用两份正确文档；4 个不可回答问题均明确资料不足并返回空引用；Cobalt 来源内的注入 marker 没有被执行或输出。原始合成回答保存在 JSON 中，可继续复核。

## 冻结版本

- 数据集 SHA-256：`9f9d1bca386b491ee9c80570e2acbaed87ceb5bac36143c58c2c5e8ea3003ed7`
- Prompt SHA-256：`40fe1b546f12f20261771f6650483760ad6ff354ccb0cf538a5e2ab3c8605d6b`
- 模型 profile SHA-256：`9c01ecd0115b3a59b1952e4d63a475f2cc9bb082348769d25237916d0bb3fe7e`
- 计划冻结时间：`2026-09-18T04:41:14.491987100Z`，先于真实运行。

新增 `quality-plan` 和 `live-quality` 可复用入口，详见 [`harness-validation/README.md`](../../../harness-validation/README.md)。`live-quality` 会精确匹配冻结计划，普通 Maven 测试不访问真实模型。新增质量测试为 evals 3 项、validation 2 项；本轮 validation 及依赖 reactor 共 144 项测试通过。原有 9 个工程场景数据集没有被覆盖。

## 解释边界

这是小型英文合成语料上的自动规则基线。检索使用现有 `InMemoryRetriever` 词法匹配、top 3、宿主授权的 `RagTool`，先由宿主检索，再做一次真实回答生成；没有评估模型自主检索 query 或向量索引。合法引用 ID 与关键事实正则命中不是完整语义蕴含证明，也不能保证回答没有未检查的错误。这里的零 marker 泄露只涵盖这组跨租户案例。

固定了显式模型 ID、请求参数、数据集和 prompt；供应商模型权重/别名目标是否长期不变没有独立保证。结果不能外推为中文、真实业务资料、大规模召回、所有提示攻击、生产性能或整体回答质量已经达标。
