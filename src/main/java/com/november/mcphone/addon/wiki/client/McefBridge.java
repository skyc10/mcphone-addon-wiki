package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCEF 反射桥（维基虚拟屏的唯一后端；不依赖 WebDisplays 方块）。
 *
 * <p>通过 net.montoyo.mcef.api.MCEFApi.getAPI() 拿到 API 实现者（MCEF.PROXY）。
 * createBrowser(String) 优先走 modern 内核的 default 方法桥接
 * （→ createBrowser(url, false)）；无该签名时回退到
 * 双参 createBrowser(String, boolean)。</p>
 *
 * <p><b>S5-2 三态探测</b>：不再「一锤定音」缓存 detect() 结果，区分三态：
 * <ul>
 * <li>{@code AVAILABLE}——确认可用（reflect 结果缓存不再变化）；</li>
 * <li>{@code PENDING}——provider 存在但 PROXY 尚未初始化（如用户先开维基、
 *     内核还没被 browser 拉起）：<b>不缓存结论</b>，available()/pending() 每次
 *     调用（即玩家每次打开维基）都重试，一一拳定会「先开维基整局不可恢复」
 *     （A8 W-03）；</li>
 * <li>{@code UNAVAILABLE}——MCEFApi 都加载不到 = 内核 provider 确认缺失：
 *     缓存该结论（免得每帧空跑反射），failReason 供错误页文案。</li>
 * </ul>
 * S5-4（wiki 反射自启 CEF）明确<b>不做</b>——留给 B1 共用门面统一实现。</p>
 */
@SideOnly(Side.CLIENT)
public final class McefBridge {

    private enum State { PENDING, AVAILABLE, UNAVAILABLE }

    private static State state = State.PENDING;
    private static State lastLogged;      // 只在状态迁移时打日志（防每帧刷屏）
    private static Object api;            // net.montoyo.mcef.api.API 实例
    private static Method mCreate;        // createBrowser(String) 或 createBrowser(String, boolean)
    private static boolean createTwoArg;  // true = 双参签名
    private static Method mIsVirtual;     // isVirtual()（可缺省）
    private static String failReason = "";

    private McefBridge() {}

    /**
     * CefApp 句柄（帧泵用）：反射 {@code getCefApp()}，Method 缓存
     * （drawScreen 每帧调用，不能反复查表）。拿不到返回 null（泵帧降级为
     * 只调 mcefUpdate）。
     */
    private static Method mGetCefApp;

    public static Object cefAppHandle() {
        if (api == null) {
            return null;
        }
        try {
            if (mGetCefApp == null) {
                mGetCefApp = api.getClass().getMethod("getCefApp");
            }
            return mGetCefApp.invoke(api);
        } catch (Throwable t) {
            return null;
        }
    }

    public static synchronized void detect() {
        // UNAVAILABLE = provider 确认缺失，缓存结论不再重试
        // （PENDING 则继续重试——这正是三态探测要救的「先开维基」场景）
        if (state == State.AVAILABLE || state == State.UNAVAILABLE) {
            return;
        }
        try {
            ClassLoader cl = McefBridge.class.getClassLoader();
            Class<?> mcefApi = cl.loadClass("net.montoyo.mcef.api.MCEFApi");
            Method get = mcefApi.getMethod("getAPI");
            api = get.invoke(null);
            if (api == null) {
                // PROXY 未初始化 → pending，不缓存结论，下次打开重试
                setState(State.PENDING, "MCEF PROXY is null (MCEF not initialized)");
                return;
            }
            try {
                mCreate = api.getClass().getMethod("createBrowser", String.class);
                createTwoArg = false;
            } catch (NoSuchMethodException e) {
                // 未带桥接的 MCEF：createBrowser(String, boolean)
                mCreate = api.getClass().getMethod("createBrowser", String.class, boolean.class);
                createTwoArg = true;
            }
            try {
                mIsVirtual = api.getClass().getMethod("isVirtual");
            } catch (Throwable t) {
                mIsVirtual = null; // 缺该方法时按「非虚拟」处理
            }
            failReason = "";
            setState(State.AVAILABLE, "MCEF bridge ready (createBrowser "
                + (createTwoArg ? "String,boolean" : "String") + ")");
        } catch (Throwable t) {
            // MCEFApi 加载/反射失败 = 内核 provider 缺失：确认不可用（缓存结论）
            api = null;
            setState(State.UNAVAILABLE, String.valueOf(t));
        }
    }

    /** 状态迁移时打一行日志（同一状态沉默，防 drawScreen 每帧刷log）。 */
    private static void setState(State s, String reason) {
        failReason = reason;
        if (lastLogged != s) {
            lastLogged = s;
            String prefix = "[mcphone_wiki] MCEF bridge "
                + (s == State.AVAILABLE ? "ready" : s == State.PENDING ? "pending" : "unavailable");
            System.out.println(prefix + ": " + reason);
        }
    }

    /** MCEF 可用且非虚拟模式。 */
    public static boolean available() {
        detect();
        if (api == null) return false;
        if (mIsVirtual == null) return true;
        try {
            return !Boolean.TRUE.equals(mIsVirtual.invoke(api));
        } catch (Throwable t) {
            return true;
        }
    }

    /** PENDING（内核初始化中，尚未完成）：调用方可显示「先开一次浏览器」提示。 */
    public static boolean pending() {
        detect();
        return state == State.PENDING;
    }

    public static String failReason() {
        return failReason;
    }

    /** 创建浏览器；失败返回 null。 */
    public static WikiHandle create(String url) {
        detect();
        if (api == null) return null;
        try {
            Object b = createTwoArg ? mCreate.invoke(api, url, Boolean.FALSE) : mCreate.invoke(api, url);
            if (b == null) {
                return null;
            }
            // S0-7 看门狗门控：本会话成功创建过浏览器（静态 volatile，
            // ExitWatchdog 从未创建时直接收工，不走 5s 宽限 + 90s kill 时间线）
            ExitWatchdog.browserCreated = true;
            return new WikiHandle(b);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] createBrowser failed: " + t);
            return null;
        }
    }
}
