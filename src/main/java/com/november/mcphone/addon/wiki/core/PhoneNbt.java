package com.november.mcphone.addon.wiki.core;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.november.mcphone.addon.wiki.WikiAddon;

/**
 * 手机 ItemStack NBT 读取（客户端，从同步过来的物品副本读）。
 * 键名 {@code mcphone:wiki:lastUrl}——技术红线：只写自己的键前缀。
 */
public final class PhoneNbt {

    private PhoneNbt() {}

    /** 读取上次维基页面（未绑定时返回 null，调用方回退到 WikiStore.lastUrl()）。 */
    public static String readLastUrl(ItemStack phone) {
        if (phone == null || !phone.hasTagCompound()) return null;
        NBTTagCompound root = phone.getTagCompound();
        if (!root.hasKey(WikiAddon.NBT_LAST_URL)) return null;
        String url = root.getString(WikiAddon.NBT_LAST_URL);
        return url == null || url.isEmpty() ? null : url;
    }
}
