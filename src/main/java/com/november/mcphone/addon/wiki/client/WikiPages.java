package com.november.mcphone.addon.wiki.client;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

import net.minecraft.util.StatCollector;

import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneButtonVariant;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;
import club.heiqi.uilib.ui.reactive.Signal;

import com.november.mcphone.addon.wiki.WikiAddon;
import com.november.mcphone.addon.wiki.core.WikiStore;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 维基管理页（页面型场景树）：
 * 站内搜索框 + 「搜索」、打开维基、书签列表（点直达/删）、历史（最近 20 条）。
 * 搜索不做任何 API 请求——直接拼站内搜索页 URL 交给 CEF（零 Java HTTP 红线）。
 * MCEF 缺失时顶部显示前置缺失横幅；打开动作仍可用（WikiScreen 内会显示错误）。
 *
 * <p>树一次性构建；变更（加/删书签等）经 {@link WikiApp#rebuildManagementPage}
 * 重建整页（与 MCphone 便签页同模式）。</p>
 */
@SideOnly(Side.CLIENT)
final class WikiPages {

    private static final int COL_TEXT = 0xFFE8EDF2;
    private static final int COL_MUTED = 0xFF8B98A8;
    private static final int COL_PANEL = 0x33FFFFFF;
    private static final int COL_WARN = 0xFFE8B84E;

    private WikiPages() {}

    static SceneNode create(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(tr("app.mcphone_wiki.wiki")));

        McefBridge.detect();
        if (!McefBridge.available()) {
            SceneNode banner = new SceneNode();
            banner.setText(tr("err.mcphone_wiki.missing_mcef"));
            banner.setTextColor(COL_WARN);
            banner.setFontSize(PhoneUi.fs(14));
            banner.setHitTestable(false);
            page.appendChild(banner);
        }

        // ============ 站内搜索行 ============
        Signal<String> searchValue = Signal.create("");

        SceneNode inputRow = SceneNode.row();
        inputRow.setFillParentWidth(true);
        inputRow.setCrossAxisAlign(CrossAxisAlign.CENTER);
        inputRow.setGap(8);

        // 输入框塞进 flexGrow 容器，避免和按钮互相挤压
        SceneNode inputBox = SceneNode.column();
        inputBox.setFlexGrow(1);
        ui.runtime()
            .mount(inputBox, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                searchValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                tr("label.mcphone_wiki.search"), 128,
                SceneInputType.TEXT, searchValue::set)))
            .getRoot()
            .setFillParentWidth(true);
        inputRow.appendChild(inputBox);

        SceneButton.Props searchProps = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_wiki.search")), Signal.create(Boolean.TRUE),
            () -> ui.post(() -> search(ui, searchValue.get())),
            SceneButtonVariant.PRIMARY);
        ui.runtime().mount(inputRow, SceneButton.create(ui.runtime(), searchProps)).getRoot();

        page.appendChild(inputRow);

        // ============ 打开维基 ============
        SceneNode homeRow = SceneNode.row();
        homeRow.setFillParentWidth(true);
        appendButton(ui, homeRow, tr("btn.mcphone_wiki.open"), () -> openUrl(ui, WikiAddon.WIKI_HOME));
        page.appendChild(homeRow);

        // ============ 书签 ============
        page.appendChild(sectionTitle(tr("label.mcphone_wiki.bookmarks")));

        List<WikiStore.Bookmark> marks = WikiStore.bookmarks();
        if (marks.isEmpty()) {
            page.appendChild(PhoneUi.muted(tr("msg.mcphone_wiki.no_bookmarks")));
        }
        for (int i = 0; i < marks.size(); i++) {
            final int idx = i;
            WikiStore.Bookmark b = marks.get(i);
            page.appendChild(listRow(ui, b.name, b.url, () -> openUrl(ui, b.url), () -> {
                WikiStore.removeBookmark(idx);
                WikiApp.rebuildManagementPage(ui);
            }));
        }

        // ============ 历史 ============
        page.appendChild(sectionTitle(tr("label.mcphone_wiki.history")));

        List<WikiStore.HistoryEntry> hist = WikiStore.history();
        if (hist.isEmpty()) {
            page.appendChild(PhoneUi.muted(tr("msg.mcphone_wiki.no_history")));
        }
        for (int i = 0; i < hist.size(); i++) {
            WikiStore.HistoryEntry h = hist.get(i);
            page.appendChild(historyRow(ui, WikiStore.pageTitle(h.url), () -> openUrl(ui, h.url)));
        }

        return page;
    }

    // ===================== 动作 =====================

    /** 站内搜索：拼 Special:Search 页面 URL（不做任何 API 请求）。 */
    private static void search(PhoneUi ui, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            ui.toast(tr("err.mcphone_wiki.no_keyword"));
            return;
        }
        String encoded;
        try {
            encoded = URLEncoder.encode(kw, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return; // UTF-8 恒存在，不会走到这里
        }
        openUrl(ui, WikiAddon.WIKI_SEARCH + encoded);
    }

    /** 从管理页打开页面：关闭手机 → 全屏维基屏。 */
    private static void openUrl(PhoneUi ui, String url) {
        if (url == null || url.trim().isEmpty()) {
            ui.toast(tr("err.mcphone_wiki.no_url"));
            return;
        }
        ui.closePhone();
        WikiScreen.open(url.trim());
    }

    // ===================== 构件 =====================

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /**
     * 自建滚动列（S0-5）：Qz 要求先 {@code setScrollable(true)}，再补漏掉的
     * {@link SceneScrolls#attach}——不 attach 则滚轮事件不会作用到本列
     * （书签+历史 20+ 条时管理页滚不动）。
     */
    private static SceneNode scrollColumn(PhoneUi ui) {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        col.setFlexGrow(1);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
        SceneScrolls.attach(ui.runtime(), col);
        return col;
    }

    private static SceneNode sectionTitle(String text) {
        SceneNode n = new SceneNode();
        n.setText(text);
        n.setTextColor(COL_MUTED);
        n.setFontSize(PhoneUi.fs(14));
        n.setHitTestable(false);
        return n;
    }

    /** 书签行：点名称直达 + 删除钮（行本身不挂点击，避免与删除钮冒泡冲突）。 */
    private static SceneNode listRow(PhoneUi ui, String name, String url, Runnable onOpen, Runnable onDelete) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        row.setPadding(6, 6, 6, 6);
        row.setCornerRadius(6);
        row.setBackgroundColor(COL_PANEL);

        SceneNode label = new SceneNode();
        label.setText(name);
        label.setTextColor(COL_TEXT);
        label.setFontSize(PhoneUi.fs(15));
        label.setMaxTextWidth(ui.panelWidth() - 150);
        row.appendChild(label);
        ui.runtime().on(label, SceneEventType.CLICK, (e, ctx) -> onOpen.run());

        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        row.appendChild(spacer);

        SceneButton.Props delProps = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_wiki.del")), Signal.create(Boolean.TRUE),
            () -> ui.post(onDelete));
        ui.runtime().mount(row, SceneButton.create(ui.runtime(), delProps)).getRoot();
        return row;
    }

    /** 历史行：点页面名直达。 */
    private static SceneNode historyRow(PhoneUi ui, String title, Runnable onOpen) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        row.setPadding(5, 6, 5, 6);
        row.setCornerRadius(6);
        row.setBackgroundColor(COL_PANEL);

        SceneNode label = new SceneNode();
        label.setText(title);
        label.setTextColor(COL_MUTED);
        label.setFontSize(PhoneUi.fs(13));
        label.setMaxTextWidth(ui.panelWidth() - 60);
        row.appendChild(label);
        ui.runtime().on(label, SceneEventType.CLICK, (e, ctx) -> onOpen.run());

        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        row.appendChild(spacer);
        return row;
    }

    private static void appendButton(PhoneUi ui, SceneNode parent, String label, Runnable onClick) {
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE), () -> ui.post(onClick));
        ui.runtime().mount(parent, SceneButton.create(ui.runtime(), props)).getRoot();
    }
}
