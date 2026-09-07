package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCEF 反射桥（维基虚拟屏的唯一后端；不依赖 WebDisplays 方块）。
 *
 * <p>通过 net.montoyo.mcef.api.MCEFApi.getAPI() 拿到 API 实现者（MCEF.PROXY）。
 * createBrowser(String) 优先走 mcphone-addon-browser 给 MCEF 0.7 打的 default
 * 方法桥接（→ createBrowser(url, false)）；对未打补丁的原版 MCEF 0.7 自动回退到
 * 双参 createBrowser(String, boolean)。</p>
 */
@SideOnly(Side.CLIENT)
public final class McefBridge {

    private static boolean detected;
    private static Object api;            // net.montoyo.mcef.api.API 实例
    private static Method mCreate;        // createBrowser(String) 或 createBrowser(String, boolean)
    private static boolean createTwoArg;  // true = 原版双参签名
    private static Method mIsVirtual;     // isVirtual()（可缺省）
    private static String failReason = "";

    private McefBridge() {}

    public static synchronized void detect() {
        if (detected) return;
        detected = true;
        try {
            ClassLoader cl = McefBridge.class.getClassLoader();
            Class<?> mcefApi = cl.loadClass("net.montoyo.mcef.api.MCEFApi");
            Method get = mcefApi.getMethod("getAPI");
            api = get.invoke(null);
            if (api == null) {
                failReason = "MCEF PROXY is null (MCEF not initialized)";
                return;
            }
            try {
                mCreate = api.getClass().getMethod("createBrowser", String.class);
                createTwoArg = false;
            } catch (NoSuchMethodException e) {
                // 未打补丁的原版 MCEF 0.7：createBrowser(String, boolean)
                mCreate = api.getClass().getMethod("createBrowser", String.class, boolean.class);
                createTwoArg = true;
            }
            try {
                mIsVirtual = api.getClass().getMethod("isVirtual");
            } catch (Throwable t) {
                mIsVirtual = null; // 缺该方法时按「非虚拟」处理
            }
            System.out.println("[mcphone_wiki] MCEF bridge ready (createBrowser "
                + (createTwoArg ? "String,boolean" : "String") + ")");
        } catch (Throwable t) {
            api = null;
            failReason = String.valueOf(t);
            System.out.println("[mcphone_wiki] MCEF bridge unavailable: " + t);
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

    public static String failReason() {
        return failReason;
    }

    /** 创建浏览器；失败返回 null。 */
    public static WikiHandle create(String url) {
        detect();
        if (api == null) return null;
        try {
            Object b = createTwoArg ? mCreate.invoke(api, url, Boolean.FALSE) : mCreate.invoke(api, url);
            return b == null ? null : new WikiHandle(b);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] createBrowser failed: " + t);
            return null;
        }
    }
}
