package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.StoredItemStack;
import com.foldworks.item.FoldworksTabletBinding;
import com.foldworks.permission.AccessControl;
import com.foldworks.space.SpacePermission;
import com.foldworks.suite.SuiteOrderSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record TabletChestActionPacket(int action, int playerSlot, ItemStack stack, int[] workbenchDistribution) implements CustomPacketPayload {
    public static final int REQUEST = 0;
    public static final int CLICK_PLAYER_LEFT = 1;
    public static final int CLICK_PLAYER_RIGHT = 2;
    public static final int CLICK_CHEST_LEFT = 3;
    public static final int CLICK_CHEST_RIGHT = 4;
    public static final int QUICK_MOVE_PLAYER = 5;
    public static final int QUICK_MOVE_CHEST = 6;
    public static final int RETURN_CARRIED = 7;
    public static final int CLICK_WORKBENCH_LEFT = 8;
    public static final int CLICK_WORKBENCH_RIGHT = 9;
    public static final int TAKE_WORKBENCH_RESULT = 10;
    public static final int QUICK_MOVE_WORKBENCH_RESULT = 11;
    public static final int CLICK_SMITHING_LEFT = 12;
    public static final int CLICK_SMITHING_RIGHT = 13;
    public static final int TAKE_SMITHING_RESULT = 14;
    public static final int QUICK_MOVE_SMITHING_RESULT = 15;
    public static final int CLICK_STONECUTTER_LEFT = 16;
    public static final int CLICK_STONECUTTER_RIGHT = 17;
    public static final int SELECT_STONECUTTER_RECIPE = 18;
    public static final int TAKE_STONECUTTER_RESULT = 19;
    public static final int QUICK_MOVE_STONECUTTER_RESULT = 20;
    public static final int CLICK_FURNACE_LEFT = 21;
    public static final int CLICK_FURNACE_RIGHT = 22;
    public static final int QUICK_MOVE_FURNACE_OUTPUT = 23;
    public static final int CLICK_SUITE_TOOL_LEFT = 24;
    public static final int CLICK_SUITE_TOOL_RIGHT = 25;
    public static final int CLICK_SUITE_RESOURCE_LEFT = 26;
    public static final int CLICK_SUITE_RESOURCE_RIGHT = 27;
    public static final int SET_SUITE_ORDER_TARGET = 28;
    public static final int SUITE_ORDER_QTY_MINUS = 29;
    public static final int SUITE_ORDER_QTY_PLUS = 30;
    public static final int CREATE_SUITE_ORDER = 31;
    public static final int CLAIM_SUITE_ORDER = 32;
    public static final int STOP_SUITE_ORDER = 33;
    public static final int CANCEL_SUITE_ORDER = 34;
    public static final int RECOVER_SUITE_ORDER = 35;
    public static final int DELETE_SUITE_ORDER = 36;
    public static final int SET_SUITE_ORDER_QUANTITY = 37;
    // 左键长按拖拽九宫格的最终分布（carried 全量被重新均分到本轮拖过的槽）。
    // 客户端在拖动期间模拟显示，释放时把每个槽本轮需要新增的数量一次性发到服务端。
    public static final int DISTRIBUTE_WORKBENCH = 38;

    private static final Map<UUID, ItemStack> CARRIED = new ConcurrentHashMap<>();
    private static final Map<UUID, NonNullList<ItemStack>> WORKBENCH_INPUTS = new ConcurrentHashMap<>();
    private static final Map<UUID, NonNullList<ItemStack>> SMITHING_INPUTS = new ConcurrentHashMap<>();
    private static final Map<UUID, NonNullList<ItemStack>> STONECUTTER_INPUTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> STONECUTTER_SELECTED = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack> SUITE_ORDER_TARGET = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SUITE_ORDER_QUANTITY = new ConcurrentHashMap<>();

    public static final Type<TabletChestActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "tablet_chest_action"));

    public TabletChestActionPacket(int action) {
        this(action, -1, ItemStack.EMPTY, null);
    }

    public TabletChestActionPacket(int action, int playerSlot) {
        this(action, playerSlot, ItemStack.EMPTY, null);
    }

    public TabletChestActionPacket(int action, ItemStack stack) {
        this(action, -1, stack, null);
    }

    public TabletChestActionPacket(int action, int playerSlot, ItemStack stack) {
        this(action, playerSlot, stack, null);
    }

    public TabletChestActionPacket(int action, int[] workbenchDistribution) {
        this(action, -1, ItemStack.EMPTY, workbenchDistribution);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, TabletChestActionPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TabletChestActionPacket decode(RegistryFriendlyByteBuf buf) {
            int action = ByteBufCodecs.VAR_INT.decode(buf);
            int playerSlot = ByteBufCodecs.VAR_INT.decode(buf);
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int[] distribution = null;
            if (buf.readBoolean()) {
                distribution = new int[9];
                for (int i = 0; i < 9; i++) distribution[i] = ByteBufCodecs.VAR_INT.decode(buf);
            }
            return new TabletChestActionPacket(action, playerSlot, stack, distribution);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, TabletChestActionPacket packet) {
            ByteBufCodecs.VAR_INT.encode(buf, packet.action);
            ByteBufCodecs.VAR_INT.encode(buf, packet.playerSlot);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.stack == null ? ItemStack.EMPTY : packet.stack.copyWithCount(1));
            boolean hasDistribution = packet.workbenchDistribution != null;
            buf.writeBoolean(hasDistribution);
            if (hasDistribution) {
                int[] dist = packet.workbenchDistribution;
                for (int i = 0; i < 9; i++) {
                    ByteBufCodecs.VAR_INT.encode(buf, i < dist.length ? dist[i] : 0);
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TabletChestActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tablet = FoldworksTabletBinding.findHeldTablet(player);
            if (tablet.isEmpty()) {
                returnCarried(player);
                returnSuiteInputs(player);
                sendUnavailable(player, false, "请手持工造终端");
                return;
            }
            if (FoldworksTabletBinding.read(tablet).isEmpty()) {
                returnCarried(player);
                returnSuiteInputs(player);
                sendUnavailable(player, false, "潜行右键维度仓以绑定");
                return;
            }

            BaseChestBlockEntity chest = FoldworksTabletBinding.resolve(player, tablet);
            if (chest == null) {
                returnCarried(player);
                returnSuiteInputs(player);
                sendUnavailable(player, true, "绑定的箱子未加载或已不存在");
                return;
            }
            FoldworksTabletBinding.refreshLocation(tablet, chest);

            if (!AccessControl.canChest(player.getUUID(), chest, SpacePermission.AccessLevel.USE)) {
                AccessControl.deny(player);
                send(player, chest);
                return;
            }

            switch (packet.action) {
                case CLICK_PLAYER_LEFT -> clickPlayer(player, packet.playerSlot, false);
                case CLICK_PLAYER_RIGHT -> clickPlayer(player, packet.playerSlot, true);
                case CLICK_CHEST_LEFT -> clickChest(player, chest, packet.stack, false);
                case CLICK_CHEST_RIGHT -> clickChest(player, chest, packet.stack, true);
                case QUICK_MOVE_PLAYER -> quickMovePlayerToChest(player, chest, packet.playerSlot);
                case QUICK_MOVE_CHEST -> quickMoveChestToPlayer(player, chest, packet.stack);
                case CLICK_FURNACE_LEFT -> clickFurnace(player, chest, packet.playerSlot, false);
                case CLICK_FURNACE_RIGHT -> clickFurnace(player, chest, packet.playerSlot, true);
                case QUICK_MOVE_FURNACE_OUTPUT -> quickMoveFurnaceOutput(player, chest);
                case CLICK_SUITE_TOOL_LEFT -> clickSuitePool(player, chest, SuiteOrderSystem.TOOL_POOL, packet.playerSlot, false);
                case CLICK_SUITE_TOOL_RIGHT -> clickSuitePool(player, chest, SuiteOrderSystem.TOOL_POOL, packet.playerSlot, true);
                case CLICK_SUITE_RESOURCE_LEFT -> clickSuitePool(player, chest, SuiteOrderSystem.RESOURCE_POOL, packet.playerSlot, false);
                case CLICK_SUITE_RESOURCE_RIGHT -> clickSuitePool(player, chest, SuiteOrderSystem.RESOURCE_POOL, packet.playerSlot, true);
                case SET_SUITE_ORDER_TARGET -> setSuiteOrderTarget(player, packet.stack);
                case SET_SUITE_ORDER_QUANTITY -> setSuiteOrderQuantity(player, packet.playerSlot);
                case SUITE_ORDER_QTY_MINUS -> adjustSuiteOrderQuantity(player, -packet.playerSlot);
                case SUITE_ORDER_QTY_PLUS -> adjustSuiteOrderQuantity(player, packet.playerSlot);
                case CREATE_SUITE_ORDER -> createSuiteOrder(player, chest);
                case CLAIM_SUITE_ORDER -> chest.getSuiteOrders().claimOrder(packet.playerSlot);
                case STOP_SUITE_ORDER -> chest.getSuiteOrders().stopOrder(packet.playerSlot);
                case CANCEL_SUITE_ORDER -> chest.getSuiteOrders().cancelOrder(packet.playerSlot);
                case RECOVER_SUITE_ORDER -> chest.getSuiteOrders().recoverOrder(packet.playerSlot);
                case DELETE_SUITE_ORDER -> chest.getSuiteOrders().deleteOrder(packet.playerSlot);
                case CLICK_WORKBENCH_LEFT -> clickWorkbenchInput(player, chest, packet.playerSlot, false);
                case CLICK_WORKBENCH_RIGHT -> clickWorkbenchInput(player, chest, packet.playerSlot, true);
                case TAKE_WORKBENCH_RESULT -> takeWorkbenchResult(player, chest, false);
                case QUICK_MOVE_WORKBENCH_RESULT -> takeWorkbenchResult(player, chest, true);
                case CLICK_SMITHING_LEFT -> clickSmithingInput(player, chest, packet.playerSlot, false);
                case CLICK_SMITHING_RIGHT -> clickSmithingInput(player, chest, packet.playerSlot, true);
                case TAKE_SMITHING_RESULT -> takeSmithingResult(player, chest, false);
                case QUICK_MOVE_SMITHING_RESULT -> takeSmithingResult(player, chest, true);
case CLICK_STONECUTTER_LEFT -> clickStonecutterInput(player, chest, false);
                case CLICK_STONECUTTER_RIGHT -> clickStonecutterInput(player, chest, true);
                case DISTRIBUTE_WORKBENCH -> distributeWorkbench(player, chest, packet.workbenchDistribution);
                case SELECT_STONECUTTER_RECIPE -> selectStonecutterRecipe(player, packet.playerSlot);
                case TAKE_STONECUTTER_RESULT -> takeStonecutterResult(player, chest, false);
                case QUICK_MOVE_STONECUTTER_RESULT -> takeStonecutterResult(player, chest, true);
                case RETURN_CARRIED -> {
                    returnCarried(player);
                    returnSuiteInputs(player);
                }
                default -> {
                }
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            send(player, chest);
        });
    }

    private static void clickPlayer(ServerPlayer player, int slot, boolean rightClick) {
        if (slot < 0 || slot >= player.getInventory().items.size()) return;
        ItemStack slotStack = player.getInventory().items.get(slot);
        ItemStack carried = carried(player);
        if (rightClick) {
            rightClickPlayerSlot(player, slot, slotStack, carried);
        } else {
            leftClickPlayerSlot(player, slot, slotStack, carried);
        }
    }

    private static void leftClickPlayerSlot(ServerPlayer player, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            setCarried(player, slotStack.copy());
            player.getInventory().items.set(slot, ItemStack.EMPTY);
            return;
        }

        if (slotStack.isEmpty()) {
            player.getInventory().items.set(slot, carried.copy());
            clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried)) {
            int moved = Math.min(carried.getCount(), Math.max(0, slotStack.getMaxStackSize() - slotStack.getCount()));
            if (moved > 0) {
                slotStack.grow(moved);
                carried.shrink(moved);
                if (carried.isEmpty()) clearCarried(player);
            }
            return;
        }

        player.getInventory().items.set(slot, carried.copy());
        setCarried(player, slotStack.copy());
    }

    private static void rightClickPlayerSlot(ServerPlayer player, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            int taken = (slotStack.getCount() + 1) / 2;
            ItemStack picked = slotStack.copyWithCount(taken);
            slotStack.shrink(taken);
            if (slotStack.isEmpty()) player.getInventory().items.set(slot, ItemStack.EMPTY);
            setCarried(player, picked);
            return;
        }

        if (slotStack.isEmpty()) {
            player.getInventory().items.set(slot, carried.copyWithCount(1));
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried) && slotStack.getCount() < slotStack.getMaxStackSize()) {
            slotStack.grow(1);
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
        }
    }

    private static void clickChest(ServerPlayer player, BaseChestBlockEntity chest, ItemStack prototype, boolean rightClick) {
        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            if (prototype == null || prototype.isEmpty()) return;
            int stored = storedCount(chest, prototype);
            if (stored <= 0) return;
            int slotStackSize = Math.min(prototype.getMaxStackSize(), stored);
            int amount = rightClick ? (slotStackSize + 1) / 2 : slotStackSize;
            int removed = chest.removeItem(prototype.copyWithCount(1), amount);
            if (removed > 0) setCarried(player, prototype.copyWithCount(removed));
            return;
        }

        if (!rightClick && prototype != null && !prototype.isEmpty()
                && !ItemStack.isSameItemSameComponents(carried, prototype)) {
            int deposited = chest.addItem(carried, carried.getCount());
            carried.shrink(deposited);
            if (carried.isEmpty()) {
                clearCarried(player);
                int stored = storedCount(chest, prototype);
                int removed = chest.removeItem(prototype.copyWithCount(1), Math.min(prototype.getMaxStackSize(), stored));
                if (removed > 0) setCarried(player, prototype.copyWithCount(removed));
            }
            return;
        }

        int amount = rightClick ? 1 : carried.getCount();
        int added = chest.addItem(carried, amount);
        carried.shrink(added);
        if (carried.isEmpty()) clearCarried(player);
    }

    private static void quickMovePlayerToChest(ServerPlayer player, BaseChestBlockEntity chest, int slot) {
        if (slot < 0 || slot >= player.getInventory().items.size()) return;
        ItemStack source = player.getInventory().items.get(slot);
        if (source.isEmpty()) return;
        int added = chest.addItem(source, source.getCount());
        if (added <= 0) return;
        source.shrink(added);
        if (source.isEmpty()) player.getInventory().items.set(slot, ItemStack.EMPTY);
    }

    private static void quickMoveChestToPlayer(ServerPlayer player, BaseChestBlockEntity chest, ItemStack prototype) {
        if (prototype == null || prototype.isEmpty()) return;
        int stored = storedCount(chest, prototype);
        if (stored <= 0) return;
        int amount = Math.min(prototype.getMaxStackSize(), stored);
        int removed = chest.removeItem(prototype.copyWithCount(1), amount);
        if (removed <= 0) return;

        ItemStack moving = prototype.copyWithCount(removed);
        player.getInventory().add(moving);
        int inserted = removed - moving.getCount();
        if (moving.getCount() > 0) chest.addItem(prototype.copyWithCount(1), moving.getCount());
    }

    private static int storedCount(BaseChestBlockEntity chest, ItemStack prototype) {
        return chest.getStoredItems().stream()
                .filter(entry -> entry.matches(prototype))
                .mapToInt(StoredItemStack::count)
                .findFirst()
                .orElse(0);
    }

    private static void clickFurnace(ServerPlayer player, BaseChestBlockEntity chest, int slot, boolean rightClick) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        if (slot < 0 || slot >= 3) return;
        NonNullList<ItemStack> items = chest.getSuiteFurnaceItems();
        if (slot == 2) {
            clickFurnaceOutput(player, items, rightClick);
            chest.setChanged();
            return;
        }

        ItemStack slotStack = items.get(slot);
        ItemStack carried = carried(player);
        if (rightClick) {
            rightClickWorkbenchSlot(player, items, slot, slotStack, carried);
        } else {
            leftClickWorkbenchSlot(player, items, slot, slotStack, carried);
        }
        chest.refreshSuiteFurnaceRecipe();
    }

    private static void clickFurnaceOutput(ServerPlayer player, NonNullList<ItemStack> items, boolean rightClick) {
        ItemStack output = items.get(2);
        if (output.isEmpty()) return;
        ItemStack carried = carried(player);
        int amount = rightClick ? Math.max(1, (output.getCount() + 1) / 2) : output.getCount();

        if (carried.isEmpty()) {
            setCarried(player, output.copyWithCount(amount));
            output.shrink(amount);
            if (output.isEmpty()) items.set(2, ItemStack.EMPTY);
            return;
        }

        if (ItemStack.isSameItemSameComponents(carried, output) && carried.getCount() < carried.getMaxStackSize()) {
            int moved = Math.min(amount, carried.getMaxStackSize() - carried.getCount());
            carried.grow(moved);
            output.shrink(moved);
            if (output.isEmpty()) items.set(2, ItemStack.EMPTY);
        }
    }

    private static void quickMoveFurnaceOutput(ServerPlayer player, BaseChestBlockEntity chest) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        ItemStack output = chest.getSuiteFurnaceItems().get(2);
        if (output.isEmpty()) return;
        ItemStack moving = output.copy();
        player.getInventory().add(moving);
        int moved = output.getCount() - moving.getCount();
        if (moved > 0) output.shrink(moved);
        if (output.isEmpty()) chest.getSuiteFurnaceItems().set(2, ItemStack.EMPTY);
        chest.setChanged();
    }

    private static void clickSuitePool(ServerPlayer player, BaseChestBlockEntity chest, int pool, int slot, boolean rightClick) {
        if (!chest.hasSuiteUpgrade()) return;
        chest.getSuiteOrders().clickPool(pool, slot, carried(player), rightClick, stack -> setCarried(player, stack));
    }

    private static void setSuiteOrderTarget(ServerPlayer player, ItemStack requested) {
        if (requested != null && !requested.isEmpty()) {
            SUITE_ORDER_TARGET.put(player.getUUID(), requested.copyWithCount(1));
            SUITE_ORDER_QUANTITY.putIfAbsent(player.getUUID(), Math.max(1, requested.getCount()));
            return;
        }
        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            SUITE_ORDER_TARGET.remove(player.getUUID());
            return;
        }
        SUITE_ORDER_TARGET.put(player.getUUID(), carried.copyWithCount(1));
        SUITE_ORDER_QUANTITY.putIfAbsent(player.getUUID(), Math.max(1, carried.getCount()));
    }

    private static void adjustSuiteOrderQuantity(ServerPlayer player, int delta) {
        int current = suiteOrderQuantity(player);
        int next = Math.max(1, Math.min(9999, current + delta));
        SUITE_ORDER_QUANTITY.put(player.getUUID(), next);
    }

    private static void setSuiteOrderQuantity(ServerPlayer player, int quantity) {
        SUITE_ORDER_QUANTITY.put(player.getUUID(), Math.max(1, Math.min(9999, quantity)));
    }

    private static void createSuiteOrder(ServerPlayer player, BaseChestBlockEntity chest) {
        if (!chest.hasSuiteUpgrade()) return;
        ItemStack target = SUITE_ORDER_TARGET.getOrDefault(player.getUUID(), ItemStack.EMPTY);
        int quantity = suiteOrderQuantity(player);
        if (chest.getSuiteOrders().createOrder(player.getUUID(), target, quantity)) {
            SUITE_ORDER_TARGET.remove(player.getUUID());
            SUITE_ORDER_QUANTITY.put(player.getUUID(), 1);
        }
    }

private static void clickWorkbenchInput(ServerPlayer player, BaseChestBlockEntity chest, int slot, boolean rightClick) {
        if (!chest.hasSuiteUpgrade()) {
            returnWorkbenchInputs(player);
            return;
        }
        if (slot < 0 || slot >= 9) return;
        NonNullList<ItemStack> inputs = workbenchInputs(player);
        ItemStack slotStack = inputs.get(slot);
        ItemStack carried = carried(player);
        if (rightClick) {
            rightClickWorkbenchSlot(player, inputs, slot, slotStack, carried);
        } else {
            leftClickWorkbenchSlot(player, inputs, slot, slotStack, carried);
        }
    }

    /**
     * 工作台左键长按拖拽的最终结算：carried 物品按客户端算出的“每槽本轮新增量”一次性分发。
     * 客户端在拖拽期间模拟显示，服务端在此调用前 grid 仍是拖拽开始时的状态。
     * 若 carried 不足或种类不符，本轮不会产生任何副作用（保持原子）。
     */
    private static void distributeWorkbench(ServerPlayer player, BaseChestBlockEntity chest, int[] addCounts) {
        if (!chest.hasSuiteUpgrade()) {
            returnWorkbenchInputs(player);
            return;
        }
        if (addCounts == null || addCounts.length != 9) return;
        ItemStack carried = carried(player);
        if (carried.isEmpty()) return;

        int total = 0;
        for (int c : addCounts) {
            if (c < 0) return;
            total += c;
        }
        if (total <= 0 || total > carried.getCount()) return;

        NonNullList<ItemStack> inputs = workbenchInputs(player);
        // 原子预校验：每个待分槽必须为空或同种同组件，否则放弃整轮。
        for (int i = 0; i < 9; i++) {
            int add = addCounts[i];
            if (add <= 0) continue;
            ItemStack slotStack = inputs.get(i);
            if (slotStack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(slotStack, carried)) return;
            if (slotStack.getCount() + add > slotStack.getMaxStackSize()) return;
        }

        for (int i = 0; i < 9; i++) {
            int add = addCounts[i];
            if (add <= 0) continue;
            ItemStack slotStack = inputs.get(i);
            if (slotStack.isEmpty()) {
                inputs.set(i, carried.copyWithCount(add));
            } else {
                slotStack.grow(add);
            }
            carried.shrink(add);
        }
        if (carried.isEmpty()) clearCarried(player);
        chest.setChanged();
    }

    private static void leftClickWorkbenchSlot(ServerPlayer player, NonNullList<ItemStack> inputs, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            setCarried(player, slotStack.copy());
            inputs.set(slot, ItemStack.EMPTY);
            return;
        }

        if (slotStack.isEmpty()) {
            inputs.set(slot, carried.copy());
            clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried)) {
            int moved = Math.min(carried.getCount(), Math.max(0, slotStack.getMaxStackSize() - slotStack.getCount()));
            if (moved > 0) {
                slotStack.grow(moved);
                carried.shrink(moved);
                if (carried.isEmpty()) clearCarried(player);
            }
            return;
        }

        inputs.set(slot, carried.copy());
        setCarried(player, slotStack.copy());
    }

    private static void rightClickWorkbenchSlot(ServerPlayer player, NonNullList<ItemStack> inputs, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            int taken = (slotStack.getCount() + 1) / 2;
            ItemStack picked = slotStack.copyWithCount(taken);
            slotStack.shrink(taken);
            if (slotStack.isEmpty()) inputs.set(slot, ItemStack.EMPTY);
            setCarried(player, picked);
            return;
        }

        if (slotStack.isEmpty()) {
            inputs.set(slot, carried.copyWithCount(1));
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried) && slotStack.getCount() < slotStack.getMaxStackSize()) {
            slotStack.grow(1);
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
        }
    }

    private static void takeWorkbenchResult(ServerPlayer player, BaseChestBlockEntity chest, boolean quickMove) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        NonNullList<ItemStack> inputs = workbenchInputs(player);
        Optional<RecipeHolder<CraftingRecipe>> recipe = workbenchRecipe(player, inputs);
        if (recipe.isEmpty()) return;
        CraftingInput input = CraftingInput.of(3, 3, copyInputs(inputs));
        ItemStack result = recipe.get().value().assemble(input, player.level().registryAccess());
        if (result.isEmpty()) return;

        if (quickMove) {
            while (!result.isEmpty()) {
                if (!canInventoryFit(player, result)) break;
                consumeWorkbenchIngredients(player, inputs, recipe.get(), input);
                ItemStack moving = result.copy();
                player.getInventory().add(moving);
                if (!moving.isEmpty()) player.drop(moving, false);
                recipe = workbenchRecipe(player, inputs);
                if (recipe.isEmpty()) break;
                input = CraftingInput.of(3, 3, copyInputs(inputs));
                result = recipe.get().value().assemble(input, player.level().registryAccess());
            }
            return;
        }

        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            setCarried(player, result.copy());
            consumeWorkbenchIngredients(player, inputs, recipe.get(), input);
            return;
        }

        if (ItemStack.isSameItemSameComponents(carried, result)
                && carried.getCount() + result.getCount() <= carried.getMaxStackSize()) {
            carried.grow(result.getCount());
            consumeWorkbenchIngredients(player, inputs, recipe.get(), input);
        }
    }

    private static void consumeWorkbenchIngredients(ServerPlayer player, NonNullList<ItemStack> inputs,
                                                    RecipeHolder<CraftingRecipe> recipe, CraftingInput input) {
        NonNullList<ItemStack> remaining = recipe.value().getRemainingItems(input);
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = inputs.get(i);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) inputs.set(i, ItemStack.EMPTY);
            }

            ItemStack remainder = i < remaining.size() ? remaining.get(i) : ItemStack.EMPTY;
            if (remainder.isEmpty()) continue;
            ItemStack slotStack = inputs.get(i);
            if (slotStack.isEmpty()) {
                inputs.set(i, remainder.copy());
            } else if (ItemStack.isSameItemSameComponents(slotStack, remainder)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int moved = Math.min(remainder.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
                slotStack.grow(moved);
                if (moved < remainder.getCount()) giveOrDrop(player, remainder.copyWithCount(remainder.getCount() - moved));
            } else {
                giveOrDrop(player, remainder.copy());
            }
        }
    }

    private static Optional<RecipeHolder<CraftingRecipe>> workbenchRecipe(ServerPlayer player, NonNullList<ItemStack> inputs) {
        CraftingInput input = CraftingInput.of(3, 3, copyInputs(inputs));
        if (input.isEmpty()) return Optional.empty();
        return player.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, player.level());
    }

    private static void clickSmithingInput(ServerPlayer player, BaseChestBlockEntity chest, int slot, boolean rightClick) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        if (slot < 0 || slot >= 3) return;
        NonNullList<ItemStack> inputs = smithingInputs(player);
        ItemStack slotStack = inputs.get(slot);
        ItemStack carried = carried(player);
        if (rightClick) {
            rightClickWorkbenchSlot(player, inputs, slot, slotStack, carried);
        } else {
            leftClickWorkbenchSlot(player, inputs, slot, slotStack, carried);
        }
    }

    private static void takeSmithingResult(ServerPlayer player, BaseChestBlockEntity chest, boolean quickMove) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        NonNullList<ItemStack> inputs = smithingInputs(player);
        Optional<RecipeHolder<SmithingRecipe>> recipe = smithingRecipe(player, inputs);
        if (recipe.isEmpty()) return;
        SmithingRecipeInput input = smithingInput(inputs);
        ItemStack result = recipe.get().value().assemble(input, player.level().registryAccess());
        if (result.isEmpty() || !result.isItemEnabled(player.level().enabledFeatures())) return;

        if (quickMove) {
            while (!result.isEmpty()) {
                if (!canInventoryFit(player, result)) break;
                consumeSmithingIngredients(player, inputs, recipe.get(), input);
                ItemStack moving = result.copy();
                player.getInventory().add(moving);
                if (!moving.isEmpty()) player.drop(moving, false);
                recipe = smithingRecipe(player, inputs);
                if (recipe.isEmpty()) break;
                input = smithingInput(inputs);
                result = recipe.get().value().assemble(input, player.level().registryAccess());
                if (!result.isItemEnabled(player.level().enabledFeatures())) break;
            }
            return;
        }

        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            setCarried(player, result.copy());
            consumeSmithingIngredients(player, inputs, recipe.get(), input);
            return;
        }

        if (ItemStack.isSameItemSameComponents(carried, result)
                && carried.getCount() + result.getCount() <= carried.getMaxStackSize()) {
            carried.grow(result.getCount());
            consumeSmithingIngredients(player, inputs, recipe.get(), input);
        }
    }

    private static void consumeSmithingIngredients(ServerPlayer player, NonNullList<ItemStack> inputs,
                                                   RecipeHolder<SmithingRecipe> recipe, SmithingRecipeInput input) {
        NonNullList<ItemStack> remaining = recipe.value().getRemainingItems(input);
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = inputs.get(i);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) inputs.set(i, ItemStack.EMPTY);
            }
            ItemStack remainder = i < remaining.size() ? remaining.get(i) : ItemStack.EMPTY;
            if (!remainder.isEmpty()) insertRemainder(player, inputs, i, remainder);
        }
    }

    private static Optional<RecipeHolder<SmithingRecipe>> smithingRecipe(ServerPlayer player, NonNullList<ItemStack> inputs) {
        SmithingRecipeInput input = smithingInput(inputs);
        if (input.isEmpty()) return Optional.empty();
        return player.level().getRecipeManager().getRecipesFor(RecipeType.SMITHING, input, player.level()).stream()
                .filter(holder -> {
                    ItemStack result = holder.value().assemble(input, player.level().registryAccess());
                    return !result.isEmpty() && result.isItemEnabled(player.level().enabledFeatures());
                })
                .findFirst();
    }

    private static SmithingRecipeInput smithingInput(NonNullList<ItemStack> inputs) {
        return new SmithingRecipeInput(inputs.get(0).copy(), inputs.get(1).copy(), inputs.get(2).copy());
    }

    private static ItemStack smithingResult(ServerPlayer player) {
        NonNullList<ItemStack> inputs = smithingInputs(player);
        Optional<RecipeHolder<SmithingRecipe>> recipe = smithingRecipe(player, inputs);
        if (recipe.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = recipe.get().value().assemble(smithingInput(inputs), player.level().registryAccess());
        return result.isItemEnabled(player.level().enabledFeatures()) ? result : ItemStack.EMPTY;
    }

    private static void clickStonecutterInput(ServerPlayer player, BaseChestBlockEntity chest, boolean rightClick) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        NonNullList<ItemStack> input = stonecutterInput(player);
        ItemStack before = input.get(0).copy();
        if (rightClick) {
            rightClickWorkbenchSlot(player, input, 0, input.get(0), carried(player));
        } else {
            leftClickWorkbenchSlot(player, input, 0, input.get(0), carried(player));
        }
        if (!ItemStack.isSameItemSameComponents(before, input.get(0))) {
            STONECUTTER_SELECTED.put(player.getUUID(), -1);
        }
    }

    private static void selectStonecutterRecipe(ServerPlayer player, int index) {
        List<RecipeHolder<StonecutterRecipe>> recipes = stonecutterRecipes(player);
        if (index >= 0 && index < recipes.size()) {
            STONECUTTER_SELECTED.put(player.getUUID(), index);
        }
    }

    private static void takeStonecutterResult(ServerPlayer player, BaseChestBlockEntity chest, boolean quickMove) {
        if (!chest.hasSuiteUpgrade()) {
            returnSuiteInputs(player);
            return;
        }
        NonNullList<ItemStack> input = stonecutterInput(player);
        List<RecipeHolder<StonecutterRecipe>> recipes = stonecutterRecipes(player);
        int selected = stonecutterSelected(player);
        if (selected < 0 || selected >= recipes.size() || input.get(0).isEmpty()) return;
        RecipeHolder<StonecutterRecipe> recipe = recipes.get(selected);
        ItemStack result = recipe.value().assemble(new SingleRecipeInput(input.get(0)), player.level().registryAccess());
        if (result.isEmpty() || !result.isItemEnabled(player.level().enabledFeatures())) return;

        if (quickMove) {
            while (!input.get(0).isEmpty()) {
                if (!canInventoryFit(player, result)) break;
                input.get(0).shrink(1);
                if (input.get(0).isEmpty()) input.set(0, ItemStack.EMPTY);
                ItemStack moving = result.copy();
                player.getInventory().add(moving);
                if (!moving.isEmpty()) player.drop(moving, false);
                recipes = stonecutterRecipes(player);
                selected = stonecutterSelected(player);
                if (selected < 0 || selected >= recipes.size()) break;
                recipe = recipes.get(selected);
                result = recipe.value().assemble(new SingleRecipeInput(input.get(0)), player.level().registryAccess());
                if (result.isEmpty() || !result.isItemEnabled(player.level().enabledFeatures())) break;
            }
            return;
        }

        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            setCarried(player, result.copy());
            input.get(0).shrink(1);
            if (input.get(0).isEmpty()) input.set(0, ItemStack.EMPTY);
            return;
        }

        if (ItemStack.isSameItemSameComponents(carried, result)
                && carried.getCount() + result.getCount() <= carried.getMaxStackSize()) {
            carried.grow(result.getCount());
            input.get(0).shrink(1);
            if (input.get(0).isEmpty()) input.set(0, ItemStack.EMPTY);
        }
    }

    private static List<RecipeHolder<StonecutterRecipe>> stonecutterRecipes(ServerPlayer player) {
        ItemStack input = stonecutterInput(player).get(0);
        if (input.isEmpty()) return List.of();
        return player.level().getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, new SingleRecipeInput(input), player.level()).stream()
                .filter(holder -> {
                    ItemStack result = holder.value().assemble(new SingleRecipeInput(input), player.level().registryAccess());
                    return !result.isEmpty() && result.isItemEnabled(player.level().enabledFeatures());
                })
                .toList();
    }

    private static List<ItemStack> stonecutterResults(ServerPlayer player) {
        ItemStack input = stonecutterInput(player).get(0);
        if (input.isEmpty()) return List.of();
        List<ItemStack> results = new ArrayList<>();
        for (RecipeHolder<StonecutterRecipe> recipe : stonecutterRecipes(player)) {
            ItemStack result = recipe.value().assemble(new SingleRecipeInput(input), player.level().registryAccess());
            if (!result.isEmpty()) results.add(result.copy());
        }
        return results;
    }

    private static ItemStack stonecutterResult(ServerPlayer player) {
        List<RecipeHolder<StonecutterRecipe>> recipes = stonecutterRecipes(player);
        int selected = stonecutterSelected(player);
        if (selected < 0 || selected >= recipes.size()) return ItemStack.EMPTY;
        ItemStack input = stonecutterInput(player).get(0);
        if (input.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = recipes.get(selected).value().assemble(new SingleRecipeInput(input), player.level().registryAccess());
        return result.isItemEnabled(player.level().enabledFeatures()) ? result : ItemStack.EMPTY;
    }

    private static int suiteOrderQuantity(ServerPlayer player) {
        return Math.max(1, SUITE_ORDER_QUANTITY.getOrDefault(player.getUUID(), 1));
    }

    private static List<TabletChestSyncPacket.OrderEntry> suiteOrderEntries(BaseChestBlockEntity chest) {
        List<TabletChestSyncPacket.OrderEntry> entries = new ArrayList<>();
        for (SuiteOrderSystem.OrderView view : chest.getSuiteOrders().orderViewsForSync()) {
            entries.add(new TabletChestSyncPacket.OrderEntry(view.id(), view.target(), view.requested(), view.ready(),
                    view.claimed(), view.state(), view.reason(), view.canClaim(), view.canRecover(), view.canDelete()));
        }
        return entries;
    }

    private static int stonecutterSelected(ServerPlayer player) {
        int selected = STONECUTTER_SELECTED.getOrDefault(player.getUUID(), -1);
        int size = stonecutterRecipes(player).size();
        if (selected >= size) {
            selected = -1;
            STONECUTTER_SELECTED.put(player.getUUID(), -1);
        }
        return selected;
    }

    private static ItemStack workbenchResult(ServerPlayer player) {
        NonNullList<ItemStack> inputs = workbenchInputs(player);
        Optional<RecipeHolder<CraftingRecipe>> recipe = workbenchRecipe(player, inputs);
        if (recipe.isEmpty()) return ItemStack.EMPTY;
        return recipe.get().value().assemble(CraftingInput.of(3, 3, copyInputs(inputs)), player.level().registryAccess());
    }

    private static NonNullList<ItemStack> workbenchInputs(ServerPlayer player) {
        return WORKBENCH_INPUTS.computeIfAbsent(player.getUUID(), id -> NonNullList.withSize(9, ItemStack.EMPTY));
    }

    private static NonNullList<ItemStack> smithingInputs(ServerPlayer player) {
        return SMITHING_INPUTS.computeIfAbsent(player.getUUID(), id -> NonNullList.withSize(3, ItemStack.EMPTY));
    }

    private static NonNullList<ItemStack> stonecutterInput(ServerPlayer player) {
        return STONECUTTER_INPUTS.computeIfAbsent(player.getUUID(), id -> NonNullList.withSize(1, ItemStack.EMPTY));
    }

    private static List<ItemStack> workbenchInputsForSync(ServerPlayer player) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : workbenchInputs(player)) copy.add(stack.copy());
        return copy;
    }

    private static List<ItemStack> smithingInputsForSync(ServerPlayer player) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : smithingInputs(player)) copy.add(stack.copy());
        return copy;
    }

    private static List<ItemStack> copyInputs(NonNullList<ItemStack> inputs) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : inputs) copy.add(stack.copy());
        return copy;
    }

    private static void returnWorkbenchInputs(ServerPlayer player) {
        NonNullList<ItemStack> inputs = WORKBENCH_INPUTS.remove(player.getUUID());
        if (inputs == null) return;
        for (ItemStack stack : inputs) {
            if (!stack.isEmpty()) giveOrDrop(player, stack.copy());
        }
    }

    private static void returnSuiteInputs(ServerPlayer player) {
        returnWorkbenchInputs(player);
        NonNullList<ItemStack> smithing = SMITHING_INPUTS.remove(player.getUUID());
        if (smithing != null) {
            for (ItemStack stack : smithing) {
                if (!stack.isEmpty()) giveOrDrop(player, stack.copy());
            }
        }
        NonNullList<ItemStack> cutting = STONECUTTER_INPUTS.remove(player.getUUID());
        if (cutting != null) {
            for (ItemStack stack : cutting) {
                if (!stack.isEmpty()) giveOrDrop(player, stack.copy());
            }
        }
        STONECUTTER_SELECTED.remove(player.getUUID());
    }

    private static void insertRemainder(ServerPlayer player, NonNullList<ItemStack> inputs, int slot, ItemStack remainder) {
        ItemStack slotStack = inputs.get(slot);
        if (slotStack.isEmpty()) {
            inputs.set(slot, remainder.copy());
        } else if (ItemStack.isSameItemSameComponents(slotStack, remainder)
                && slotStack.getCount() < slotStack.getMaxStackSize()) {
            int moved = Math.min(remainder.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
            slotStack.grow(moved);
            if (moved < remainder.getCount()) giveOrDrop(player, remainder.copyWithCount(remainder.getCount() - moved));
        } else {
            giveOrDrop(player, remainder.copy());
        }
    }

    private static boolean canInventoryFit(ServerPlayer player, ItemStack stack) {
        int remaining = stack.getCount();
        for (ItemStack invStack : player.getInventory().items) {
            if (!invStack.isEmpty() && ItemStack.isSameItemSameComponents(invStack, stack)) {
                remaining -= Math.max(0, invStack.getMaxStackSize() - invStack.getCount());
                if (remaining <= 0) return true;
            }
        }
        for (ItemStack invStack : player.getInventory().items) {
            if (invStack.isEmpty()) {
                remaining -= stack.getMaxStackSize();
                if (remaining <= 0) return true;
            }
        }
        return false;
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        ItemStack remaining = stack.copy();
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) player.drop(remaining, false);
    }

    private static ItemStack carried(ServerPlayer player) {
        ItemStack stack = CARRIED.get(player.getUUID());
        return stack == null ? ItemStack.EMPTY : stack;
    }

    private static void setCarried(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) clearCarried(player);
        else CARRIED.put(player.getUUID(), stack.copy());
    }

    private static void clearCarried(ServerPlayer player) {
        CARRIED.remove(player.getUUID());
    }

    private static void returnCarried(ServerPlayer player) {
        ItemStack stack = carried(player);
        if (stack.isEmpty()) return;
        giveOrDrop(player, stack);
        clearCarried(player);
    }

    private static void sendUnavailable(ServerPlayer player, boolean bound, String message) {
        PacketDistributor.sendToPlayer(player, new TabletChestSyncPacket(
                bound, false, message, "", "", BlockPos.ZERO, 0, 0, false, carried(player).copy(),
                List.of(), 0, 0, 0, 0, 0,
                workbenchInputsForSync(player), workbenchResult(player),
                smithingInputsForSync(player), smithingResult(player),
                stonecutterInput(player).get(0).copy(), stonecutterResults(player),
                stonecutterSelected(player), stonecutterResult(player),
                SUITE_ORDER_TARGET.getOrDefault(player.getUUID(), ItemStack.EMPTY).copy(),
                suiteOrderQuantity(player), 0,
                List.of(), List.of(), List.of(),
                List.of()));
    }

    private static void send(ServerPlayer player, BaseChestBlockEntity chest) {
        List<ChestSyncPacket.ItemEntry> items = new ArrayList<>();
        for (StoredItemStack entry : chest.getStoredItems()) {
            items.add(new ChestSyncPacket.ItemEntry(entry.prototype(), entry.count()));
        }
        items.sort(Comparator.<ChestSyncPacket.ItemEntry>comparingInt(ChestSyncPacket.ItemEntry::count).reversed());

        String dimension = chest.getLevel() == null ? "" : chest.getLevel().dimension().location().toString();
        PacketDistributor.sendToPlayer(player, new TabletChestSyncPacket(
                true,
                true,
                "",
                chest.getChestId(),
                dimension,
                chest.getBlockPos(),
                chest.getUsedCapacity(),
                chest.getMaxItemCapacity(),
                chest.hasSuiteUpgrade(),
                carried(player).copy(),
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceItemsForSync() : List.of(),
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceLitTime() : 0,
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceLitDuration() : 0,
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceCookingProgress() : 0,
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceCookingTotalTime() : 0,
                chest.hasSuiteUpgrade() ? chest.getSuiteFurnaceMode() : 0,
                chest.hasSuiteUpgrade() ? workbenchInputsForSync(player) : List.of(),
                chest.hasSuiteUpgrade() ? workbenchResult(player) : ItemStack.EMPTY,
                chest.hasSuiteUpgrade() ? smithingInputsForSync(player) : List.of(),
                chest.hasSuiteUpgrade() ? smithingResult(player) : ItemStack.EMPTY,
                chest.hasSuiteUpgrade() ? stonecutterInput(player).get(0).copy() : ItemStack.EMPTY,
                chest.hasSuiteUpgrade() ? stonecutterResults(player) : List.of(),
                chest.hasSuiteUpgrade() ? stonecutterSelected(player) : -1,
                chest.hasSuiteUpgrade() ? stonecutterResult(player) : ItemStack.EMPTY,
                chest.hasSuiteUpgrade() ? SUITE_ORDER_TARGET.getOrDefault(player.getUUID(), ItemStack.EMPTY).copy() : ItemStack.EMPTY,
                chest.hasSuiteUpgrade() ? suiteOrderQuantity(player) : 1,
                chest.hasSuiteUpgrade() ? chest.getSuiteOrders().maxOrders() : 0,
                chest.hasSuiteUpgrade() ? chest.getSuiteOrders().toolPoolForSync() : List.of(),
                chest.hasSuiteUpgrade() ? chest.getSuiteOrders().resourcePoolForSync() : List.of(),
                chest.hasSuiteUpgrade() ? suiteOrderEntries(chest) : List.of(),
                items
        ));
    }
}
