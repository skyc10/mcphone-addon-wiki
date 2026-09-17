package com.november.mcphone.addon.wiki.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

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
 * <p>注入约定（与 modern CefBrowserOsr 对齐）：
 * 鼠标按钮为 AWT 编号（1=左 2=中 3=右）；injectMouseMove 的 focus=false 才是
 * MOUSE_MOVED（true=EXITED）；键盘 Pressed → (chr≠0 时) Typed → Released，
 * modifiers 恒 0。1.7.10 只在按下时回调 keyTyped，Released 在
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
    private int pressedCefBtn = -1;
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
                }
            }
        } else {
            WikiHandle b = browser;
            if (b != null) {
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

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc
            closeScreen();
            return;
        }
        // 页面模式：Pressed → (chr≠0 时) Typed；Released 由 handleKeyboardInput 补发。
        // 键盘注入不涉及页面坐标，无需缩放。
        WikiHandle b = browser;
        if (b != null) {
            b.injectKeyPressed(typedChar, 0);
            if (typedChar != 0) {
                b.injectKeyTyped(typedChar, 0);
            }
        }
    }

    @Override
    public void handleKeyboardInput() {
        super.handleKeyboardInput();
        // keyTyped 只在按下时回调；这里补发松开事件（修饰键/按键状态不残留）。
        if (!Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) {
            WikiHandle b = browser;
            if (b != null) {
                b.injectKeyReleased(Keyboard.getEventCharacter(), 0);
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
            pressedCefBtn = toAwtButton(btn);
            // S0-4：注入坐标按 cefW/viewW、cefH/viewH 缩放到 CEF 渲染分辨率
            b.injectMouseButton(cefX(mx), cefY(my), 0, pressedCefBtn, true, 1);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which != -1) {
            // 释放：无论是否仍在页面内都配对发送，避免 CEF 侧按键卡死
            if (pressedCefBtn != -1) {
                WikiHandle b = browser;
                if (b != null) {
                    b.injectMouseButton(cefX(mx), cefY(my), 0, pressedCefBtn, false, 1);
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
        // focus=false → MOUSE_MOVED；true → MOUSE_EXITED（离开页面时补发一次）
        if (over || lastInPage) {
            b.injectMouseMove(cefX(ex), cefY(ey), 0, !over);
        }
        lastInPage = over;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && over) {
            // Java MouseWheelEvent：rotation 正值=向下；MC 正值=向上
            int rotation = wheel > 0 ? -1 : 1;
            b.injectMouseWheel(cefX(ex), cefY(ey), 0, 120, rotation);
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
