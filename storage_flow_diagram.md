# 箱子存储逻辑流程图

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        客户端 (Client)                             │
│  ┌──────────────────┐         ┌──────────────────┐               │
│  │ BaseChestScreen  │         │ BaseChestMenu    │               │
│  │  - 渲染UI        │◄────────┤  - 槽位管理       │               │
│  │  - 滚动条        │         │  - 虚拟容器       │               │
│  │  - 配置面板       │         └──────────────────┘               │
│  └──────────────────┘                                            │
│         │ mouseScrolled()                                        │
│         │ send(7, row)                                           │
│         ▼                                                        │
│  ChestConfigPacket ──────────────────────────────────────────►  │
└─────────────────────────────────────────────────────────────────┘
                                   Network
┌─────────────────────────────────────────────────────────────────┐
│                        服务端 (Server)                             │
│  ChestConfigPacket.handle()                                      │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ BaseChestBlockEntity                                      │   │
│  │   itemStorage: Map<Item, Integer>  (真实存储)            │   │
│  │   viewScrollRow: int              (当前滚动位置)          │   │
│  │   storageDirty: boolean           (同步标志)              │   │
│  └──────────────────────────────────────────────────────────┘   │
│         │                              ▲                         │
│         │ 更新                         │ 读取                    │
│         ▼                              │                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ VirtualChestContainer (27个固定槽位)                      │   │
│  │   refill(scrollRow)                                       │   │
│  │     - 从 Map<Item,Integer> 读取                           │   │
│  │     - 按 scrollRow 偏移映射到 27 个 Slot                  │   │
│  │     - 使用 ItemStack.setCount() 强制设置真实数量           │   │
│  │                                                            │   │
│  │   setItem(slot, stack)                                    │   │
│  │     - 移除旧物品：blockEntity.removeItem()                │   │
│  │     - 添加新物品：blockEntity.addItem()                   │   │
│  │     - 触发同步：storageDirty = true                        │   │
│  │     - 刷新槽位：refill(currentScroll)                      │   │
│  │                                                            │   │
│  │   removeItem(slot, amount)                                │   │
│  │     - 计算取出数量：min(amount, have)                     │   │
│  │     - 从 BlockEntity 移除：blockEntity.removeItem()        │   │
│  │     - 触发同步：storageDirty = true                        │   │
│  │     - 刷新槽位：refill(currentScroll)                      │   │
│  │     - 返回实际移除的物品数量                               │   │
│  └──────────────────────────────────────────────────────────┘   │
│         │                                                        │
│         ▼                                                        │
│  BaseChestMenu.broadcastChanges()                               │
│    - 检查 storageDirty                                           │
│    - 调用 chestContainer.refill(scrollRow)                      │
│    - 重置 storageDirty = false                                   │
│    - 同步到客户端                                                │
└─────────────────────────────────────────────────────────────────┘
```

## 核心数据流

### 1. 放入物品流程

```
玩家拖拽物品到槽位
    ↓
Slot.safeInsert() / quickMoveStack()
    ↓
VirtualChestContainer.setItem(slot, stack)
    ├─ 旧槽位不空？
    │   └─ blockEntity.removeItem(旧物品, 全部数量)
    ├─ blockEntity.addItem(新物品, stack.getCount())
    ├─ refill(currentScroll)  ← 刷新所有槽位显示
    ├─ blockEntity.setChanged()
    └─ blockEntity.storageDirty = true  ← 触发同步
         ↓
BaseChestMenu.broadcastChanges() [每tick调用]
    ├─ 检查 storageDirty == true
    ├─ chestContainer.refill(scrollRow)
    ├─ storageDirty = false
    └─ 同步槽位数据到客户端
         ↓
客户端收到同步 → 重新渲染UI
```

### 2. 取出物品流程

```
玩家点击槽位
    ↓
Slot.remove(amount)
    ↓
VirtualChestContainer.removeItem(slot, amount)
    ├─ 计算取出数量：take = min(amount, have)
    ├─ actualRemoved = blockEntity.removeItem(item, take)
    ├─ refill(currentScroll)  ← 刷新所有槽位显示
    ├─ blockEntity.setChanged()
    ├─ blockEntity.storageDirty = true  ← 触发同步
    └─ 返回 ItemStack(item, actualRemoved)  ← 注意：使用实际移除数量
         ↓
BaseChestMenu.broadcastChanges() [每tick调用]
    ├─ 检查 storageDirty == true
    ├─ chestContainer.refill(scrollRow)
    ├─ storageDirty = false
    └─ 同步槽位数据到客户端
         ↓
客户端收到同步 → 重新渲染UI
```

### 3. 滚动条流程

```
客户端：鼠标滚轮 on 存货区
    ↓
BaseChestScreen.mouseScrolled()
    ├─ 计算新的 localScrollRow
    ├─ 限制范围：[0, totalRows - VISIBLE_ROWS]
    └─ send(7, String.valueOf(localScrollRow))
         ↓ [网络包]
服务端：ChestConfigPacket.handle(action=7)
    ├─ 解析 newRow = Integer.parseInt(value)
    ├─ 计算 maxRow = (总物品数 + 8) / 9 - 3
    ├─ be.viewScrollRow = clamp(newRow, 0, maxRow)
    ├─ menu.chestContainer.refill(be.viewScrollRow)
    ├─ be.setChanged()
    └─ be.storageDirty = true  ← 触发同步
         ↓
BaseChestMenu.broadcastChanges()
    └─ 同步新的槽位显示到客户端
         ↓
客户端收到同步 → 重新渲染UI（显示新的3行）
```

## 关键设计点

### ✅ 强制设置真实数量（绕过64限制）

```java
// VirtualChestContainer.refill()
ItemStack stack = new ItemStack(entry.getKey(), 1);  // 构造函数限制到 maxStackSize
stack.setCount(entry.getValue());  // 强制设置真实数量（可能>64）
super.setItem(slot++, stack);
```

### ✅ 立即同步机制

```java
// VirtualChestContainer.setItem() / removeItem()
blockEntity.setChanged();
blockEntity.storageDirty = true;  // ← 关键：强制下一tick同步

// BaseChestMenu.broadcastChanges()
if (blockEntity.storageDirty) {
    chestContainer.refill(blockEntity.viewScrollRow);
    blockEntity.storageDirty = false;
}
super.broadcastChanges();  // 同步到客户端
```

### ✅ 1物品=1格 存储模型

```
Map<Item, Integer> itemStorage  (服务端真实存储)
    ├─ Oak_Log → 5000
    ├─ Stone → 1200
    └─ Diamond → 64

refill(scrollRow=0) 映射到 27个槽位:
    [0] Oak_Log × 5000
    [1] Stone × 1200
    [2] Diamond × 64
    [3-26] EMPTY

滚动到 scrollRow=1 后:
    [0] Stone × 1200   ← 第9个物品
    [1] Diamond × 64   ← 第10个物品
    [2-26] EMPTY
```

### ✅ 滚动范围计算

```java
// BaseChestMenu.totalRows()
int types = blockEntity.getAllItems().size();  // 物品种类数
int totalRows = (types + CHEST_COLS - 1) / CHEST_COLS;  // 向上取整

// 最大滚动行数
int maxScroll = Math.max(0, totalRows - CHEST_VISIBLE_ROWS);

// 示例：
// 3种物品 → totalRows=1, maxScroll=0 (不能滚动)
// 28种物品 → totalRows=4, maxScroll=1 (可滚动1行)
// 100种物品 → totalRows=12, maxScroll=9 (可滚动9行)
```

## 潜在问题检查点

### ❓ 问题1：滚动后槽位显示不更新

**可能原因**：
- `storageDirty` 没有正确设置 → **已修复**：现在 action=7 设置了 `storageDirty = true`
- 客户端 `localScrollRow` 与服务端 `viewScrollRow` 不同步 → **需检查**

**检查方法**：
1. 在 `ChestConfigPacket.handle(action=7)` 添加日志：
   ```java
   System.out.println("Scroll to row: " + newRow + ", max: " + maxRow);
   ```
2. 在 `refill()` 添加日志：
   ```java
   System.out.println("Refill: scrollRow=" + scrollRow + ", items=" + items.size());
   ```

### ❓ 问题2：物品放入后数量显示错误

**可能原因**：
- `getRealCount()` 返回的是 `stack.getCount()`，但可能被截断

**检查方法**：
```java
// VirtualChestContainer.getRealCount()
public int getRealCount(int slot) {
    ItemStack s = getItem(slot);
    if (s.isEmpty()) return 0;
    // 检查：是否应该从 blockEntity.getItemCount(s.getItem()) 读取？
    return s.getCount();  // 当前实现
}
```

### ❓ 问题3：格子满27格后无法滚动

**可能原因**：
- `totalRows()` 计算错误
- `maxScroll` 限制错误

**当前实现**：
```java
// BaseChestMenu.totalRows()
int types = blockEntity.getAllItems().size();
return Math.max(CHEST_VISIBLE_ROWS, (types + CHEST_COLS - 1) / CHEST_COLS);
```

**示例计算**：
- 27种物品：(27+8)/9 = 3行 → maxScroll = 3-3 = 0 ✅ 正确（不滚动）
- 28种物品：(28+8)/9 = 4行 → maxScroll = 4-3 = 1 ✅ 正确（可滚动1行）
- 36种物品：(36+8)/9 = 4行 → maxScroll = 4-3 = 1 ✅ 正确（可滚动1行）
- 37种物品：(37+8)/9 = 5行 → maxScroll = 5-3 = 2 ✅ 正确（可滚动2行）

### ❓ 问题4：字体显示不清晰

**已修复**：改用原版渲染方式

```java
// 旧版（自定义背景 + 大字体）
g.fill(...背景...);
g.drawString(font, countText, x, y, 0xFFFFFFFF, true);

// 新版（原版对齐方式）
g.drawString(font, countText,
            slotX + 17 - font.width(countText),  // 右对齐
            slotY + 9,                           // 垂直居中偏下
            0xFFFFFF, true);                     // 白色 + 阴影
```

## 下一步调试建议

1. **验证滚动逻辑**：
   - 在箱子里放入 **30+** 种不同物品
   - 观察滚动条是否出现
   - 尝试滚动，观察物品是否切换显示

2. **验证字体渲染**：
   - 对比玩家背包快捷栏的数字显示
   - 确认存货区数字与背包数字一致

3. **添加调试日志**：
   ```java
   // BaseChestScreen.mouseScrolled()
   System.out.println("Scroll: localRow=" + localScrollRow + ", totalRows=" + menu.totalRows());
   
   // ChestConfigPacket.handle(action=7)
   System.out.println("Server scroll: newRow=" + newRow + ", itemCount=" + be.getAllItems().size());
   ```

4. **检查网络同步**：
   - 客户端滚动后，服务端是否收到 action=7？
   - 服务端 `refill()` 后，客户端是否收到同步？

---

**问题总结**：

| 问题 | 状态 | 说明 |
|------|------|------|
| 字体显示 | ✅ 已修复 | 改用原版渲染方式（右对齐+白色+阴影） |
| 滚动条不响应 | ✅ 已修复 | action=7 现在设置 `storageDirty = true` |
| 滚动范围计算 | ⚠️ 待测试 | 理论正确，需实际验证 |
| 立即同步 | ✅ 已实现 | 所有操作都设置 `storageDirty = true` |

请测试并告诉我具体现象！
