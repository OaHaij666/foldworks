package com.foldworks.compat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.foldworks.blockentity.FoldworksStressEndpoint;
import com.foldworks.compat.create.CreateFoldworksChestBlock;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class CreateChestShaftRenderer {
    private record RotationSample(float speed, BlockPos offsetPos) {}

    private CreateChestShaftRenderer() {
    }

    public static boolean render(BlockEntity be, Direction side, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!(be instanceof KineticBlockEntity kinetic)) return false;

        BlockState state = kinetic.getBlockState();
        Direction.Axis axis = side.getAxis();
        if (!state.hasProperty(CreateFoldworksChestBlock.STRESS_AXIS)
                || state.getValue(CreateFoldworksChestBlock.STRESS_AXIS) != axis) {
            return false;
        }

        SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, side);
        RotationSample rotation = renderedRotation(kinetic, side, axis);
        float time = AnimationTickHolder.getRenderTime(kinetic.getLevel());
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(kinetic, rotation.offsetPos(), axis);
        float angle = ((time * rotation.speed() * 3f / 10 + offset) % 360) / 180f * (float) Math.PI;
        KineticBlockEntityRenderer.kineticRotationTransform(shaft, kinetic, axis, angle, packedLight);
        shaft.renderInto(pose, buffers.getBuffer(RenderType.solid()));
        return true;
    }

    private static RotationSample renderedRotation(KineticBlockEntity kinetic, Direction side, Direction.Axis axis) {
        float speed = kinetic.getSpeed();
        if (speed != 0) return new RotationSample(speed, kinetic.getBlockPos());

        KineticBlockEntity neighbour = connectedNeighbour(kinetic, side, axis);
        if (neighbour != null) {
            speed = neighbour.getSpeed();
            if (speed != 0) return new RotationSample(speed, neighbour.getBlockPos());
        }

        if (kinetic instanceof FoldworksStressEndpoint endpoint) {
            speed = endpoint.graphStressSpeed();
            if (speed != 0) return new RotationSample(speed, kinetic.getBlockPos());
        }

        return new RotationSample(0, kinetic.getBlockPos());
    }

    private static KineticBlockEntity connectedNeighbour(KineticBlockEntity kinetic, Direction side, Direction.Axis axis) {
        Level level = kinetic.getLevel();
        if (level == null) return null;

        BlockEntity neighbourBe = level.getBlockEntity(kinetic.getBlockPos().relative(side));
        if (!(neighbourBe instanceof KineticBlockEntity neighbour)) return null;
        if (neighbour.getSpeed() == 0 || neighbour.isOverStressed()) return null;

        BlockState neighbourState = neighbour.getBlockState();
        if (!(neighbourState.getBlock() instanceof IRotate rotation)) return null;
        if (rotation.getRotationAxis(neighbourState) != axis) return null;

        if (RotationPropagator.isConnected(kinetic, neighbour) || RotationPropagator.isConnected(neighbour, kinetic)) {
            return neighbour;
        }

        return rotation.hasShaftTowards(level, neighbour.getBlockPos(), neighbourState, side.getOpposite())
                ? neighbour
                : null;
    }
}
