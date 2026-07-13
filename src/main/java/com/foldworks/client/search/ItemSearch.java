package com.foldworks.client.search;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * “物品搜索”的统一入口：把 “匹配物品显示名（走拼音） + 物品 ID（普通包含）” 的逻辑收敛到一处，
 * 这样所有搜索框（袋内物品、合成目标搜索、传输图节点过滤搜索…）只需调用本类，即可：
 * <ul>
 *   <li>玩家装了 JEC 时自动获得拼音搜索能力；</li>
 *   <li>没装 JEC 时行为退化成原来的 {@code String.contains}。</li>
 * </ul>
 *
 * <p>实现细节：物品显示名里可能含中文，借此走 {@link SearchSupport#contains};而物品 ID（如
 * {@codeminecraft:dirt}）属英文/路径信息，拼音无意义，统一保持普通小写包含。
 *
 * <p>调用方约定 {@code query} 已经过自身习惯的小写化处理（或未处理也无所谓，本类内部统一做）。
 */
public final class ItemSearch {
    private ItemSearch() {}

    /**
     * 匹配物品显示名（支持拼音） + 物品 ID（普通包含）。
     * 与 {@link #matches(String, String, String)} 等价，但接受 {@link ItemStack} 作为输入。
     */
    public static boolean matches(ItemStack stack, String query) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getHoverName().getString();
        String id = idOf(stack);
        return matches(name, id, query);
    }

    /**
     * 匹配物品显示名（支持拼音） + 物品 ID（普通包含）。
     * 与 {@link #matches(ItemStack, String)} 等价，但接受 {@link Item} 作为输入，
     * 用于搜索目标列表构造阶段（还没有具体 {@link ItemStack}）。
     */
    public static boolean matches(Item item, String query) {
        if (item == null) return false;
        ItemStack stack = new ItemStack(item);
        return matches(stack, query);
    }

    /**
     * 直接以“显示名 + 资源路径”进行匹配。供那些只有 {@link ResourceLocation} 而没有 ItemStack 的
     * 调用点使用（如传输图里 fluid / item 的 ResourceLocation）。
     *
     * @param displayName 物品/流体的中文显示名（可为空字符串）
     * @param idString    物品/流体 ResourceLocation.toString()（含命名空间 + 路径）
     * @param query       用户输入的查询字符串
     */
    public static boolean matches(String displayName, String idString, String query) {
        if (query == null || query.isEmpty()) return true;
        if (displayName != null && SearchSupport.contains(displayName, query)) return true;
        if (idString != null && idString.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }
}