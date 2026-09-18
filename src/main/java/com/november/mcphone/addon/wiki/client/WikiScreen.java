package com.november.mcphone.addon.wiki.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.wiki.WikiAddon;
import com.november.mcphone.addon.wiki.core.WikiStore;

/**
 * 全屏维基虚拟屏（不放置任何方块）：16:9、约占游戏窗口 80% 的 CEF 页面，
 * 顶部工具栏（后退/刷新/首页/关闭）。页面纹理由 MCEF 离屏渲染，
 * 鼠标/键盘/滚轮注入 CEF。只服务 gtnh.huijiwiki.com，无 URL 栏。
 *
 * <p>顶部显示只读的当前地址（节流 500ms 跟随 CEF 真实 URL）；地址变化即视为
 * 一次页面访问，写入历史并更新「上次页面」——站内链接点击由 CEF 完成，
 * 通过轮询捕获（这也是重新打开时恢复到上次页面的依据）。</p>
 *
 * <p><b>S5-1 引导</b>：不再挂本地页引导（旧的 mod 样式引道 URL 在 modern
 * 内核下无 scheme、无该资源，A8 W-01 死链）；改为<b>直接以目标 URL 创建
 * 浏览器</b>，首帧/视口竞态由 S0-4 的「首帧后校验并重断言 resize」兜底。
 * 回退备选：目标页面迟迟不出帧时改用 {@code data:text/html} 空白引导页，
 * 出帧后再导航到目标页（仅一次，防无限重试）。</p>
 *
 * <p>S0 自驱动要点：drawScreen 每帧调用 {@code pumpFrameUpload()}（帧泵自驱动，
 * 幂等）；首帧后校验 CEF 实际渲染视口，与期望不一致则重断言 resize；
 * CEF 渲染分辨率 cefW/cefH 与 GUI 尺寸独立，注入坐标按 cefW/viewW、cefH/viewH
 * 缩放（S0-4 坐标错位修复）。</p>
 *
 * <p>注入约定（与 modern CefBrowserOsr 对齐，T6 与 browser 侧口径统一）：
 * 鼠标按钮为 AWT 编号（1=左 2=中 3=右），modifiers 为 AWT 修饰键+按钮掩码
 * （native 层经 getModifiersEx 读）；键盘：字符/控制字符走 Pressed → Typed →
 * Released，非字符键（方向键/Delete/Home/End/翻页/F1-F12）走 ByKeyCode 管道
 * （remapKeycode → GLFW 码）且按下/释放配对；OSR 焦点在首帧上传后与每次页面
 * 点击前重挂。1.7.10 只在按下时回调 keyTyped，Released 在
 * handleKeyboardInput 里补发。</p>
 */
public class WikiScreen extends GuiScreen {

    /** 16:9 且覆盖约 80% 游戏窗口。 */
    private static final double SCALE = 0.8;
    private static final int BAR = 22;

    /**
     * 降级备选引导页（S5-1）：data: URL 空白页，CEF 原生支持。
     * 仅当直接以目标 URL 创建后 ~120 帧（≈4~6 s）仍无首帧时使用一次。
     */
    private static final String DATA_BLANK_URL =
        "data:text/html,<html><body style=\"background:#141414\"></body></html>";
    /** 首帧等待上限（帧）：约 4~6 秒，到点后走 data: 引导降级（仅一次）。 */
    private static final int FRAME_FALLBACK_MAX_FRAMES = 120;

    private volatile WikiHandle browser;
    private int viewW;
    private int viewH;
    /** CEF 渲染分辨率（物理像素，≠ GUI 尺寸）：注入坐标按 cefW/viewW 缩放。 */
    private int cefW = 64;
    private int cefH = 36;
    /** 首帧后校验 CEF 视口并重断言 resize（每 browser 一次，S0-4）。 */
    private boolean viewportAsserted;
    private final String pendingUrl;
    private boolean created;
    private String createError;
    private long lastUrlSync;
    private boolean lastInPage;
    private boolean wheelDiagDone; // 首次滚轮诊断日志只打一次
    private int pressedCefBtn = -1;
    // T8/C6：活动 press 的原始注入坐标——initGui/onGuiClosed 兜底释放与
    // 「未配对 press 补发」诊断日志使用；配对释放后仅作诊断残留，无行为影响。
    private int pressedGuiX, pressedGuiY, pressedCefX, pressedCefY;
    // T8/C6 防御计数：press 注入前发现上一击未配对时，已补发 release 的次数。
    private int staleRescueCount;
    /** 补发 rescue 日志只打一次（罕见事件，默认开——直接佐证 C6 是否真实发生）。 */
    private boolean rescueDiagDone;
    // T8 导航后首点一次性诊断：armed 于 create 与每次 drawScreen 观测到 URL 变化；
    // 预算 2 行/实例（page1 基线 + 导航后首点各一），绝不刷屏。
    private boolean navClickDiag;
    private int navClickDiagBudget = 2;
    private String shownUrl = "";

    /** S5-1 降级：已尝试过 data: 引导 / CEF 当前停在 data: 引导页。 */
    private boolean dataFallbackTried;
    private boolean dataBootstrap;
    /** 目标页直连创建后的等待帧数（降级计时用）。 */
    private int openFrames;

    /** 当前打开的 WikiScreen（供看门狗关闭）。 */
    private static WikiScreen current;

    private long stuckSince; // textureId()==0 且 MCEF 可用的起始时刻（0=未计时）

    public WikiScreen(String url) {
        this.pendingUrl = url;
        current = this;
    }

    /** 打开维基大屏并记录历史/上次页面。 */
    public static void open(String url) {
        if (url == null || url.isEmpty()) return;
        WikiStore.setLastUrl(url);
        WikiStore.addHistory(url);
        Minecraft.getMinecraft().displayGuiScreen(new WikiScreen(url));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** 本次打开的目标页面（pendingUrl 或维基首页）。 */
    private String targetUrl() {
        return pendingUrl != null ? pendingUrl : WikiAddon.WIKI_HOME;
    }

    // ===================== 布局 =====================

    private void computeSize() {
        double k = Math.min(this.width * SCALE / 16.0, this.height * SCALE / 9.0);
        viewW = (int) Math.floor(k * 16.0);
        viewH = (int) Math.floor(k * 9.0);
    }

    /**
     * CEF 渲染视口（S0-4）：自适应档 = 页面 GUI 尺寸 × GUI 缩放系数 = 物理像素，
     * 1 CEF 像素 ↔ 1 屏幕像素（最清晰）。鼠标坐标注入前必须按
     * cefW/viewW、cefH/viewH 缩放。
     */
    private void computeCefSize() {
        float guiScale = this.width > 0 && this.height > 0
            ? (float) this.mc.displayWidth / this.width : 1f;
        cefW = Math.max(64, (int) Math.round(viewW * guiScale));
        cefH = Math.max(36, (int) Math.round(viewH * guiScale));
    }

    private int boxX() {
        return (this.width - viewW) / 2;
    }

    private int boxY() {
        return BAR + 2 + Math.max(0, (this.height - BAR - 2 - viewH) / 2);
    }

    private boolean inPage(int mx, int my) {
        return mx >= boxX() && mx < boxX() + viewW && my >= boxY() && my < boxY() + viewH;
    }

    /** GUI 坐标 → CEF 视口 X（S0-4：乘 cefW/viewW）。 */
    private int cefX(int guiX) {
        return (int) Math.round((guiX - boxX()) * (double) cefW / Math.max(1, viewW));
    }

    /** GUI 坐标 → CEF 视口 Y（S0-4：乘 cefH/viewH）。 */
    private int cefY(int guiY) {
        return (int) Math.round((guiY - boxY()) * (double) cefH / Math.max(1, viewH));
    }

    // 工具栏按钮区（与绘制严格一致）
    private boolean inBack(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 6 && mx < 24;
    }

    private boolean inReload(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 26 && mx < 44;
    }

    private boolean inHome(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 46 && mx < 64;
    }

    private boolean inClose(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= this.width - 48 && mx < this.width - 30;
    }

    // ===================== 生命周期 =====================

    @Override
    public void initGui() {
        super.initGui();
        computeSize();
        computeCefSize();
        if (!created) {
            created = true;
            shownUrl = targetUrl();
            McefBridge.detect();
            if (McefBridge.available()) {
                // S5-1：直接以目标 URL 创建浏览器（首帧/视口竞态由首帧后重断言
                // resize 兜底）；出帧过慢的场景由 tickFramePolicy 降级 data: 引导。
                browser = McefBridge.create(targetUrl());
                if (browser == null) {
                    createError = McefBridge.failReason();
                } else {
                    computeCefSize();
                    browser.resize(cefW, cefH);
                    navClickDiag = true; // T8：初始加载也是一次导航（t3：wiki 首页即 data:→wiki_home 跳转后的新页）
                }
            }
        } else {
            WikiHandle b = browser;
            if (b != null) {
                // T8/C6 兜底：resize 触发的 initGui 重入时，清掉可能滞留的未配对
                // press（先补发一次 release 再复位；守卫内才触发，正常路径无注入）。
                if (pressedCefBtn != -1) {
                    b.injectMouseButton(pressedCefX, pressedCefY,
                        toAwtMask(pressedCefBtn) | awtModifiers(), pressedCefBtn, false, 1);
                    pressedCefBtn = -1;
                }
                computeCefSize();
                b.resize(cefW, cefH);
            }
        }
    }

    /**
     * 首帧推进（S5-1 降级路径）：目标 URL 直连创建一直不出帧（120 帧约 4~6 s）
     * 时，改用 data: 空白引导页创建一次，其出帧后再导航到目标页；引导页不写
     * 历史/上次页面记录。仅降级一次，不无限重试。
     */
    private void tickFramePolicy() {
        WikiHandle b = browser;
        if (b == null) {
            return;
        }
        boolean painted = b.hasPaintedFrame();
        if (dataBootstrap) {
            if (painted) {
                // data: 引导页出帧 → 导航到目标页
                dataBootstrap = false;
                b.loadURL(targetUrl());
            }
            return;
        }
        if (painted || dataFallbackTried) {
            return;
        }
        openFrames++;
        if (openFrames >= FRAME_FALLBACK_MAX_FRAMES) {
            dataFallbackTried = true;
            System.out.println("[mcphone_wiki] no first frame after " + openFrames
                + " frames — falling back to data: bootstrap, then navigate to target");
            b.loadURL(DATA_BLANK_URL);
            dataBootstrap = true;
            openFrames = 0;
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (current == this) {
            current = null;
        }
        WikiHandle b = browser;
        if (b != null) {
            // T8/C6 兜底：GUI 关闭时若尚有未配对的 press（mouseMovedOrUp 因故未
            // 到达），先补发一次 release 再关闭，防止按钮状态语义上悬着
            // （其后的 close 会销毁内核侧状态，补发本身无害）。
            if (pressedCefBtn != -1) {
                b.injectMouseButton(pressedCefX, pressedCefY,
                    toAwtMask(pressedCefBtn) | awtModifiers(), pressedCefBtn, false, 1);
                pressedCefBtn = -1;
            }
            browser = null;
            b.close();
        }
    }

    /**
     * 退出看门狗调用：异步关闭我们打开的浏览器。
     *
     * <p>close() 最终走到 JCEF 的 native n_Close，MC 主循环停止后 CEF 消息泵
     * 已死，该调用会永久阻塞——绝不能在看门狗线程上同步执行（同步调用会把
     * 看门狗卡死、导致强杀逻辑永远没跑到的）。放到守护线程里，阻塞也只阻塞
     * 它自己，不影响看门狗的扫描与强杀。</p>
     */
    static void forceClose() {
        WikiScreen s = current;
        if (s == null) {
            return;
        }
        WikiHandle b = s.browser;
        if (b == null) {
            return;
        }
        s.browser = null;
        Thread t = new Thread(() -> b.close(), "mcphone_wiki-async-close");
        t.setDaemon(true);
        t.start();
    }

    // ===================== 渲染 =====================

    @Override
    public void drawScreen(int mx, int my, float pt) {
        tickFramePolicy();
        drawRect(0, 0, this.width, this.height, 0xD0141414);

        // ---- 顶部工具栏 ----
        drawRect(0, 0, this.width, BAR, 0xF02B2B2B);

        WikiHandle b = browser;
        if (b != null) {
            // 帧泵自驱动（S0-2，幂等）：泵 CEF 消息循环 + 上传排队帧到纹理
            b.pumpFrameUpload();
            // 首帧视口校验（S0-4）：resize() 与 CEF 内部异步创建存在竞态，
            // 首帧上传后实际视口已确定，此时校验并重断言一次 resize。
            if (!viewportAsserted && b.hasPaintedFrame()) {
                int aw = b.cefViewWidth();
                int ah = b.cefViewHeight();
                if (aw > 0 && ah > 0) {
                    viewportAsserted = true;
                    if (aw != cefW || ah != cefH) {
                        System.out.println("[mcphone_wiki] viewport mismatch (actual "
                            + aw + "x" + ah + " != expected " + cefW + "x" + cefH
                            + ") — re-asserting resize");
                        b.resize(cefW, cefH);
                    }
                }
            }
            // MCEF 上游 CefRenderer.initialize() 孤儿化兜底：Client thread 渲染
            // 路径上有 GL context，是执行 glGenTextures 的安全时机（内部只跑一次）。
            b.ensureRendererInitialized();
        }

        // 后退 ◀ (6..24)
        drawRect(6, 5, 24, BAR - 5, b != null ? 0xFF4A4A4A : 0xFF383838);
        fontRendererObj.drawStringWithShadow("<", 12, 8, b != null ? 0xFFFFFF : 0x909090);
        // 刷新 R (26..44)
        drawRect(26, 5, 44, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("R", 32, 8, 0xFFFFFF);
        // 首页 H (46..64)
        drawRect(46, 5, 64, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("H", 52, 8, 0xFFFFFF);

        // 只读地址显示（70..width-56）：节流跟随真实 URL，变化即记历史。
        // data: 降级引导期间 CEF 停在空白页，跳过同步以免污染历史/上次页面。
        if (b != null && !dataBootstrap && System.currentTimeMillis() - lastUrlSync > 500) {
            lastUrlSync = System.currentTimeMillis();
            String cur = b.getURL();
            if (cur != null && !cur.isEmpty() && !cur.startsWith("data:")
                && !cur.equals(shownUrl)) {
                shownUrl = cur;
                WikiStore.setLastUrl(cur);
                WikiStore.addHistory(cur);
                navClickDiag = true; // T8：观测到 URL 变化=一次导航，arm 下一次首点诊断
            }
        }
        String shown = shownUrl;
        int maxW = this.width - 56 - 70;
        while (fontRendererObj.getStringWidth(shown) > maxW && shown.length() > 1) {
            shown = shown.substring(1);
        }
        fontRendererObj.drawStringWithShadow(shown, 70, 8, 0xB0C0D0);

        // 关闭 X (width-48..width-30) + Esc 提示
        drawRect(this.width - 48, 5, this.width - 30, BAR - 5, 0xFF8A3A3A);
        fontRendererObj.drawStringWithShadow("X", this.width - 42, 8, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Esc", this.width - 26, 8, 0x707070);

        // ---- 页面区域 ----
        int bx = boxX();
        int by = boxY();
        if (b != null && b.textureId() != 0) {
            if (WikiHandle.legacyRender) {
                // 旧路径（对比用，-Dmcphone_wiki.legacyRenderer=true）：
                // 直接走 CefRenderer.render()（Angelica GLSM 下可能整页透明）
                org.lwjgl.opengl.GL11.glPushAttrib(org.lwjgl.opengl.GL11.GL_ALL_ATTRIB_BITS);
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
                org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f);
                b.draw(bx, by, bx + viewW, by + viewH);
                org.lwjgl.opengl.GL11.glPopAttrib();
                org.lwjgl.opengl.GL11.glColor4f(1f, 1f, 1f, 1f);
            } else {
                // 自绘四边形（S0-3）：正确 UV + 显式关 ALPHA_TEST/blend
                b.drawSelf(bx, by, bx + viewW, by + viewH);
            }
        } else {
            drawErrorPage(bx, by, b);
        }

        // T6-3：反射探测降级提示——不再静默，页面上常显一行（仅降级时出现）
        if (b != null && b.inputDegraded()) {
            fontRendererObj.drawStringWithShadow(
                "Input degraded: " + b.degradedNotice(), bx + 8, by + viewH - 12, 0xFFFF55);
        }

        // 页面边框
        drawRect(bx - 1, by - 1, bx + viewW + 1, by, 0xFF5A5A5A);
        drawRect(bx - 1, by + viewH, bx + viewW + 1, by + viewH + 1, 0xFF5A5A5A);
        drawRect(bx - 1, by, bx, by + viewH, 0xFF5A5A5A);
        drawRect(bx + viewW, by, bx + viewW + 1, by + viewH, 0xFF5A5A5A);

        super.drawScreen(mx, my, pt);
    }

    /**
     * 错误/加载页（S5-3 可行动文案）：区分 createError / 内核初始化中(pending) /
     * 正常加载 / 缺前置 四种状态；缺前置时给出「装什么、怎么救」的明确指引。
     */
    private void drawErrorPage(int bx, int by, WikiHandle b) {
        String msg;
        String detail = null;
        String detail2 = null;
        if (createError != null) {
            msg = StatCollector.translateToLocal("err.mcphone_wiki.create_failed");
            detail = createError;
        } else if (McefBridge.pending()) {
            // 内核初始化中（如 browser 的 lazy-init 还没跑过）：可行动文案
            msg = StatCollector.translateToLocal("err.mcphone_wiki.engine_pending");
            detail = StatCollector.translateToLocal("guide.mcphone_wiki.open_browser");
        } else if (McefBridge.available()) {
            msg = StatCollector.translateToLocal("msg.mcphone_wiki.loading");
            // 超时诊断：CEF 存活但纹理始终为 0 —— 兜底已跑过仍未生效，
            // 提示可能是 MCEF 上游 bug（不自动重试，避免刷屏）。
            long now = System.currentTimeMillis();
            if (stuckSince == 0) {
                stuckSince = now;
            } else if (now - stuckSince > 15000) {
                detail = StatCollector.translateToLocal("msg.mcphone_wiki.texture_stuck");
            }
        } else {
            // 缺前置：确认不可用 → 两行可行动指引（S5-3）
            msg = StatCollector.translateToLocal("err.mcphone_wiki.missing_mcef");
            detail = StatCollector.translateToLocal("guide.mcphone_wiki.need_mcef");
            detail2 = StatCollector.translateToLocal("guide.mcphone_wiki.open_browser");
        }
        drawRect(bx, by, bx + viewW, by + viewH, 0xFF0A0A0A);
        int mw = fontRendererObj.getStringWidth(msg);
        fontRendererObj.drawStringWithShadow(msg, bx + (viewW - mw) / 2, by + viewH / 2 - 4, 0xFFFF55);
        if (detail != null) {
            fontRendererObj.drawStringWithShadow(detail, bx + 8, by + viewH / 2 + 14, 0xFF5555);
        }
        if (detail2 != null) {
            fontRendererObj.drawStringWithShadow(detail2, bx + 8, by + viewH / 2 + 28, 0xFF5555);
        }
    }

    // ===================== 键盘 =====================

    /**
     * 当前 AWT 修饰键掩码（从 LWJGL 键盘状态实时读取）。JCEF native 层经
     * {@code KeyEvent.getModifiersEx} 读修饰键；恒传 0 会让 CEF 认为修饰键全松开
     * ——Shift+字符、Ctrl+V 等在页面内全部失效（与 browser 侧口径一致，T6-2）。
     * 无修饰键时返回 0。
     */
    private static int awtModifiers() {
        int m = 0;
        if (Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54)) m |= 64;   // SHIFT_DOWN_MASK
        if (Keyboard.isKeyDown(29) || Keyboard.isKeyDown(157)) m |= 128; // CTRL_DOWN_MASK
        if (Keyboard.isKeyDown(56) || Keyboard.isKeyDown(184)) m |= 512; // ALT_DOWN_MASK
        return m;
    }

    /**
     * LWJGL 键码能否经 ByKeyCode 管道表达——白名单必须与嵌入版
     * {@code CefBrowserOsr.remapKeycode} 认识的键一致（LWJGL → GLFW），
     * 其余键发过去会变成 keyCode=0/keyChar=0 的垃圾事件，直接不发
     * （T6-1：Enter/Delete/方向键/Home/End/翻页/F1-F12 全部走 keyCode 通道）。
     */
    private static boolean isExpressableNonCharKey(int keyCode) {
        switch (keyCode) {
            case 14:  // Backspace
            case 15:  // Tab
            case 28:  // Enter（小键盘）
            case 199: // Home
            case 200: // Up
            case 201: // Page Up
            case 203: // Left
            case 205: // Right
            case 207: // End
            case 208: // Down
            case 209: // Page Down
            case 211: // Delete
                return true;
            default:
                return isFunctionKey(keyCode); // F1-F12
        }
    }

    /** F1–F10 = LWJGL 59..68、F11 = 87、F12 = 88（内核 remapKeycode 映射 GLFW 290-301）。 */
    private static boolean isFunctionKey(int keyCode) {
        return keyCode >= 59 && keyCode <= 68 || keyCode == 87 || keyCode == 88;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc
            closeScreen();
            return;
        }
        // 页面模式：字符/控制字符（\b \r \t，内核 controlCharToGlfwKey 映射）走
        // Pressed → Typed；非字符键（character=0）走 ByKeyCode 管道（嵌入版
        // remapKeycode → GLFW 码 → natives），白名单外的键不发（T6-1）。
        // Released 由 handleKeyboardInput 补发。键盘注入不涉及页面坐标，无需缩放。
        WikiHandle b = browser;
        if (b != null) {
            int mods = awtModifiers();
            if (typedChar == 0) {
                if (isExpressableNonCharKey(keyCode)) {
                    b.injectKeyPressedByKeyCode(keyCode, '\0', mods);
                }
            } else {
                b.injectKeyPressed(typedChar, mods);
                b.injectKeyTyped(typedChar, mods);
            }
        }
    }

    @Override
    public void handleKeyboardInput() {
        super.handleKeyboardInput();
        // keyTyped 只在按下时回调；这里补发松开事件（修饰键/按键状态不残留）。
        // 非字符键的释放走 ByKeyCode，与按下路径配对（T6-1）。
        if (!Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) {
            WikiHandle b = browser;
            if (b != null) {
                char c = Keyboard.getEventCharacter();
                if (c == 0) {
                    int k = Keyboard.getEventKey();
                    if (isExpressableNonCharKey(k)) {
                        b.injectKeyReleasedByKeyCode(k, '\0', awtModifiers());
                    }
                } else {
                    b.injectKeyReleased(c, awtModifiers());
                }
            }
        }
    }

    private void closeScreen() {
        this.mc.displayGuiScreen(null);
        this.mc.setIngameFocus();
    }

    // ===================== 鼠标 =====================

    /** MC 按钮 → AWT 按钮（1=左 2=中 3=右）。 */
    private static int toAwtButton(int mcBtn) {
        return mcBtn == 0 ? 1 : mcBtn == 1 ? 3 : 2;
    }

    /**
     * AWT 按钮 → {@code InputEvent.BUTTONx_DOWN_MASK}（左 1024 / 中 2048 / 右 4096）。
     * JCEF 原生层经 {@code getModifiersEx} 读掩码判定按的是哪个按钮——恒传 0 会被
     * 当成「无按钮按下」而整个点击被 Blink 忽略（T6-2：与 browser 侧同款修复）。
     */
    private static int toAwtMask(int awtBtn) {
        return awtBtn == 1 ? 1024 : awtBtn == 3 ? 4096 : 2048;
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        if (btn == 0) {
            if (inBack(mx, my)) {
                WikiHandle b = browser;
                if (b != null) b.goBack();
                return;
            }
            if (inReload(mx, my)) {
                WikiHandle b = browser;
                if (b != null) {
                    String cur = b.getURL();
                    if (cur != null && !cur.isEmpty()) b.loadURL(cur);
                }
                return;
            }
            if (inHome(mx, my)) {
                navigateHome();
                return;
            }
            if (inClose(mx, my)) {
                closeScreen();
                return;
            }
        }
        WikiHandle b = browser;
        if (inPage(mx, my) && b != null) {
            // OSR 焦点重挂：create 时 native browser 尚在异步创建，那时的一次性
            // setFocus 很可能被丢弃——CEF 无焦点时点击/键盘事件被整体忽略
            // （T6-4：与 browser 侧 BrowserScreen.mouseClicked 同款）。
            b.setFocus(true);
            int cx = cefX(mx);
            int cy = cefY(my);
            // T8/C6 防御（43 报告 §5 C6 行，与 browser 侧同款）：上一击的 release
            // 若因故未配对（mouseMovedOrUp 未到达），pressedCefBtn 滞留 → Chromium
            // 认该键仍按下 → 本次 mousedown 被当 drag/重复按吞掉。press 之前先补发
            // 一次 release 兜底；守卫内才触发，正常路径零额外开销、无语义副作用。
            int stale = pressedCefBtn;
            if (stale != -1) {
                b.injectMouseButton(pressedCefX, pressedCefY,
                    toAwtMask(stale) | awtModifiers(), stale, false, 1);
                staleRescueCount++;
                pressedCefBtn = -1;
                if (!rescueDiagDone) {
                    rescueDiagDone = true;
                    System.out.println("[mcphone_wiki] stale press rescued: leaked btn="
                        + stale + " pressed_at gui=(" + pressedGuiX + "," + pressedGuiY
                        + ") cef=(" + pressedCefX + "," + pressedCefY
                        + ") release_resent_at cef=(" + cx + "," + cy
                        + ") total_rescues=" + staleRescueCount);
                }
            }
            pressedCefBtn = toAwtButton(btn);
            pressedGuiX = mx;
            pressedGuiY = my;
            pressedCefX = cx;
            pressedCefY = cy;
            int mask = toAwtMask(pressedCefBtn);
            // T8：导航后首点一次性诊断（默认开，预算 2 行/实例）——字段供
            // 43 报告 §4 E1-E6 判读：press 是否注入、坐标/视口是否正确、
            // 是否是「补发 release」救了这次点击
            if (navClickDiag && navClickDiagBudget > 0) {
                navClickDiag = false;
                navClickDiagBudget--;
                String cur = b.getURL();
                System.out.println("[mcphone_wiki] first post-nav click: url="
                    + ((cur != null && !cur.isEmpty()) ? cur : "n/a")
                    + " gui=(" + mx + "," + my + ")"
                    + " page=(" + (mx - boxX()) + "," + (my - boxY()) + ")"
                    + " cef=(" + cx + "," + cy + ")"
                    + " pressedBtn=" + pressedCefBtn
                    + " stalePressedBtn=" + stale
                    + " rescued=" + (stale != -1)
                    + " clickCount=1"
                    + " mods=" + (mask | awtModifiers())
                    + " viewport=" + cefW + "x" + cefH
                    + " actualViewport=" + b.cefViewWidth() + "x" + b.cefViewHeight()
                    + " hasFocus=" + diagHasFocus(b));
            }
            // S0-4：注入坐标按 cefW/viewW、cefH/viewH 缩放到 CEF 渲染分辨率；
            // modifiers = 按钮掩码 | 实时修饰键（T6-2）。
            b.injectMouseButton(cx, cy, mask | awtModifiers(), pressedCefBtn, true, 1);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which != -1) {
            // 释放：无论是否仍在页面内都配对发送，避免 CEF 侧按键卡死。
            // 掩码与按下时一致（JCEF native 按掩码识别按钮，release 传 0 同样失效）。
            if (pressedCefBtn != -1) {
                WikiHandle b = browser;
                if (b != null) {
                    b.injectMouseButton(cefX(mx), cefY(my),
                        toAwtMask(pressedCefBtn) | awtModifiers(), pressedCefBtn, false, 1);
                }
                pressedCefBtn = -1;
            }
        }
        // 纯移动由 handleMouseInput 统一处理（1.7.10 纯移动不会走到这里）
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        WikiHandle b = browser;
        if (b == null) {
            lastInPage = false;
            return;
        }
        int ex = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int ey = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        boolean over = inPage(ex, ey);
        // focus=false → MOUSE_MOVED；true → MOUSE_EXITED（离开页面时补发一次）；
        // modifiers 为实时修饰键掩码（T6-2 口径统一）。
        if (over || lastInPage) {
            b.injectMouseMove(cefX(ex), cefY(ey), awtModifiers(), !over);
        }
        lastInPage = over;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && over) {
            // 符号口径（t2 取证，roadmap-2026-09/42 §1 推导链，勿改回；T1 旧注释
            // 「不在注入层/内核另案处理」已被 t2 否定，勿复用）：
            // LWJGL2 Mouse.getEventDWheel() 正=滚轮向上；cefclient OSR 官方样例
            // 原样直通不改号，native 层读 getUnitsToScroll()=amount×delta 直灌
            // Blink ⇒ CEF/Blink deltaY 正=向上。故 rotation 与 getEventDWheel()
            // 同号（正值=向上滚）。初版 `wheel > 0 ? -1 : 1` 系被 java.awt
            // MouseWheelEvent「rotation 正值=向下」误导，是全仓滚轮反向的唯一翻转层。
            int rotation = wheel > 0 ? 1 : -1;
            // 一次性滚轮诊断（默认关，-Dmcphone_wiki.diag 开启；wiki 与 browser
            // 反射隔离，用独立开关，不复用 mcphone_browser.diag）
            if (!wheelDiagDone && Boolean.getBoolean("mcphone_wiki.diag")) {
                wheelDiagDone = true;
                System.out.println("[mcphone_wiki] first wheel: cef=(" + cefX(ex)
                    + "," + cefY(ey) + ") rotation=" + rotation
                    + " actualViewport=" + b.cefViewWidth() + "x" + b.cefViewHeight());
            }
            b.injectMouseWheel(cefX(ex), cefY(ey), awtModifiers(), 120, rotation);
        }
    }

    /**
     * T8 诊断：反射穿透 {@link WikiHandle} 的内核包装，探测内核是否有
     * {@code hasFocus()} 入口并读取。任一环节不成立返回 {@code "n/a"}——只读
     * 探测，无任何副作用（WikiHandle.java 非 t16 inScope，故不改它，只在
     * WikiScreen 侧做一次性日志时刻的反射探测）。
     */
    private static String diagHasFocus(WikiHandle b) {
        try {
            Field f = WikiHandle.class.getDeclaredField("browser");
            f.setAccessible(true);
            Object osr = f.get(b);
            if (osr == null) return "n/a";
            Method m = osr.getClass().getMethod("hasFocus");
            return String.valueOf(m.invoke(osr));
        } catch (Throwable t) {
            return "n/a";
        }
    }

    private void navigateHome() {
        WikiHandle b = browser;
        if (b != null) {
            b.loadURL(WikiAddon.WIKI_HOME);
        }
        WikiStore.setLastUrl(WikiAddon.WIKI_HOME);
        WikiStore.addHistory(WikiAddon.WIKI_HOME);
    }
}
