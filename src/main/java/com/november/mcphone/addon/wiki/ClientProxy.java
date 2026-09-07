package com.november.mcphone.addon.wiki;

import com.november.mcphone.addon.wiki.client.WikiApp;
import com.november.mcphone.addon.wiki.client.ExitWatchdog;

import com.november.mcphone.api.PhoneApi;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端代理：向 MCphone 注册「维基」App，并启动退出看门狗。
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        try {
            WikiApp app = new WikiApp();
            if (!PhoneApi.register(app)) {
                System.out.println("[mcphone_wiki] PhoneApi.register: id '" + app.id()
                    + "' already registered (services auto-discovery)");
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] Failed to register WikiApp: " + t);
        }
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        // 退出看门狗：MCEF CefApp.dispose 挂死时强制结束进程（守护线程，正常退出不会触发）
        try {
            ExitWatchdog.arm();
        } catch (Throwable t) {
            System.err.println("[mcphone_wiki] ExitWatchdog arm failed: " + t);
        }
    }
}
