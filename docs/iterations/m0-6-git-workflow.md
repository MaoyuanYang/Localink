# 迭代任务卡：M0.6 Git 规范落地

| 字段 | 内容 |
|---|---|
| 迭代编号 | M0.6 |
| 分支 | `feature/m0-6-git-workflow` |
| 状态 | 待用户确认 |

---

## 1. 目标

把 AGENTS.md 第 5 节的 Git 规范从"纸面约定"变成"可验证的落地状态"：补齐 `.gitignore` 的敏感文件防线、确认分支策略与 Conventional Commits 已在真实迭代中执行、并以本 PR 本身作为 feature 分支 → PR → 合入 main 的流程演示。

涉及表：无；涉及接口：无；涉及 Redis Key：无。

## 2. 设计取舍

- **分支策略选 main + feature 短命分支，不选 Git Flow**：Git Flow（develop/release/hotfix 多主线）适合有版本发布周期的多人团队，本项目为单人迭代、持续演进，多主线只会增加合并成本。备选 trunk-based（主干直接提交）——被否：求职作品需要展示 PR 审查流程，且 AGENTS.md 铁律 4 明确 main 受保护
- **Conventional Commits 靠约定而非工具强制**：不引入 commitlint + husky 钩子链。理由：单人开发，钩子工具链（Node 依赖、hook 安装、CI 校验）的维护成本大于收益；规范单点记录在 AGENTS.md，历史 8 个 PR 的提交信息已 100% 符合 `type(scope): subject`。团队扩张后再引入工具强制不迟
- **.gitignore 用"显式点名"而非通配符拦本地配置**：只拦 `application-local.*` 与 `*.env`，不用 `*-local.*` 之类的宽模式。理由：Spring Boot 的 profile 文件命名是确定的（`application-{profile}.yml`），宽通配符会误伤未来可能入库的正常文件；显式名单可读、可审查。`application-local.yml` 尚未创建（M1 接数据库时才出现），现在就把防线写好，保证它诞生那天起就不可能入库
- **合并方式用普通 merge commit，不用 squash**：保留 feature 分支上的真实提交历史（含踩坑修复过程），merge commit 关联 PR 编号，回溯链完整。squash 会抹掉演进痕迹，与本项目"演进式开发"的展示目标冲突
- **为什么 M0.6 现在才做**：M0.1~M0.5 已实际跑了 8 个 PR，规范在实战中成型；M0.6 是对既成事实的确认、补漏（.gitignore）与文档化，属于收尾性质，不阻塞也不被阻塞

## 3. 产出物

| 文件 | 说明 |
|---|---|
| `.gitignore` | 追加本地环境配置防线：`application-local.yml/yaml/properties` + `*.env`（AGENTS.md §5 禁止项的机器化保障） |
| `docs/iterations/m0-6-git-workflow.md` | 本任务卡：规范确认 + 设计取舍记录 |
| 本 PR | feature 分支 → Conventional Commits → PR 自审 → 合入 main → 删分支 的完整流程演示 |
| 分支清理 | 删除 3 个已合并的遗留 feature 分支（远端 + 本地） |

**已在执行的规范确认**（无需新增文件，事实核查）：

| 规范项 | 证据 |
|---|---|
| feature 分支命名 `feature/mX-Y-描述` | PR #1~#8 分支：`feature/m0-1-prd` … `feature/m0-7-frontend-plan` |
| Conventional Commits | 全部提交符合 `type(scope): subject`（feat/docs/chore 均已出现） |
| main 受保护、不直接提交 | 8 次合入全部经 PR，无直推 main 记录 |
| 合入后删除远端 feature 分支 | 本次清理存量遗留：`feature/m0-4-skeleton`、`feature/m0-5-common`、`feature/m0-7-frontend-plan` 三个已合并分支的远端与本地均已删除 |

## 4. 验证记录

| 检查项 | 命令 | 结果 |
|---|---|---|
| 本地配置被 ignore 规则命中 | `git check-ignore -v localink-server/src/main/resources/application-local.yml` | 命中 `.gitignore` 中 `application-local.yml` 规则 |
| env 文件被拦截 | `git check-ignore -v .env` | 命中 `*.env` 规则 |
| 正常文件不受影响 | `git check-ignore -v localink-server/src/main/resources/application.yml` | 无输出（不被忽略） |
| 历史提交信息合规 | `git log --oneline` 逐条核对 | 全部符合 Conventional Commits |

## 5. 学习清单

**核心知识点**
1. `.gitignore` 匹配规则：模式不带 `/` 时匹配任意层级；`!` 取反的生效前提（父目录未被忽略）；`git check-ignore -v` 排查命中规则
2. 已提交文件的忽略：`.gitignore` 只对未跟踪文件生效，已入库文件需 `git rm --cached` 后提交才生效
3. merge vs rebase vs squash：merge 保历史、rebase 线性化、squash 聚合；各自适用的团队协作场景
4. 敏感信息防线分层：`.gitignore`（事前）→ pre-commit 钩子扫描（事中）→ BFG/git filter-repo 清史（事后）

**面试必问题**
1. "误提交了密码/密钥怎么办？"——未推送：`git reset` 回退；已推送：立即轮换密钥（清史不能替代作废密钥），再 BFG 清史 + 强推
2. "rebase 和 merge 怎么选？"——个人分支同步主干用 rebase 保线性，合入主干用 merge 留审查痕迹；黄金法则：不 rebase 已共享的提交
3. "怎么保证团队提交信息规范？"——约定 + 工具强制（commitlint + husky/CI 校验），单人项目约定即可，规模化后再上工具

## 6. 下一步

M1.1 数据库设计文档：ER 图 + 12 张表字段说明（`docs/database.md`），进入 M1 基础业务闭环。
