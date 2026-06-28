package com.pockethomestead.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pockethomestead.block.AbstractHomesteadBlock;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class HomesteadChestRenderer implements BlockEntityRenderer<BlockEntity> {
    private static final String CREATE_SHAFT_RENDERER = "com.pockethomestead.compat.create.client.CreateChestShaftRenderer";
    private static Method createShaftRenderMethod;
    private static boolean createShaftRendererUnavailable;

    public HomesteadChestRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BlockEntity be, float partialTicks, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(be);
        if (chest == null) return;
        BlockState state = chest.getBlockState();
        if (state == null) return;
        Direction front = state.getValue(AbstractHomesteadBlock.FACING);

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
        if (createShaftRendererUnavailable || !ModList.get().isLoaded("create")) return false;
        try {
            Method method = createShaftRenderMethod;
            if (method == null) {
                Class<?> renderer = Class.forName(CREATE_SHAFT_RENDERER);
                method = renderer.getMethod("render", BlockEntity.class, Direction.class, PoseStack.class, MultiBufferSource.class, int.class);
                createShaftRenderMethod = method;
            }
            Object rendered = method.invoke(null, be, side, pose, buffers, packedLight);
            return rendered instanceof Boolean result && result;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | LinkageError ex) {
            createShaftRendererUnavailable = true;
            return false;
        } catch (InvocationTargetException ex) {
            createShaftRendererUnavailable = true;
            return false;
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
