package com.pockethomestead.compat.create;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadStressEndpoint;
import com.pockethomestead.menu.HomesteadChestMenu;
import com.pockethomestead.registration.ModBlockEntities;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    private float outputCapacitySu;
    private float inputReservationSu;
    private long lastGraphStressRequestGameTime = Long.MIN_VALUE;
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
        refreshOrExpireStressInputRequest(level.getGameTime());
        retryStressInputConnection(level.getGameTime());
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
        outputCapacitySu = 0;
        inputReservationSu = 0;
        super.invalidate();
    }

    @Override
    public void onChunkUnloaded() {
        chest.setRemoved();
        stressLeases.clear();
        outputSpeed = 0;
        outputCapacitySu = 0;
        inputReservationSu = 0;
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
        if (clientPacket) refreshClientModelData();
    }

    @Override
    public net.neoforged.neoforge.client.model.data.ModelData getModelData() {
        syncDelegateState();
        return chest.getModelData();
    }

    private void refreshClientModelData() {
        if (level == null || !level.isClientSide) return;
        requestModelDataUpdate();
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, 8);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public float calculateStressApplied() {
        if (!hasStressInputRole()) return 0;
        return baseStressForSpeed(inputReservationSu, getTheoreticalSpeed());
    }

    @Override
    public float calculateAddedStressCapacity() {
        if (!hasStressOutputRole()) return 0;
        return baseCapacityForCurrentSpeed();
    }

    @Override
    public float getGeneratedSpeed() {
        if (!hasStressOutputRole()) return 0;
        return outputSpeed;
    }

    @Override
    public boolean canSendGraphStress() {
        return graphStressCapacity() > 0 && graphStressSpeed() != 0;
    }

    @Override
    public boolean canReceiveGraphStress() {
        return hasStressOutputRole();
    }

    @Override
    public float graphStressSpeed() {
        if (hasStressOutputRole() && outputSpeed != 0 && remainingLeasedStressCapacity() > 0) return outputSpeed;
        float inputSpeed = graphInputStressSpeed();
        if (inputSpeed != 0) return inputSpeed;
        return 0;
    }

    @Override
    public float graphStressCapacity() {
        if (hasStressOutputRole() && outputSpeed != 0) return remainingLeasedStressCapacity();
        if (hasStressInputRole()) return graphInputStressCapacity();
        return 0;
    }

    @Override
    public float receiveGraphStressLease(String leaseId, float speed, float capacity, long gameTime) {
        if (!hasStressOutputRole() || leaseId == null || leaseId.isBlank() || speed == 0 || capacity <= 0) return 0;
        pruneStressOutputLeases(gameTime);
        float configuredSpeed = chest.configuredStressOutputSpeed(speed);
        float otherCapacity = 0;
        for (Map.Entry<String, StressLease> entry : stressLeases.entrySet()) {
            if (leaseId.equals(entry.getKey())) continue;
            StressLease lease = entry.getValue();
            if (Float.compare(chest.configuredStressOutputSpeed(lease.speed()), configuredSpeed) != 0) return 0;
            otherCapacity += lease.capacity();
        }
        float acceptedCapacity = Math.min(capacity, Math.max(0, chest.getStressTransferLimit() - otherCapacity));
        if (acceptedCapacity <= 0) return 0;
        stressLeases.put(leaseId, new StressLease(speed, acceptedCapacity, gameTime + 2));
        refreshStressOutput(gameTime);
        return acceptedCapacity;
    }

    @Override
    public void recordGraphStressLease(String leaseId, float speed, float capacity, long gameTime) {
    }

    private boolean hasStressInputRole() {
        return chest.hasStressUpgrade() && chest.configuredStressInputSides() == 1;
    }

    private boolean hasStressOutputRole() {
        return chest.hasStressUpgrade() && chest.configuredStressOutputSides() == 1;
    }

    private boolean hasAnyStressRole() {
        return hasStressInputRole() || hasStressOutputRole();
    }

    private float remainingLeasedStressCapacity() {
        if (!hasStressOutputRole() || outputCapacitySu <= 0) return 0;
        if (isOverStressed()) return 0;
        return Math.max(0, outputCapacitySu - Math.max(0, stress));
    }

    private float graphInputStressCapacity() {
        if (!hasStressInputRole() || chest.getStressTransferLimit() <= 0) return 0;
        if (level == null || level.isClientSide) return Math.max(0, inputReservationSu);
        lastGraphStressRequestGameTime = level.getGameTime();
        refreshStressInputRequest();
        return Math.max(0, inputReservationSu);
    }

    private float baseCapacityForCurrentSpeed() {
        return baseStressForSpeed(outputCapacitySu, outputSpeed);
    }

    private float baseStressForSpeed(float totalSu, float speedRpm) {
        float speed = Math.abs(speedRpm);
        if (speed <= 0) return 0;
        return totalSu / speed;
    }

    private float graphInputStressSpeed() {
        if (!hasStressInputRole() || chest.getStressTransferLimit() <= 0) return 0;
        if (getSpeed() != 0 && !isOverStressed()) return getSpeed();
        KineticBlockEntity neighbour = connectedInputNeighbour();
        if (neighbour == null || neighbour.getSpeed() == 0 || neighbour.isOverStressed()) return 0;
        return neighbour.getSpeed();
    }

    @Nullable
    private KineticBlockEntity connectedInputNeighbour() {
        if (level == null) return null;
        Direction configuredSide = chest.getConfiguredStressInputWorldSide();
        if (configuredSide == null) return null;
        KineticBlockEntity primary = connectedInputNeighbour(configuredSide);
        if (primary != null) return primary;
        return connectedInputNeighbour(configuredSide.getOpposite());
    }

    @Nullable
    private KineticBlockEntity connectedInputNeighbour(Direction side) {
        if (level == null || side == null) return null;
        if (side.getAxis() != getBlockState().getValue(CreateHomesteadChestBlock.STRESS_AXIS)) return null;
        if (!(level.getBlockEntity(worldPosition.relative(side)) instanceof KineticBlockEntity neighbour)) return null;
        return RotationPropagator.isConnected(this, neighbour) ? neighbour : null;
    }

    private void refreshOrExpireStressInputRequest(long gameTime) {
        if (lastGraphStressRequestGameTime == Long.MIN_VALUE || gameTime - lastGraphStressRequestGameTime > 2) {
            setStressInputRequest(0);
            return;
        }
        refreshStressInputRequest();
    }

    private void refreshStressInputRequest() {
        float next = 0;
        if (level != null && !level.isClientSide && hasStressInputRole() && chest.getStressTransferLimit() > 0
                && hasNetwork() && graphInputStressSpeedCandidate() != 0) {
            float otherStress = Math.max(0, stress - inputReservationSu);
            float available = Math.max(0, capacity - otherStress);
            next = Math.min(chest.getStressTransferLimit(), available);
        }
        setStressInputRequest(next);
    }

    private void setStressInputRequest(float next) {
        next = Math.max(0, next);
        if (Math.abs(inputReservationSu - next) < 0.01f) return;
        inputReservationSu = next;
        updateInputStressApplied();
    }

    private float graphInputStressSpeedCandidate() {
        if (getSpeed() != 0) return getSpeed();
        KineticBlockEntity neighbour = connectedInputNeighbour();
        if (neighbour == null || neighbour.getSpeed() == 0 || neighbour.isOverStressed()) return 0;
        return neighbour.getSpeed();
    }

    private void updateInputStressApplied() {
        if (level == null || level.isClientSide || !hasNetwork()) return;
        var network = getOrCreateNetwork();
        if (network == null) return;
        network.updateStressFor(this, calculateStressApplied());
        setChanged();
    }

    private void refreshStressOutput(long gameTime) {
        boolean removed = pruneStressOutputLeases(gameTime);

        float nextSpeed = 0;
        float nextCapacity = 0;
        for (StressLease lease : stressLeases.values()) {
            if (nextSpeed == 0) {
                nextSpeed = chest.configuredStressOutputSpeed(lease.speed());
                nextCapacity = lease.capacity();
                continue;
            }
            float configuredSpeed = chest.configuredStressOutputSpeed(lease.speed());
            if (Float.compare(nextSpeed, configuredSpeed) == 0) {
                nextCapacity += lease.capacity();
            }
        }
        nextCapacity = Math.min(nextCapacity, chest.getStressTransferLimit());

        if (!hasStressOutputRole()) {
            nextSpeed = 0;
            nextCapacity = 0;
        }

        boolean speedChanged = Float.compare(outputSpeed, nextSpeed) != 0;
        boolean capacityChanged = Float.compare(outputCapacitySu, nextCapacity) != 0;
        boolean changed = removed || speedChanged || capacityChanged;
        outputSpeed = nextSpeed;
        outputCapacitySu = nextCapacity;
        if (changed && level != null && !level.isClientSide) {
            updateGeneratedRotation();
            if (!speedChanged && capacityChanged && hasNetwork()) {
                notifyStressCapacityChange(baseCapacityForCurrentSpeed());
            }
            setChanged();
        }
    }

    private boolean pruneStressOutputLeases(long gameTime) {
        boolean removed = false;
        for (Iterator<Map.Entry<String, StressLease>> it = stressLeases.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().untilGameTime() < gameTime) {
                it.remove();
                removed = true;
            }
        }
        return removed;
    }

    public void refreshKineticStateFromConfig() {
        syncDelegateState();
        if (level == null || level.isClientSide) return;
        detachKinetics();
        clearKineticInformation();
        updateSpeed = true;
        refreshStressOutput(level.getGameTime());
        updateGeneratedRotation();
        if (hasStressInputRole()) attachKinetics();
        markAdjacentKineticsForUpdate();
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(worldPosition.relative(direction), getBlockState().getBlock());
        }
        setChanged();
        sendData();
    }

    private void retryStressInputConnection(long gameTime) {
        if (!hasStressInputRole() || chest.getStressTransferLimit() <= 0 || getSpeed() != 0) return;
        int offset = Math.floorMod(worldPosition.hashCode(), 20);
        if ((gameTime + offset) % 20 != 0) return;
        updateSpeed = true;
        attachKinetics();
    }

    private void markAdjacentKineticsForUpdate() {
        if (level == null || !hasAnyStressRole()) return;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != getBlockState().getValue(CreateHomesteadChestBlock.STRESS_AXIS)) continue;
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof KineticBlockEntity kinetic) {
                kinetic.updateSpeed = true;
            }
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
