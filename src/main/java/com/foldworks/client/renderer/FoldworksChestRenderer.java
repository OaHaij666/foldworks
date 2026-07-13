package com.foldworks.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.foldworks.Foldworks;
import com.foldworks.block.AbstractFoldworksBlock;
import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.blockentity.RelativeSide;
import com.foldworks.blockentity.ResourceKind;
import com.foldworks.blockentity.SideMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public class FoldworksChestRenderer implements BlockEntityRenderer<BlockEntity> {
    private static final String CREATE_SHAFT_RENDERER =
            "com.foldworks.compat.create.client.CreateChestShaftRenderer";
    private static final AtomicBoolean INVOCATION_FAILURE_LOGGED = new AtomicBoolean();

    public FoldworksChestRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BlockEntity be, float partialTicks, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BaseChestBlockEntity chest = FoldworksChestAccess.resolve(be);
        if (chest == null) return;
        BlockState state = chest.getBlockState();
        if (state == null) return;
        Direction front = state.getValue(AbstractFoldworksBlock.FACING);

        for (Direction worldSide : Direction.values()) {
            RelativeSide rel = RelativeSide.fromWorld(worldSide, front);
            ResourceKind activeKind = null;
            for (ResourceKind kind : ResourceKind.values()) {
                SideMode mode = chest.getSideMode(kind, rel);
                if (mode != SideMode.DISABLED) {
                    activeKind = kind;
                    break;
                }
            }
            if (activeKind != ResourceKind.STRESS) continue;

            renderCreateStressShaft(be, worldSide, pose, buffers, packedLight);
        }
    }

    private boolean renderCreateStressShaft(BlockEntity be, Direction side, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!ModList.get().isLoaded("create")) return false;
        Method renderMethod = CreateRendererBridge.RENDER_METHOD;
        if (renderMethod == null) return false;
        try {
            Object rendered = renderMethod.invoke(null, be, side, pose, buffers, packedLight);
            return Boolean.TRUE.equals(rendered);
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (INVOCATION_FAILURE_LOGGED.compareAndSet(false, true)) {
                Foldworks.LOGGER.warn("Create shaft renderer invocation failed; disabling rendered shafts", ex);
            }
            return false;
        }
    }

    private static final class CreateRendererBridge {
        private static final Method RENDER_METHOD = resolve();

        private static Method resolve() {
            try {
                return Class.forName(CREATE_SHAFT_RENDERER).getMethod("render", BlockEntity.class,
                        Direction.class, PoseStack.class, MultiBufferSource.class, int.class);
            } catch (ReflectiveOperationException | LinkageError ex) {
                Foldworks.LOGGER.warn("Create is loaded but its shaft renderer bridge is unavailable", ex);
                return null;
            }
        }
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    @Override
    public boolean shouldRenderOffScreen(BlockEntity be) {
        return false;
    }
}
