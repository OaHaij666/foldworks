package com.foldworks.item;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.registration.ModItems;
import com.foldworks.registry.ChestRegistryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;
import java.util.UUID;

public final class FoldworksTabletBinding {
    private static final String ROOT = "FoldworksBoundChest";
    private static final String OWNER = "Owner";
    private static final String CHEST_UUID = "ChestUuid";
    private static final String CHEST_ID = "ChestId";
    private static final String DIMENSION = "Dimension";
    private static final String POS = "Pos";

    private FoldworksTabletBinding() {
    }

    public record BoundChest(UUID owner, UUID chestUuid, String chestId, String dimensionKey, BlockPos pos) {
    }

    public static Optional<BoundChest> read(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = data.copyTag();
        if (!root.contains(ROOT)) return Optional.empty();
        CompoundTag tag = root.getCompound(ROOT);
        if (!tag.hasUUID(OWNER)) return Optional.empty();
        String chestId = tag.getString(CHEST_ID);
        String dimension = tag.getString(DIMENSION);
        if (chestId.isBlank() || dimension.isBlank()) return Optional.empty();
        UUID chestUuid = tag.hasUUID(CHEST_UUID) ? tag.getUUID(CHEST_UUID) : null;
        return Optional.of(new BoundChest(tag.getUUID(OWNER), chestUuid, chestId, dimension, BlockPos.of(tag.getLong(POS))));
    }

    public static void bind(ItemStack stack, BaseChestBlockEntity chest) {
        if (stack == null || stack.isEmpty() || chest == null || chest.getLevel() == null || chest.getOwnerUUID() == null) return;
        write(stack, chest.getOwnerUUID(), chest.getChestUUID(), chest.getChestId(), chest.getLevel().dimension().location().toString(), chest.getBlockPos());
    }

    public static void refreshLocation(ItemStack stack, BaseChestBlockEntity chest) {
        if (stack == null || stack.isEmpty() || chest == null || chest.getLevel() == null || chest.getOwnerUUID() == null) return;
        Optional<BoundChest> bound = read(stack);
        if (bound.isEmpty()) return;
        BoundChest value = bound.get();
        if (!value.owner().equals(chest.getOwnerUUID())) return;
        if (value.chestUuid() != null) {
            if (!value.chestUuid().equals(chest.getChestUUID())) return;
        } else if (!value.chestId().equals(chest.getChestId())) {
            return;
        }
        write(stack, value.owner(), chest.getChestUUID(), chest.getChestId(), chest.getLevel().dimension().location().toString(), chest.getBlockPos());
    }

    public static boolean rewriteChestIdIfMatches(ItemStack stack, UUID owner, String oldChestId, String dimensionKey, BlockPos pos, String newChestId) {
        Optional<BoundChest> bound = read(stack);
        if (bound.isEmpty()) return false;
        BoundChest value = bound.get();
        if (!value.owner().equals(owner) || !value.chestId().equals(oldChestId)) return false;
        if (dimensionKey != null && !dimensionKey.equals(value.dimensionKey())) return false;
        if (pos != null && !pos.equals(value.pos())) return false;
        write(stack, value.owner(), value.chestUuid(), newChestId, value.dimensionKey(), value.pos());
        return true;
    }

    private static void write(ItemStack stack, UUID owner, UUID chestUuid, String chestId, String dimensionKey, BlockPos pos) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag root = data.copyTag();
        CompoundTag tag = new CompoundTag();
        tag.putUUID(OWNER, owner);
        if (chestUuid != null) tag.putUUID(CHEST_UUID, chestUuid);
        tag.putString(CHEST_ID, chestId == null ? "" : chestId);
        tag.putString(DIMENSION, dimensionKey == null ? "" : dimensionKey);
        tag.putLong(POS, pos == null ? BlockPos.ZERO.asLong() : pos.asLong());
        root.put(ROOT, tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    public static ItemStack findHeldTablet(Player player) {
        if (player == null) return ItemStack.EMPTY;
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.FOLDWORKS_TABLET.get())) return main;
        ItemStack off = player.getOffhandItem();
        return off.is(ModItems.FOLDWORKS_TABLET.get()) ? off : ItemStack.EMPTY;
    }

    public static BaseChestBlockEntity resolve(ServerPlayer player, ItemStack tablet) {
        Optional<BoundChest> bound = read(tablet);
        if (bound.isEmpty() || !(player.level() instanceof ServerLevel currentLevel)) return null;
        BoundChest value = bound.get();

        BaseChestBlockEntity registered = value.chestUuid() == null
                ? null
                : ChestRegistryManager.getInstance().findChestByUuid(value.owner(), value.chestUuid(), currentLevel);
        if (matches(registered, value)) return registered;
        registered = ChestRegistryManager.getInstance().findChest(value.owner(), value.chestId(), currentLevel);
        if (matches(registered, value)) return registered;

        ResourceLocation dimensionId = ResourceLocation.tryParse(value.dimensionKey());
        if (dimensionId == null || player.server == null) return null;
        ServerLevel targetLevel = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
        if (targetLevel == null) return null;
        if (!targetLevel.isLoaded(value.pos())) return null;
        BaseChestBlockEntity atSavedPos = FoldworksChestAccess.resolve(targetLevel.getBlockEntity(value.pos()));
        return matches(atSavedPos, value) ? atSavedPos : null;
    }

    private static boolean matches(BaseChestBlockEntity chest, BoundChest bound) {
        return chest != null
                && chest.getOwnerUUID() != null
                && chest.getOwnerUUID().equals(bound.owner())
                && (bound.chestUuid() != null
                    ? chest.getChestUUID().equals(bound.chestUuid())
                    : chest.getChestId().equals(bound.chestId()));
    }
}
