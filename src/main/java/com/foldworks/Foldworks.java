package com.foldworks;

import com.foldworks.config.ModConfig;
import com.foldworks.dimension.SpaceDimensionService;










import com.foldworks.registration.*;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceChunkLoadingManager;
import com.foldworks.space.SpaceManager;
import com.foldworks.space.SpaceStorage;
import dev.galacticraft.dynamicdimensions.api.event.DimensionAddedCallback;
import dev.galacticraft.dynamicdimensions.api.event.DimensionRemovedCallback;
import dev.galacticraft.dynamicdimensions.api.event.DynamicDimensionLoadCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;


@Mod(Foldworks.MODID)
public class Foldworks {
    public static final String MODID = "foldworks";
    private static final String SAVED_DATA_NAME = "foldworks_spaces";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Foldworks(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有 DeferredRegister
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModDimensions.CHUNK_GENERATORS.register(modEventBus);
        com.foldworks.compat.create.CreateCompat.registerEarlyMovementCompatibility(modEventBus);

        // 注册配置
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ModConfig.SPEC);
        modEventBus.addListener(SpaceChunkLoadingManager::registerTicketControllers);
        modEventBus.addListener(Foldworks::onCommonSetup);

        // 配置热更新：管理员通过 /config 修改调度参数后立即生效，无需重启服务器
        modEventBus.addListener(ModConfigEvent.Reloading.class, event -> {
            com.foldworks.scheduler.SpaceScheduler.getInstance().reloadBudget();
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(() -> SpaceChunkLoadingManager.getInstance().reconcile(server));
            }
        });

        // 网络包注册统一在 ModMessages 中处理
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.foldworks.client.ClientEvents.register(modEventBus);
        }

        // 让存档空间在服务器启动时通过 DynamicDimensions 的正式路径创建。
        // 这避免了运行时首次进入才创建维度，把"创建"和"玩家首次进入"分离，
        // 减少边界场景下的客户端同步压力。
        DynamicDimensionLoadCallback.register(Foldworks::loadSavedSpaceDimensions);

        // 修复 DynamicDimensions 在 NeoForge 1.21 上的 bug：runtime 维度加入后
        // MinecraftServer.worldArray 缓存不刷新，tickChildren 会跳过新维度，
        // 表现为方块无法破坏、生物 AI 冻结、不刷怪。
        // 对照 TeamGalacticraft/DynamicDimensions Issue #10 与 PR #9。
        DimensionAddedCallback.register((key, level) -> level.getServer().markWorldsDirty());
        DimensionRemovedCallback.register((key, level) -> level.getServer().markWorldsDirty());
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(com.foldworks.compat.create.CreateCompat::registerMovementCompatibility);
        com.foldworks.suite.NativeCraftingPlanner.tryLoad();
    }

    private static void loadSavedSpaceDimensions(MinecraftServer server,
                                                 DynamicDimensionLoadCallback.DynamicDimensionLoader loader) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) {
                LOGGER.warn("过世界尚未就绪，跳过空间维度加载");
                return;
            }

            // 触发 SpaceStorage 加载（会通过 SpaceManager.loadSpaces() 填充 spaces）
            overworld.getDataStorage().computeIfAbsent(SpaceStorage.factory(), SAVED_DATA_NAME);

            SpaceDimensionService svc = SpaceDimensionService.getInstance();
            int loaded = 0;
            for (SpaceData space : SpaceManager.getInstance().getAllSpaces()) {
                try {
                    ResourceLocation id = space.getDimensionId();
                    if (server.getLevel(space.getDimensionKey()) != null) {
                        continue;
                    }
                    ServerLevel level = loader.loadDynamicDimension(id,
                            svc.buildGenerator(server, space),
                            svc.buildDimensionType(server, space));
                    if (level != null) {
                        loaded++;
                    }
                } catch (Exception e) {
                    LOGGER.error("启动时加载空间维度失败: {}", space.getDimensionId(), e);
                }
            }
            LOGGER.info("DynamicDimensionLoadCallback 加载了 {} 个空间维度", loaded);
        } catch (Exception e) {
            LOGGER.error("DynamicDimensionLoadCallback 执行异常", e);
        }
    }

}
