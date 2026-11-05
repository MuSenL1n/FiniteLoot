# FiniteLoot（NeoForge 侧）

每个玩家各自一份战利品，同一个容器全服只允许 N 个人领取。**纯服务端模组，客户端不需要装任何东西。**

本目录是 [FiniteLoot](../README.md)（Paper/Purpur 插件）的 NeoForge 移植，与插件同仓库、同版本号发布，但面向不同的服务端：插件装在 Paper 服务器上，本模组装在 NeoForge 的模组服/整合包服务器上。两者不同时运行，游戏版本也因此各自独立——插件跟随 Paper 支持的版本，模组锁定下方表格里的版本。

| | |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.235 |
| Java | 21（构建需 21 与 25 两个工具链，Gradle 会自动补齐缺失的） |
| 环境 | **仅服务端**（`displayTest` 已从 1.21.1 移除；模组注册的 payload 数量为 0） |
| 依赖 | 无。SQLite 与 SnakeYAML 通过 `jarJar` 内嵌 |

---

## 它是怎么做到"客户端不用装"的

原版插件用 Bukkit 的 `InventoryOpenEvent` 换掉窗口。模组侧没有这个事件，但也不需要它——**唯一权威是 `AbstractContainerMenu`**：

1. 玩家右键容器，`PlayerInteractEvent.RightClickBlock` 被取消（`setUseBlock`/`setUseItem` 都是 `FALSE`），原版的开箱流程根本不会跑。
2. 我们自己给玩家开一个 `RewardMenu extends ChestMenu`，`MenuType` 用原版的 `generic_9x3`。
3. 客户端收到的 `OPEN_SCREEN` 是**原版协议包**，服务端向它发送的内容全部来自原版容器语义——客户端没有理由、也没有机会知道对面不是真箱子。

因此客户端不需要模组，也不需要任何握手。验证方式之一是拿**完全原版**的客户端连入（见"已验证"）。

---

## 它到底改了什么

七个动作会被接管，全部可独立开关：

| # | 场景 | 结果 |
|---|---|---|
| 1 | 玩家右键一个受管容器 | 打开**他自己的**战利品窗口，而不是共享容器 |
| 2 | 玩家右键一个从未被接管、但有战利品表的容器 | 现场接管，按战利品表生成他的那一份 |
| 3 | 玩家破坏受管容器 | 取消（`allow-breaking-exhausted-containers` 可放行已领完的） |
| 4 | 爆炸波及受管容器 | 从 `getToBlow()` 里摘掉，只有这一个方块免疫 |
| 5 | 漏斗/管道抽受管容器 | 拒绝。走到 `IItemHandler` 能力层，所以模组管道同样被拦 |
| 6 | 双箱 | 两半归一到同一个容器 id，共用一个领取计数 |
| 7 | 最后一个名额被领走 | 按 `final-claim-action` 交回原版，或保留个人窗口 |

"接管"的判定是**有战利品表**或**已有 id**。所以整合包里那些自带战利品表的模组容器（只要它实现原版的 `Container`）会自动被纳入，不需要配置方块清单。

---

## 安装

1. 服务端装 NeoForge 21.1.235（Minecraft 1.21.1）。
2. 把 `finiteloot-<version>-1.21.1-neoforge.jar` 丢进 `mods/`（同目录里不带版本与加载器后缀的 `finiteloot-<version>.jar` 是开发薄 jar，缺 SQLite 驱动，别用）。
3. 启动。首次启动会生成：
   - `config/finiteloot/config.yml` —— 与 Paper 版**字节级一致**，老配置可以直接搬过来
   - `finiteloot/claims.db` —— SQLite，含容器、领取、个人库存三张表

客户端什么都不用装。玩家用原版客户端直接连。

> **升级前请备份 `finiteloot/claims.db`。** `/fl migrate` 会在同目录写一份 `VACUUM INTO` 出来的副本，但它不替代备份。

---

## 命令

所有命令需要 **OP 等级 2**。`/fl` 与 `/finiteloot` 等价。

| 命令 | 作用 |
|---|---|
| `/fl inspect` | 显示光标所指容器的 id、上限、已领数、战利品表、维度与坐标。只读 |
| `/fl set <上限>` | 改当前容器的全服上限 |
| `/fl reset container` | 把当前容器的已领数清零（不动个人库存） |
| `/fl reset player <玩家>` | 清掉某个玩家在该容器上的领取记录 |
| `/fl remove` | 解除接管：删掉数据库行，并给方块打上 `ignored` 标记，之后不会被自动接管 |
| `/fl adopt` | 反向操作：清掉 `ignored`，**并把原来的 id 写回**，重新纳入管理 |
| `/fl migrate` | 备份数据库（`VACUUM INTO` + `integrity_check`） |
| `/fl reload` | 重读 `config.yml` 与语言文件 |

`set` / `reset` / `remove` / `adopt` 需要先用手持物品**右键一次目标容器**来选定它（命令作用于"你正在看的那个"）。

`adopt` 不只是清标记：它会按坐标找回原来的行并把 id 写回。少了这一步，下一次交互会铸一个新 id，同一个箱子出现两条行、两套计数。

---

## 配置

`config/finiteloot/config.yml`，热重载用 `/fl reload`。

| 键 | 默认 | 说明 |
|---|---|---|
| `default-max-claims` | `3` | 新容器的全服领取上限 |
| `loot-table-overrides` | 4 项 | 按战利品表覆盖上限（末地城 2、远古城市 2、地牢 5、废弃矿井 4） |
| `per-player-limit` | `1` | **必须保持 1**，否则拒绝启动。这是"每人一份"的硬保证 |
| `show-remaining-claims` | `true` | 开窗时在聊天框显示剩余名额 |
| `play-container-animation` | `true` | 首位开、末位关时播放真实开合动画 |
| `play-container-sounds` | `true` | 播放对应容器的开合音效 |
| `trigger-piglin-anger` | `true` | 打开受猪灵看守的容器会激怒附近猪灵 |
| `prevent-item-insertion` | `false` | `true` 时禁止往个人窗口里放东西 |
| `completed-container-becomes-normal` | `true` | `PERSONAL` 模式下取空后打开共享容器 |
| `final-claim-action` | `VANILLA_CONTAINER` | 末位交回原版，或 `PERSONAL` 保持个人窗口 |
| `clear-personal-inventories-on-final-claim` | `true` | 交回原版时清掉其他玩家的个人库存记录 |
| `show-final-claim-message` | `true` | 给拿到末位名额的玩家提示 |
| `prevent-hopper-extraction` | `true` | 拦漏斗与漏斗矿车 |
| `prevent-breaking` | `true` | 拦玩家破坏 |
| `allow-breaking-exhausted-containers` | `false` | 放行已领完的容器（会删掉残留记录，含未取空的个人库存） |
| `prevent-explosions` | `true` | 把受管容器从爆炸方块表里摘掉 |
| `count-creative-players` | `false` | 创造模式是否消耗名额 |
| `admin-bypass-counts` | `false` | 有 `finiteloot.bypass` 的玩家是否不消耗名额 |
| `excluded-worlds` | `[]` | 不参与自动接管的维度 |
| `language` | `zh_CN` | `zh_CN` / `en_US` |
| `exhausted-action` | `DENY` | 名额耗尽后的动作，目前只支持 `DENY` |

`per-player-limit` 与 `exhausted-action` 是**启动期校验**，写错会直接拒绝加载并说明原因，不会静默降级。

---

## 已验证 / 未验证

七轮分阶段验证，全部在**生产配置的真实服务端**上跑，探针默认不运行（`-Dfiniteloot.probes=...` 按需开启）。单元测试 **92 个**全绿。

| 阶段 | 内容 | 结果 |
|---|---|---|
| P0 | 零模组客户端连入、`generic_9x3` 出窗口、`jarJar` 的 SQLite 能加载、漏斗拦截、通用容器接管 | ✅ 全部端到端 |
| P1 | 数据层七个文件**零行改动**搬运 | ✅ 单测 7/7，生产端跑通迁移/驱动/备份 |
| P2 | 配置与本地化，`config.yml` 与语言文件字节级一致 | ✅ 单测 |
| P3 | 打标用 `AttachmentType`，双箱归一 | ✅ **硬杀重启**后标记存活，磁盘 NBT 独立复核 |
| P4 | 领取状态机（纯函数） | ✅ 31 个单测穷举 + 端到端含防复制断言 |
| P5 | 个人窗口、插入拦截、末位交接 | ✅ 三条绕过路径全部实测拦住 |
| P6 | 生产装配 + 7 个管理命令 | ✅ 24 条断言，经真实 Brigadier dispatcher |
| P7 | 交互/破坏/爆炸/漏斗/猪灵五面防护 | ✅ 20 条断言 |
| 跨模组 | 由**另一个 mod id** 注册的战利品容器能否被接管 | ✅ 9 条断言 |

**跨模组验证是怎么做的**：另建了一个独立模组（mod id `fltest`），它注册自己的容器方块与方块实体，并且像普通科技模组那样为自己的容器提供 `IItemHandler`。FiniteLoot 在这个容器上的结果是：`eligible=true`、拿到的 handler 是 `ProtectedHandler`（而不是该模组自己的 `InvWrapper`）、右键被拦截并打开个人窗口、解除接管后该模组的 handler 又回来了。

这说明三件事：遍历 `BLOCK_ENTITY_TYPE` 的注册确实覆盖到别人注册的方块实体；`HIGHEST` 优先级让我们排在对方前面，所以"拒绝"先被问到；不接管的时候完全隐形，对方模组的功能不受影响。

**已知边界**：
- 只接管实现原版 `Container` 且带战利品表的容器。纯 `IItemHandler`、不实现 `Container` 的机器缓冲不在范围内（那些本来也没有"一份战利品"可分配）。
- 如果某个模组**也在 `HIGHEST`** 为自己的容器注册 `IItemHandler` 并返回非空，谁先被问到取决于监听器注册顺序，理论上可能盖住我们。普通模组用默认优先级，不受影响。
- `ChestMenu` 的 `stillValid` 是 8 格判定而非真实射程，所以模组自己先做距离校验；超出 `BLOCK_INTERACTION_RANGE` 的右键不会被接管。

---

## 构建

在仓库根目录执行（两个子项目一起构建）：

```bash
./gradlew build
```

产物在 `neoforge/build/libs/`，发的是 `finiteloot-<version>-1.21.1-neoforge.jar`（内嵌 SQLite 与 SnakeYAML）；Paper 插件在 `paper/build/libs/`。

```bash
./gradlew :neoforge:dataLayerTest   # 只跑不依赖 Minecraft 的数据层测试
```

`dataLayerTest` 存在的意义是防回归：如果谁的 `vip.naya.finiteloot.data` 里混进了 Minecraft 或 NeoForge 的 import，这个任务会当场失败。

内部设计与移植决策见[架构说明](docs/architecture.md)。

---

## 与 Paper 版的差异

只改移植必需的部分，行为对齐：

| 项 | Paper | 这里 | 原因 |
|---|---|---|---|
| 换窗口 | `InventoryOpenEvent` | 取消交互 + `ChestMenu` | 模组侧没有等价事件 |
| 打标 | `PersistentDataContainer` | `AttachmentType` on `BlockEntity` | 同生命周期，且进 NBT |
| 维度身份 | `World#getUID()` | 维度名哈希成 UUID | MC 没有持久化世界 UUID；数据层因此零改动 |
| 漏斗 | `InventoryMoveItemEvent` | 能力层 provider | 覆盖面更广，且不需要事件 |
| 猪灵 | 自建租约服务 | `PiglinAi.angerNearbyPiglins` | 判定标准就是原版 |
| 消息渲染 | Adventure / MiniMessage | 自实现子集（5 种颜色 + 占位符） | 生产环境没有 Adventure |
| 权限 | 权限节点 | OP 等级 2 | 无需额外插件 |
| 爆炸保护 | `EntityExplodeEvent` | `ExplosionEvent.Detonate` | 等价 |

---

## 来源与许可

这是 [NayaCcR/FiniteLoot](https://github.com/NayaCcR/FiniteLoot)（Paper/Purpur 插件）的 NeoForge 移植，沿用同一许可 **GPL-3.0-only**。原插件的配置格式、数据库结构、领取状态机与消息文案都尽量原样保留——移植的目标是「行为一致」，不是重写。

- 数据层（`vip.naya.finiteloot.data`）与原插件逐字节相同，一行未改。
- `config.yml` 与两份语言文件与 Paper 版字节级一致。
- 与原插件不同之处集中在一层薄适配：事件替换窗口、能力层拦漏斗、`AttachmentType` 代替 `PersistentDataContainer`，见 [docs/architecture.md](docs/architecture.md)。

改动记录见 [CHANGELOG.md](CHANGELOG.md)。
