package com.november.mcphone.addon.wiki.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

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
 * <p>注入约定（与 MCEF 0.7 CefBrowserOsr 对齐）：
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
     * CEF 引导页（MCEF jar 内置本地页，mod:// scheme 恒注册）。
     * F10 示例浏览器在本实例唯一验证可行的路径是「先用本地页创建 → 首帧渲染后
     * 再导航到外链」；直接用外链 URL 创建的 OSR 浏览器首帧不上传纹理（纯白），
     * 疑似 CEF 3.2171 视口初始化竞态。故创建时先挂引导页，渲染稳定后 loadURL。
     */
    private static final String BOOTSTRAP_URL = "mod://mcef/home.html";
    /** 首帧等待上限（帧）：约 4~6 秒，防反射探测不可用时永久白屏。 */
    private static final int BOOTSTRAP_MAX_FRAMES = 120;

    private volatile WikiHandle browser;
    private int viewW;
    private int viewH;
    private final String pendingUrl;
    private boolean created;
    private String createError;
    private long lastUrlSync;
    private boolean lastInPage;
    private int pressedCefBtn = -1;
    private String shownUrl = "";

    /** 引导状态：BOOTSTRAP（本地页，等首帧）→ 每帧尝试 loadURL(pendingUrl)。 */
    private boolean bootstrapping;
    private int bootstrapFrames;

    /** 当前打开的 WikiScreen（供看门狗关闭）。 */
    private static WikiScreen current;

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

    // ===================== 布局 =====================

    private void computeSize() {
        double k = Math.min(this.width * SCALE / 16.0, this.height * SCALE / 9.0);
        viewW = (int) Math.floor(k * 16.0);
        viewH = (int) Math.floor(k * 9.0);
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
        if (!created) {
            created = true;
            String url = pendingUrl != null ? pendingUrl : WikiAddon.WIKI_HOME;
            shownUrl = url;
            McefBridge.detect();
            if (McefBridge.available()) {
                // 先用内置本地页创建（F10 验证过的安全路径），首帧后再导航到目标页
                browser = McefBridge.create(BOOTSTRAP_URL);
                if (browser == null) {
                    createError = McefBridge.failReason();
                } else {
                    browser.resize(viewW, viewH);
                    bootstrapping = true;
                    bootstrapFrames = 0;
                }
            }
        } else {
            WikiHandle b = browser;
            if (b != null) {
                b.resize(viewW, viewH);
            }
        }
    }

    /** 引导推进：首帧（或超时）后导航到目标页；pendingUrl 只消费一次。 */
    private void tickBootstrap() {
        if (!bootstrapping) {
            return;
        }
        WikiHandle b = browser;
        if (b == null) {
            bootstrapping = false;
            return;
        }
        bootstrapFrames++;
        if (b.hasPaintedFrame() || bootstrapFrames >= BOOTSTRAP_MAX_FRAMES) {
            bootstrapping = false;
            String url = pendingUrl != null ? pendingUrl : WikiAddon.WIKI_HOME;
            b.loadURL(url);
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

    /** 看门狗用：游戏退出时关掉还活着的浏览器。 */
    static void forceClose() {
        WikiScreen s = current;
        if (s != null) {
            WikiHandle b = s.browser;
            if (b != null) {
                s.browser = null;
                try {
                    b.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    // ===================== 渲染 =====================

    @Override
    public void drawScreen(int mx, int my, float pt) {
        tickBootstrap();
        drawRect(0, 0, this.width, this.height, 0xD0141414);

        // ---- 顶部工具栏 ----
        drawRect(0, 0, this.width, BAR, 0xF02B2B2B);

        WikiHandle b = browser;

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
        // 引导期间 CEF 还停在 mod:// 引导页，跳过同步以免污染历史/上次页面。
        if (b != null && !bootstrapping && System.currentTimeMillis() - lastUrlSync > 500) {
            lastUrlSync = System.currentTimeMillis();
            String cur = b.getURL();
            if (cur != null && !cur.isEmpty() && !cur.equals(shownUrl)) {
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
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            b.draw(bx, by, bx + viewW, by + viewH);
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } else {
            String msg;
            String detail = null;
            if (createError != null) {
                msg = StatCollector.translateToLocal("err.mcphone_wiki.create_failed");
                detail = createError;
            } else if (McefBridge.available()) {
                msg = StatCollector.translateToLocal("msg.mcphone_wiki.loading");
            } else {
                msg = StatCollector.translateToLocal("err.mcphone_wiki.missing_mcef");
                String r = McefBridge.failReason();
                if (r != null && !r.isEmpty()) {
                    detail = r;
                }
            }
            drawRect(bx, by, bx + viewW, by + viewH, 0xFF0A0A0A);
            int mw = fontRendererObj.getStringWidth(msg);
            fontRendererObj.drawStringWithShadow(msg, bx + (viewW - mw) / 2, by + viewH / 2 - 4, 0xFFFF55);
            if (detail != null) {
                fontRendererObj.drawStringWithShadow(detail, bx + 8, by + viewH / 2 + 14, 0xFF5555);
            }
        }

        // 页面边框
        drawRect(bx - 1, by - 1, bx + viewW + 1, by, 0xFF5A5A5A);
        drawRect(bx - 1, by + viewH, bx + viewW + 1, by + viewH + 1, 0xFF5A5A5A);
        drawRect(bx - 1, by, bx, by + viewH, 0xFF5A5A5A);
        drawRect(bx + viewW, by, bx + viewW + 1, by + viewH, 0xFF5A5A5A);

        super.drawScreen(mx, my, pt);
    }

    // ===================== 键盘 =====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc
            closeScreen();
            return;
        }
        // 页面模式：Pressed → (chr≠0 时) Typed；Released 由 handleKeyboardInput 补发。
        // MCEF 0.7 的 keyCode 恒为 0，非字符键（方向键等）无法表达——接受此限制。
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
            b.injectMouseButton(mx - boxX(), my - boxY(), 0, pressedCefBtn, true, 1);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which != -1) {
            // 释放：无论是否仍在页面内都配对发送，避免 CEF 侧按键卡死
            if (pressedCefBtn != -1) {
                WikiHandle b = browser;
                if (b != null) {
                    b.injectMouseButton(mx - boxX(), my - boxY(), 0, pressedCefBtn, false, 1);
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
            b.injectMouseMove(ex - boxX(), ey - boxY(), 0, !over);
        }
        lastInPage = over;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && over) {
            // Java MouseWheelEvent：rotation 正值=向下；MC 正值=向上
            int rotation = wheel > 0 ? -1 : 1;
            b.injectMouseWheel(ex - boxX(), ey - boxY(), 0, 120, rotation);
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
