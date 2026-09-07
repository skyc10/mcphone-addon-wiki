package com.november.mcphone.addon.wiki;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * MCphone 附属：GTNH 中文维基 App（mcphone_wiki）。
 *
 * <p>点击手机上的「维基」图标直接打开全屏虚拟界面，由 MCEF/CEF 离屏渲染真实
 * 网页，只服务 gtnh.huijiwiki.com（无 URL 栏，不显示其它任何内容）；加载上次
 * 页面或维基首页。Shift+点击打开管理页（站内搜索、书签、历史）。</p>
 *
 * <p>前置：<b>MCphone</b>（必需）、<b>MCEF 0.7</b> 真实浏览器模式（必需，
 * WebDisplays 不是本附属的前置）。MCEF 缺失或处于虚拟模式时显示错误页，绝不崩溃。</p>
 *
 * <p>技术红线：零 Java HTTP（页面全部交给 CEF）；本附属全程 try-catch，
 * 任何初始化失败只打日志，绝不拖垮手机本体与游戏。</p>
 */
@Mod(
    modid = WikiAddon.MODID,
    name = "MCphone Addon - GTNH Wiki",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:mcphone;after:MCEF")
public class WikiAddon {

    public static final String MODID = "mcphone_wiki";

    /** 手机 NBT 键前缀（技术红线：只写自己的键）。 */
    public static final String NBT_LAST_URL = "mcphone:wiki:lastUrl";

    /** 唯一服务站点：GTNH 中文维基。 */
    public static final String WIKI_HOME = "https://gtnh.huijiwiki.com/wiki/%E9%A6%96%E9%A1%B5";

    /** 站内搜索页（不做任何 API 请求，直接拼搜索页 URL 交给 CEF）。 */
    public static final String WIKI_SEARCH = "https://gtnh.huijiwiki.com/wiki/Special:Search?search=";

    @Mod.Instance(MODID)
    public static WikiAddon INSTANCE;

    @SidedProxy(
        clientSide = "com.november.mcphone.addon.wiki.ClientProxy",
        serverSide = "com.november.mcphone.addon.wiki.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            proxy.preInit(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] preInit failed (addon disabled, game continues): " + t);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        try {
            proxy.init(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] init failed (addon disabled, game continues): " + t);
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        try {
            proxy.postInit(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] postInit failed (addon disabled, game continues): " + t);
        }
    }
}
