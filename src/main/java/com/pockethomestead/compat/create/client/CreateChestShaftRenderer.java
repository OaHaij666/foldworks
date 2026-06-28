package com.pockethomestead.compat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pockethomestead.compat.create.CreateHomesteadChestBlock;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CreateChestShaftRenderer {
    private CreateChestShaftRenderer() {
    }

    public static boolean render(BlockEntity be, Direction side, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!(be instanceof KineticBlockEntity kinetic)) return false;

        BlockState state = kinetic.getBlockState();
        Direction.Axis axis = side.getAxis();
        if (!state.hasProperty(CreateHomesteadChestBlock.STRESS_AXIS)
                || state.getValue(CreateHomesteadChestBlock.STRESS_AXIS) != axis) {
            return false;
        }

        SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, side);
        float angle = KineticBlockEntityRenderer.getAngleForBe(kinetic, kinetic.getBlockPos(), axis);
        KineticBlockEntityRenderer.kineticRotationTransform(shaft, kinetic, axis, angle, packedLight);
        shaft.renderInto(pose, buffers.getBuffer(RenderType.solid()));
        return true;
    }
}
