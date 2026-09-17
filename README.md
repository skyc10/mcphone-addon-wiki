# mcphone-addon-wiki

MCphone 附属 mod：**GTNH 中文维基 App**（id=`wiki`）。在 MCphone 手机里查
[GTNH 中文维基](https://gtnh.huijiwiki.com/wiki/首页)——无 URL 栏、只服务这一个站点。

平台：Minecraft **1.7.10 + Forge 1614**（GTNH 2.9.0-beta-3，lwjgl3ify，运行时 Java 17–26）。
构建链：RetroFuturaGradle + Jabel（Java 21+ 语法 → Java 8 字节码）。

## 功能

- **点击手机上的「维基」图标**：直接打开全屏虚拟界面（16:9、约 80% 游戏窗口），
  由 MCEF 内嵌 Chromium 离屏渲染真实网页。页面优先级：手机 NBT 上次页面 →
  本地记录 → 维基首页。不放置任何方块、零 Java HTTP（页面全部由 CEF 加载，
  真 Chromium UA 天然规避站点 403 反爬）。
- **全屏界面工具栏**：`<` 后退、`R` 刷新、`H` 首页、`X` 关闭（Esc 亦可）。
  顶部只读显示当前页面地址；站内链接点击、页面滚动由 CEF 原生处理；
  页面地址变化自动记入历史并更新「上次页面」。
- **Shift+点击图标**：打开管理页（手机内 Qz-UILib 场景 UI）：
  - 站内搜索框 + 搜索按钮：直接拼 `Special:Search?search=<URL编码关键词>`
    在全屏界面打开（不做任何 API 请求）；
  - 书签列表（点名称直达 / 删除，预置维基首页书签）；
  - 历史记录（最近 20 条，点条目直达）。
- **持久化**：书签/历史/上次页面存
  `.minecraft/mcphone/addons/wiki/*.json`（显式 UTF-8）；
  上次页面同时写手机 NBT 键 `mcphone:wiki:lastUrl`（只写自己的键前缀）。

## 依赖（前置）

| 前置 | 说明 |
|------|------|
| **MCphone**（mcphone-1.0.0.jar，必需） | 手机本体 mod，提供 `IPhoneApp` SPI 与手机 UI |
| **qz_uilib**（4.8+，必需） | MCphone 的 UI 框架（mcphone 的传递前置） |
| **MCEF 0.7**（必需） | 内嵌 Chromium 渲染后端，**必须为真实浏览器模式**（非虚拟模式） |

> 不依赖 WebDisplays——本附属用 MCEF 虚拟屏渲染，不需要任何世界方块。
> MCEF 建议使用 mcphone-addon-browser 项目修补过的 Java 17+/lwjgl3ify 兼容版
> （`MCEF-1.7.10-patched.jar`，详见该项目的 MCEF-PATCH-NOTES.md）。
> MCEF 缺失或处于虚拟模式时，App 显示前置缺失提示页，绝不崩溃。

**浏览器引导机制（S5 后）**：打开维基屏时**直接以目标 URL 创建 OSR 浏览器**，
不再挂本地引导页；首帧/视口竞态由首帧后探测
`CefRenderer.view_width_/view_height_` 并重断言 `resize` 兜底（S0-4）。
若目标页面约 4~6 秒（120 帧）仍未出首帧，降级一次 `data:text/html` 空白引导页、
出帧后再导航到目标页（仅一次，防无限重试）。引导页/空白页一律不写入
书签/历史/上次页面记录。内核探测为三态（S5-2）：确认可用 / 初始化中（pending，
每次打开维基都重试）/ 确认缺失（缺 MCEF modern 或 mcphone-addon-browser 等
前置时给出可行动指引文案）。

安装：把 `mcphone-addon-wiki-1.0.0.jar` 放进实例 `mods/`（与 mcphone、qz_uilib、MCEF 同目录）。

## 构建

```bat
gradlew.bat build
```

要求 JDK 17+（本机 toolchain 配置在用户级 `%USERPROFILE%\.gradle\gradle.properties`）。
产物：`build/libs/mcphone-addon-wiki-1.0.0.jar`。

## 验收清单（进存档逐项实测）

1. 装好 MCphone + qz_uilib + MCEF 后游戏可正常进存档，手机 GUI 与各 App 不受影响；
2. 点击维基图标 → 全屏虚拟界面出现并加载 gtnh.huijiwiki.com 首页；
3. 网页可滚动、可点击站内链接跳转（CEF 原生能力，验证即可）；
4. Shift+点击 → 管理页搜索框输入「格雷」→ 搜索 → 全屏打开站内搜索结果页；
5. 翻到某页面后关界面（Esc/X）→ 再点维基图标 → 回到上次浏览的页面；
6. 退出存档重进：书签/历史/上次页面保留；
7. 卸掉 MCEF 再启动：维基 App 显示前置缺失提示，游戏与手机本体不崩溃。

## 测试协议（每次改动）

关闭游戏 → `gradlew build` → 替换实例 mods 里的 jar → 启动游戏 → 进存档 →
按验收清单逐项验证 → 关闭游戏再进行下一版。崩溃时读 `crash-reports/` 最新报告定位。

## 代码结构

```
src/main/java/com/november/mcphone/addon/wiki/
├── WikiAddon.java        @Mod 入口（全生命周期 try-catch）
├── CommonProxy.java      服务端空代理
├── ClientProxy.java      PhoneApi.register(WikiApp) + ExitWatchdog.arm()
├── core/
│   ├── WikiStore.java    书签/历史/上次页面 JSON 持久化（UTF-8）
│   └── PhoneNbt.java     手机 NBT 读上次页面（mcphone:wiki:lastUrl）
└── client/
    ├── WikiApp.java      IPhoneApp 实现（直达型 + Shift 管理页，openingPage 翻转）
    ├── WikiPages.java    管理页场景树（搜索/书签/历史，回调全走 ui.post）
    ├── WikiScreen.java   全屏 16:9 CEF 虚拟屏（无 URL 栏，输入注入 CEF）
    ├── McefBridge.java   MCEF API 反射探测/创建浏览器
    ├── WikiHandle.java   IBrowser 反射包装
    └── ExitWatchdog.java CEF dispose 挂死退出看门狗
```

注册方式双保险：`META-INF/services/com.november.mcphone.api.IPhoneApp`（ServiceLoader）
+ ClientProxy 手动 `PhoneApi.register`（冲突时先注册者优先，后到只打日志）。

## 技术红线（开发约定）

- 零 Java HTTP：任何页面展示/搜索都交给 CEF，不写 HttpURLConnection/HttpClient；
- 不改 MCphone / Qz-UILib / MCEF 源码，对 MCEF 全程反射；
- 手机 NBT 只写 `mcphone:wiki:` 前缀键；
- 附属任何初始化失败只打日志，不拖垮手机本体与游戏。

## 发版检查清单（X-01 最小 CI + X-03 许可证治理）

> 落地：`.github/workflows/release.yml`（tag → 干净工作区闸门 → clean build → 制品校验 →
> GitHub Release）。CI 文件统一由"契约/CI"任务（t9/X-01）撰写 —— 本仓 AW-09 的 CI 部分由
> 同一模板覆盖，**勿在 AW-09 里重复写一版**（避免双 workflow 冲突）；AW-09 的实际动作
> 收敛为：补 LICENSE 本体 + README 许可节 + 确认 CI 校验通过。

1. **流程**：用户确认后 → 打 tag（必须打在干净工作区的 HEAD 上）→ 推分支 + tag →
   CI 自动构建并发 Release → 事后 `gh release edit --notes-file <file.md>` 补中文说明
   （中文内联参数会被编码破坏，必须走 `--notes-file`）。
2. **`-dirty` 拒绝发版**：本机构建前 `git status --porcelain` 必须为空；CI 闸门在工作区不干净
   或 tag 不在 HEAD 时直接 fail（`-dirty` 产物无法对应 commit）。
3. **jar 必须在 tag 之后 clean 重建**（`Tags.VERSION` 才正确；CI 已强制 `clean build`）。
4. **许可声明随 jar（X-03/X-12 检查项）**：本仓 LICENSE 正在补齐（AW-09，建议 MIT，见
   `13-build-license-state.md` §4.1 的真实缺口记录）。**在 LICENSE 落仓之前，tag 发版会被
   CI 的"jar 内无许可声明"校验拒绝——这是治理设计，不是故障**：无许可证 = 默认保留所有
   权利，不得对外发版。CI 校验发布 jar 内必须含 `LICENSE` / `THIRD-PARTY` / `NOTICE` 条目。
5. 版本号三分辨：产物名 = `git describe`；`gradle.properties` 的 `version` 是兜底死值；
   `-dev` / `-api` / `-sources` 后缀 jar 不是发布制品。
