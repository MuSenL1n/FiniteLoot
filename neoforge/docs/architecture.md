# 架构

面向维护者。玩家与服主看 [README](../README.md)。

---

## 分层

```
                    ┌──────────────────────────────┐
   事件 / 命令 ───▶ │  event/  command/            │  适配层：只做翻译
                    └──────────────┬───────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │  claim/  container/  gui/    │  领域层：状态与规则
                    └──────────────┬───────────────┘
                                   ▼
                    ┌──────────────────────────────┐
                    │  data/                       │  纯 JDBC，零平台符号
                    └──────────────────────────────┘
```

`data/` 与 Paper 版**逐字节相同**，一行没改。`claim/ClaimStateMachine` 是纯函数，也是唯一被穷举测试的部分。

分层的判据不是"好不好看"，而是**能不能在没有游戏的情况下测**。`ClaimStateMachineIsolationTest` 会读字节码常量池，一旦 `data/` 或状态机里出现平台类型，测试直接失败——这条规则是自动执行的，不靠自觉。

---

## 一次右键发生了什么

```
RightClickBlock
  ├─ 已取消 / 非主手 / 非 ServerPlayer ─────────────▶ 放行
  ├─ 管理员待执行动作 ─────────────────────────────▶ AdminActions.handleClick
  ├─ excluded-worlds 命中 ─────────────────────────▶ 放行
  ├─ 超出 BLOCK_INTERACTION_RANGE ─────────────────▶ 放行（不接管，避免"窗口闪一下"）
  ├─ ContainerManager.resolve(pos) 为空 ───────────▶ 放行（普通方块）
  └─ 接管：
       ├─ 取消事件（useBlock/useItem 都置 FALSE）
       ├─ ContainerPresentation.animate + playSound
       ├─ PiglinAnger.reactToOpen（方块在 GUARDED_BY_PIGLINS 里才触发）
       └─ ClaimController.open(player, target)
            └─ 分配 → 回调 → RewardSessions.openPersonal → openMenu(RewardMenu)
```

**顺序是有原因的**：距离校验必须在开窗之前。`ChestMenu.stillValid` 只查 8 格，超距开窗会让服务端下一 tick 把窗口收回，玩家看到闪烁（R27）。

---

## 数据模型

三张表，从 Paper 版原样继承：

| 表 | 关键列 | 说明 |
|---|---|---|
| `containers` | `id`, `world_uid`, `x/y/z`, `partner_x/y/z`, `loot_table`, `max_claims`, `claim_count` | 一个箱子一行；双箱用 partner 三列指向另一半 |
| `claims` | `container_id`, `player_uuid`, `state` | `ON DELETE CASCADE` |
| `personal_inventories` | `container_id`, `player_uuid`, `items` (BLOB) | 个人窗口的内容，`ON DELETE CASCADE` |

`world_uid` 是**维度名哈希出来的 UUID**（`UUID.nameUUIDFromBytes`），不是 Minecraft 的任何东西。这样做的好处是数据层完全不用改；代价是它不可逆——所以 `/fl inspect` 显示的维度名来自 `ServerLevel.dimension()`，永远不要试图从哈希反解。

---

## 三处"看起来该用事件、其实不能"的地方

### 1. 漏斗

原版没有"漏斗要抽取了"的事件。可选方案是 mixin `HopperBlockEntity`，但 NeoForge 的能力层已经足够：

```java
for (var provider : providers.getOrDefault(block, List.of())) {
    var ret = provider.getCapability(...);
    if (ret != null) return ret;      // ← null 只表示"不是我的，问下一个"
}
```

`providers` 是**按方块聚合的列表**，注册得早（`EventPriority.HIGHEST`）就排在原版前面。于是：

- 需要保护时返回一个拒绝抽出的包装；
- 不需要时返回 `null`，查找继续走到原版 provider。

第二条是关键。如果返回 `null` 会让查找终止，那整合包里**每一个**容器都会失去物品能力——那不是"隐形"，是"把别人的箱子变成黑洞"。实测对照：同一次运行里受管箱子拿到 `ProtectedHandler`，普通箱子拿到原版 `InvWrapper`，漏斗照常抽空后者。

包装类仍然暴露真实槽位：漏斗得先看到有东西才谈得上"被拦住"。报 0 格也能挡住漏斗，但会让所有物品扫描模组认为箱子是空的。

### 2. 打开窗口

`PlayerInteractEvent` + `AbstractContainerMenu`，不是事件替换。见 README §"它是怎么做到客户端不用装"。

### 3. 猪灵

一行 `PiglinAi.angerNearbyPiglins(player, true)`。原版判定就是权威，不需要复刻 Paper 那 170 行租约服务。

**注意 `true` 的含义**：它不是"射线可见"，而是"大脑记忆 `NEAREST_VISIBLE_LIVING_ENTITIES` 里有这个玩家"。刚生成、还没跑过感知周期的猪灵即使贴着玩家也算"看不见"（R29）。这是原版行为，不要"修"。

---

## 状态机的边界

`ClaimStateMachine` 的输入是"容器还剩几个名额、这个玩家领没领过、是不是创造模式"，输出是六种动作之一。它是纯函数，因此能穷举。

两条容易写错的语义，测试里已经钉住：

- `claimCount` 是**分配之后**的计数，所以末位判据是 `== maxClaims`，不是 `== maxClaims - 1`。
- `counted` 在末位判断里承重：不计数（创造模式、bypass）的领取不能让容器退役。

---

## 三个必须记住的坑

**① 读一次战利品容器，就会结算它的战利品表。**

`RandomizableContainerBlockEntity` 的表在被读取时结算并置空。所以：

- 判定"是否可接管"不能只看表在不在——它是会中途消失的。现在用 `loot_table_id` 标记记住表 id，资格判定与战利品生成都以它为准（R30）。
- 这个标记存的是**表 id 而不是布尔值**：布尔值只能说明"曾经有战利品"，说不出是哪一份；而容器一旦被读走表，没有 id 就**生成不出**这名玩家的那一份，领取会直接失败。
- 写探针时不要往带表的箱子里 `setItem`：那一步就结算了表，等于把被测对象先变成"表已消失"的状态。
- `/fl set` 要在解析容器时**先**取表（`ContainerTarget.lootTable()`），不能在访问背包之后再取。

**② 数据库写入与方块实体标记不在同一个 tick 生效。**

`ClaimController` 的回调经 `server.execute` 回到服务端线程。同一 tick 内数据库已可见，但回调里的标记写入要等下一 tick。探针若在发起 tick 就断言，会得到假失败。

**③ 不要在服务端线程上阻塞等待 `ClaimController` 的回调。**

它会自己回到服务端线程，`join()` 就是死锁。生产代码统一用 `whenComplete` → `onServerThread`；只有探针允许 `join()`。

---

## 打标为什么用 `AttachmentType`

Paper 用 `PersistentDataContainer`。NeoForge 的对应物是 data attachment：`BlockEntity extends AttachmentHolder`，且 `saveAdditional`/`loadAdditional` 被 patch 过，会写进方块实体自己的 NBT。

所以生命周期与 Paper 完全一致：跟着方块走、跟随区块卸载、随方块消失。三个标记都声明了 `shouldSerialize` 谓词，默认值不会被写盘——否则世界上每个箱子都会因为被碰过一次而多出几字节 FiniteLoot NBT。

| 标记 | 含义 |
|---|---|
| `container_id` | 容器 id（UUID 字符串），空表示不受管 |
| `ignored` | 管理员解除过接管，**不要再自动接管**。这是防复制的实际闸门 |
| `loot_table_id` | 这个容器曾带的战利品表 id。让接管资格**和战利品生成**都不依赖那张会被读走的表 |

`ignored` 只有 `/fl adopt` 能清除。其他任何路径都不清——防复制保证依赖它待着不动。
