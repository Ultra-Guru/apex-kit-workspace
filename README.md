# apex-kit-workspace

Android 编码工作区浏览器库 —— 服务宿主仓库 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 的 Coding 模式：全屏工作区浏览器（文件树 / 多格式预览 / Git 面板 / 变更和产物 / 实时跟随 agent 文件操作）。

## 模块

| 模块 | 类型 | 职责 |
|------|------|------|
| `workspace-core` | 纯 Kotlin JVM（零 Android 依赖） | git 五解析器（porcelain=v1 -b / numstat -M / unified diff / log / for-each-ref）+ GitEngine 编排 + 文件树扫描 + 多格式预览装配（~110 扩展名）+ 构建产物扫描 + 轮询指纹 watcher + `WorkspaceExplorerController` 单状态机 |
| `workspace-explorer` | Compose 库（compileSdk 36 / minSdk 26，与宿主严格对齐） | 全屏浏览器 UI：胶囊标签栏（文件夹 / Git / 变更和产物）+ 实时跟随开关 + 事件流悬浮窗；coil 2.6.0（与宿主同版）；全部资源 `workspace_` 前缀 |

## 宿主消费（composite build，E1 迁移期）

与 [apex-browser-kit](https://github.com/Ultra-Guru/apex-browser-kit) 完全同构：

```
# 宿主 settings.gradle.kts（已由宿主 PR 接线）
val workspaceKitSibling = file("../apex-kit-workspace")   // 本地开发：兄弟目录
val workspaceKitNested  = file("apex-kit-workspace")       // CI：嵌套目录兜底
when {
    workspaceKitSibling.isDirectory -> includeBuild(workspaceKitSibling)
    workspaceKitNested.isDirectory  -> includeBuild(workspaceKitNested)
}
```

坐标 `com.apex.workspace:{workspace-core,workspace-explorer}` 在 composite 命中时自动替换远端版本。宿主侧通过 `WorkspaceKitHolder` 注入三项能力：git 执行器（PRoot 沙箱）、工作区根（`CodeWorkspaceRoots.activeRoot()`）、日志（`AppLogger`）。

```kotlin
val controller = WorkspaceExplorerController(
    gitRunner = KitGitRunner { args, timeout -> hostRunner.run(args, timeout).let { KitResult(it.exitCode, it.stdout, it.stderr) } },
    rootProvider = WorkspaceRootProvider { roots.activeRoot() },
    scope = viewModelScope,
)
WorkspaceExplorerScreen(controller = controller, onClose = onBack, agentActive = state.isRunning)
```

## 兼容矩阵

| 依赖 | 本库 | 宿主 | 说明 |
|------|------|------|------|
| AGP | 8.13.2 | 8.13.2 | **两侧必须严格一致**（AgpVersionCompatibilityRule），宿主升级后本库必须同步 |
| Kotlin | 2.2.21 | 2.3.21 | 库编译器 ≤ 宿主（F3 规则） |
| Compose BOM | 2025.11.01 | 2026.02.00 | 库低配合法（minCompileSdk 35 ≤ 宿主 36） |
| Gradle | 8.14 | 8.14 | wrapper 带 distributionSha256Sum 校验 |
| coroutines / serialization | 1.10.2 / 1.9.0 | 同 | 一致避免 classpath 双版本 |

## 构建

```bash
./gradlew assemble :workspace-core:test :workspace-explorer:testDebugUnitTest
./gradlew publishToMavenLocal   # 发布冒烟（jar/aar/pom 产物形状）
```

测试 132 项（core 117 + explorer 15），含**真 git 集成测试**（TemporaryFolder 建仓提交改删全链路 + 隔离全局配置的身份注入验证）。

## CI（.github/workflows/ci.yml）

- **build-test**：纯度门禁（`workspace-core` 零 Android import）+ 版本列车（两模块同号）+ 全模块编译测试 + Maven Local 发布冒烟；
- **consumer-check**：宿主 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) composite 编译防崩 —— 兄弟布局检出双仓库 → 验证坐标替换发生 → `:app:compileDebugKotlin`。宿主 wiring 合入前以 notice 跳过（E1 迁移期保护），合入后自动转强校验。

## 设计纪律

- **错误折叠永不抛**：所有 IO/git 失败折叠为状态字段或 Failure 预览态（CancellationException 原样上抛是唯一例外）；
- **行为对齐宿主**：git 命令口径与宿主 `CodeGitPanelController` / `GitCommitTool` 逐项一致（porcelain=v1 -b、rename 取新路径、`-c user.name/email` 身份注入、`--no-verify`、无 HEAD 降级），避免两套 git 语义漂移；
- **路径安全三级防线**：词法校验（绝对路径/`..` 段/空段/保留目录前缀）→ canonical 解析 → 包含性断言；
- **挂起安全测试**：watcher 自重排 delay 循环绝不与 `advanceUntilIdle` 同用（虚拟时间 `advanceTimeBy` + `backgroundScope`）。

## License

MIT
