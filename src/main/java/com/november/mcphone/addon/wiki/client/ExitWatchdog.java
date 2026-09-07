package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 退出看门狗：修复「点击退出游戏后进程不结束」。
 *
 * <p>根因：MCEF 的 MCEF-Shutdown 线程（非守护）在 MC 停止后调用
 * CefApp.dispose()，真实 CEF 模式下消息泵已随主循环停止，dispose 永久阻塞，
 * JVM 因这个非守护线程无法退出（日志最后停在 "Shutting down JCEF..."）。</p>
 *
 * <p>方案：本守护线程监视 Minecraft.running；其变 false（游戏真正退出）后，
 * 先关闭我们打开的浏览器，宽限 5 秒。之后进入安全判定循环（上限 60 秒）：
 * <b>只有「Client thread」主线程已消亡、且仍存在存活的 CEF/MCEF 家族非守护线程</b>
 * 时才 {@link Runtime#halt(int)}——主线程还在意味着退出保存尚未完成，绝不打断，
 * 避免砍断世界保存造成存档损坏。若主线程死后 CEF 线程也已退场，则交由 JVM 自然退出。</p>
 */
@SideOnly(Side.CLIENT)
public final class ExitWatchdog {

    private static final long GRACE_MS = 5000;
    private static final long MAX_WAIT_MS = 60000;
    private static final String MAIN_THREAD_NAME = "Client thread";

    private ExitWatchdog() {}

    public static void arm() {
        Thread t = new Thread(ExitWatchdog::watch, "mcphone_wiki-ExitWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void watch() {
        Field running;
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
            running = findRunningField();
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] ExitWatchdog: init failed, disarmed (" + t + ")");
            return;
        }
        if (running == null) {
            System.out.println("[mcphone_wiki] ExitWatchdog: Minecraft.running field not found, disarmed");
            return;
        }
        System.out.println("[mcphone_wiki] ExitWatchdog: watching field Minecraft." + running.getName());
        while (true) {
            try {
                // 对静态/实例字段都成立（与 MCEF 同款读法）
                if (!running.getBoolean(mc)) break;
            } catch (Throwable t) {
                System.out.println("[mcphone_wiki] ExitWatchdog: read failed, disarmed (" + t + ")");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        // 游戏已开始退出：关掉我们自己打开的浏览器，给正常清理留宽限期
        try {
            WikiScreen.forceClose();
        } catch (Throwable ignored) {}
        try {
            Thread.sleep(GRACE_MS);
        } catch (InterruptedException e) {
            return;
        }
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean mainAlive = isMainThreadAlive();
            String hung = hungCefThreads();
            if (!mainAlive && hung != null) {
                // 主线程已结束（存档保存等全部完成），CEF 清理线程仍挂着 → 强制结束
                System.out.println("[mcphone_wiki] ExitWatchdog: CEF cleanup threads hung after main thread exit (" + hung + "), forcing halt");
                Runtime.getRuntime().halt(0);
                return;
            }
            if (!mainAlive && hung == null) {
                return; // 主线程已结束且无 CEF 残留 → JVM 即将自然退出
            }
            // 主线程还在（退出保存进行中）→ 继续等，绝不打断
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        // 60 秒兜底：若 CEF 线程仍在且主线程已死，强制结束；否则放弃干预
        String hung = hungCefThreads();
        if (hung != null && !isMainThreadAlive()) {
            System.out.println("[mcphone_wiki] ExitWatchdog: giving up waiting, CEF threads still hung (" + hung + "), forcing halt");
            Runtime.getRuntime().halt(0);
        }
    }

    /** 主线程（"Client thread"）是否仍存活。 */
    private static boolean isMainThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (MAIN_THREAD_NAME.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** 返回仍存活的 CEF/MCEF 家族非守护线程描述；无则 null。 */
    private static String hungCefThreads() {
        StringBuilder hung = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String n = t.getName();
            if (t.isAlive() && !t.isDaemon()
                && (n.contains("MCEF") || n.toLowerCase().contains("cef") || n.toLowerCase().contains("jcef"))) {
                if (hung.length() > 0) hung.append(", ");
                hung.append(n);
                StackTraceElement[] st = e.getValue();
                if (st != null && st.length > 0) {
                    hung.append(" at ").append(st[0]);
                }
            }
        }
        return hung.length() > 0 ? hung.toString() : null;
    }

    /** 与 MCEF 同款探测：Minecraft 里 volatile boolean 字段即 running（1.7.10）。 */
    private static Field findRunningField() {
        try {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType() == boolean.class && (f.getModifiers() & Modifier.VOLATILE) != 0) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
