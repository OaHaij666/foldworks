package com.foldworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 维度和群系名称本地化工具
 */
public final class LocalizationUtil {
    private LocalizationUtil() {}

    /**
     * 获取维度的本地化名称
     * @param dimensionId 维度ID (例如 "minecraft:overworld")
     * @return 本地化名称，如果找不到翻译则返回美化后的ID
     */
    public static String localizeDimension(String dimensionId) {
        if (dimensionId.equals("random")) {
            return Component.translatable("foldworks.space.dimension.random").getString();
        }

        try {
            ResourceLocation loc = ResourceLocation.parse(dimensionId);

            // 尝试多种翻译键格式
            String[] translationKeys = {
                "dimension." + loc.getNamespace() + "." + loc.getPath(),
                "dim." + loc.getNamespace() + "." + loc.getPath(),
                "world." + loc.getNamespace() + "." + loc.getPath()
            };

            for (String key : translationKeys) {
                String translated = tryTranslate(key);
                if (!translated.equals(key)) {
                    return translated; // 找到翻译
                }
            }

            // 没有找到翻译，返回美化后的ID
            return prettyId(loc);
        } catch (Exception e) {
            return dimensionId; // 解析失败，返回原始ID
        }
    }

    /**
     * 获取群系的本地化名称
     * @param biomeId 群系ID (例如 "minecraft:plains")
     * @return 本地化名称，如果找不到翻译则返回美化后的ID
     */
    public static String localizeBiome(String biomeId) {
        if (biomeId.equals("random")) {
            return Component.translatable("foldworks.space.biome.random").getString();
        }

        try {
            ResourceLocation loc = ResourceLocation.parse(biomeId);

            // 尝试多种翻译键格式
            String[] translationKeys = {
                "biome." + loc.getNamespace() + "." + loc.getPath(),
                "biome." + loc.getNamespace() + "." + loc.getPath().replace("/", "_"),
                "block." + loc.getNamespace() + "." + loc.getPath() // 有些群系用block键
            };

            for (String key : translationKeys) {
                String translated = tryTranslate(key);
                if (!translated.equals(key)) {
                    return translated; // 找到翻译
                }
            }

            // 没有找到翻译，返回美化后的ID
            return prettyId(loc);
        } catch (Exception e) {
            return biomeId; // 解析失败，返回原始ID
        }
    }

    /**
     * 尝试翻译一个键，如果找不到则返回键本身
     */
    private static String tryTranslate(String key) {
        try {
            String translated = Component.translatable(key).getString();
            // 如果翻译后和键一样，说明没有找到翻译
            return translated.equals(key) ? key : translated;
        } catch (Exception e) {
            return key;
        }
    }

    /**
     * 美化ID（移除下划线，大写首字母）
     */
    private static String prettyId(ResourceLocation loc) {
        String[] parts = loc.getPath().replace('_', ' ').replace('/', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)))
                  .append(p.substring(1))
                  .append(' ');
            }
        }
        return sb.toString().trim();
    }
}
