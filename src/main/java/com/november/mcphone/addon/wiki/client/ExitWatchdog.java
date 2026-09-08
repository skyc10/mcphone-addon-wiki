package com.november.mcphone.addon.wiki.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 退出看门狗：修复「点击退出游戏后进程不结束」。
 *
 * <p>根因一：MCEF 的 MCEF-Shutdown 线程（非守护）在 MC 停止后调用
 * CefApp.dispose()，真实 CEF 模式下消息泵已随主循环停止，dispose 永久阻塞，
 * JVM 因这个非守护线程无法退出（日志最后停在 "Shutting down JCEF..."）。</p>
 *
 * <p>根因二：FML 会把模组字节码里的 {@link Runtime#halt(int)} 调用重定向为
 * System.exit（走 shutdown hooks），任何阻塞的钩子（如 CraftPresence 的
 * Discord RPC）都会让退出永不完成。因此强制结束时改用<b>反射</b>调用真正的
 * halt——反射分发不经过本类字节码，FML 的重定向不生效，跳过 hooks 保证进程必死。</p>
 *
 * <p>方案：本守护线程监视 Minecraft.running；其变 false（游戏真正退出）后，
 * 先关闭我们打开的浏览器，宽限 5 秒。之后进入安全判定循环（上限 60 秒）：
 * 主线程消亡且仍有存活的 CEF/MCEF 家族非守护线程 → 强制 halt；主线程消亡且
 * 无 CEF 残留 → 交由 JVM 自然退出。宽限结束后主线程仍存活超过 60 秒 → dump
 * 主线程完整堆栈，然后<b>无条件</b>强制 halt（假设：世界保存 "Saving chunks"
 * 发生在 running=false 之前，此时保存早已完成；堆栈同时留作诊断证据）。
 * 全程关键步骤均打日志，绝不静默放弃。</p>
 */
@SideOnly(Side.CLIENT)
public final class ExitWatchdog {

    private static final long GRACE_MS = 5000;
    private static final long MAX_WAIT_MS = 60000;
    private static final long SCAN_LOG_INTERVAL_MS = 10000;
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
                System.out.println("[mcphone_wiki] ExitWatchdog: interrupted while polling running, disarmed");
                return;
            }
        }
        System.out.println("[mcphone_wiki] ExitWatchdog: running=false detected, game is exiting");
        // 游戏已开始退出：关掉我们自己打开的浏览器，给正常清理留宽限期
        try {
            System.out.println("[mcphone_wiki] ExitWatchdog: force-closing our wiki screens, grace period starting (" + GRACE_MS + " ms)");
            WikiScreen.forceClose();
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] ExitWatchdog: forceClose failed, continuing (" + t + ")");
        }
        try {
            Thread.sleep(GRACE_MS);
        } catch (InterruptedException e) {
            System.out.println("[mcphone_wiki] ExitWatchdog: interrupted during grace period, continuing to scan");
        }
        System.out.println("[mcphone_wiki] ExitWatchdog: grace period ended, entering scan loop (max " + (MAX_WAIT_MS / 1000) + " s)");
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        long nextScanLog = 0;
        while (System.currentTimeMillis() < deadline) {
            boolean mainAlive = isMainThreadAlive();
            String hung = hungCefThreads();
            long waited = MAX_WAIT_MS - Math.max(0, deadline - System.currentTimeMillis());
            if (waited >= nextScanLog) {
                System.out.println("[mcphone_wiki] ExitWatchdog: scan @" + (waited / 1000)
                    + "s: main thread alive=" + mainAlive
                    + ", hung CEF threads=" + (hung != null ? hung : "none"));
                nextScanLog += SCAN_LOG_INTERVAL_MS;
            }
            if (!mainAlive && hung != null) {
                // 主线程已结束（存档保存等全部完成），CEF 清理线程仍挂着 → 强制结束
                System.out.println("[mcphone_wiki] ExitWatchdog: CEF cleanup threads hung after main thread exit (" + hung + "), forcing halt");
                forceHalt();
                return;
            }
            if (!mainAlive && hung == null) {
                System.out.println("[mcphone_wiki] ExitWatchdog: main thread gone and no CEF threads remain, JVM should exit naturally");
                return;
            }
            // 主线程还在（退出保存进行中）→ 继续等，绝不打断
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                System.out.println("[mcphone_wiki] ExitWatchdog: interrupted during scan loop, continuing");
            }
        }
        // 60 秒兜底：主线程仍存活 → dump 其堆栈并无条件强制 halt；主线程已死
        // 且 CEF 线程仍在 → 同样强制 halt；两者都已退场 → 放弃干预并留日志。
        boolean mainAlive = isMainThreadAlive();
        String hung = hungCefThreads();
        System.out.println("[mcphone_wiki] ExitWatchdog: scan deadline (60s) reached: main thread alive=" + mainAlive
            + ", hung CEF threads=" + (hung != null ? hung : "none"));
        if (mainAlive) {
            // 世界保存("Saving chunks")按 1.7.10 退出流程发生在 running=false 之前，
            // 此处主线程卡死只可能是清理挂起；dump 堆栈留证后无条件强杀。
            System.out.println("[mcphone_wiki] ExitWatchdog: main thread still alive 60s after running=false —"
                + " assuming world save already finished (Saving chunks happens before running=false), dumping main thread stack then forcing halt");
            dumpMainThreadStack();
            forceHalt();
            return;
        }
        if (hung != null) {
            System.out.println("[mcphone_wiki] ExitWatchdog: giving up waiting, CEF threads still hung (" + hung + "), forcing halt");
            forceHalt();
            return;
        }
        System.out.println("[mcphone_wiki] ExitWatchdog: main thread gone and no CEF threads remain at deadline, standing down");
    }

    /**
     * 强制结束进程。绕过 FML 对 {@link Runtime#halt(int)} 的字节码重定向
     * （重定向会把 halt 变成 System.exit，从而被阻塞中的 shutdown hooks 卡死）：
     * 反射分发不经过本类字节码，FML 的转换器改不到它，真正 halt 直接杀进程。
     */
    private static void forceHalt() {
        System.out.println("[mcphone_wiki] ExitWatchdog: forcing halt(0) via reflection to bypass FML's Runtime.halt -> System.exit redirect");
        try {
            Method halt = Runtime.class.getMethod("halt", int.class);
            halt.invoke(Runtime.getRuntime(), 0);
            // 正常情况下上一行不会返回（进程已死）
            System.out.println("[mcphone_wiki] ExitWatchdog: reflective halt returned unexpectedly, process still alive");
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] ExitWatchdog: reflective halt failed (" + t + "), falling back to FMLCommonHandler.exitJava(0, false)");
            try {
                FMLCommonHandler.instance().exitJava(0, false);
            } catch (Throwable t2) {
                System.out.println("[mcphone_wiki] ExitWatchdog: FMLCommonHandler.exitJava failed (" + t2 + "), last resort: direct Runtime.halt (may be FML-redirected to System.exit)");
                Runtime.getRuntime().halt(0);
            }
        }
    }

    /** 把主线程（"Client thread"）完整堆栈 dump 到日志。 */
    private static void dumpMainThreadStack() {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (!MAIN_THREAD_NAME.equals(t.getName())) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("[mcphone_wiki] ExitWatchdog: stack of thread \"").append(t.getName())
                    .append("\" state=").append(t.getState()).append(':');
                StackTraceElement[] st = e.getValue();
                if (st == null || st.length == 0) {
                    sb.append(" (no stack)");
                } else {
                    for (StackTraceElement el : st) {
                        sb.append("\n\tat ").append(el);
                    }
                }
                System.out.println(sb);
                return;
            }
            System.out.println("[mcphone_wiki] ExitWatchdog: main thread \"" + MAIN_THREAD_NAME + "\" not found in stack dump");
        } catch (Throwable t) {
            System.out.println("[mcphone_wiki] ExitWatchdog: main thread stack dump failed (" + t + ")");
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
