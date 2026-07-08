package com.pockethomestead.suite;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.api.suite.SuiteOperationTemplate;
import com.pockethomestead.api.suite.SuiteToolRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rust 原生合成规划器的 Java 封装。
 * <p>
 * 架构：
 * 1. syncRecipes() — 服务端启动时调用一次，发送全量配方到 Rust
 * 2. craftingPlan() — 每 tick 调用，只发库存+工具+订单
 * 3. Rust 用全局状态存储配方，构建 output_id → recipes 索引
 */
public final class NativeCraftingPlanner {
    private static volatile boolean loaded = false;
    private static volatile boolean loadFailed = false;
    private static volatile boolean recipesSynced = false;
    private static volatile boolean syncFailed = false;

    // 全局物品索引：syncRecipes 时构建，craftingPlan 时复用
    private static volatile ItemIndex globalItemIndex;
    private static volatile List<SyncedRecipe> syncedRecipes;

    private NativeCraftingPlanner() {
    }

    // ── 原生库加载 ──────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        return loaded && recipesSynced;
    }

    public static synchronized void tryLoad() {
        if (loaded || loadFailed) return;
        try {
            String libName = nativeLibraryName();
            java.nio.file.Path libPath = extractNativeLibrary(libName);
            if (libPath != null) {
                System.load(libPath.toString());
                loaded = true;
                PocketHomestead.LOGGER.info("Rust 合成规划器加载成功: {}", libPath);
            } else {
                loadFailed = true;
                PocketHomestead.LOGGER.warn("未找到 Rust 合成规划器原生库，将使用 Java 兜底");
            }
        } catch (UnsatisfiedLinkError | Exception e) {
            loadFailed = true;
            PocketHomestead.LOGGER.warn("Rust 合成规划器加载失败，将使用 Java 兜底: {}", e.getMessage());
        }
    }

    private static String nativeLibraryName() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String osPart;
        String archPart;
        if (os.contains("windows")) {
            osPart = "windows";
        } else if (os.contains("linux")) {
            osPart = "linux";
        } else if (os.contains("mac")) {
            osPart = "macos";
        } else {
            osPart = os.replace(' ', '_');
        }
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archPart = "aarch64";
        } else {
            archPart = "x86_64";
        }
        return "pocket_homestead_crafting_" + osPart + "_" + archPart;
    }

    private static java.nio.file.Path extractNativeLibrary(String libName) {
        String[] extensions;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            extensions = new String[]{".dll"};
        } else if (os.contains("mac")) {
            extensions = new String[]{".dylib"};
        } else {
            extensions = new String[]{".so"};
        }

        for (String ext : extensions) {
            String resourcePath = "/natives/" + libName + ext;
            var url = NativeCraftingPlanner.class.getResource(resourcePath);
            if (url == null) continue;

            try {
                java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("pocket_homestead_native_");
                java.nio.file.Path tempFile = tempDir.resolve(libName + ext);
                try (var in = NativeCraftingPlanner.class.getResourceAsStream(resourcePath)) {
                    if (in == null) continue;
                    java.nio.file.Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                tempFile.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
                return tempFile;
            } catch (Exception e) {
                PocketHomestead.LOGGER.warn("提取原生库失败: {}", e.getMessage());
            }
        }
        return null;
    }

    // ── 原生方法 ────────────────────────────────────────────────────────────

    private static native boolean syncRecipes(ByteBuffer input, int inputLen);

    private static native int updateStateAndPlan(
            ByteBuffer input, int inputLen,
            ByteBuffer output, int outputCapacity);

    // 预分配的 DirectByteBuffer，复用避免每 tick 分配
    private static ByteBuffer sharedInputBuffer;
    private static ByteBuffer sharedOutputBuffer;

    private static ByteBuffer getInputBuffer() {
        if (sharedInputBuffer == null || sharedInputBuffer.capacity() < 8192) {
            sharedInputBuffer = ByteBuffer.allocateDirect(8192);
            sharedInputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        return sharedInputBuffer;
    }

    private static ByteBuffer getOutputBuffer() {
        if (sharedOutputBuffer == null || sharedOutputBuffer.capacity() < 4096) {
            sharedOutputBuffer = ByteBuffer.allocateDirect(4096);
            sharedOutputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        return sharedOutputBuffer;
    }

    // ── 数据结构 ────────────────────────────────────────────────────────────

    public record PlanAction(
            int orderIndex,
            ItemStack tool,
            ItemStack output,
            int timeTicks,
            boolean isTargetOutput
    ) {
    }

    public record OrderContext(ItemStack target, int requested, int ready, int activeTargetOutputCount) {
    }

    // ── 配方同步 ────────────────────────────────────────────────────────────

    /**
     * 从 RecipeManager 收集所有适配器支持的配方，同步到 Rust。
     * 仅在首次调用时执行，之后跳过。
     */
    public static void syncAllRecipesIfNeeded(ServerLevel level) {
        if (recipesSynced || !loaded || syncFailed) return;
        PocketHomestead.LOGGER.info("Rust 合成规划器：开始同步配方...");
        syncAllRecipes(level);
        PocketHomestead.LOGGER.info("Rust 合成规划器：同步完成, recipesSynced={}", recipesSynced);
    }

    /**
     * 从 RecipeManager 收集所有适配器支持的配方，同步到 Rust。
     * 应在服务端启动后调用。
     */
    public static synchronized void syncAllRecipes(ServerLevel level) {
        if (!loaded) return;

        try {
            ItemIndex itemIndex = new ItemIndex();
            List<SyncedRecipe> recipeList = new ArrayList<>();
            Set<String> seenRecipes = new HashSet<>();

            // 遍历所有 RecipeType 的配方，获取输出物品，通过 operationsFor 转换
            for (var recipeHolder : level.getRecipeManager().getRecipes()) {
                var recipe = recipeHolder.value();
                ItemStack outputStack = recipe.getResultItem(level.registryAccess());
                if (outputStack.isEmpty()) continue;

                List<SuiteOperationTemplate> ops = SuiteToolRegistry.operationsFor(level, outputStack);
                for (var op : ops) {
                    // 去重
                    String recipeKey = ItemIndex.stackKey(op.output()) + "|" + op.timeTicks() + "|" + op.inputs().hashCode();
                    if (!seenRecipes.add(recipeKey)) continue;

                    // 注册物品
                    int outputId = itemIndex.assign(op.output());
                    int toolId = itemIndex.assign(op.tool());
                    List<List<Integer>> inputIds = new ArrayList<>();
                    for (Ingredient ing : op.inputs()) {
                        if (ing.isEmpty()) {
                            inputIds.add(List.of());
                            continue;
                        }
                        List<Integer> candidates = new ArrayList<>();
                        for (ItemStack item : ing.getItems()) {
                            candidates.add(itemIndex.assign(item));
                        }
                        inputIds.add(candidates);
                    }

                    recipeList.add(new SyncedRecipe(outputId, op.output().getCount(), toolId, op.timeTicks(), inputIds));
                }
            }

            // 序列化到 ByteBuffer
            int estimatedSize = 4096 + itemIndex.size() * 32 + recipeList.size() * 64;
            ByteBuffer buf = ByteBuffer.allocateDirect(estimatedSize);
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

            buf.put((byte) 0x01); // command = syncRecipes

            // item dictionary
            buf.putShort((short) itemIndex.size());
            for (int id = 0; id < itemIndex.size(); id++) {
                String name = itemIndex.getName(id);
                byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.putShort((short) nameBytes.length);
                buf.put(nameBytes);
            }

            // recipes
            buf.putShort((short) recipeList.size());
            for (var r : recipeList) {
                buf.putShort((short) r.outputId);
                buf.put((byte) r.outputCount);
                buf.putShort((short) r.toolId);
                buf.putShort((short) r.timeTicks);
                buf.put((byte) r.inputIds.size());
                for (var candidates : r.inputIds) {
                    buf.put((byte) candidates.size());
                    for (int candId : candidates) {
                        buf.putShort((short) candId);
                    }
                }
            }

            int len = buf.position();
            buf.flip();

            boolean ok = syncRecipes(buf, len);
            if (ok) {
                globalItemIndex = itemIndex;
                syncedRecipes = recipeList;
                recipesSynced = true;
                PocketHomestead.LOGGER.info("Rust 合成规划器配方同步成功: {} 物品, {} 配方", itemIndex.size(), recipeList.size());
            } else {
                syncFailed = true;
                PocketHomestead.LOGGER.warn("Rust 合成规划器配方同步失败（native 返回 false）");
            }
        } catch (Throwable e) {
            syncFailed = true;
            PocketHomestead.LOGGER.warn("Rust 合成规划器配方同步异常: {}", e);
        }
    }

    // ── 规划调用 ────────────────────────────────────────────────────────────

    public static List<PlanAction> plan(
            ServerLevel level,
            List<OrderContext> orderContexts,
            com.pockethomestead.blockentity.BaseChestBlockEntity chest,
            List<ItemStack> toolPool,
            int maxDepth
    ) {
        if (!loaded || !recipesSynced) return null;

        try {
            return doPlan(level, orderContexts, chest, toolPool, maxDepth);
        } catch (Throwable e) {
            PocketHomestead.LOGGER.warn("Rust 合成规划器执行异常，回退 Java: {}", e);
            return null;
        }
    }

    private static List<PlanAction> doPlan(
            ServerLevel level,
            List<OrderContext> orderContexts,
            com.pockethomestead.blockentity.BaseChestBlockEntity chest,
            List<ItemStack> toolPool,
            int maxDepth
    ) {
        ItemIndex idx = globalItemIndex;
        if (idx == null) return null;

        long t0 = System.nanoTime();

        // 复用预分配的 input buffer
        ByteBuffer inputBuf = getInputBuffer();
        inputBuf.clear();

        inputBuf.put((byte) 0x03); // command = updateStateAndPlan
        inputBuf.putInt(maxDepth);

        // inventory（nativeItemId 缓存命中时跳过 HashMap + Registry 查找）
        var storedItems = chest.getStoredItemsDirect();
        inputBuf.putShort((short) storedItems.size());
        for (var stored : storedItems) {
            int id = stored.nativeItemId();
            if (id < 0) {
                id = idx.getId(stored.prototypeRef());
                stored.nativeItemId(id);
            }
            inputBuf.putShort((short) id);
            inputBuf.putInt((int) Math.min(stored.count(), Integer.MAX_VALUE));
        }

        // tools
        inputBuf.putShort((short) toolPool.size());
        for (var tool : toolPool) {
            inputBuf.putShort((short) idx.getId(tool));
        }

        // orders
        inputBuf.put((byte) Math.min(orderContexts.size(), 255));
        for (int i = 0; i < orderContexts.size() && i < 255; i++) {
            var order = orderContexts.get(i);
            inputBuf.putShort((short) idx.getId(order.target()));
            inputBuf.putInt(order.requested());
            inputBuf.putInt(order.ready());
            inputBuf.putInt(order.activeTargetOutputCount());
        }

        int inputLen = inputBuf.position();
        inputBuf.flip();

        long t1 = System.nanoTime();

        // 复用预分配的 output buffer
        ByteBuffer outputBuf = getOutputBuffer();
        outputBuf.clear();
        int outputLen = updateStateAndPlan(inputBuf, inputLen, outputBuf, outputBuf.capacity());

        if (outputLen <= 0) {
            return List.of();
        }

        // 反序列化结果
        outputBuf.limit(outputLen);
        List<PlanAction> results = new ArrayList<>();
        byte version = outputBuf.get();
        byte resultCount = outputBuf.get();
        for (int i = 0; i < resultCount; i++) {
            int orderIndex = outputBuf.get() & 0xFF;
            int toolId = outputBuf.getShort() & 0xFFFF;
            int outputId = outputBuf.getShort() & 0xFFFF;
            int timeTicks = outputBuf.getInt();
            boolean isTarget = outputBuf.get() != 0;

            ItemStack toolStack = idx.getStack(toolId);
            ItemStack outputStack = idx.getStack(outputId);
            if (toolStack.isEmpty() || outputStack.isEmpty()) continue;

            results.add(new PlanAction(orderIndex, toolStack.copyWithCount(1), outputStack.copyWithCount(1), timeTicks, isTarget));
        }

        return results;
    }

    // ── 辅助类 ──────────────────────────────────────────────────────────────

    private static class ItemIndex {
        private final Map<String, Integer> keyToId = new HashMap<>();
        private final LongHashMap fastKeyToId = new LongHashMap();
        private final List<String> idToName = new ArrayList<>();
        private final List<ItemStack> idToStack = new ArrayList<>();

        int assign(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return 0;
            String key = stackKey(stack);
            return keyToId.computeIfAbsent(key, k -> {
                int id = idToName.size();
                idToName.add(key);
                idToStack.add(stack.copyWithCount(1));
                fastKeyToId.put(fastStackKey(stack), id);
                return id;
            });
        }

        int getId(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return 0;
            int id = fastKeyToId.get(fastStackKey(stack));
            if (id >= 0) return id;
            Integer idObj = keyToId.get(stackKey(stack));
            if (idObj != null) {
                fastKeyToId.put(fastStackKey(stack), idObj);
                return idObj;
            }
            return 0;
        }

        ItemStack getStack(int id) {
            return id >= 0 && id < idToStack.size() ? idToStack.get(id) : ItemStack.EMPTY;
        }

        String getName(int id) {
            return id >= 0 && id < idToName.size() ? idToName.get(id) : "";
        }

        int size() {
            return idToName.size();
        }

        static String stackKey(ItemStack stack) {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                    + "|" + stack.getComponentsPatch().hashCode();
        }

        static long fastStackKey(ItemStack stack) {
            return ((long) System.identityHashCode(stack.getItem()) << 32)
                    | (stack.getComponentsPatch().hashCode() & 0xFFFFFFFFL);
        }
    }

    private static final class LongHashMap {
        private static final int MASK = 0xFFFF;
        private final int[] keys = new int[MASK + 1];
        private final int[] values = new int[MASK + 1];
        private final boolean[] occupied = new boolean[MASK + 1];

        void put(long key, int value) {
            int idx = mix(key) & MASK;
            // open addressing, linear probe
            for (int i = 0; i < 16; i++) {
                int slot = (idx + i) & MASK;
                if (!occupied[slot] || keys[slot] == (int) (key ^ (key >>> 32))) {
                    occupied[slot] = true;
                    keys[slot] = (int) (key ^ (key >>> 32));
                    values[slot] = value;
                    return;
                }
            }
        }

        int get(long key) {
            int idx = mix(key) & MASK;
            int k = (int) (key ^ (key >>> 32));
            for (int i = 0; i < 16; i++) {
                int slot = (idx + i) & MASK;
                if (!occupied[slot]) return -1;
                if (keys[slot] == k) return values[slot];
            }
            return -1;
        }

        private static int mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }
    }

    private record SyncedRecipe(int outputId, int outputCount, int toolId, int timeTicks, List<List<Integer>> inputIds) {
    }
}
