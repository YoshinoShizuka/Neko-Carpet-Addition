# 「手持潜影盒右键打不开」排查：根因不在本模组，在 QuickShulker 的 rightClickToOpen

> 日期：2026-10-02 ｜ 影响版本：26.3（实机 RO26.3）
> 结论：**本模组未改动一个字**；根因是 `config/quickshulkerconfig.json5` 的 `rightClickToOpen: false`

## 0. 一句话

| 现象 | 真实根因 | 处置 |
|---|---|---|
| 同时装 QuickShulker Multi + carpet-Neko-Addition，开启 `voidWarehouse` 后，**手持潜影盒右键打不开** | QuickShulker 的 `UseItemCallback` 第一道闸门 `config.rightClickToOpen` 是 `false`，回调直接 `return PASS`，右键**什么都没发生** | 把 `rightClickToOpen` 改回 `true`（它就是该 mod 的默认值） |

**为什么会被当成本模组的 bug**：第一轮「QuickShulker 抢走虚空仓库右键」那个 bug，
**只有 `rightClickToOpen = true` 时才会触发**（抢右键的就是这条回调）。
当时的权宜之计是把这个开关关掉；而 1.0.3 已经从根上修掉了抢占
（拦截层上移到 `ServerGamePacketListenerImpl#handleUseItem`），
所以这个开关现在可以、也应该重新打开。

## 1. 报告原文

> 有一个bug需要修复，如果在模组同时安装quickshulker Mult和Neko-carpet模组时，
> 开启虚空仓库功能，会导致玩家手持潜影盒无法右键将其打开

实机模组：`quickshulker-v3.2.11-mc26.3-212-c725454-release.jar`（社区分支 **Quick Shulker Multi**，
`EnderPhantomWing/quickshulker-multi`，mod id 仍是 `quickshulker`）+ `carpet-Neko-Addition-mc26.3-1.0.3.jar`。

## 2. 取证：`rightClickToOpen` 是唯一的第一道闸门

反汇编 `net/kyrptonaught/quickshulker/QuickShulkerMod#lambda$onInitialize$0`（注册进 `UseItemCallback` 的回调体）：

```
ItemStack stack = player.getItemInHand(hand);
if (!level.isClientSide()
        && getConfig().rightClickToOpen          // ← ★ 第一道闸门，false 就直接 PASS
        && Util.isOpenableItem(stack)
        && Util.canOpenInHand(stack)) {
    Util.openItem(player, 0, hand == MAIN_HAND ? inventory.getSelectedSlot() : 40);
    return InteractionResult.SUCCESS_SERVER;
}
return InteractionResult.PASS;
```

`config/quickshulkerconfig.json5` 里 `rightClickToOpen: false` ⇒ 这条回调**永远走不到开界面那一步**。

`ConfigOptions` 构造函数里这个字段的字节码是 `iconst_1`（默认 `true`），且另一个实例
`versions/26.1.1 20260824 Fabric/config/quickshulkerconfig.json5` 里也是 `true` ——
**只有装了本模组的 RO26.3 被改成了 `false`**，这正是「第一轮关掉当权宜之计」留下的痕迹。

### 旁证：实机日志里从来没有过「手持右键打开」

`logs/latest.log`（18:22–18:26）中 4 次 `ShulkerBoxMenu ... initialized` 全部紧跟 `ChestMenu` 出现：

```
[18:23:57] Content for container menu ChestMenu@9429656 initialized
[18:23:58] Content for container menu ShulkerBoxMenu@3d69c18d initialized
```

即用户实际一直在用 `rightClickInv`（**在容器界面里右键背包中的潜影盒**）——
而 `rightClickInv` 不经过 `rightClickToOpen` 那道闸门，所以它一直是好的。

## 3. A/B 实测（同一份代码、同一套 12 个实机模组，只改一个布尔）

自检走**真实包链路** `player.connection.handleUseItem(new ServerboundUseItemPacket(...))`，
断言「手持潜影盒右键后 `containerMenu` 是不是 `ShulkerBoxMenu`」。

### A 轮：`rightClickToOpen = false`

```
== QuickShulker rightClickToOpen = false / quickShulkerBox = true / loaded = true
   [S1 规则关]       isOpenableItem=true  menu=InventoryMenu
   [S2 规则开]       isOpenableItem=true  menu=InventoryMenu
   [S3 规则关(复跑)] isOpenableItem=true  menu=InventoryMenu
   [S4 规则开(复跑)] isOpenableItem=true  menu=InventoryMenu
SMOKE RESULT vw-qsshulker/26.3 checks=6 failures=4
```

**A 轮完美复现了用户报告的症状。** 三个关键读数：

1. `isOpenableItem=true` —— 排除「盒子数量/类型被认不出」这条路；
2. `voidWarehouse` 规则**开与关结果完全一致** —— 与本模组无关；
3. `menu=InventoryMenu` —— 右键彻底没反应（不是开错界面，是没反应）。

### B 轮：`rightClickToOpen = true`

```
== QuickShulker rightClickToOpen = true / quickShulkerBox = true / loaded = true
   [S1 规则关]       isOpenableItem=true  menu=ShulkerBoxMenu
   [S2 规则开]       isOpenableItem=true  menu=ShulkerBoxMenu
   [S3 规则关(复跑)] isOpenableItem=true  menu=ShulkerBoxMenu
   [S4 规则开(复跑)] isOpenableItem=true  menu=ShulkerBoxMenu
   [R1 规则开·手持改名工作台] menu=VoidWarehouseMenu
   [R2 规则关·手持改名工作台] menu=CraftingMenu
SMOKE RESULT vw-qsshulker/26.3 checks=9 failures=0
```

**A/B 完全反转 ⇒ 变量就是 `rightClickToOpen`，不是本模组。**

R 组同时守住了第一轮那个 bug 的回归：**规则开**时手持改名工作台右键开出的是
`VoidWarehouseMenu`（我们的包处理层拦截生效，没被 QuickShulker 抢）；**规则关**时
QuickShulker 立刻把右键抢去开 `CraftingMenu` —— 后者是**反证组**，证明这个"抢"的机制确实活着，
R1 的「没被抢」才是有意义的。

## 4. 顺带查清：潜影盒交付的不变量（count 必须 = 1）

`Util.isOpenableItem` 里有一条硬门槛：

```java
QuickShulkerData data = QuickOpenableRegistry.getQuickie(stack.getItem());
if (data == null) return false;
if (data.ignoreSingleStackCheck) return true;
return stack.getCount() <= 1;        // ← ★ count > 1 的潜影盒在它眼里「不可打开」
```

对虚空仓库这种「一种物品 = 一格、数量无上限 `BigInteger`」的模型，这是个真陷阱：
**任何 `count > 1` 的潜影盒落到玩家手里，QuickShulker 都会静默拒绝打开。**

本轮把**每一条**会把物品交给玩家的路径都对着字节码核了一遍，确认全部按
`maxStackSize` 夹取，不存在超堆叠外流：

| 路径 | 夹取依据 |
|---|---|
| `VoidWarehouseShulker.pack` | 恒 `new ItemStack(SHULKER_BOX)`，count = 1 |
| `VoidWarehouseMenu#withdrawToInventory` / `#quickMoveStack` | `batchSize = Entry.maxStack() = max(1, getMaxStackSize())`，潜影盒 = 1 |
| `VoidWarehouseMenu#withdrawShulkerBox` | `pack(...)` → `moveItemStackTo`，且注释已说明「盒子 maxStackSize 恒为 1」 |
| `VoidWarehouseWithdraw#appendLoose` | `max = max(1, sample.getMaxStackSize())`，按组拆分 |
| `VoidWarehouseData#takeStack` | `limit = min(wanted, max(1, getMaxStackSize()))` |
| `restock/PrinterTakeService#tryGive` | `max = max(1, unit.getMaxStackSize())`；且潜影盒在 `isBlacklisted` 里被整类排除 |
| `VoidWarehouseExtractor#pull`（出料端补料） | `limit = max(1, hopper.getMaxStackSize(sample))` —— `Container#getMaxStackSize(ItemStack)` 的默认实现是 `Math.min(getMaxStackSize(), stack.getMaxStackSize())`，已 `javap` 核对；`HopperBlockEntity` **没有覆盖**它 ⇒ 潜影盒 = 1 |
| `VoidWarehouseMenu#removed`（光标回吐） | `rest = carried.copy()`，只搬运不造量 |

⇒ **本模组不会产出 `count > 1` 的潜影盒。** 这条不变量已写进自检
（`isOpenableItem=true` 的断言就是它的哨兵），模板存
`VoidWarehouseQsShulkerSelfCheck.java.template`。

## 5. 排查中被逐一排除的怀疑点（留档，避免下次重复走）

| 怀疑点 | 排除依据 |
|---|---|
| 我们的右键拦截误伤潜影盒 | 六个入口全部只认 `VoidWarehouseTrigger.isVoidWarehouse`（工作台 + 名字精确等于「虚空仓库」）；`isTerminal` / `isHopperConnected` 还额外要求 `state.is(Blocks.CRAFTING_TABLE)`。自检直接断言「普通潜影盒 / 名字叫虚空仓库的潜影盒都不是触发物」 |
| 和 QuickShulker 撞注入点 | 两边注入点**零交集**：我们 = `Item#use` / `BlockItem#useOn` / `ServerPlayerGameMode#useItem`·`#useItemOn` / `ServerGamePacketListenerImpl#handleUseItem`·`#handleChat` / `Player#interactOn` / `Block#setPlacedBy` / `BlockBehaviour#affectNeighborsAfterRemoval` / `HopperBlockEntity#pushItemsTick`·`#saveAdditional`·`#loadAdditional`·`#canPlaceItemInContainer`·`#ejectItems` / `Container#canPlaceItem` / `Slot#setByPlayer`；QuickShulker = `AbstractContainerMenu#clicked` / `ServerPlayer#openMenu`·`#doCloseContainer` / `{CraftingMenu,StonecutterMenu,ItemCombinerMenu,GrindstoneMenu,LoomMenu}#stillValid` / `Item#overrideOtherStackedOnMe`·`#overrideStackedOnIt` |
| Fork 的注册表按类名匹配、认不出潜影盒 | `QuickOpenableRegistry` 按 `Class` 存；`getQuickie` 对 `BlockItem` 取 `blockItem.getBlock().getClass()`。26.3 里无色与 16 色潜影盒都是 `ShulkerBoxBlock` 类的实例 ⇒ 全部命中。自检实测 `quickie != null` |
| 某处 `Container#canPlaceItem` 注入把容器写入掐了 | `VoidWarehouseClassifier.blockMachineInsert` 第一句就是 `container instanceof HopperBlockEntity`，非漏斗容器（含 QuickShulker 的 `ItemStackInventory`）一律放行 |
| `VoidWarehouseExtension#onTick` 关掉菜单 | 只在规则**关闭**时关 `VoidWarehouseMenu`，且 `instanceof VoidWarehouseMenu` 判死 |
| 潜影盒模式的展示堆叠被当成真物品取出 | `buildDisplay` 用 `template.copyWithCount(1)`；且 `clicked` 被整体覆写，内容格一律走 `withdrawShulkerBox` / `withdrawToInventory`，永远到不了原版 `doClick` |
| 客户端侧有本模组的 mixin 干扰 | `fabric.mod.json` 只挂一个配置、且无 `client` 节；`environment: "*"` 但那几个 common mixin 的判定同样只认触发物 |

## 6. 处置

1. **`config/quickshulkerconfig.json5`：`rightClickToOpen` 改回 `true`**（已改，原文件备份为
   `quickshulkerconfig.json5.bak-20261002`）。这是本 mod 的出厂默认值，也是「手持潜影盒右键打开」的唯一开关。
2. **不需要重出 jar**：本轮源码树零改动（`git status` 干净）。
3. 自检模板留档：`VoidWarehouseQsShulkerSelfCheck.java.template`。

## 7. 教训

1. **同一个开关既能让 A 好也能让 B 坏，改它之前必须先问「它当初为什么被改」。**
   第一轮为了绕开「QuickShulker 抢右键」，把 `rightClickToOpen` 关了 —— 于是本轮的
   「手持潜影盒打不开」是**那次权宜之计的必然副作用**，而不是新 bug。
   **修完根因（拦截换层）之后，必须回头把当时的临时开关复原。**
2. **「变量隔离」比「读代码」更快定案。** 同一份代码、同一套模组，只切一个布尔连跑两轮，
   现象完全反转 —— 这比再读一万行代码都有说服力。凡是怀疑跨模组，先做这个 A/B。
3. **反证组要能证明"机制活着"。** R2 断言「规则关时 QuickShulker 确实抢到了」，
   否则 R1 的「没被抢」可能只是「QuickShulker 压根没加载」。
4. **跨模组的硬门槛值得记进文档。** QuickShulker 的 `isOpenableItem` 要求 `count <= 1`，
   对「一格装到亿级」的仓库模型是个天然的敌对约束，交付路径必须逐条确认合规。
