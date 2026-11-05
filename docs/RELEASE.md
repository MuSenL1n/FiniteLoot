# 发布流程

`main` 分支推送通过快速测试后，Release 工作流会自动根据插件版本创建标签。例如插件版本为 `1.1.1` 时会创建 `v1.1.1`。

`release.yml` 的流程为：

1. 等待 `main` 分支的 `quick-test.yml` 成功。
2. 创建并推送与插件版本一致的 `v<version>` 标签。
3. 使用快速测试通过的源码进行完整构建、测试和 Checkstyle。
4. 创建 GitHub Release，同时上传两个可部署产物：插件 shaded JAR（`paper/`）与 NeoForge 模组 JAR（`neoforge/`）。发布前会校验两边清单里的版本一致，版本不匹配或构建失败时不会发布。

也可以在 GitHub Actions 页面手动运行 Release 工作流并输入标签；留空时自动生成标签。版本不匹配或构建失败时不会发布 Release。
