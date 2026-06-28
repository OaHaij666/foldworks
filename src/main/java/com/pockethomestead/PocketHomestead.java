package com.pockethomestead;

import com.pockethomestead.config.ModConfig;
import com.pockethomestead.dimension.SpaceDimensionService;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.network.ChestSyncPacket;
import com.pockethomestead.network.ProductionStatsSyncPacket;
import com.pockethomestead.network.RequestProductionStatsPacket;
import com.pockethomestead.network.RequestTransferGraphPacket;
import com.pockethomestead.network.SaveTransferGraphPacket;
import com.pockethomestead.network.TransferGraphSyncPacket;
import com.pockethomestead.network.TransferGraphValidationPacket;
import com.pockethomestead.network.UpdateProductionStatsPacket;
import com.pockethomestead.registration.*;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpaceStorage;
import dev.galacticraft.dynamicdimensions.api.event.DimensionAddedCallback;
import dev.galacticraft.dynamicdimensions.api.event.DimensionRemovedCallback;
import dev.galacticraft.dynamicdimensions.api.event.DynamicDimensionLoadCallback;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(PocketHomestead.MODID)
public class PocketHomestead {
    public static final String MODID = "pockethomestead";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PocketHomestead(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有 DeferredRegister
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENU_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModDimensions.CHUNK_GENERATORS.register(modEventBus);

        // 注册配置
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ModConfig.SPEC);

        // 注册网络数据包
        modEventBus.addListener(PocketHomestead::registerPayloads);
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.pockethomestead.client.ClientEvents.register(modEventBus);
        }

        // 让存档空间在服务器启动时通过 DynamicDimensions 的正式路径创建。
        // 这避免了运行时首次进入才创建维度，把"创建"和"玩家首次进入"分离，
        // 减少边界场景下的客户端同步压力。
        DynamicDimensionLoadCallback.register(PocketHomestead::loadSavedSpaceDimensions);

        // 修复 DynamicDimensions 在 NeoForge 1.21 上的 bug：runtime 维度加入后
        // MinecraftServer.worldArray 缓存不刷新，tickChildren 会跳过新维度，
        // 表现为方块无法破坏、生物 AI 冻结、不刷怪。
        // 对照 TeamGalacticraft/DynamicDimensions Issue #10 与 PR #9。
        DimensionAddedCallback.register((key, level) -> level.getServer().markWorldsDirty());
        DimensionRemovedCallback.register((key, level) -> level.getServer().markWorldsDirty());
    }

    /**
     * 注册自定义网络数据包
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(
            ChestConfigPacket.TYPE,
            ChestConfigPacket.STREAM_CODEC,
            ChestConfigPacket::handle
        );
        registrar.playToClient(
            ChestSyncPacket.TYPE,
            ChestSyncPacket.STREAM_CODEC,
            ChestSyncPacket::handle
        );
        registrar.playToServer(
            RequestTransferGraphPacket.TYPE,
            RequestTransferGraphPacket.STREAM_CODEC,
            RequestTransferGraphPacket::handle
        );
        registrar.playToServer(
            SaveTransferGraphPacket.TYPE,
            SaveTransferGraphPacket.STREAM_CODEC,
            SaveTransferGraphPacket::handle
        );
        registrar.playToClient(
            TransferGraphSyncPacket.TYPE,
            TransferGraphSyncPacket.STREAM_CODEC,
            TransferGraphSyncPacket::handle
        );
        registrar.playToClient(
            TransferGraphValidationPacket.TYPE,
            TransferGraphValidationPacket.STREAM_CODEC,
            TransferGraphValidationPacket::handle
        );
        registrar.playToServer(
            RequestProductionStatsPacket.TYPE,
            RequestProductionStatsPacket.STREAM_CODEC,
            RequestProductionStatsPacket::handle
        );
        registrar.playToServer(
            UpdateProductionStatsPacket.TYPE,
            UpdateProductionStatsPacket.STREAM_CODEC,
            UpdateProductionStatsPacket::handle
        );
        registrar.playToClient(
            ProductionStatsSyncPacket.TYPE,
            ProductionStatsSyncPacket.STREAM_CODEC,
            ProductionStatsSyncPacket::handle
        );
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
            overworld.getDataStorage().computeIfAbsent(SpaceStorage.factory(), "pockethomestead_spaces");

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
