# 开发与测试

## 本地构建

```powershell
.\gradlew.bat clean build
```

一条命令构建两个子项目：

| 子项目 | 产物 | 工具链 |
| --- | --- | --- |
| `paper/` | `paper/build/libs/finiteloot-<version>-26.2-paper.jar` | Java 25 |
| `neoforge/` | `neoforge/build/libs/finiteloot-<version>-1.21.1-neoforge.jar` | Java 21 |

发布文件名规则：`finiteloot-<版本>-<游戏版本>-<加载器>.jar`；插件的游戏版本是它编译用的 Paper 版本线（`gradle.properties` 的 `paperMinecraftVersion`），模组的是锁定的 Minecraft 版本。

本机只需要一个能运行 Gradle 的 JDK（17 以上）；两个子项目各自的编译工具链由 Gradle 的 toolchain 机制自动下载（settings 里的 foojay resolver）。也可以用 `org.gradle.java.installations.paths`（放进 `GRADLE_USER_HOME/gradle.properties`，不要提交）指向本机已有的安装。

插件侧使用 Gradle Kotlin DSL、Paper API，不使用 NMS、反射或版本绑定代码。SQLite JDBC 由 Paper 按 `paper-plugin.yml` 的 `libraries` 配置在运行时加载，不会打进插件 JAR；服务器首次启动需要访问 Maven 仓库，之后由 Paper 缓存依赖。

模组侧的移植说明见[neoforge/README.md](../neoforge/README.md)，架构与验证记录见[neoforge/docs/architecture.md](../neoforge/docs/architecture.md)。

## 测试

`build` 会执行两个子项目的 JUnit；插件侧还有 Checkstyle。核心领取逻辑与 Bukkit 事件监听器分离，测试覆盖领取上限、重复开启、并发争抢、重启恢复、双箱归一、LootTable 覆盖和数据库迁移。

只跑一侧：

```powershell
.\gradlew.bat :paper:test
.\gradlew.bat :neoforge:test
.\gradlew.bat :neoforge:dataLayerTest   # 模组数据层的独立验证（不依赖 Minecraft）
```

版本按 Git 提交数自动生成：干净工作区为 `1.1.<补丁号>`，脏工作区附加 `-dev`。同一提交重试不会跳号。两个产物共用同一个版本号；`release.yml` 在发布前会校验两边清单里的版本一致。

## GitHub Actions

三个 workflow 都装 Java 21 与 Java 25 两个工具链：

- `quick-test.yml`：分支推送和 PR 自动执行快速单元测试。
- `build.yml`：手动执行完整构建、测试、Checkstyle 并上传构建产物（插件与模组一起）。
- `release.yml`：`main` 分支快速测试成功后自动创建版本标签，并完成构建、测试和 GitHub Release 发布——一个 tag 同时发布插件与模组两个 JAR，发布前校验两边版本一致。
