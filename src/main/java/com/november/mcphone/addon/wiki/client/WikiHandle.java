package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * net.montoyo.mcef.api.IBrowser 的反射包装（避免编译期依赖 MCEF）。
 *
 * <p>注入方法参数与 MCEF 0.7 的 {@code CefBrowserOsr} 实现对齐
 * （内部构造 java.awt.event 事件）：</p>
 * <ul>
 * <li>{@code injectMouseMove(x, y, modifiers, focus)}：focus=false → MOUSE_MOVED，
 *     true → MOUSE_EXITED（id 505）；</li>
 * <li>{@code injectMouseButton(x, y, modifiers, button, pressed, clickCount)}：
 *     button 为 AWT 编号（1=左 2=中 3=右）；</li>
 * <li>{@code injectKeyXxx(char, modifiers)}：keyCode 恒为 0，char 才是有效载荷，
 *     modifiers 传 0；</li>
 * <li>{@code injectMouseWheel(x, y, modifiers, scrollAmount, wheelRotation)}：
 *     rotation 正值=向下滚。</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public final class WikiHandle {

    private final Object browser;
    private final Method resize, close, draw, getTextureID, loadURL, goBack, goForward, getURL;
    private final Method injectMouseMove, injectMouseButton, injectMouseWheel;
    private final Method injectKeyPressed, injectKeyTyped, injectKeyReleased;

    WikiHandle(Object browser) throws Exception {
        this.browser = browser;
        Class<?> c = browser.getClass();
        resize = c.getMethod("resize", int.class, int.class);
        close = c.getMethod("close");
        draw = c.getMethod("draw", double.class, double.class, double.class, double.class);
        getTextureID = c.getMethod("getTextureID");
        loadURL = c.getMethod("loadURL", String.class);
        goBack = c.getMethod("goBack");
        goForward = c.getMethod("goForward");
        getURL = c.getMethod("getURL");
        injectMouseMove = c.getMethod("injectMouseMove", int.class, int.class, int.class, boolean.class);
        injectMouseButton = c.getMethod("injectMouseButton", int.class, int.class, int.class, int.class, boolean.class, int.class);
        injectMouseWheel = c.getMethod("injectMouseWheel", int.class, int.class, int.class, int.class, int.class);
        injectKeyPressed = c.getMethod("injectKeyPressed", char.class, int.class);
        injectKeyTyped = c.getMethod("injectKeyTyped", char.class, int.class);
        injectKeyReleased = c.getMethod("injectKeyReleased", char.class, int.class);
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

    /** CefRenderer 渲染页面四边形（绑定纹理、处理翻转）。 */
    public void draw(double x1, double y1, double x2, double y2) {
        try {
            draw.invoke(browser, x1, y1, x2, y2);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] draw failed: " + t);
        }
    }

    /** 当前页面纹理 ID；未绘制首帧时为 0。 */
    public int textureId() {
        try {
            return (Integer) getTextureID.invoke(browser);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void loadURL(String url) {
        try {
            loadURL.invoke(browser, url);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] loadURL failed: " + t);
        }
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

    /** focus=false → MOUSE_MOVED；true → MOUSE_EXITED。 */
    public void injectMouseMove(int x, int y, int modifiers, boolean focus) {
        try {
            injectMouseMove.invoke(browser, x, y, modifiers, focus);
        } catch (Throwable t) {}
    }

    /** button 为 AWT 编号：1=左 2=中 3=右。 */
    public void injectMouseButton(int x, int y, int modifiers, int button, boolean pressed, int clickCount) {
        try {
            injectMouseButton.invoke(browser, x, y, modifiers, button, pressed, clickCount);
        } catch (Throwable t) {}
    }

    /** rotation 正值=向下滚。 */
    public void injectMouseWheel(int x, int y, int modifiers, int scrollAmount, int rotation) {
        try {
            injectMouseWheel.invoke(browser, x, y, modifiers, scrollAmount, rotation);
        } catch (Throwable t) {}
    }

    /** modifiers 恒传 0（keyCode 在 MCEF 0.7 中无法表达）。 */
    public void injectKeyPressed(char c, int modifiers) {
        try {
            injectKeyPressed.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    public void injectKeyTyped(char c, int modifiers) {
        try {
            injectKeyTyped.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    public void injectKeyReleased(char c, int modifiers) {
        try {
            injectKeyReleased.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }
}
