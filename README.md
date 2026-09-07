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
