# 容器系统重构调研

## 当前问题

1. **完全自定义实现**，自己维护所有逻辑：
   - `VirtualChestContainer` 继承 `SimpleContainer`
   - 手动实现 `setItem()` / `removeItem()` / `refill()`
   - 槽位交互全部自己拦截（`ChestSlot.setByPlayer()` / `remove()`）

2. **Bug频发**：
   - 客户端/服务端重复调用
   - 槽位显示与真实数据不同步
   - 玩家手中物品扣除不正确
   - 增量计算逻辑复杂易错

3. **根本矛盾**：
   - **显示层**：需要固定27个槽位（9×3可滚动）
   - **存储层**：`Map<Item, Integer>` 无限种类，每种无限数量
   - 当前通过 `refill(scrollRow)` 动态映射，但导致槽位状态不稳定

---

## 原版容器系统架构

### 1. **标准箱子（ChestBlockEntity）**

```
ChestBlockEntity
  └─ NonNullList<ItemStack> items  (固定27/54槽位)
       ↓
  ChestMenu extends AbstractContainerMenu
       ↓
  每个槽位 = 1个真实的 ItemStack (maxStackSize=64)
```

**特点**：
- 槽位数量固定
- 每个槽位存储1种物品，最多64个（或物品的 maxStackSize）
- 槽位状态稳定，Minecraft 自动处理所有交互逻辑
- **无需自己实现** `setItem` / `removeItem`

### 2. **Hopper / Dropper（类似的容器）**

同样使用 `NonNullList<ItemStack>`，槽位固定。

### 3. **Shulker Box（潜影盒）**

```
ShulkerBoxBlockEntity
  └─ NonNullList<ItemStack> items  (27槽位)
  └─ 可以存储到物品NBT中
```

**启发**：潜影盒证明了"容器可以序列化到NBT并携带"。

---

## 可能的重构方案

### 方案A：继承 `RandomizableContainerBlockEntity`（推荐）

**原理**：使用原版的容器基类，获得免费的交互逻辑。

```java
public abstract class BaseChestBlockEntity extends RandomizableContainerBlockEntity {
    private NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
    
    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }
    
    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }
    
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new BaseChestMenu(containerId, playerInventory, this);
    }
}
```

**优点**：
- ✅ **零bug风险**：所有槽位交互由 Minecraft 原生处理
- ✅ **自动同步**：客户端/服务端同步由基类处理
- ✅ **自动序列化**：`saveAdditional` / `loadAdditional` 自动处理
- ✅ **支持 Hopper 交互**：原版漏斗可以自动工作

**缺点**：
- ❌ **放弃无限容量**：每个槽位最多64个（或 maxStackSize）
- ❌ **放弃1物品=1格**：需要按64拆分成多个槽位

**适配方案**：
- **显示层**：27个固定槽位（原版槽位，直接显示）
- **存储层**：也是27个槽位，但允许滚动切换"页"
- **滚动逻辑**：
  ```
  实际存储：Page 0 [slot 0-26], Page 1 [slot 27-53], Page 2 [slot 54-80]...
  滚动切换页码，每页27个槽位
  ```

---

### 方案B：保留 `Map<Item, Integer>` + 改进同步逻辑

**原理**：继续使用虚拟容器，但改进实现方式。

#### B1：只读槽位 + 自定义点击处理

```java
public class ReadOnlySlot extends Slot {
    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;  // 禁止原版放入
    }
    
    @Override
    public ItemStack remove(int amount) {
        return ItemStack.EMPTY;  // 禁止原版取出
    }
}

// 在 Screen 中完全自定义鼠标点击逻辑
@Override
public boolean mouseClicked(double mx, double my, int button) {
    Slot slot = findSlotUnderMouse();
    if (slot instanceof ReadOnlySlot) {
        // 自己处理取出/放入，发送自定义网络包
        sendCustomPacket(...);
        return true;
    }
}
```

**优点**：
- ✅ 保留 `Map<Item, Integer>` 无限容量
- ✅ 保留 1物品=1格 显示

**缺点**：
- ❌ 需要完全重写鼠标交互（拖拽、Shift+点击、数字键等）
- ❌ 无法使用原版的 `quickMoveStack` 等便利功能
- ❌ 仍然需要自己维护同步逻辑

#### B2：使用 `ItemHandler` (NeoForge/Forge API)

```java
public class UnlimitedItemHandler implements IItemHandler {
    private Map<Item, Integer> storage = new HashMap<>();
    
    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!simulate) {
            storage.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return ItemStack.EMPTY;  // 全部接受
    }
    
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        // ...
    }
}
```

然后在 BlockEntity 中暴露：
```java
@Override
public @NotNull <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
    if (cap == ForgeCapabilities.ITEM_HANDLER) {
        return itemHandlerCap.cast();
    }
    return super.getCapability(cap, side);
}
```

**优点**：
- ✅ 标准化接口，其他mod可以交互
- ✅ 保留无限容量

**缺点**：
- ❌ `ItemHandler` 仍然需要固定槽位数量
- ❌ 无法直接用于 GUI（需要包装成 `SlotItemHandler`）
- ❌ 交互逻辑仍需自己处理

---

### 方案C：混合方案（推荐）

**核心思路**：**分离显示与存储**。

#### 架构：

```
玩家交互层 (27个标准Slot)
    ↓ [仅用于交互，不存储]
临时缓冲区 (NonNullList<ItemStack>)
    ↓ [每tick同步]
真实存储层 (Map<Item, Integer>)
```

#### 实现：

1. **BlockEntity 使用标准容器接口**：
   ```java
   public class BaseChestBlockEntity extends RandomizableContainerBlockEntity {
       // 真实存储（无限容量）
       private Map<Item, Integer> realStorage = new HashMap<>();
       
       // 显示缓冲（27个槽位，用于GUI交互）
       private NonNullList<ItemStack> displayBuffer = NonNullList.withSize(27, ItemStack.EMPTY);
       
       @Override
       protected NonNullList<ItemStack> getItems() {
           return displayBuffer;  // 原版系统操作这个
       }
       
       // 每tick同步：displayBuffer ↔ realStorage
       public static void serverTick(...) {
           be.syncDisplayToStorage();  // 把displayBuffer的变化写入realStorage
           be.syncStorageToDisplay();  // 把realStorage的内容刷新到displayBuffer
       }
   }
   ```

2. **同步逻辑**：
   ```java
   private void syncDisplayToStorage() {
       for (int i = 0; i < displayBuffer.size(); i++) {
           ItemStack stack = displayBuffer.get(i);
           if (!stack.isEmpty()) {
               // 有物品 → 添加到 realStorage
               realStorage.merge(stack.getItem(), stack.getCount(), Integer::sum);
               displayBuffer.set(i, ItemStack.EMPTY);  // 清空缓冲
           }
       }
   }
   
   private void syncStorageToDisplay() {
       // 根据 scrollRow 从 realStorage 填充 displayBuffer
       List<Entry<Item, Integer>> entries = new ArrayList<>(realStorage.entrySet());
       int startIdx = scrollRow * 9;
       for (int i = 0; i < 27 && startIdx + i < entries.size(); i++) {
           Entry<Item, Integer> entry = entries.get(startIdx + i);
           ItemStack stack = new ItemStack(entry.getKey(), Math.min(entry.getValue(), 64));
           displayBuffer.set(i, stack);
       }
   }
   ```

**优点**：
- ✅ **使用原版交互逻辑**：玩家操作 displayBuffer，Minecraft 自动处理
- ✅ **保留无限容量**：真实数据在 realStorage
- ✅ **简化同步**：每tick单向同步，清晰明确

**缺点**：
- ⚠️ 每种物品在 displayBuffer 中最多显示64个
- ⚠️ 需要处理"分批取出"（例如1000个木头需要多次点击）

**改进**：
- 右键取出：一次取64个
- Shift+点击：取全部（分批转移）
- 显示数量用覆盖层渲染（不受64限制）

---

## 推荐方案对比

| 特性 | 方案A (标准容器) | 方案C (混合方案) | 当前方案 (完全自定义) |
|------|-----------------|-----------------|---------------------|
| 交互bug风险 | ✅ 极低 | ✅ 低 | ❌ 高 |
| 无限容量 | ❌ 否 | ✅ 是 | ✅ 是 |
| 1物品=1格 | ❌ 否 | ⚠️ 显示是，交互否 | ✅ 是 |
| 开发复杂度 | ✅ 极低 | ⚠️ 中 | ❌ 极高 |
| 维护成本 | ✅ 低 | ⚠️ 中 | ❌ 高 |
| 原版兼容性 | ✅ 完美 | ✅ 好 | ⚠️ 中 |

---

## 最终建议

### 短期（修复当前bug）：
保持当前架构，但简化逻辑：
1. **移除 `ChestSlot` 的拦截**，改为在 `Container` 层监听变化
2. 使用 `ContainerListener` 接口而非重写 Slot 方法
3. 添加"脏标记"防止重复操作

### 长期（重构）：
**采用方案C（混合方案）**：
1. 玩家操作标准槽位（displayBuffer）
2. 每tick同步到 realStorage
3. GUI 显示时覆盖渲染真实数量

这样既保留了无限容量的需求，又避免了自己实现交互逻辑的bug。

---

## 实现优先级

1. **P0（立即修复）**：添加去重标记，防止重复操作
2. **P1（本周）**：添加详细的调试日志，定位到底哪个环节重复调用
3. **P2（下周）**：评估重构为混合方案的工作量
4. **P3（未来）**：如果混合方案可行，分阶段迁移

---

## 需要确认的需求

1. **是否必须"1物品=1格显示"？**
   - 如果可以接受"1物品占多格"（类似原版），方案A最简单
   
2. **是否必须无限容量？**
   - 如果可以接受"有限但很大"（例如10000格），方案A可行
   
3. **取出交互是否可以"分批"？**
   - 如果可以接受"点击多次取出大量物品"，方案C可行

请告诉我你的需求优先级！
