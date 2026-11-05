# FiniteLoot / 限量战利品

Paper/Purpur 26.2 服务端插件：为每名玩家提供独立战利品，同时限制每个容器的全服领取人数。无需客户端模组，数据库位于 `plugins/FiniteLoot/data.db`。

本仓库同时维护两个产物，同一个版本号发布：

| 产物 | 适用于 | 目录 |
| --- | --- | --- |
| `finiteloot-<version>-26.2-paper.jar` | Paper / Purpur 服务器（插件） | [`paper/`](paper/) |
| `finiteloot-<version>-1.21.1-neoforge.jar` | NeoForge 1.21.1 模组服务器（模组） | [`neoforge/`](neoforge/) |

两者面向不同的服务端，不同时运行，因此各自跟随不同的 Minecraft 版本。模组侧的说明见[NeoForge 移植](neoforge/README.md)。

## 要求与安装

- Paper 或 Purpur 26.2
- Java 25+

将 `finiteloot-<version>-26.2-paper.jar` 放入 `plugins`，启动服务器即可。首次启动会生成配置、语言文件和 SQLite 数据库，并由 Paper 下载并缓存 SQLite 驱动；离线服务器请提前准备 Paper 的库缓存。

## 配置

```yaml
default-max-claims: 3
show-remaining-claims: true
play-container-animation: true
play-container-sounds: true
trigger-piglin-anger: true
per-player-limit: 1
exhausted-action: DENY
prevent-item-insertion: false
completed-container-becomes-normal: true
final-claim-action: VANILLA_CONTAINER
clear-personal-inventories-on-final-claim: true
show-final-claim-message: true
prevent-hopper-extraction: true
prevent-breaking: true
allow-breaking-exhausted-containers: false
prevent-explosions: true
count-creative-players: false
admin-bypass-counts: false
excluded-worlds: []
language: zh_CN
loot-table-overrides:
  minecraft:chests/end_city_treasure: 2
  minecraft:chests/ancient_city: 2
  minecraft:chests/simple_dungeon: 5
  minecraft:chests/abandoned_mineshaft: 4
```

配置项的完整说明见[配置说明](docs/CONFIGURATION.md)。修改后执行 `/fl reload`。

自动识别仍带原版 LootTable 的箱子、木桶等 Lootable 容器。已被原版清除 LootTable 的普通容器需要使用 `/fl set` 手动转换。双箱会共享同一容器记录。

## 命令

主命令为 `/finiteloot`，别名 `/fl`。管理命令执行后，按提示点击目标容器。完整操作说明见[管理员操作](docs/ADMINISTRATION.md)。

| 命令 | 作用 |
| --- | --- |
| `/fl reload` | 重载配置和语言 |
| `/fl inspect` | 查看容器 ID、LootTable、上限和领取记录 |
| `/fl set <次数>` | 转换容器或修改领取上限 |
| `/fl reset container` | 重置容器全部领取状态 |
| `/fl reset player <玩家>` | 清除玩家对选中容器的记录 |
| `/fl remove` | 取消 FiniteLoot 管理 |
| `/fl migrate` | 备份、迁移并校验数据库 |

## 权限

`finiteloot.admin`、`finiteloot.inspect`、`finiteloot.set`、`finiteloot.reset`、`finiteloot.remove`、`finiteloot.reload`、`finiteloot.bypass`。默认均授予 OP；`finiteloot.*` 为常用父权限。

## 备份与升级

升级前先执行 `/fl migrate`，确认 `plugins/FiniteLoot/backups` 中有备份，再停止服务器并替换 JAR。详见[数据、备份与升级](docs/DATA-MAINTENANCE.md)。

## 兼容说明

详见[兼容性与冲突](docs/COMPATIBILITY.md)。

## 构建与发布

```powershell
.\gradlew.bat clean build
```

一条命令构建两个产物：`paper/build/libs/finiteloot-<version>-26.2-paper.jar` 与 `neoforge/build/libs/finiteloot-<version>-1.21.1-neoforge.jar`。文件名规则是 `finiteloot-<版本>-<游戏版本>-<加载器>.jar`，插件的游戏版本写它编译用的 Paper 版本线。构建只需本机有一个 JDK（17 以上即可运行 Gradle），Paper 侧的 Java 25 与模组侧的 Java 21 工具链由 Gradle 自动补齐。

构建、测试和 GitHub Actions 说明见[开发与测试](docs/DEVELOPMENT.md)和[发布流程](docs/RELEASE.md)。

## 限制与验证

当前仅支持方块 TileState Lootable 容器；`exhausted-action` 仅支持 `DENY`，`per-player-limit` 仅支持 `1`。

已使用 Java 25 在 Paper 26.2 build 63 和 Purpur 26.2 build 2613 上验证插件加载、数据库初始化、重载和迁移；本地 JUnit、Checkstyle 和完整构建均通过。

## 许可证

GNU GPLv3，详见 `LICENSE`。

## 文档

- [配置说明](docs/CONFIGURATION.md)
- [管理员操作](docs/ADMINISTRATION.md)
- [数据、备份与升级](docs/DATA-MAINTENANCE.md)
- [兼容性与冲突](docs/COMPATIBILITY.md)
- [开发与测试](docs/DEVELOPMENT.md)
- [发布流程](docs/RELEASE.md)
- [NeoForge 移植](neoforge/README.md) · [移植架构](neoforge/docs/architecture.md)
