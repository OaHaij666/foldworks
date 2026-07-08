package com.pockethomestead.client.search;

import java.lang.reflect.Method;

/**
 * 统一的文本搜索匹配工具。对外暴露与 {@link String#contains(CharSequence)} 同语义的
 * {@link #contains(String, String)}，仅在内部决定走拼音匹配或回退到普通包含匹配。
 *
 * <p>当玩家安装了 <a href="https://github.com/Towdium/JustEnoughCharacters">JustEnoughCharacters</a>
 * 时，其内嵌的 PinIn 库（{@code me.towdium.pinin.PinIn}）会出现在 classpath 上。
 * 本类通过反射惰性加载它并调用 {@code contains(CharSequence, CharSequence)}，从而让所有
 * 经由此类的搜索框自动支持拼音（全拼、声母、混拼、声调…）。
 *
 * <p>未安装 JEC / PinIn 时，本类自动回退到 {@link String#contains(CharSequence)}，
 * 行为与改造前完全一致，零开销、零强依赖。
 *
 * <p>项目中所有“按用户输入文本过滤物品/资源/玩家可读名称”的位置都应通过本类而不是直接
 * 使用 {@code String.contains}，这样只要 here 调整即可全局获得拼音搜索能力，避免实现散落。
 */
public final class SearchSupport {
    private SearchSupport() {}

    private static volatile boolean initialized;
    private static volatile boolean pinInAvailable;
    private static Object pinInContext;
    private static Method pinInContains;

    private static void init() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> cls = Class.forName("me.towdium.pinin.PinIn");
            pinInContext = cls.getDeclaredConstructor().newInstance();
            pinInContains = cls.getMethod("contains", CharSequence.class, CharSequence.class);
            pinInAvailable = true;
        } catch (Throwable ignored) {
            pinInContext = null;
            pinInContains = null;
            pinInAvailable = false;
        }
    }

    /**
     * 判断 JEC / PinIn 是否已就绪。仅用于调试或 UI 提示。
     */
    public static boolean isPinyinActive() {
        if (!initialized) init();
        return pinInAvailable;
    }

    /**
     * 统一的“包含”匹配。约定 {@code text} 与 {@code query} 都视为“原始大小写”，
     * 内部会统一转小写再做匹配（拼音匹配本身与大小写无关，但 fallback 大小写敏感）。
     *
     * <p>当 {@code query} 为空字符串时返回 {@code true}，方便调用方写 “空查询 = 不过滤”。
     */
    public static boolean contains(String text, String query) {
        if (query == null || query.isEmpty()) return true;
        if (text == null) return false;
        if (!initialized) init();
        String q = query.toLowerCase();
        String t = text.toLowerCase();
        if (pinInAvailable) {
            try {
                Object result = pinInContains.invoke(pinInContext, t, q);
                return Boolean.TRUE.equals(result);
            } catch (Throwable ignored) {
                // 反射失败则回退，保证始终可用。
            }
        }
        return t.contains(q);
    }

    /**
     * 多备选文本中任一命中即返回 true，方便调用点一次写完 “name OR id OR path”。
     */
    public static boolean containsAny(String query, String... texts) {
        if (query == null || query.isEmpty()) return true;
        for (String t : texts) {
            if (contains(t, query)) return true;
        }
        return false;
    }
}