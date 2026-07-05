package com.pockethomestead.suite;

import com.pockethomestead.api.suite.SuiteOperationTemplate;
import com.pockethomestead.api.suite.SuiteToolRegistry;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.StoredItemStack;
import com.pockethomestead.config.ModConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SuiteOrderSystem {
    public static final int TOOL_POOL = 0;
    public static final int RESOURCE_POOL = 1;

    public enum OrderState {
        RUNNING("运行中"),
        WAITING_TOOL("等待工具"),
        WAITING_MATERIAL("等待材料"),
        PAUSED("已暂停"),
        PAUSING("暂停中"),
        STOPPED("已停止"),
        CANCELING("取消中"),
        READY("可领取"),
        RECOVERABLE("待清理"),
        DONE("已完成");

        private final String label;

        OrderState(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record OrderView(int id, ItemStack target, int requested, int ready, int claimed,
                            String state, String reason, boolean canClaim,
                            boolean canRecover, boolean canDelete) {
    }

    private final BaseChestBlockEntity chest;
    private final List<ItemStack> toolPool = new ArrayList<>();
    private final List<ItemStack> resourcePool = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final List<ToolFuel> toolFuel = new ArrayList<>();
    private int nextOrderId = 1;
    private int schedulerCursor;

    public SuiteOrderSystem(BaseChestBlockEntity chest) {
        this.chest = chest;
    }

    public List<ItemStack> toolPoolForSync() {
        return copyStacks(toolPool);
    }

    public List<ItemStack> resourcePoolForSync() {
        return copyStacks(resourcePool);
    }

    public List<OrderView> orderViewsForSync() {
        List<OrderView> views = new ArrayList<>();
        for (Order order : orders) views.add(order.view());
        return views;
    }

    public boolean hasContent() {
        return !toolPool.isEmpty() || !resourcePool.isEmpty() || !orders.isEmpty() || !toolFuel.isEmpty();
    }

    public int orderCount() {
        return orders.size();
    }

    public int maxOrders() {
        return Math.max(0, ModConfig.SUITE_MAX_ORDERS_PER_CHEST.get());
    }

    public boolean canCreateOrder() {
        int max = maxOrders();
        return max <= 0 || orders.size() < max;
    }

    public boolean createOrder(UUID playerId, ItemStack target, int count) {
        if (target == null || target.isEmpty() || count <= 0 || !canCreateOrder()) return false;
        Order order = new Order(nextOrderId++, playerId, target.copyWithCount(1), count);
        orders.add(order);
        chest.setChanged();
        return true;
    }

    public void stopOrder(int id) {
        Order order = findOrder(id);
        if (order == null || order.cancelRequested) return;
        if (!order.acceptingNewOperations) {
            order.acceptingNewOperations = true;
            order.state = OrderState.WAITING_MATERIAL;
            order.reason = "";
        } else {
            order.acceptingNewOperations = false;
            order.state = order.activeOperations.isEmpty() ? OrderState.PAUSED : OrderState.PAUSING;
            order.reason = order.activeOperations.isEmpty() ? "已暂停" : "当前操作完成后暂停";
        }
        chest.setChanged();
    }

    public void cancelOrder(int id) {
        Order order = findOrder(id);
        if (order == null) return;
        order.acceptingNewOperations = false;
        order.cancelRequested = true;
        moveAll(order.internalInventory, order.recoverableInventory);
        recoverList(order.recoverableInventory);
        order.refreshPassiveState();
        if (order.canDelete()) orders.remove(order);
        chest.setChanged();
    }

    public void deleteOrder(int id) {
        Order order = findOrder(id);
        if (order == null || !order.canDelete()) return;
        orders.remove(order);
        chest.setChanged();
    }

    public void claimOrder(int id) {
        Order order = findOrder(id);
        if (order == null || order.ready <= 0) return;
        ItemStack stack = order.target.copyWithCount(order.ready);
        int added = chest.addItem(stack, stack.getCount());
        if (added <= 0) {
            order.reason = "箱子空间不足";
            return;
        }
        order.ready -= added;
        order.claimed += added;
        if (order.cancelRequested && order.ready <= 0 && order.activeOperations.isEmpty()
                && order.internalInventory.isEmpty() && order.recoverableInventory.isEmpty()) {
            orders.remove(order);
        } else if (order.claimed >= order.requested && order.ready <= 0 && order.activeOperations.isEmpty()) {
            if (order.internalInventory.isEmpty() && order.recoverableInventory.isEmpty()) {
                orders.remove(order);
            } else {
                order.state = OrderState.RECOVERABLE;
                order.reason = "待归还材料";
            }
        } else {
            order.refreshPassiveState();
        }
        chest.setChanged();
    }

    public void recoverOrder(int id) {
        Order order = findOrder(id);
        if (order == null) return;
        recoverList(order.internalInventory);
        recoverList(order.recoverableInventory);
        if (order.canDelete()) {
            orders.remove(order);
        } else if (!order.recoverableInventory.isEmpty() || !order.internalInventory.isEmpty() || order.ready > 0) {
            order.state = OrderState.RECOVERABLE;
            order.reason = "箱子空间不足";
        }
        chest.setChanged();
    }

    public void clickPool(int pool, int index, ItemStack carried, boolean rightClick, java.util.function.Consumer<ItemStack> setCarried) {
        List<ItemStack> stacks = pool == TOOL_POOL ? toolPool : resourcePool;
        int slotCount = Math.max(18, stacks.size() + 1);
        if (index < 0 || index >= slotCount) return;
        if (index >= stacks.size()) {
            if (carried == null || carried.isEmpty()) return;
            int amount = rightClick ? 1 : carried.getCount();
            ItemStack moving = carried.copyWithCount(amount);
            addStack(stacks, moving);
            carried.shrink(amount);
            if (carried.isEmpty()) setCarried.accept(ItemStack.EMPTY);
            compact(stacks);
            chest.setChanged();
            return;
        }

        ItemStack slot = stacks.get(index);
        if (slot.isEmpty()) return;
        if (carried == null || carried.isEmpty()) {
            int amount = rightClick ? Math.max(1, (slot.getCount() + 1) / 2) : slot.getCount();
            setCarried.accept(slot.copyWithCount(amount));
            slot.shrink(amount);
            compact(stacks);
            chest.setChanged();
            return;
        }
        if (ItemStack.isSameItemSameComponents(slot, carried)) {
            int amount = rightClick ? 1 : carried.getCount();
            int moved = Math.min(amount, slot.getMaxStackSize() - slot.getCount());
            if (moved > 0) {
                slot.grow(moved);
                carried.shrink(moved);
                if (carried.isEmpty()) setCarried.accept(ItemStack.EMPTY);
                chest.setChanged();
            }
            return;
        }
        stacks.set(index, carried.copy());
        setCarried.accept(slot.copy());
        compact(stacks);
        chest.setChanged();
    }

    public void tick(ServerLevel level) {
        VanillaSuiteAdapters.registerBuiltIns();
        long now = level.getGameTime();
        boolean changed = completeOperations(now);
        if (!chest.hasSuiteUpgrade()) return;
        int attempts = Math.min(orders.size(), 32);
        for (int i = 0; i < attempts && !orders.isEmpty(); i++) {
            schedulerCursor = Math.floorMod(schedulerCursor, orders.size());
            Order order = orders.get(schedulerCursor);
            schedulerCursor = (schedulerCursor + 1) % Math.max(1, orders.size());
            if (tryFulfillFromPublicStock(order)) {
                changed = true;
                order.refreshPassiveState();
                continue;
            }
            if (!order.acceptingNewOperations || order.cancelRequested || order.claimed + order.ready + order.activeTargetOutputCount() >= order.requested) {
                order.refreshPassiveState();
                continue;
            }
            if (tryStartFor(order, order.target, true, level, now, 0)) {
                changed = true;
            }
        }
        if (changed) chest.setChanged();
    }

    private boolean completeOperations(long now) {
        boolean changed = false;
        for (Order order : orders) {
            Iterator<RunningOperation> it = order.activeOperations.iterator();
            while (it.hasNext()) {
                RunningOperation op = it.next();
                op.resolveRelativeTime(now);
                if (op.finishAt > now) continue;
                it.remove();
                if (order.cancelRequested) {
                    if (op.targetOutput && ItemStack.isSameItemSameComponents(op.output, order.target)) {
                        order.ready += op.output.getCount();
                    } else {
                        addStack(order.recoverableInventory, op.output.copy());
                        recoverList(order.recoverableInventory);
                    }
                } else if (op.targetOutput && ItemStack.isSameItemSameComponents(op.output, order.target)) {
                    order.ready += op.output.getCount();
                } else {
                    addStack(order.internalInventory, op.output.copy());
                }
                changed = true;
            }
            order.refreshPassiveState();
        }
        orders.removeIf(Order::canDeleteAfterCancel);
        return changed;
    }

    private boolean tryStartFor(Order order, ItemStack desired, boolean targetOutput,
                                ServerLevel level, long now, int depth) {
        if (desired.isEmpty()) return false;
        if (depth > Math.max(1, ModConfig.SUITE_PLANNER_MAX_DEPTH.get())) {
            order.state = OrderState.WAITING_MATERIAL;
            order.reason = "找路深度已达上限";
            return false;
        }
        List<SuiteOperationTemplate> candidates = SuiteToolRegistry.operationsFor(level, desired).stream()
                .filter(op -> hasTool(op.tool()))
                .limit(Math.max(1, ModConfig.SUITE_PLANNER_MAX_CANDIDATES_PER_ITEM.get()))
                .toList();
        if (candidates.isEmpty()) {
            order.state = hasAnyRecipe(level, desired) ? OrderState.WAITING_TOOL : OrderState.STOPPED;
            order.reason = hasAnyRecipe(level, desired) ? "缺少可用工具" : "找不到生产路线";
            return false;
        }
        for (SuiteOperationTemplate op : candidates) {
            int toolIndex = availableToolIndex(op.tool());
            if (toolIndex < 0) {
                order.state = OrderState.WAITING_TOOL;
                order.reason = "工具忙碌";
                continue;
            }
            Ingredient missing = firstMissingIngredient(order, op.inputs());
            if (missing != null) {
                for (ItemStack next : ingredientTargets(order, missing)) {
                    if (!next.isEmpty() && tryStartFor(order, next.copyWithCount(1), false, level, now, depth + 1)) {
                        order.state = OrderState.RUNNING;
                        order.reason = "生产中间材料";
                        return true;
                    }
                }
                order.state = OrderState.WAITING_MATERIAL;
                order.reason = "缺少材料";
                continue;
            }
            if (!ensureFuel(op, toolIndex)) {
                order.state = OrderState.WAITING_MATERIAL;
                order.reason = "缺少燃料";
                continue;
            }
            consumeIngredients(order, op.inputs());
            order.activeOperations.add(new RunningOperation(op.tool().copyWithCount(1), op.output().copy(),
                    targetOutput, toolIndex, now + Math.max(1, op.timeTicks())));
            order.state = OrderState.RUNNING;
            order.reason = "";
            return true;
        }
        return false;
    }

    private boolean tryFulfillFromPublicStock(Order order) {
        if (order.cancelRequested || !order.acceptingNewOperations) return false;
        int remaining = order.requested - order.claimed - order.ready - order.activeTargetOutputCount();
        if (remaining <= 0) return false;
        int removed = chest.removeItem(order.target, remaining);
        if (removed <= 0) return false;
        order.ready += removed;
        order.state = OrderState.READY;
        order.reason = "库存已满足";
        return true;
    }

    private boolean hasAnyRecipe(ServerLevel level, ItemStack desired) {
        return !SuiteToolRegistry.operationsFor(level, desired).isEmpty();
    }

    private boolean hasTool(ItemStack tool) {
        return countStacks(toolPool, tool) > 0;
    }

    private int availableToolIndex(ItemStack tool) {
        int total = countStacks(toolPool, tool);
        if (total <= 0) return -1;
        boolean[] busy = new boolean[total];
        for (Order order : orders) {
            for (RunningOperation op : order.activeOperations) {
                if (ItemStack.isSameItemSameComponents(op.tool, tool)
                        && op.toolIndex >= 0 && op.toolIndex < busy.length) {
                    busy[op.toolIndex] = true;
                }
            }
        }
        for (int i = 0; i < busy.length; i++) {
            if (!busy[i]) return i;
        }
        return -1;
    }

    private Ingredient firstMissingIngredient(Order order, List<Ingredient> ingredients) {
        List<ItemStack> reserved = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack match = findIngredientAvailable(order, ingredient, reserved);
            if (match.isEmpty()) return ingredient;
            addStack(reserved, match.copyWithCount(1));
        }
        return null;
    }

    private ItemStack findIngredientAvailable(Order order, Ingredient ingredient, List<ItemStack> reserved) {
        for (ItemStack stack : order.internalInventory) {
            if (!stack.isEmpty() && ingredient.test(stack) && countStacks(reserved, stack) < stack.getCount()) return stack.copyWithCount(1);
        }
        for (StoredItemStack stored : chest.getStoredItems()) {
            ItemStack stack = stored.prototype();
            if (!stack.isEmpty() && ingredient.test(stack) && countStacks(reserved, stack) < stored.count()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private List<ItemStack> ingredientTargets(Order order, Ingredient ingredient) {
        List<ItemStack> targets = new ArrayList<>();
        if (ingredient.isEmpty()) return targets;
        ItemStack existing = findIngredientAvailable(order, ingredient, List.of());
        if (!existing.isEmpty()) targets.add(existing.copyWithCount(1));
        ItemStack[] options = ingredient.getItems();
        int limit = Math.max(1, ModConfig.SUITE_PLANNER_MAX_CANDIDATES_PER_ITEM.get());
        for (ItemStack option : options) {
            if (option.isEmpty() || containsSameStack(targets, option)) continue;
            targets.add(option.copyWithCount(1));
            if (targets.size() >= limit) break;
        }
        return targets;
    }

    private static boolean containsSameStack(List<ItemStack> stacks, ItemStack target) {
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameComponents(stack, target)) return true;
        }
        return false;
    }

    private void consumeIngredients(Order order, List<Ingredient> ingredients) {
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            ItemStack match = removeIngredient(order.internalInventory, ingredient, 1);
            if (!match.isEmpty()) {
                addCraftingRemainder(order, match);
                continue;
            }
            for (StoredItemStack stored : chest.getStoredItems()) {
                ItemStack proto = stored.prototype();
                if (!ingredient.test(proto)) continue;
                int removed = chest.removeItem(proto, 1);
                if (removed > 0) {
                    addCraftingRemainder(order, proto.copyWithCount(1));
                    break;
                }
            }
        }
    }

    private void addCraftingRemainder(Order order, ItemStack consumed) {
        if (consumed.hasCraftingRemainingItem()) {
            addStack(order.internalInventory, consumed.getCraftingRemainingItem());
        }
    }

    private ItemStack removeIngredient(List<ItemStack> stacks, Ingredient ingredient, int count) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !ingredient.test(stack)) continue;
            ItemStack removed = stack.copyWithCount(Math.min(count, stack.getCount()));
            stack.shrink(removed.getCount());
            compact(stacks);
            return removed;
        }
        return ItemStack.EMPTY;
    }

    private boolean ensureFuel(SuiteOperationTemplate op, int toolIndex) {
        if (op.fuelTicks() <= 0) return true;
        String key = toolKey(op.tool()) + "#" + Math.max(0, toolIndex);
        ToolFuel fuel = fuelFor(key);
        while (fuel.ticks < op.fuelTicks()) {
            ItemStack fuelStack = takeFuelItem(op.fuelRecipeType());
            if (fuelStack.isEmpty()) return false;
            fuel.ticks += SuiteToolRegistry.fuelTicks(fuelStack, op.fuelRecipeType());
            if (fuelStack.hasCraftingRemainingItem()) addStack(resourcePool, fuelStack.getCraftingRemainingItem());
        }
        fuel.ticks -= op.fuelTicks();
        return true;
    }

    private ItemStack takeFuelItem(RecipeType<?> recipeType) {
        for (ItemStack stack : resourcePool) {
            if (stack.isEmpty() || SuiteToolRegistry.fuelTicks(stack, recipeType) <= 0) continue;
            ItemStack one = stack.copyWithCount(1);
            stack.shrink(1);
            compact(resourcePool);
            return one;
        }
        return ItemStack.EMPTY;
    }

    private ToolFuel fuelFor(String key) {
        for (ToolFuel fuel : toolFuel) {
            if (fuel.toolKey.equals(key)) return fuel;
        }
        ToolFuel fuel = new ToolFuel(key, 0);
        toolFuel.add(fuel);
        return fuel;
    }

    private Order findOrder(int id) {
        for (Order order : orders) {
            if (order.id == id) return order;
        }
        return null;
    }

    private void recoverList(List<ItemStack> stacks) {
        Iterator<ItemStack> it = stacks.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack.isEmpty()) {
                it.remove();
                continue;
            }
            int added = chest.addItem(stack, stack.getCount());
            stack.shrink(added);
            if (stack.isEmpty()) it.remove();
        }
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : stacks) copy.add(stack.copy());
        return copy;
    }

    private static void addStack(List<ItemStack> stacks, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        int remaining = stack.getCount();
        for (ItemStack existing : stacks) {
            if (!ItemStack.isSameItemSameComponents(existing, stack)) continue;
            int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
            if (moved > 0) {
                existing.grow(moved);
                remaining -= moved;
            }
            if (remaining <= 0) return;
        }
        while (remaining > 0) {
            int moved = Math.min(remaining, stack.getMaxStackSize());
            stacks.add(stack.copyWithCount(moved));
            remaining -= moved;
        }
    }

    private static void moveAll(List<ItemStack> from, List<ItemStack> to) {
        for (ItemStack stack : from) addStack(to, stack);
        from.clear();
    }

    private static int countStacks(List<ItemStack> stacks, ItemStack prototype) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (ItemStack.isSameItemSameComponents(stack, prototype)) count += stack.getCount();
        }
        return count;
    }

    private static void compact(List<ItemStack> stacks) {
        stacks.removeIf(ItemStack::isEmpty);
    }

    private static String toolKey(ItemStack tool) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(tool.getItem()).toString()
                + "|" + tool.getComponentsPatch().hashCode();
    }

    public void save(CompoundTag tag, HolderLookup.Provider reg) {
        tag.putInt("NextOrderId", nextOrderId);
        tag.put("Tools", saveStacks(toolPool, reg));
        tag.put("Resources", saveStacks(resourcePool, reg));
        long now = chest.getLevel() instanceof ServerLevel serverLevel ? serverLevel.getGameTime() : -1L;
        ListTag fuelList = new ListTag();
        for (ToolFuel fuel : toolFuel) {
            if (fuel.ticks <= 0) continue;
            CompoundTag t = new CompoundTag();
            t.putString("ToolKey", fuel.toolKey);
            t.putInt("Ticks", fuel.ticks);
            fuelList.add(t);
        }
        tag.put("ToolFuel", fuelList);
        ListTag orderList = new ListTag();
        for (Order order : orders) orderList.add(order.save(reg, now));
        tag.put("Orders", orderList);
    }

    public void load(CompoundTag tag, HolderLookup.Provider reg) {
        nextOrderId = Math.max(1, tag.getInt("NextOrderId"));
        toolPool.clear();
        resourcePool.clear();
        toolFuel.clear();
        orders.clear();
        loadStacks(tag.getList("Tools", Tag.TAG_COMPOUND), toolPool, reg);
        loadStacks(tag.getList("Resources", Tag.TAG_COMPOUND), resourcePool, reg);
        for (Tag t : tag.getList("ToolFuel", Tag.TAG_COMPOUND)) {
            CompoundTag ft = (CompoundTag) t;
            int ticks = ft.getInt("Ticks");
            if (ticks > 0) toolFuel.add(new ToolFuel(ft.getString("ToolKey"), ticks));
        }
        for (Tag t : tag.getList("Orders", Tag.TAG_COMPOUND)) {
            orders.add(Order.load((CompoundTag) t, reg));
        }
    }

    private static ListTag saveStacks(List<ItemStack> stacks, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.put("Stack", stack.saveOptional(reg));
            list.add(t);
        }
        return list;
    }

    private static void loadStacks(ListTag list, List<ItemStack> target, HolderLookup.Provider reg) {
        for (Tag raw : list) {
            ItemStack stack = ItemStack.parseOptional(reg, ((CompoundTag) raw).getCompound("Stack"));
            if (!stack.isEmpty()) addStack(target, stack);
        }
    }

    private static final class ToolFuel {
        private final String toolKey;
        private int ticks;

        private ToolFuel(String toolKey, int ticks) {
            this.toolKey = toolKey;
            this.ticks = Math.max(0, ticks);
        }
    }

    private static final class RunningOperation {
        private final ItemStack tool;
        private final ItemStack output;
        private final boolean targetOutput;
        private final int toolIndex;
        private long finishAt;
        private boolean relativeTime;

        private RunningOperation(ItemStack tool, ItemStack output, boolean targetOutput, int toolIndex, long finishAt) {
            this(tool, output, targetOutput, toolIndex, finishAt, false);
        }

        private RunningOperation(ItemStack tool, ItemStack output, boolean targetOutput, int toolIndex, long finishAt, boolean relativeTime) {
            this.tool = tool;
            this.output = output;
            this.targetOutput = targetOutput;
            this.toolIndex = Math.max(0, toolIndex);
            this.finishAt = finishAt;
            this.relativeTime = relativeTime;
        }

        private void resolveRelativeTime(long now) {
            if (!relativeTime) return;
            finishAt = now + Math.max(0, finishAt);
            relativeTime = false;
        }

        private CompoundTag save(HolderLookup.Provider reg, long now) {
            CompoundTag tag = new CompoundTag();
            tag.put("Tool", tool.saveOptional(reg));
            tag.put("Output", output.saveOptional(reg));
            tag.putBoolean("TargetOutput", targetOutput);
            tag.putInt("ToolIndex", toolIndex);
            tag.putLong("RemainingTicks", now >= 0 ? Math.max(0, finishAt - now) : Math.max(0, finishAt));
            return tag;
        }

        private static RunningOperation load(CompoundTag tag, HolderLookup.Provider reg) {
            return new RunningOperation(ItemStack.parseOptional(reg, tag.getCompound("Tool")),
                    ItemStack.parseOptional(reg, tag.getCompound("Output")),
                    tag.getBoolean("TargetOutput"), Math.max(0, tag.getInt("ToolIndex")),
                    tag.contains("RemainingTicks") ? tag.getLong("RemainingTicks") : tag.getLong("FinishAt"),
                    tag.contains("RemainingTicks"));
        }
    }

    private static final class Order {
        private final int id;
        private final UUID requester;
        private final ItemStack target;
        private final int requested;
        private int ready;
        private int claimed;
        private boolean acceptingNewOperations = true;
        private boolean cancelRequested;
        private OrderState state = OrderState.WAITING_MATERIAL;
        private String reason = "";
        private final List<ItemStack> internalInventory = new ArrayList<>();
        private final List<ItemStack> recoverableInventory = new ArrayList<>();
        private final List<RunningOperation> activeOperations = new ArrayList<>();

        private Order(int id, UUID requester, ItemStack target, int requested) {
            this.id = id;
            this.requester = requester;
            this.target = target.copyWithCount(1);
            this.requested = Math.max(1, requested);
        }

        private OrderView view() {
            return new OrderView(id, target.copyWithCount(1), requested, ready, claimed,
                    state.label(), reason == null ? "" : reason,
                    ready > 0, !recoverableInventory.isEmpty() || !internalInventory.isEmpty(),
                    canDelete());
        }

        private boolean canDelete() {
            return activeOperations.isEmpty() && ready <= 0 && internalInventory.isEmpty() && recoverableInventory.isEmpty();
        }

        private boolean canDeleteAfterCancel() {
            return cancelRequested && canDelete();
        }

        private int activeTargetOutputCount() {
            int count = 0;
            for (RunningOperation op : activeOperations) {
                if (op.targetOutput && ItemStack.isSameItemSameComponents(op.output, target)) count += op.output.getCount();
            }
            return count;
        }

        private void refreshPassiveState() {
            if (cancelRequested) {
                if (ready > 0) {
                    state = OrderState.READY;
                    reason = "可领取";
                } else if (!activeOperations.isEmpty()) {
                    state = OrderState.CANCELING;
                    reason = "等待当前操作完成";
                } else if (!internalInventory.isEmpty() || !recoverableInventory.isEmpty()) {
                    state = OrderState.RECOVERABLE;
                    reason = "待归还材料";
                } else {
                    state = OrderState.DONE;
                    reason = "已取消";
                }
                return;
            }
            if (!acceptingNewOperations) {
                if (ready > 0 && activeOperations.isEmpty()) {
                    state = OrderState.READY;
                    reason = "可领取";
                } else {
                    state = activeOperations.isEmpty() ? OrderState.PAUSED : OrderState.PAUSING;
                    reason = activeOperations.isEmpty() ? "已暂停" : "当前操作完成后暂停";
                }
                return;
            }
            if (claimed >= requested && ready <= 0 && activeOperations.isEmpty()) {
                state = OrderState.DONE;
                reason = "已完成";
                return;
            }
            if (ready > 0 && claimed + ready >= requested && activeOperations.isEmpty()) {
                state = OrderState.READY;
                reason = "可领取";
                return;
            }
            if (ready > 0 && !acceptingNewOperations && activeOperations.isEmpty()) {
                state = OrderState.READY;
                reason = "可领取";
            }
        }

        private CompoundTag save(HolderLookup.Provider reg, long now) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Id", id);
            tag.putUUID("Requester", requester);
            tag.put("Target", target.saveOptional(reg));
            tag.putInt("Requested", requested);
            tag.putInt("Ready", ready);
            tag.putInt("Claimed", claimed);
            tag.putBoolean("Accepting", acceptingNewOperations);
            tag.putBoolean("CancelRequested", cancelRequested);
            tag.putString("State", state.name());
            tag.putString("Reason", reason == null ? "" : reason);
            tag.put("Internal", saveStacks(internalInventory, reg));
            tag.put("Recoverable", saveStacks(recoverableInventory, reg));
            ListTag ops = new ListTag();
            for (RunningOperation op : activeOperations) ops.add(op.save(reg, now));
            tag.put("Active", ops);
            return tag;
        }

        private static Order load(CompoundTag tag, HolderLookup.Provider reg) {
            Order order = new Order(tag.getInt("Id"),
                    tag.hasUUID("Requester") ? tag.getUUID("Requester") : new UUID(0L, 0L),
                    ItemStack.parseOptional(reg, tag.getCompound("Target")),
                    Math.max(1, tag.getInt("Requested")));
            order.ready = Math.max(0, tag.getInt("Ready"));
            order.claimed = Math.max(0, tag.getInt("Claimed"));
            order.acceptingNewOperations = tag.getBoolean("Accepting");
            order.cancelRequested = tag.getBoolean("CancelRequested");
            try {
                order.state = OrderState.valueOf(tag.getString("State").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                order.state = OrderState.WAITING_MATERIAL;
            }
            order.reason = tag.getString("Reason");
            loadStacks(tag.getList("Internal", Tag.TAG_COMPOUND), order.internalInventory, reg);
            loadStacks(tag.getList("Recoverable", Tag.TAG_COMPOUND), order.recoverableInventory, reg);
            for (Tag raw : tag.getList("Active", Tag.TAG_COMPOUND)) {
                order.activeOperations.add(RunningOperation.load((CompoundTag) raw, reg));
            }
            return order;
        }
    }
}
