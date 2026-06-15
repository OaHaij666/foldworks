package com.pockethomestead.client;

import com.pockethomestead.PocketHomestead;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;

/**
 * 自定义字体管理器 - 为UI提供TTF字体引用
 */
public class CustomFontManager {
    private static final ResourceLocation SMOOTH_FONT = ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "smooth_ui");
    private static boolean initialized = false;

    /**
     * 标记字体系统已就绪（在资源包加载完成后）
     */
    public static void markReady() {
        initialized = true;
        PocketHomestead.LOGGER.info("自定义字体系统已就绪");
    }

    /**
     * 获取字体资源位置
     */
    public static ResourceLocation getFontLocation() {
        return SMOOTH_FONT;
    }

    /**
     * 检查自定义字体是否可用
     */
    public static boolean isCustomFontAvailable() {
        return initialized;
    }

    /**
     * 获取默认游戏字体
     */
    public static Font getDefaultFont() {
        return Minecraft.getInstance().font;
    }
}
