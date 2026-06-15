package com.pockethomestead.client;

import net.minecraft.client.Minecraft;

/** 客户端界面入口（由物品通过反射调用，避免服务端类加载客户端代码）。 */
public final class ClientScreenHooks {
    private ClientScreenHooks() {
    }

    /** 打开主界面（默认上次所在页 / 管理页）。 */
    public static void openHomestead() {
        Minecraft.getInstance().setScreen(new HomesteadScreen());
    }

    /** 打开主界面并定位到指定页。 */
    public static void openHomestead(String pageId) {
        Minecraft.getInstance().setScreen(new HomesteadScreen(pageId));
    }
}
