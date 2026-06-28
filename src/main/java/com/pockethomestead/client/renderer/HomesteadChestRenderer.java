package com.pockethomestead.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pockethomestead.block.AbstractHomesteadBlock;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import com.pockethomestead.compat.create.client.CreateChestShaftRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

public class HomesteadChestRenderer implements BlockEntityRenderer<BlockEntity> {
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
        if (!ModList.get().isLoaded("create")) return false;
        try {
            return CreateChestShaftRenderer.render(be, side, pose, buffers, packedLight);
        } catch (LinkageError ex) {
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
