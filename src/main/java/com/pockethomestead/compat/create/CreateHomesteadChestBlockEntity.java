package com.pockethomestead.compat.create;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadStressEndpoint;
import com.pockethomestead.menu.HomesteadChestMenu;
import com.pockethomestead.registration.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CreateHomesteadChestBlockEntity extends GeneratingKineticBlockEntity implements MenuProvider, HomesteadStressEndpoint {
    private record StressLease(float speed, float capacity, long untilGameTime) {}

    private final HomesteadChestBlockEntity chest;
    private final Map<String, StressLease> stressLeases = new LinkedHashMap<>();
    private float outputSpeed;
    private float outputCapacity;
    private boolean delegateLoaded;

    public CreateHomesteadChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOMESTEAD_CHEST.get(), pos, state);
        this.chest = new HomesteadChestBlockEntity(pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        chest.setLevel(level);
    }

    @Override
    public void setBlockState(BlockState blockState) {
        super.setBlockState(blockState);
        chest.setBlockState(blockState);
    }

    @Override
    public BaseChestBlockEntity homesteadChest() {
        syncDelegateState();
        return chest;
    }

    private void syncDelegateState() {
        if (level != null && chest.getLevel() != level) chest.setLevel(level);
        chest.setBlockState(getBlockState());
    }

    @Override
    public void initialize() {
        syncDelegateState();
        if (!delegateLoaded) {
            chest.onLoad();
            delegateLoaded = true;
        }
        super.initialize();
        if (!level.isClientSide) updateGeneratedRotation();
    }

    @Override
    public void tick() {
        syncDelegateState();
        super.tick();
        if (level == null || level.isClientSide) return;
        BaseChestBlockEntity.serverTick(level, worldPosition, getBlockState(), chest);
        refreshStressOutput(level.getGameTime());
    }

    @Override
    public void remove() {
        chest.setRemoved();
        super.remove();
    }

    @Override
    public void invalidate() {
        chest.setRemoved();
        stressLeases.clear();
        outputSpeed = 0;
        outputCapacity = 0;
        super.invalidate();
    }

    @Override
    public void onChunkUnloaded() {
        chest.setRemoved();
        stressLeases.clear();
        outputSpeed = 0;
        outputCapacity = 0;
        super.onChunkUnloaded();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.put("ChestData", chest.saveCustomOnly(registries));
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("ChestData")) chest.loadCustomOnly(tag.getCompound("ChestData"), registries);
        syncDelegateState();
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public float calculateStressApplied() {
        if (!isStressInputRole()) return 0;
        return chest.getStressTransferLimit();
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (!isStressOutputRole()) return 0;
        return outputCapacity;
    }

    @Override
    public float getGeneratedSpeed() {
        if (!isStressOutputRole()) return 0;
        return outputSpeed;
    }

    @Override
    public boolean canSendGraphStress() {
        return isStressInputRole() && getSpeed() != 0 && !isOverStressed() && chest.getStressTransferLimit() > 0;
    }

    @Override
    public float graphStressSpeed() {
        return canSendGraphStress() ? getSpeed() : 0;
    }

    @Override
    public float graphStressCapacity() {
        return canSendGraphStress() ? chest.getStressTransferLimit() : 0;
    }

    @Override
    public void receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime) {
        if (!isStressOutputRole() || leaseId == null || leaseId.isBlank() || speed == 0 || capacity <= 0) return;
        float acceptedCapacity = Math.min(capacity, chest.getStressTransferLimit());
        if (acceptedCapacity <= 0) return;
        stressLeases.put(leaseId, new StressLease(speed, acceptedCapacity, gameTime + 2));
        refreshStressOutput(gameTime);
    }

    private boolean isStressInputRole() {
        return chest.hasStressUpgrade() && chest.configuredStressInputSides() == 1 && chest.configuredStressOutputSides() == 0;
    }

    private boolean isStressOutputRole() {
        return chest.hasStressUpgrade() && chest.configuredStressOutputSides() == 1 && chest.configuredStressInputSides() == 0;
    }

    private void refreshStressOutput(long gameTime) {
        boolean removed = false;
        for (Iterator<Map.Entry<String, StressLease>> it = stressLeases.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().untilGameTime() < gameTime) {
                it.remove();
                removed = true;
            }
        }

        float nextSpeed = 0;
        float nextCapacity = 0;
        for (StressLease lease : stressLeases.values()) {
            if (nextSpeed == 0) {
                nextSpeed = lease.speed();
                nextCapacity = lease.capacity();
                continue;
            }
            if (Float.compare(nextSpeed, lease.speed()) == 0) {
                nextCapacity += lease.capacity();
            }
        }
        nextCapacity = Math.min(nextCapacity, chest.getStressTransferLimit());

        if (!isStressOutputRole()) {
            nextSpeed = 0;
            nextCapacity = 0;
        }

        boolean changed = removed || Float.compare(outputSpeed, nextSpeed) != 0 || Float.compare(outputCapacity, nextCapacity) != 0;
        outputSpeed = nextSpeed;
        outputCapacity = nextCapacity;
        if (changed && level != null && !level.isClientSide) {
            notifyStressCapacityChange(outputCapacity);
            updateGeneratedRotation();
            setChanged();
        }
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        syncDelegateState();
        return new HomesteadChestMenu(containerId, playerInventory, chest);
    }

    @Override
    public Component getDisplayName() {
        return chest.getDisplayName();
    }

    @Override
    public boolean canPlayerUse(Player player) {
        return chest.stillValid(player);
    }

}
