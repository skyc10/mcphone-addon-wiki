package com.november.mcphone.addon.wiki.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.november.mcphone.addon.wiki.WikiAddon;

/**
 * 书签、历史与上次页面的客户端持久化（JSON）。
 *
 * <p>目录约定：.minecraft/mcphone/addons/wiki/（mcphone 未提供 appDataDir 时
 * 自建该目录，路径写死约定——见附属需求书）。</p>
 *
 * <p>全部在客户端 UI 线程调用；文件很小，同步 IO 可接受（与 MCphone 便签/相册一致）。
 * 显式 UTF-8 读写（Java 17 + zh-CN Windows 默认 GBK，中文书签名必须显式 UTF-8）。</p>
 */
public final class WikiStore {

    /** 书签：名称 + URL。 */
    public static final class Bookmark {

        public String name = "";
        public String url = "";
    }

    /** 历史条目：URL + 时间戳（毫秒）。 */
    public static final class HistoryEntry {

        public String url = "";
        public long ts;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HISTORY = 20;

    /** 默认预置书签：维基首页。 */
    public static final String DEFAULT_BOOKMARK_URL = WikiAddon.WIKI_HOME;
    public static final String DEFAULT_BOOKMARK_NAME = "GTNH 中文维基首页";

    private static List<Bookmark> bookmarks;
    private static List<HistoryEntry> history;
    private static String lastUrl;

    private WikiStore() {}

    private static File dir() {
        File d = new File(Minecraft.getMinecraft().mcDataDir, "mcphone/addons/wiki");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File bookmarksFile() {
        return new File(dir(), "bookmarks.json");
    }

    private static File settingsFile() {
        return new File(dir(), "settings.json");
    }

    private static File historyFile() {
        return new File(dir(), "history.json");
    }

    // ===================== 上次页面 =====================

    /** 上次浏览的页面（settings.json 的 lastUrl；null = 从未浏览）。 */
    public static synchronized String lastUrl() {
        if (lastUrl == null) {
            java.util.Map<?, ?> m = readSettings();
            if (m != null && m.get("lastUrl") instanceof String) {
                String s = (String) m.get("lastUrl");
                lastUrl = s.isEmpty() ? null : s;
            }
        }
        return lastUrl;
    }

    public static synchronized void setLastUrl(String url) {
        if (url == null || url.isEmpty()) return;
        lastUrl = url;
        saveSettings();
    }

    private static java.util.Map<?, ?> readSettings() {
        File f = settingsFile();
        if (!f.isFile()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, java.util.Map.class);
        } catch (Exception e) {
            System.err.println("[mcphone_wiki] Failed to load settings.json: " + e);
            return null;
        }
    }

    private static void saveSettings() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(settingsFile(), false), StandardCharsets.UTF_8)) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            java.util.Map<?, ?> old = readSettings();
            if (old != null) {
                for (java.util.Map.Entry<?, ?> e : old.entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof String) {
                        m.put((String) e.getKey(), (String) e.getValue());
                    }
                }
            }
            if (lastUrl != null) m.put("lastUrl", lastUrl);
            GSON.toJson(m, w);
        } catch (Exception e) {
            System.err.println("[mcphone_wiki] Failed to save settings.json: " + e);
        }
    }

    // ===================== 书签 =====================

    public static synchronized List<Bookmark> bookmarks() {
        if (bookmarks == null) {
            bookmarks = load(bookmarksFile(), new TypeToken<List<Bookmark>>() {});
            if (bookmarks == null) {
                // 仅在从未创建过书签文件时预置默认书签；玩家删光的空列表保持为空
                bookmarks = new ArrayList<>();
                Bookmark b = new Bookmark();
                b.name = DEFAULT_BOOKMARK_NAME;
                b.url = DEFAULT_BOOKMARK_URL;
                bookmarks.add(b);
                save(bookmarksFile(), bookmarks);
            }
        }
        return bookmarks;
    }

    public static synchronized void addBookmark(String url) {
        if (url == null || url.isEmpty()) return;
        List<Bookmark> list = bookmarks();
        for (Bookmark b : list) {
            if (url.equals(b.url)) return; // 去重
        }
        Bookmark b = new Bookmark();
        b.url = url;
        b.name = pageTitle(url);
        list.add(b);
        save(bookmarksFile(), list);
    }

    public static synchronized void removeBookmark(int index) {
        List<Bookmark> list = bookmarks();
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        save(bookmarksFile(), list);
    }

    // ===================== 历史 =====================

    public static synchronized List<HistoryEntry> history() {
        if (history == null) {
            history = load(historyFile(), new TypeToken<List<HistoryEntry>>() {});
            if (history == null) history = new ArrayList<>();
        }
        return history;
    }

    /** 新条目插到最前，超过 20 条裁剪；与最新一条相同则跳过（去连续重复）。 */
    public static synchronized void addHistory(String url) {
        if (url == null || url.isEmpty()) return;
        List<HistoryEntry> list = history();
        if (!list.isEmpty() && url.equals(list.get(0).url)) {
            return;
        }
        HistoryEntry e = new HistoryEntry();
        e.url = url;
        e.ts = System.currentTimeMillis();
        list.add(0, e);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        save(historyFile(), list);
    }

    // ===================== 底层 =====================

    private static <T> List<T> load(File f, TypeToken<List<T>> type) {
        if (!f.isFile()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type.getType());
        } catch (Exception e) {
            System.err.println("[mcphone_wiki] Failed to load " + f.getName() + ": " + e);
            return null;
        }
    }

    private static void save(File f, Object data) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            System.err.println("[mcphone_wiki] Failed to save " + f.getName() + ": " + e);
        }
    }

    /** 从维基 URL 提取页面名作书签名（/wiki/<URL编码页名>）；非维基页则退回主机名。 */
    public static String pageTitle(String url) {
        try {
            String marker = "/wiki/";
            int i = url.indexOf(marker);
            if (i >= 0) {
                String page = url.substring(i + marker.length());
                int q = page.indexOf('?');
                if (q >= 0) page = page.substring(0, q);
                int h = page.indexOf('#');
                if (h >= 0) page = page.substring(0, h);
                page = URLDecoder.decode(page, "UTF-8").replace('_', ' ').trim();
                if (!page.isEmpty()) return page;
            }
            String s = url;
            int c = s.indexOf("://");
            if (c >= 0) s = s.substring(c + 3);
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            return s.isEmpty() ? url : s;
        } catch (Throwable t) {
            return url;
        }
    }
}
