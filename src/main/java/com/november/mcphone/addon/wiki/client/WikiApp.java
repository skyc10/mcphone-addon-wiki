package com.november.mcphone.addon.wiki.client;

import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.wiki.WikiAddon;
import com.november.mcphone.addon.wiki.core.PhoneNbt;
import com.november.mcphone.addon.wiki.core.WikiStore;
import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.client.scene.PhoneUi;

import club.heiqi.uilib.ui.scene.node.SceneNode;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「维基」App：游戏内查 GTNH 中文维基（gtnh.huijiwiki.com），无 URL 栏。
 *
 * <p><b>点击</b>：直接打开全屏虚拟维基屏（16:9、约 80% 游戏窗口），加载上次
 * 页面（手机 NBT → 本地记录）或维基首页，不放置任何方块。</p>
 *
 * <p><b>Shift+点击</b>：打开管理页（页面型：站内搜索、书签、历史、打开维基）。</p>
 *
 * <p>实现说明：直达型 App 的 Shift+点击进入 {@link #onActivate(PhoneUi, boolean)}
 * （shift=true）。打开管理页需要 {@link PhoneUi#openApp(String)}，而它会拒绝直达型
 * App——这里用「临时翻转变量为 false」的窗口期让 openApp 正常建页（全程在
 * PhoneUi.post 的单线程回调内，无竞态）。</p>
 */
@SideOnly(Side.CLIENT)
public class WikiApp implements IPhoneApp {

    private static WikiApp instance;

    /**
     * 正在以「页面型」身份打开管理页（isDirectAction 短暂返回 false）。
     * 必须是 static：services 自动发现与手动注册可能产生两个实例，
     * 而 PhoneUi.openApp 按查表到的实例判断 isDirectAction——实例级标志会失效。
     */
    private static boolean openingPage;

    public WikiApp() {
        instance = this;
    }

    @Override
    public String id() {
        return "wiki";
    }

    @Override
    public String displayName() {
        return StatCollector.translateToLocal("app.mcphone_wiki.wiki");
    }

    @Override
    public int iconColor() {
        return 0xFF2E8B74;
    }

    @Override
    public String iconGlyph() {
        return "维";
    }

    @Override
    public String iconTexture() {
        return "mcphone_wiki:textures/ui/app_wiki.png";
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    @Override
    public boolean isDirectAction() {
        return !openingPage;
    }

    @Override
    public void onActivate(PhoneUi ui, boolean shift) {
        if (shift) {
            openManagementPage(ui);
            return;
        }
        // 直达：打开全屏维基屏。页面优先级：手机 NBT → 本地 lastUrl → 维基首页
        String url = PhoneNbt.readLastUrl(ui.phoneStack());
        if (url == null) {
            url = WikiStore.lastUrl();
        }
        if (url == null) {
            url = WikiAddon.WIKI_HOME;
        }
        WikiScreen.open(url);
    }

    @Override
    public SceneNode createPage(PhoneUi ui) {
        return WikiPages.create(ui);
    }

    /** 打开（或重建）管理页；供本 App 与 WikiPages 在列表变更后刷新。 */
    static void openManagementPage(PhoneUi ui) {
        openingPage = true;
        try {
            ui.openApp("wiki");
        } finally {
            openingPage = false;
        }
    }

    /** 在管理页状态下重建页面（书签/历史增删后调用）。 */
    static void rebuildManagementPage(PhoneUi ui) {
        openingPage = true;
        try {
            ui.rebuildPage();
        } finally {
            openingPage = false;
        }
    }
}
