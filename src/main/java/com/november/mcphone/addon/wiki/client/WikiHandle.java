package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.Tessellator;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * net.montoyo.mcef.api.IBrowser 的反射包装（避免编译期依赖 MCEF）。
 *
 * <p>按 modern 内核主线编写：wiki 只针对实例里 browser jar 内嵌的 modern MCEF
 * 内核（CefBrowserOsr 帧队列 + mcefUpdate() 上传），不做 legacy MCEF 0.6/0.7
 * 兼容分支；注入方法签名以 modern {@code CefBrowserOsr} 为准（与 browser 侧
 * BrowserHandle/BrowserScreen 口径一致，T6 对齐）：</p>
 * <ul>
 * <li>{@code injectMouseMove(x, y, modifiers, focus)}：focus=false → MOUSE_MOVED，
 *     true → MOUSE_EXITED（id 505）；modifiers 为 AWT 修饰键掩码；</li>
 * <li>{@code injectMouseButton(x, y, modifiers, button, pressed, clickCount)}：
 *     button 为 AWT 编号（1=左 2=中 3=右）；modifiers 为 AWT 修饰键+按钮掩码
 *     （{@code InputEvent.*_DOWN_MASK}），native 层经 {@code getModifiersEx}
 *     读——恒传 0 会被当「无按钮按下」而整个点击被 Blink 忽略；</li>
 * <li>{@code injectKeyXxx(char, modifiers)}：字符/控制字符（\b \r \t）通道，
 *     modifiers 传 AWT 修饰键掩码；</li>
 * <li>{@code injectKeyXxxByKeyCode(keyCode, char, modifiers)}：非字符键通道
 *     （方向键/Delete/Home/End/翻页/F1-F12，LWJGL 键码经内核 remapKeycode 映射
 *     GLFW 码），仅嵌入版 {@code CefBrowserOsr} 实现，探测失败则降级禁用
 *     （降级显式可见，见 {@link #degradedNotice()}）；</li>
 * <li>{@code setFocus(boolean)}：OSR 显式获焦——CEF 无焦点时点击/键盘事件被
 *     整体忽略；首帧上传后、每次导航后、页面点击前重挂；</li>
 * <li>{@code injectMouseWheel(x, y, modifiers, scrollAmount, wheelRotation)}：
 *     rotation 正值=向上滚（与 {@code Mouse.getEventDWheel()} 同号；历史 bug：
 *     67c371a / 2d73b54 曾写反，勿改回）。</li>
 * </ul>
 *
 * <p><b>S5-5 签名容错（probe）</b>：核心方法（resize/close/draw/getTextureID/
 * loadURL/goBack/goForward/getURL）是 IBrowser 接口方法，查找失败直接抛错；
 * 注入类方法用 {@link #probe} 容错探测——某个签名对不上只禁用对应功能
 * （对应注入降级为 no-op + 显式降级提示），绝不拖垮整个 WikiHandle 构造、让
 * createBrowser 整体报错（browser 侧实测根因同款）。</p>
 *
 * <p>S0-2 帧泵自驱动：GTNH 环境下 MCEF 的 onTick 帧上传未被驱动，onPaint 缓存
 * 的帧永远停在 {@code queue} 里；{@link #pumpFrameUpload()} 在 drawScreen 每帧
 * 自驱动 {@code CefApp.N_DoMessageLoopWork()} + {@code mcefUpdate()}（两者与
 * MCEF 自带泵并存幂等无害）。</p>
 *
 * <p>S0-3 自绘四边形：绕开 CefRenderer.render()（Angelica GLSM 下 UV 缺陷 +
 * ALPHA_TEST 丢 alpha=0 帧 → 整页透明），用正确 UV(0,0)-(1,1) 自绘；系统属性
 * {@code -Dmcphone_wiki.legacyRenderer=true} 可退回原路径对比。</p>
 */
@SideOnly(Side.CLIENT)
public final class WikiHandle {

    // ---- 绘制路径开关（S0-3，可退回 CefRenderer.render() 对比） ----
    /** true = 走 MCEF 原生 render()（旧行为，对比用）；默认自绘四边形。 */
    public static volatile boolean legacyRender =
        Boolean.getBoolean("mcphone_wiki.legacyRenderer");

    private final Object browser;
    private final Method resize, close, draw, getTextureID, loadURL, goBack, goForward, getURL;
    private final Method injectMouseMove, injectMouseButton, injectMouseWheel;
    private final Method injectKeyPressed, injectKeyTyped, injectKeyReleased;
    private final Method injectKeyPressedByKeyCode, injectKeyReleasedByKeyCode;
    private final Method setFocus;

    // ---- 输入降级可见性（T6-3）：反射探测失败时不再只留一行日志即静默 ----
    private final StringBuilder degraded = new StringBuilder();
    private boolean degradedAnnounced;

    // ---- 帧泵（S0-2）：探测可缺省，缺哪个只降级对应功能 ----
    private final Method mcefUpdate;   // CefBrowserOsr.mcefUpdate()（上传排队帧）
    private final Field queueField;    // CefBrowserOsr.queue（诊断：帧是否到达）

    // 首帧探测：CefRenderer.view_width_/view_height_（渲染分辨率实际值，
    // 与期望值比对——S0-4 视口竞态；render() 在二者为 0 时直接返回，>0 即
    // 至少上传过一帧）。同一 renderer_ 实例兼作纹理初始化兜底目标，探测共享。
    private final Object renderer;
    private final java.lang.reflect.Field fViewW, fViewH;

    // ---- 纹理初始化兜底 ----
    private final Method rendererInit;
    private boolean rendererInitialized;

    // ---- 帧泵静态缓存（drawScreen 每帧调用，不能反复查表） ----
    private static Method messageLoop;          // CefApp.N_DoMessageLoopWork()（静态）
    private static boolean messageLoopProbed;

    private boolean firstFrameLogged;
    private boolean firstFrameFocused;

    WikiHandle(Object browser) throws Exception {
        this.browser = browser;
        Class<?> c = browser.getClass();
        // 核心方法：接口必有，缺失即整体失败（构造抛错→createBrowser 才报错）
        resize = c.getMethod("resize", int.class, int.class);
        close = c.getMethod("close");
        draw = c.getMethod("draw", double.class, double.class, double.class, double.class);
        getTextureID = c.getMethod("getTextureID");
        loadURL = c.getMethod("loadURL", String.class);
        goBack = c.getMethod("goBack");
        goForward = c.getMethod("goForward");
        getURL = c.getMethod("getURL");
        // 注入方法：probe 容错（S5-5），缺失只禁用对应功能
        injectMouseMove = probe(c, "injectMouseMove", int.class, int.class, int.class, boolean.class);
        injectMouseButton = probe(c, "injectMouseButton",
            int.class, int.class, int.class, int.class, boolean.class, int.class);
        injectMouseWheel = probe(c, "injectMouseWheel",
            int.class, int.class, int.class, int.class, int.class);
        injectKeyPressed = probe(c, "injectKeyPressed", char.class, int.class);
        injectKeyTyped = probe(c, "injectKeyTyped", char.class, int.class);
        injectKeyReleased = probe(c, "injectKeyReleased", char.class, int.class);
        // T6-1/2/3：非字符键 ByKeyCode 通道 + OSR 焦点（与 browser 侧同款探测）
        injectKeyPressedByKeyCode = probe(c, "injectKeyPressedByKeyCode",
            int.class, char.class, int.class);
        injectKeyReleasedByKeyCode = probe(c, "injectKeyReleasedByKeyCode",
            int.class, char.class, int.class);
        setFocus = probe(c, "setFocus", boolean.class);

        if (injectKeyPressedByKeyCode == null || injectKeyReleasedByKeyCode == null) {
            degraded.append("key-by-keycode(方向键/Delete/Home/End/翻页不可用) ");
        }
        if (setFocus == null) {
            degraded.append("setFocus(页面可能收不到点击/键盘) ");
        }

        Object r = null;
        java.lang.reflect.Field fw = null, fh = null;
        try {
            java.lang.reflect.Field rf = c.getDeclaredField("renderer_");
            rf.setAccessible(true);
            r = rf.get(browser);
            if (r != null) {
                fw = r.getClass().getDeclaredField("view_width_");
                fw.setAccessible(true);
                fh = r.getClass().getDeclaredField("view_height_");
                fh.setAccessible(true);
            }
        } catch (Throwable t) {
            r = null; // 探测不可用时走调用方的超时兜底
        }
        this.renderer = r;
        this.fViewW = fw;
        this.fViewH = fh;

        // 探测 CefRenderer.initialize()（纹理 id 唯一赋值点）并缓存。
        Method init = null;
        if (r != null) {
            try {
                init = r.getClass().getDeclaredMethod("initialize");
                init.setAccessible(true);
            } catch (Throwable t) {
                // 内核可能已自行修复，打一行日志后不再尝试；
                // 首帧探测结果（renderer/fViewW/fViewH）保持不动。
                System.out.println("[mcphone_wiki] renderer init shim not applicable: " + t);
                init = null;
            }
        }
        this.rendererInit = init;
        if (init != null) {
            System.out.println("[mcphone_wiki] OSR renderer texture-init shim armed");
        }

        // 帧泵探测（S0-2）：mcefUpdate()（帧上传入口）+ queue（帧队列，诊断用）。
        Method upd = null;
        try {
            upd = c.getMethod("mcefUpdate");
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] mcefUpdate not found (frame pump disabled): " + t);
        }
        mcefUpdate = upd;
        Field q = null;
        try {
            q = c.getDeclaredField("queue");
            q.setAccessible(true);
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] paint queue probe failed (diagnostics disabled): " + t);
        }
        queueField = q;
        System.out.println("[mcphone_wiki] frame pump armed (mcefUpdate=" + (upd != null)
            + ", queue=" + (q != null) + ", viewFields=" + (fw != null) + ")"
            + (legacyRender ? " [legacyRender=true, draw path = CefRenderer.render()]" : ""));
        System.out.println("[mcphone_wiki] input probe: byKeyCode="
            + (injectKeyPressedByKeyCode != null && injectKeyReleasedByKeyCode != null)
            + ", setFocus=" + (setFocus != null)
            + ", charChannel=" + (injectKeyPressed != null && injectKeyReleased != null));
        // T6-3：构造期即兜底输出一次显眼降级日志（onFirstFrameUploaded 里还会再
        // 守一次；degradedAnnounced 保证总共至多一条）。
        announceDegraded();
    }

    /**
     * 容错方法探测（S5-5）：找到返回 Method，找不到打日志返回 null（对应注入
     * 功能降级禁用），绝不让注入类方法的不匹配拖垮 WikiHandle 构造。
     */
    private static Method probe(Class<?> c, String name, Class<?>... paramTypes) {
        try {
            return c.getMethod(name, paramTypes);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] WARN: " + c.getName() + "." + name
                + " signature not found (feature disabled): " + t);
            return null;
        }
    }

    // ===================== 输入降级可见性（T6-3） =====================

    /**
     * 反射探测失败的功能清单（空串 = 全部通道可用）。降级不再静默：
     * 首帧上传后向日志显式输出一次（{@link #announceDegraded()}），
     * 调用方可据此在页面上叠加可见文案（{@link #inputDegraded()}）。
     */
    public String degradedNotice() {
        return degraded.toString().trim();
    }

    /** 一次性显眼日志：降级发生时在首帧后输出一次，不刷屏。 */
    void announceDegraded() {
        if (degradedAnnounced || degraded.length() == 0) {
            return;
        }
        degradedAnnounced = true;
        System.err.println("[mcphone_wiki] NOTICE: input injection degraded — "
            + degradedNotice()
            + "(probe failed on modern CefBrowserOsr; wiki falls back to char-only keys)");
    }

    /** 是否存在已探测失败的输入通道（UI 叠加提示用）。 */
    public boolean inputDegraded() {
        return degraded.length() > 0;
    }

    public void resize(int w, int h) {
        try {
            resize.invoke(browser, w, h);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] resize failed: " + t);
        }
    }

    public void close() {
        try {
            close.invoke(browser);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] close failed: " + t);
        }
    }

    /** CefRenderer 渲染页面四边形（绑定纹理、处理翻转）——legacy 对比路径。 */
    public void draw(double x1, double y1, double x2, double y2) {
        try {
            draw.invoke(browser, x1, y1, x2, y2);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] draw failed: " + t);
        }
    }

    /**
     * 自绘页面四边形——「透明页面」的绘制侧修复（S0-3），绕开
     * CefRenderer.render()：其 UV 取值有缺陷（v1=(1,1) 而非 (1,0)、v4 重复
     * v1 的 UV），在 Angelica GLSM 的 FFP shader 变体下行为不确定；且 CEF OSR
     * 默认背景色 alpha 可能为 0，vanilla 常驻 GL_ALPHA_TEST 会把 alpha&lt;0.1 的
     * 片段整体丢弃 → 页面全透明。
     *
     * <p>自绘要点：绑定 CEF 纹理后显式 {@code glDisable(GL_ALPHA_TEST)}、
     * {@code glDisable(GL_BLEND)}（纹理 alpha 不参与混合）、
     * {@code glEnable(GL_TEXTURE_2D)}、恢复 {@code glColor4f(1,1,1,1)}，用正确
     * 朝向的 UV(0,0)-(1,1) 画四边形。</p>
     */
    public void drawSelf(double x1, double y1, double x2, double y2) {
        int tex = textureId();
        if (tex == 0) {
            return;
        }
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);      // CEF 帧 alpha 不参与混合
            GL11.glDisable(GL11.GL_ALPHA_TEST); // 防 alpha=0 帧被整体丢弃
            GL11.glColor4f(1f, 1f, 1f, 1f);

            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.setColorOpaque_F(1f, 1f, 1f);
            // 正确朝向的 UV：CEF onPaint 帧顶行在 buffer 开头 → 纹理 t=0 为页面顶部。
            // 1.7.10 GUI y 向下，故 v = y 对应 (y1→0, y2→1)。
            t.addVertexWithUV(x1, y1, 0, 0d, 0d);
            t.addVertexWithUV(x1, y2, 0, 0d, 1d);
            t.addVertexWithUV(x2, y2, 0, 1d, 1d);
            t.addVertexWithUV(x2, y1, 0, 1d, 0d);
            t.draw();

            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable tr) {
            System.err.println("[mcphone_wiki] drawSelf failed: " + tr);
        }
    }

    /**
     * 当前页面纹理 ID；未绘制首帧时为 0。
     *
     * <p>上游 bug 兜底：CefRenderer.initialize()（纹理 id 的唯一赋值点
     * glGenTextures）若无任何调用者，getTextureID() 恒 0。首次在此读到 0 且
     * 兜底尚未执行时调用一次 initialize()。调用方在 WikiScreen.drawScreen
     * （Client thread 渲染路径，有 GL context）调用，线程安全。</p>
     */
    public int textureId() {
        try {
            int id = (Integer) getTextureID.invoke(browser);
            if (id == 0) {
                ensureRendererInitialized();
                id = (Integer) getTextureID.invoke(browser);
            }
            return id;
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] getTextureID failed: " + t);
            return 0;
        }
    }

    /**
     * 纹理初始化兜底（反射调用 CefRenderer.initialize() 一次）。
     * 必须在持有 GL context 的线程上执行；无论成败只执行一次。
     */
    void ensureRendererInitialized() {
        if (rendererInitialized || rendererInit == null) {
            return;
        }
        rendererInitialized = true;
        try {
            rendererInit.invoke(renderer);
            int id = (Integer) getTextureID.invoke(browser);
            if (id != 0) {
                System.out.println("[mcphone_wiki] CefRenderer.initialize() invoked (texture id=" + id + ")");
            } else {
                System.out.println(
                    "[mcphone_wiki] WARN: CefRenderer.initialize() invoked but texture id still 0"
                        + " (GL context unavailable? giving up, no retry)");
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] CefRenderer.initialize() failed: " + t);
        }
    }

    /**
     * CEF 是否已向纹理上传过真实帧（view_width_/view_height_ &gt; 0）。
     * 反射不可用时返回 true（调用方走超时兜底）。
     */
    public boolean hasPaintedFrame() {
        if (renderer == null || fViewW == null || fViewH == null) {
            return true;
        }
        try {
            return fViewW.getInt(renderer) > 0 && fViewH.getInt(renderer) > 0;
        } catch (Throwable t) {
            return true;
        }
    }

    // ===================== 帧泵（S0-2） =====================

    /**
     * 帧上传泵——「页面白/透明」的修复核心（S0-2）。
     *
     * <p>modern CefBrowserOsr 的 onPaint 只把帧缓存进 queue；真正上传到 GL 纹理
     * 的是 {@code mcefUpdate()}，其唯一调用方是 MCEF ClientProxy 的 tick 回调。
     * GTNH 环境下该回调未被驱动 → 帧永远停在队列里、视口字段恒 0。</p>
     *
     * <p>在 WikiScreen.drawScreen（GL 线程）每帧自驱动：
     * ① {@code CefApp.N_DoMessageLoopWork()} 泵 CEF 消息循环（把 onPaint 送达）；
     * ② {@code mcefUpdate()} 把队列里的帧上传到纹理。两者与 MCEF 自带的泵并存
     * 幂等无害（mcefUpdate synchronized、队列空即空转）。</p>
     */
    public void pumpFrameUpload() {
        if (mcefUpdate == null) {
            return;
        }
        try {
            Object app = McefBridge.cefAppHandle();
            if (app != null) {
                Method ml = messageLoopMethod(app);
                if (ml != null) {
                    ml.invoke(app);
                }
            }
        } catch (Throwable t) {
            // 消息循环泵失败只降级：CEF 内部线程仍可能继续送帧，不刷屏
        }
        try {
            mcefUpdate.invoke(browser);
            if (!firstFrameLogged && fViewW != null && renderer != null) {
                int w = fViewW.getInt(renderer);
                int h = fViewH.getInt(renderer);
                if (w > 0 && h > 0) {
                    firstFrameLogged = true;
                    System.out.println("[mcphone_wiki] first frame uploaded (" + w + "x" + h + ")");
                    // T6-4：browser 异步创建完毕后此刻 setFocus 才真正生效
                    //（创建后立刻调的那次会落在 CEF 内部 browser 指针为空的窗口期被丢弃）
                    onFirstFrameUploaded();
                }
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] mcefUpdate failed: " + t);
        }
    }

    /** 首帧上传后重挂焦点（此时 native browser 已就绪，setFocus 不再被丢弃）。 */
    private void onFirstFrameUploaded() {
        if (firstFrameFocused) {
            return;
        }
        firstFrameFocused = true;
        System.out.println("[mcphone_wiki] first frame uploaded — re-focusing browser");
        setFocus(true);
        announceDegraded();
    }

    private static Method messageLoopMethod(Object app) {
        if (!messageLoopProbed) {
            messageLoopProbed = true;
            try {
                messageLoop = app.getClass().getMethod("N_DoMessageLoopWork");
            } catch (Throwable t) {
                System.err.println("[mcphone_wiki] WARN: CefApp.N_DoMessageLoopWork not found"
                    + " (message-loop pump disabled): " + t);
            }
        }
        return messageLoop;
    }

    // ===================== 诊断 =====================

    /**
     * 等待上传的帧数（queue 深度）。&gt;0 = CEF 在产帧、只是没被上传；
     * 0 = CEF 没有产出新帧；-1 = 探测失败（无诊断能力）。
     */
    public int queuedFrames() {
        if (queueField == null) {
            return -1;
        }
        try {
            return ((java.util.LinkedList<?>) queueField.get(browser)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 渲染视口宽（反射 CefRenderer.view_width_）。-1 = 探测失败。 */
    public int cefViewWidth() {
        if (fViewW == null || renderer == null) {
            return -1;
        }
        try {
            return fViewW.getInt(renderer);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 渲染视口高（镜像 {@link #cefViewWidth()}），-1 = 探测失败。 */
    public int cefViewHeight() {
        if (fViewH == null || renderer == null) {
            return -1;
        }
        try {
            return fViewH.getInt(renderer);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void loadURL(String url) {
        try {
            loadURL.invoke(browser, url);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] loadURL failed: " + t);
            return;
        }
        // T6-4：跳转/刷新后 CEF 的焦点态会随新页面重置，重挂一次
        //（与 browser 侧 BrowserHandle.loadURL 同款）。
        setFocus(true);
    }

    public void goBack() {
        try {
            goBack.invoke(browser);
        } catch (Throwable t) {}
    }

    @SuppressWarnings("unused")
    public void goForward() {
        try {
            goForward.invoke(browser);
        } catch (Throwable t) {}
    }

    public String getURL() {
        try {
            return (String) getURL.invoke(browser);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== 输入注入（probe 可降级：缺失时 no-op） =====================

    /** focus=false → MOUSE_MOVED；true → MOUSE_EXITED。modifiers 为 AWT 修饰键掩码。 */
    public void injectMouseMove(int x, int y, int modifiers, boolean focus) {
        if (injectMouseMove == null) return;
        try {
            injectMouseMove.invoke(browser, x, y, modifiers, focus);
        } catch (Throwable t) {}
    }

    /**
     * button 为 AWT 编号（1=左 2=中 3=右）；modifiers 为 AWT 修饰键+按钮掩码
     * （{@code InputEvent.BUTTONx_DOWN_MASK}：左 1024 / 中 2048 / 右 4096）。
     * JCEF native 层经 {@code getModifiersEx} 读掩码判定按的是哪个按钮——
     * 恒传 0 会被当成「无按钮按下」而整个点击被 Blink 忽略（browser 侧已实证）。
     */
    public void injectMouseButton(int x, int y, int modifiers, int button, boolean pressed, int clickCount) {
        if (injectMouseButton == null) return;
        try {
            injectMouseButton.invoke(browser, x, y, modifiers, button, pressed, clickCount);
        } catch (Throwable t) {}
    }

    /** rotation 正值=向上滚（与 {@code Mouse.getEventDWheel()} 同号；历史 bug：
     67c371a / 2d73b54 曾写反，勿改回）。 */
    public void injectMouseWheel(int x, int y, int modifiers, int scrollAmount, int rotation) {
        if (injectMouseWheel == null) return;
        try {
            injectMouseWheel.invoke(browser, x, y, modifiers, scrollAmount, rotation);
        } catch (Throwable t) {}
    }

    /** 字符/控制字符（\b \r \t）按下；modifiers 为 AWT 修饰键掩码（Shift+字符/Ctrl+V 需要）。 */
    public void injectKeyPressed(char c, int modifiers) {
        if (injectKeyPressed == null) return;
        try {
            injectKeyPressed.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    public void injectKeyTyped(char c, int modifiers) {
        if (injectKeyTyped == null) return;
        try {
            injectKeyTyped.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    /** 字符/控制字符释放（与按下同 modifiers，按键状态不残留）。 */
    public void injectKeyReleased(char c, int modifiers) {
        if (injectKeyReleased == null) return;
        try {
            injectKeyReleased.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    /**
     * 非字符键注入（方向键/Delete/Home/End/翻页/F1-F12，LWJGL 键码 1.7.10 侧
     * 有值而 character=0）。仅嵌入版 {@code CefBrowserOsr} 实现了 ByKeyCode 管道
     * （remapKeycode → GLFW 码 → natives）；探测失败则 no-op（降级可见）。
     *
     * <p>modifiers 与字符版相同（AWT 掩码）。</p>
     */
    public void injectKeyPressedByKeyCode(int keyCode, char c, int modifiers) {
        if (injectKeyPressedByKeyCode == null) return;
        try {
            injectKeyPressedByKeyCode.invoke(browser, keyCode, c, modifiers);
        } catch (Throwable t) {}
    }

    /** 配对的非字符键释放注入（见 {@link #injectKeyPressedByKeyCode}）。 */
    public void injectKeyReleasedByKeyCode(int keyCode, char c, int modifiers) {
        if (injectKeyReleasedByKeyCode == null) return;
        try {
            injectKeyReleasedByKeyCode.invoke(browser, keyCode, c, modifiers);
        } catch (Throwable t) {}
    }

    /**
     * OSR 浏览器显式获焦。上游 JCEF 在 createBrowserIfRequired 里创建后必调
     * setFocus(true)；不调的话 CEF 收到点击/键盘事件但认为自己无焦点、直接忽略
     * ——「点击/键盘全灭」的头号嫌疑。调用点：首帧上传后（onFirstFrameUploaded）、
     * loadURL 后、页面点击前（WikiScreen）。
     */
    public void setFocus(boolean focus) {
        if (setFocus == null) {
            return;
        }
        try {
            setFocus.invoke(browser, focus);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] setFocus failed: " + t);
        }
    }
}
