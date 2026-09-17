package com.november.mcphone.addon.wiki.client;

import java.io.File;
import java.io.FileOutputStream;
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
 * <p>根因：MCEF 的 MCEF-Shutdown 线程（非守护）在 MC 停止后调用
 * CefApp.dispose()，真实 CEF 模式下消息泵已随主循环停止，dispose 永久阻塞，
 * JVM 因这个非守护线程无法退出（日志最后停在 "Shutting down JCEF..."）。
 * FML 还会把模组字节码里的 {@link Runtime#halt(int)} 重定向为 System.exit
 * （走 shutdown hooks），任何阻塞的钩子都会让退出永不完成——因此强制结束时
 * 用<b>反射</b>调用真正的 halt，反射分发不经过本类字节码，FML 改不到它。</p>
 *
 * <p><b>S0-7 门控</b>：wiki-local 静态 volatile {@link #browserCreated}，
 * {@link McefBridge#create} 成功时置位。本会话从未创建过浏览器时直接收工，
 * 不再走 5 s 宽限 + 90 s kill 时间线（中期由宿主 C9 的
 * registerShutdownCleanup 归口后取代本类）。</p>
 *
 * <p>时序（有浏览器时）：running=false → 异步关掉我们打开的浏览器（close 的
 * native 调用会阻塞，同步执行会把看门狗自己卡死）→ 宽限 5 秒 → 扫描循环：</p>
 * <ul>
 * <li>主线程消亡且无 CEF 残留线程 → 交由 JVM 自然退出；</li>
 * <li>主线程消亡但有存活的 CEF/MCEF 非守护线程 → 反射 halt；</li>
 * <li><b>无条件 kill timer：running=false 后 90 秒</b>，无论主线程死活、无论
 *     上面判定如何，dump 全部非守护线程堆栈（留诊断证据）后强制 halt。
 *     世界保存 "Saving chunks" 发生在 running=false 之前，90 秒足够覆盖一切
 *     正常清理；宁可错杀也不留僵尸进程（用户首要痛点）。</li>
 * </ul>
 *
 * <p>日志策略：arm() 起双写 System.out + 实例 logs/mcphone_wiki_watchdog.log；
 * 检测到 running=false 后<b>只写文件</b>——退出阶段 log4j 已停、控制台管道状态
 * 不可控，println 可能阻塞或丢字，文件才是唯一可信通道。</p>
 */
@SideOnly(Side.CLIENT)
public final class ExitWatchdog {

    private static final long GRACE_MS = 5000;
    private static final long KILL_TIMER_MS = 90000;
    private static final long SCAN_LOG_INTERVAL_MS = 10000;
    private static final String MAIN_THREAD_NAME = "Client thread";

    /**
     * 本会话是否成功创建过维基浏览器（S0-7 门控信号源）。
     * McefBridge.create 成功时置位；从未创建 → 看门狗直接收工。
     */
    public static volatile boolean browserCreated;

    private static File logFile;
    private static volatile boolean fileOnly;

    private ExitWatchdog() {}

    public static void arm() {
        try {
            File logs = new File(Minecraft.getMinecraft().mcDataDir, "logs");
            logs.mkdirs();
            logFile = new File(logs, "mcphone_wiki_watchdog.log");
        } catch (Throwable t) {
            logFile = null;
        }
        log("ExitWatchdog armed; file log: "
            + (logFile != null ? logFile.getAbsolutePath() : "<unavailable>")
            + "; browserCreated=" + browserCreated);
        Thread t = new Thread(ExitWatchdog::watch, "mcphone_wiki-ExitWatchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 关键步骤打日志：退出阶段只写文件，绝不因 println 阻塞看门狗。 */
    private static void log(String msg) {
        String line = "[mcphone_wiki] ExitWatchdog: " + msg;
        if (!fileOnly) {
            System.out.println(line);
        }
        if (logFile != null) {
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) {}
        }
    }

    private static void watch() {
        Field running;
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
            running = findRunningField();
        } catch (Throwable t) {
            log("init failed, disarmed (" + t + ")");
            return;
        }
        if (running == null) {
            log("Minecraft.running field not found, disarmed");
            return;
        }
        log("watching field Minecraft." + running.getName());
        while (true) {
            try {
                // 对静态/实例字段都成立（与 MCEF 同款读法）
                if (!running.getBoolean(mc)) break;
            } catch (Throwable t) {
                log("read failed, disarmed (" + t + ")");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                log("interrupted while polling running, disarmed");
                return;
            }
        }
        fileOnly = true;
        log("running=false detected, game is exiting (switching to file-only logging)");

        // S0-7 门控：从未创建过维基浏览器 → 本进程不可能有我们留下的 CEF 阻塞，
        // 直接收工，不再走 5 s 宽限 + 90 s kill 时间线。
        if (!browserCreated) {
            log("no wiki browser was ever created this session — disarming directly"
                + " (no grace period, no kill timer)");
            return;
        }

        // 异步关闭我们打开的浏览器（b.close() 的 native 调用在消息泵死后会
        // 永久阻塞，不能占用看门狗线程），随后给正常清理留宽限期
        try {
            log("force-closing our browser screens (async), grace period " + GRACE_MS + " ms");
            WikiScreen.forceClose();
        } catch (Throwable t) {
            log("forceClose failed, continuing (" + t + ")");
        }
        try {
            Thread.sleep(GRACE_MS);
        } catch (InterruptedException e) {
            log("interrupted during grace period, continuing to scan");
        }

        long exitAt = System.currentTimeMillis();
        long killDeadline = exitAt + KILL_TIMER_MS;
        long nextScanLog = 0;
        while (true) {
            long now = System.currentTimeMillis();
            boolean mainAlive = isMainThreadAlive();
            String hung = hungCefThreads();
            long waited = now - exitAt;
            if (waited >= nextScanLog) {
                log("scan @" + (waited / 1000) + "s: main thread alive=" + mainAlive
                    + ", hung CEF threads=" + (hung != null ? hung : "none"));
                nextScanLog += SCAN_LOG_INTERVAL_MS;
            }
            if (!mainAlive && hung == null) {
                log("main thread gone and no CEF threads remain, JVM should exit naturally");
                return;
            }
            if (!mainAlive) {
                // 主线程已结束（存档保存等全部完成），CEF 清理线程仍挂着 → 强制结束
                log("CEF cleanup threads hung after main thread exit (" + hung + "), forcing halt");
                forceHalt();
                return;
            }
            if (now >= killDeadline) {
                // 世界保存按 1.7.10 退出流程发生在 running=false 之前，此时主线程
                // 90 秒还没死只可能是清理挂起；dump 全部非守护线程堆栈留证后无条件强杀
                log("KILL TIMER (" + (KILL_TIMER_MS / 1000) + "s) expired with main thread alive —"
                    + " assuming world save already finished, dumping non-daemon stacks then forcing halt unconditionally");
                dumpAllNonDaemonStacks();
                forceHalt();
                return;
            }
            // 主线程还在（退出保存/清理进行中）→ 继续等
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log("interrupted during scan loop, continuing");
            }
        }
    }

    /**
     * 强制结束进程。绕过 FML 对 {@link Runtime#halt(int)} 的字节码重定向
     * （重定向会把 halt 变成 System.exit，从而被阻塞中的 shutdown hooks 卡死）：
     * 反射分发不经过本类字节码，FML 的转换器改不到它，真正 halt 直接杀进程。
     */
    private static void forceHalt() {
        log("forcing halt(0) via reflection to bypass FML's Runtime.halt -> System.exit redirect");
        try {
            Method halt = Runtime.class.getMethod("halt", int.class);
            halt.invoke(Runtime.getRuntime(), 0);
            // 正常情况下上一行不会返回（进程已死）
            log("reflective halt returned unexpectedly, process still alive");
        } catch (Throwable t) {
            log("reflective halt failed (" + t + "), falling back to FMLCommonHandler.exitJava(0, false)");
            try {
                FMLCommonHandler.instance().exitJava(0, false);
            } catch (Throwable t2) {
                log("FMLCommonHandler.exitJava failed (" + t2 + "), last resort: direct Runtime.halt (may be FML-redirected to System.exit)");
                Runtime.getRuntime().halt(0);
            }
        }
    }

    /** 把所有存活的非守护线程堆栈 dump 到日志（kill timer 触发时的诊断证据）。 */
    private static void dumpAllNonDaemonStacks() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("non-daemon thread stacks at kill time:");
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (!t.isAlive() || t.isDaemon()) {
                    continue;
                }
                sb.append("\n\"").append(t.getName()).append("\" state=").append(t.getState());
                StackTraceElement[] st = e.getValue();
                if (st == null || st.length == 0) {
                    sb.append("\n\t(no stack)");
                } else {
                    for (StackTraceElement el : st) {
                        sb.append("\n\tat ").append(el);
                    }
                }
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("non-daemon stack dump failed (" + t + ")");
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
