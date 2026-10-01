# 虚空仓库两个 bug 的修复：装盒材料账 + QuickShulker 抢走右键

> 日期：2026-10-01 ｜ 影响版本：26.3 / 26.2 / 26.1.1 / 26.1.2 / 1.21.11（五版本同步）
> 产物：carpet-Neko-Addition 1.0.3（五版本全量重出）

## 0. 一句话

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 潜影盒模式下从仓库取满盒（1728）物品，仓库**没有**潜影壳 / 箱子但**有**空潜影盒，却提示「潜影壳和箱子数量不足」 | 「装盒材料账」在**两条取出口**上各写了一份：聊天按数量取货那条有空盒优先，界面点格子那条只认壳 + 箱 | 抽成 `VoidWarehouseData.planBoxMaterials` 一处，两条出口共用 |
| 2 | 客户端装了 QuickShulker 后，**对空气右键**改名「虚空仓库」的工作台会打开原版合成界面；**对方块右键**正常 | QuickShulker 把 `CraftingTableBlock` 注册成「可快速打开」，并通过 `UseItemCallback` 抢先返回 `SUCCESS_SERVER` | 服务端入口上移到 `ServerGamePacketListenerImpl#handleUseItem`（Fabric 没碰过这一层） |

两个 bug 都跑了真实 `runServer` 自检：`checks=34 failures=0`，**连跑 3 次**结果一致。

---

# 第一部分：bug 1 —— 有空盒却说材料不足

## 1. 现象

玩家反馈（原文）：

> 取物模式为潜影盒模式时，当从库存取出满盒物品，此时虚空仓库没有潜影壳和箱子但是有空潜影盒时，
> 取出数量符合 1728 的满盒物品，会提示潜影壳和箱子数量不足，而不是直接消耗空潜影盒进行取出。

关键点是「**有空盒**」和「**1728 的满盒**」：仓库里躺着空潜影盒（整盒入库时攒下来的），
玩家点一下仓库界面里那格圆石，想装一盒带走 —— 结果被提示补潜影壳。

## 2. 取证：同一个语义，两条出口

虚空仓库的「潜影盒模式」有**两个**取出口，走的是两套完全不同的代码：

| 出口 | 触发方式 | 代码位置 |
|---|---|---|
| 界面点击取一盒 | 在仓库界面里左键点格子 | `VoidWarehouseMenu#withdrawShulkerBox` |
| 聊天按数量取货 | 在界面里右键格子 → 聊天栏输数量 | `VoidWarehouseWithdraw#deliver` |

翻这两处，材料账的写法是这样的：

```java
// VoidWarehouseMenu#withdrawShulkerBox（修复前）
if (this.data.amountOf(Items.SHULKER_SHELL).compareTo(SHELL_NEED) < 0
        || this.data.amountOf(Items.CHEST).compareTo(CHEST_NEED) < 0) {
    this.message(player, MSG_NO_BOX_PARTS);   // ← 只认壳 + 箱，压根没看空盒
    return;
}
```

```java
// VoidWarehouseWithdraw#deliver（修复前）
long emptyBoxes = data.emptyBoxCount().min(BigInteger.valueOf(totalBoxes)).longValue();
long needCraft  = totalBoxes - emptyBoxes;          // ← 空盒优先，剩下的才合成
BigInteger needShells = BigInteger.valueOf(needCraft * VoidWarehouseShulker.SHELL_COST);
...
```

**聊天那条路早就有空盒优先，界面那条路从来没有。** 于是玩家会遇到非常分裂的一幕：

- 在界面里点格子 → 「潜影壳不足」，取不出来；
- 关掉界面用聊天取同样的东西 → 顺利取出，空盒被消耗。

## 3. 根因

不是「算法写错了」，而是**同一条业务规则被复制成了两份，只改了其中一份**。

`emptyBoxCount()` / `takeEmptyBoxes()` 是后来才加的（空潜影盒整盒入库攒下来的库存），
加的时候只接进了聊天那条路，界面那条路的 `SHELL_NEED` / `CHEST_NEED` 原地没动。
复制粘贴的分叉没有类型系统拦着，编译器完全不会报错。

## 4. 顺带修掉的一个隐患：索引安全

改这段代码时发现取出口还有个**顺序会致命**的问题：

```java
// 扣空盒 / 壳 / 箱会移除别的格子 ⇒ entries 前移
// 若先扣材料、再按 index 取物，index 可能已经指向别的物品了
this.data.take(index, fill);          // ← 必须在最前
this.data.consumeBoxMaterials(plan);
```

`takeEmptyBoxes` 反向遍历并 `entries.remove(i)`；`takeItem` 同理。
**任何一次移除都会让后面的格子整体前移一格**，先前解析出来的 `index` 立刻失效。
修复前的旧代码恰好是「先扣壳 + 箱、后 `take(index)`」—— 只要壳和箱子刚好各占一格且排在
圆石前面，玩家点圆石就会取走钻石。

> 自检 A5 组就是专门盯这条的（圆石旁边放一格钻石，取完验证钻石一个没少，见 §12）。

## 5. 修复

在 `VoidWarehouseData` 里把「装盒材料账」抽成一个不可变的小 record + 三个方法，两条出口共用：

```java
/** 「装 totalBoxes 个盒子」需要哪些材料 */
public BoxMaterials planBoxMaterials(long totalBoxes) {
    BigInteger want  = BigInteger.valueOf(Math.max(0L, totalBoxes));
    BigInteger empty = this.emptyBoxCount().min(want);       // ① 尽量用现成的空盒
    BigInteger craft = want.subtract(empty);                 // ② 剩下的才走原版配方
    return new BoxMaterials(
            empty.intValueExact(),
            craft.multiply(BigInteger.valueOf(VoidWarehouseShulker.SHELL_COST)).intValueExact(),
            craft.multiply(BigInteger.valueOf(VoidWarehouseShulker.CHEST_COST)).intValueExact());
}

/** 只校验「需要现合成」的那部分对应的壳 / 箱 */
public boolean hasBoxMaterials(BoxMaterials plan) { ... }

/** 按 plan 扣账，顺序：先空盒（可能移除格子），再壳，再箱 */
public int consumeBoxMaterials(BoxMaterials plan) { ... }

public record BoxMaterials(int emptyBoxes, int shells, int chests) { }
```

设计上刻意做成「**先 plan、再 has、再 consume**」三步而不是一个 `tryConsume`：

- `plan` 是纯计算，可以随便调用来预演，自检里也能直接断言「这笔账该花多少」；
- `has` 与 `consume` 分离，让取出口能维持原本的 **all-or-nothing** 语义
  （装盒材料不够 ⇒ 整次取消，物品一分不动）；
- `record` 不可变，从 `plan` 到 `consume` 之间不会被别的代码改掉。

两处调用点各精简成 3 行：

```java
// VoidWarehouseMenu#withdrawShulkerBox（修复后）
VoidWarehouseData.BoxMaterials plan = this.data.planBoxMaterials(1);
if (!this.data.hasBoxMaterials(plan)) { this.message(player, MSG_NO_BOX_PARTS); return; }
...
this.data.take(index, fill);              // ← 先取物
this.data.consumeBoxMaterials(plan);      // ← 后扣料
```

```java
// VoidWarehouseWithdraw#deliver（修复后）
VoidWarehouseData.BoxMaterials plan = data.planBoxMaterials(totalBoxes);
if (!data.hasBoxMaterials(plan)) { player.sendSystemMessage(MSG_NO_PARTS); return; }
...
data.takeExact(sample, (int) requested);
data.consumeBoxMaterials(plan);
```

---

# 第二部分：bug 2 —— QuickShulker 抢走右键

## 6. 现象里最重要的那条线索：**不对称**

> 对着**空气**右键 → 打开**工作台**界面（错）；
> 对着**方块**右键 → 正常打开**虚空仓库**（对）。

「方块那条对、空气那条错」这个不对称，直接把范围缩到了**服务端处理右键的那条链上的某一层**：
同一个物品、同一个玩家、同一个规则开关，两条路径只有**入口**不同。

虚空仓库的触发物是「原版工作台 + 自定义名精确等于「虚空仓库」」。原版工作台是已注册单例，
换不掉 Item 实例，所以三个右键入口本来就是靠 Mixin 接管的：

| 入口 | 走哪条包 | 服务端处理 |
|---|---|---|
| 对空气右键 | `ServerboundUseItemPacket` | `ServerGamePacketListenerImpl#handleUseItem` → `ServerPlayerGameMode#useItem` → `Item#use` |
| 对方块右键 | `ServerboundUseItemOnPacket` | `ServerGamePacketListenerImpl#handleUseItemOn` → `ServerPlayerGameMode#useItemOn` |
| 对实体右键 | `ServerboundInteractPacket` | `ServerGamePacketListenerImpl#handleInteract` → … |

## 7. 取证一：QuickShulker 到底干了什么

解包实机 `quickshulker-v3.2.11-mc26.3-212-c725454-release.jar`，`javap -c` 它的入口类。

**（a）它注册了 `UseItemCallback`**，回调体是（字节码直译）：

```
// QuickShulkerMod#lambda$onInitialize$0  —— 注册为 UseItemCallback
if (!level.isClientSide()
        && getConfig().rightClickToOpen
        && Util.isOpenableItem(stack)          // 这个东西算不算「可快速打开」
        && Util.canOpenInHand(stack)) {
    Util.openItem(player, 0, hand == MAIN_HAND ? inventory.getSelectedSlot() : 40);
    return InteractionResult.SUCCESS_SERVER;   // ← 抢走，并且「事情已经办完」
}
return InteractionResult.PASS;
```

关键在于 **`SUCCESS_SERVER` 是终态返回值**：谁先返回非 `PASS` 且把 `CallbackInfoReturnable`
cancel 掉，后面所有人的逻辑就都不跑了。

**（b）它把工作台注册成了「可快速打开」**。`registerProviders()` 的常量池里 8 条记录，
前 2 条是潜影盒 / 末影箱，后 6 条是各种「有界面方块」：

```
class net/minecraft/world/level/block/CraftingTableBlock  → String container.crafting
class net/minecraft/world/level/block/StonecutterBlock    → String container.stonecutter
class net/minecraft/world/level/block/AnvilBlock          → String container.repair
class net/minecraft/world/level/block/GrindstoneBlock     → String container.grindstone_title
class net/minecraft/world/level/block/SmithingTableBlock  → String container.upgrade
class net/minecraft/world/level/block/LoomBlock           → String container.loom
```

**虚空仓库的触发物恰好就是一张改名的工作台** ⇒ `isOpenableItem(stack)` 判真
（它按 **BlockItem 的方块类型** 匹配，不看物品名）⇒ 打开合成界面 ⇒ 返回 `SUCCESS_SERVER`。

## 8. 取证二：第一轮修复为什么「看起来好了、再跑一次又坏」

第一反应是「我们的钩子被抢了，那就往上挪一层」：从 `Item#use` 的 HEAD 挪到
`ServerPlayerGameMode#useItem` 的 HEAD。

**跑了一次，通过了。第二次跑同一份代码，又坏了。** 同一份代码两种结果 —— 这只能说明
**那一层上还有别人，且执行顺序不由我们决定**。

把实机 `fabric-api-0.161.0+26.3.jar` 里的 `META-INF/jars/fabric-events-interaction-v0-5.3.6`
解出来，`javap -v` 它的 `ServerPlayerGameModeMixin`：

```
  public void interactItem(ServerPlayer, Level, ItemStack, InteractionHand, CallbackInfoReturnable<InteractionResult>);
    RuntimeVisibleAnnotations:
      org.spongepowered.asm.mixin.injection.Inject(
        at=[@org.spongepowered.asm.mixin.injection.At(value="HEAD")]
        method=["useItem"]                 ← ★ 挂的就是 ServerPlayerGameMode#useItem，HEAD
        cancellable=true
      )
    Code:
       0: getstatic  UseItemCallback.EVENT
       3: invokevirtual Event.invoker
      13: invokeinterface UseItemCallback.interact(player, level, hand)
      20: if (result == PASS) goto 41
      28: cir.setReturnValue(result)
      35: cir.cancel()                     ← ★ 非 PASS 就掐断整条链
      41: return
```

**`UseItemCallback` 的触发点，正是 `ServerPlayerGameMode#useItem` 的 HEAD。**
我们上移一层之后落脚的地方，和 Fabric 的回调是**同一个方法的同一个注入点**。
两个 HEAD 回调谁后执行谁生效，而 Mixin 的注入顺序不由模组控制 —— 于是结果随 JVM 启动顺序漂。

顺带把另外两个坑位也记下来（都是「公共钩子坑」）：

```
ItemStackMixin#handleUseEvent   @WrapOperation(method=["use"],  at=@At("INVOKE", target="Item#use"))
                                → ItemEvents.USE              ← 也就是我们最初挂的那一层
ItemStackMixin#handleUseOnEvent @WrapOperation(method=["useOn"], at=@At("INVOKE", target="Item#useOn"))
                                → ItemEvents.USE_ON
```

## 9. 最终修复：落到没有竞争者的一层

结论很简单 —— **往上走到 Fabric 没碰过的那一层**。
`javap` 一下 Fabric 的 `ServerGamePacketListenerImplMixin`，它在这个类里只 hook 了这些：

```
private void tryPickItem(ItemStack)
public  ItemStack onPickItemFromBlock(BlockState, LevelReader, BlockPos, boolean, Operation, ServerboundPickItemFromBlockPacket)
public  ItemStack onPickItemFromEntity(Entity, Operation, ServerboundPickItemFromEntityPacket)
public  void      handleInteract(ServerboundInteractPacket, CallbackInfo, Entity)
```

**`handleUseItem` 一个字都没碰。** 那里就是安全区。

新增 `ServerGamePacketListenerImplMixin`：

```java
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(
            method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem("
                           + "Lnet/minecraft/server/level/ServerPlayer;"
                           + "Lnet/minecraft/world/level/Level;"
                           + "Lnet/minecraft/world/item/ItemStack;"
                           + "Lnet/minecraft/world/InteractionHand;"
                           + ")Lnet/minecraft/world/InteractionResult;"
            ),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseHandleUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null || !VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        InteractionHand hand = packet.hand();
        ItemStack stack = player.getItemInHand(hand);
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        VoidWarehouseTrigger.interact(player.level(), player, hand);
        ci.cancel();
    }
}
```

**为什么注入点是 `INVOKE useItem(...)` 而不是 HEAD**：

- 用 HEAD 的话，包序号回执（`ackBlockChangesUpTo`）、朝向同步（`absSnapTo`）、
  `resetLastActionTime()` 这些「收到一个 use-item 包必须做的准备动作」全被跳过了，
  客户端会因为没收到回执而卡输入；
- 用 `INVOKE useItem` 恰好卡在**准备动作已做完、正事还没开始**的那一点：
  cancel 掉之后，`ServerPlayerGameMode#useItem` 一次都不会被调用 ⇒
  `Item#use` 不会跑 ⇒ **任何挂在 `Item#use` / `useItem` 上的第三方回调（含 QuickShulker）
  根本不会被触发**，而不是「触发了再抢回来」。

原来的 `ServerPlayerGameModeUseItemMixin` 保留但**降级为兜底**（`priority = 0`）：

- 主路径（对空气右键）已经在包处理层被 cancel，`useItem` 压根到不了；
- 「对实体右键」走 `ServerboundInteractPacket`，最终还是会回落到 `useItem`，
  那一层仍然有 QuickShulker 竞争，但有兜底比没有强；
- `priority = 0` 让自己的回调尽量排在最后执行，覆盖掉别人的 `SUCCESS_SERVER`。

**这是本轮最该记住的一条经验**：修「钩子被抢」类问题的正确姿势不是「抢得更用力」
（调 priority、换个 @At），而是**换到没有竞争者的那一层**。抢，终究是顺序依赖的。

## 10. 版本差异（javap 逐版本核对，不猜）

| | 26.3 | 26.2 / 26.1.1 / 26.1.2 / 1.21.11 |
|---|---|---|
| 取手 | `packet.hand()` | `packet.getHand()` |
| 原因 | 26.3 的 `ServerboundUseItemPacket` 是 **record**，访问器无 `get` 前缀 | 普通类 |

`ServerPlayerGameMode#useItem(ServerPlayer, Level, ItemStack, InteractionHand)` 五个版本签名一致。
1.21.11 的混淆名：`ayi.method_12065` = `handleUseItem`、方法 `axh.a(...)` = `useItem`、
`class_2886` = `ServerboundUseItemPacket`（`method_12551` = `getHand`）。
产物 refmap 已核验注解被正确重写为 `method_12065(Lnet/minecraft/class_2886;)V`。

---

# 第三部分：自检

## 11. 怎么让断言「有牙」

**bug 1 的断言**：光断言「有空盒时能取出」是**没牙的** —— 老代码在「壳箱都有」时也能取出。
必须有一组**只有新算法才过、老算法必挂**的输入：仓库里**只有空盒、一个壳一个箱都没有**。

**bug 2 的断言**：光断言「右键能开虚空仓库」也是没牙的（dev 环境里根本没装 QuickShulker，
永远绿）。真正的断言是「**第三方 `UseItemCallback` 一次都没被调用**」——
所以在自检里**手工复刻** QuickShulker 的抢注：

```java
static boolean hijacked = false;
...
UseItemCallback.EVENT.register((player, world, hand) -> {
    hijacked = true;                                 // 被调用过 ⇒ 我们的入口不够早
    return InteractionResult.SUCCESS_SERVER;         // 和 QuickShulker 一模一样的返回值
});
```

然后断言 `hijacked == false`。**再配一组反证**（拿着普通工作台右键 ⇒ `hijacked == true`），
证明这个 flag 真的会置位、C3 的 `false` 是有意义的，而不是「回调压根没注册成功」。

**另外两个必须踩的坑**：

1. **要走真实包链路**，不能图省事直接调 `gameMode.useItem(...)` —— 那样绕过的正是
   本轮修复的那一层，等于自检自证：

   ```java
   player.connection.handleUseItem(new ServerboundUseItemPacket(
           InteractionHand.MAIN_HAND, 0, player.getYRot(), player.getXRot()));
   ```

2. **手工造的 `ServerPlayer` 没走完握手**，`hasClientLoaded()` 返回 false，
   `handleUseItem` 第一行就 return，界面永远开不出来。要用反射把它摁成已加载：

   ```java
   for (String name : new String[]{"waitingForRespawn", "clientLoadedTimeoutTimer"}) {
       Field f = ServerGamePacketListenerImpl.class.getDeclaredField(name);
       f.setAccessible(true);
       if (f.getType() == boolean.class) { f.setBoolean(conn, false); } else { f.setInt(conn, 0); }
   }
   ```

## 12. A 组 / B 组实测（bug 1，真实 26.3 服务端）

A 组走界面点击路径（每次取 1 盒 = 1728 个），B 组走聊天按数量路径。

| 组 | 仓库初始状态 | 结果 | 账目 |
|---|---|---|---|
| **A1** | 圆石 3456、**空盒 1**、壳 0、箱 0 | ✅ 取出 | 圆石 3456→**1728**、空盒 1→**0**、壳 0→0、箱 0→0 |
| **A2** | 圆石 3456、**什么材料都没有** | ❌ 拒绝 | 圆石 3456→**3456**（all-or-nothing，一分没动） |
| **A3** | 圆石 3456、空盒 0、**壳 2、箱 1** | ✅ 取出 | 壳 2→**0**、箱 1→**0** |
| **A4** | 圆石 3456、**空盒 1 + 壳 2 + 箱 1** | ✅ 取出 | 空盒 1→**0**、壳 2→**2**、箱 1→**1**（优先空盒，壳箱一个没花） |
| **A5** | 圆石 2000 + **旁边一格钻石 10** + 空盒 1 | ✅ 取出 | 圆石 2000→**272**、钻石 10→**10**（未被误扣）、空盒 1→0 |
| **B1** | 请求 3456（需 2 盒），只有 1 空盒、无壳箱 | ❌ 整次取消 | 空盒仍为 **1**（取消时连空盒都不扣） |
| **B2** | B1 之后补 2 壳 + 1 箱，重新请求 | ✅ 取出 | 圆石 4000→**544**、空盒→0、壳→0、箱→0 |
| **B3** | 圆石 4000、空盒 2 + 壳 2 + 箱 1 | ✅ 取出 | 空盒→**0**、壳 2→**2**、箱 1→**1**（空盒足量，不碰壳箱） |

**A1 就是「有牙」的那一组**：老代码（只认 `SHELL_NEED`/`CHEST_NEED`）在壳 = 0 时必定拒绝，
新代码能取出 —— 一挂一过，才证明断言真的在测这件事。

## 13. C 组实测（bug 2，真实 26.3 服务端）

| 组 | 断言 | 结果 |
|---|---|---|
| **C1** | 包处理层确实挂上了我们的 mixin（扫 `handleUseItem` 的方法名，用 `endsWith` 匹配 —— Mixin 会加 `handler$xxx$modid$` 前缀） | ✅ |
| **C2** | 手持「虚空仓库」走真实 `handleUseItem` 包 ⇒ 打开的是 `VoidWarehouseMenu` | ✅ |
| **C3** | 同一次右键，**第三方 `UseItemCallback` 没被调用**（`hijacked == false`） | ✅ |
| **C4** | 反证：手持**普通**工作台 ⇒ 不开虚空仓库，且 `hijacked == **true**`（证明抢注回调注册成功、C3 的 false 有区分力） | ✅ |
| **C5** | 反证组配套：这次第三方回调确实被调用了 | ✅ |
| **C6** | 规则 `voidWarehouse` 关掉 ⇒ 不开虚空仓库，且 `hijacked == true`（干净地交还原版链路） | ✅ |

## 14. 稳定性

`SMOKE RESULT vw-box/26.3 checks=34 failures=0`，**连跑 3 次全部 `failures=0`**。

> 之所以要连跑，就是 §8 那个教训的直接产物：第一轮修复只跑了一次就以为好了，
> 第二次跑就翻车。**凡是靠「注入顺序」赢下来的修复，一次通过都不算修好。**

---

# 第四部分：交付

## 15. 改动清单（五版本同步）

| 文件 | 变更 |
|---|---|
| `VoidWarehouseData.java` | +`planBoxMaterials` / `hasBoxMaterials` / `consumeBoxMaterials` / `record BoxMaterials` |
| `VoidWarehouseMenu.java` | `withdrawShulkerBox` 改用共用算法；`take(index)` 提到扣料之前；删掉 `SHELL_NEED`/`CHEST_NEED` |
| `VoidWarehouseWithdraw.java` | 删掉自己那份算法，改用 `planBoxMaterials` |
| `mixin/ServerGamePacketListenerImplMixin.java` | **新建** —— bug 2 主拦截层 |
| `mixin/ServerPlayerGameModeUseItemMixin.java` | **新建** —— bug 2 兜底层（`priority = 0`） |
| `carpet_neko_addition.mixins.json` | 注册上面两个 mixin |

新增工具 / 模板：

- `.workbuddy/tools/sync_voidwarehouse_box.py`（116 行）—— 整文件复制 Withdraw + 两个新 mixin
  （非 26.3 自动把 `packet.hand()` 换成 `packet.getHand()`），对 Menu 打两段补丁，注册 mixins.json。
  **Menu 不能整文件复制**：其余四个版本的 Menu 有真差异（`ContainerInput`/`ClickType`、
  `Prediction.SERVER_ONLY`、`VoidWarehouseText.info` 等）。
- `.workbuddy/tools/VoidWarehouseBoxSelfCheck.java.template`（429 行）—— 自检模板，
  跑完从源码树删除（模板留在 `tools/`）。
- `VoidWarehouseData` 仍走 `sync_restock.py` 整文件同步；**1.21.11 例外**，
  它的 `SavedDataType` 的 id 是 String 而非 Identifier，不能盲拷，新方法手工移植。

## 16. 五版本产物核验

```
carpet-Neko-Addition-mc26.3-1.0.3.jar     自检残留:0  新mixin:2  md5=5c258f3e24…
carpet-Neko-Addition-mc26.2-1.0.3.jar     自检残留:0  新mixin:2  md5=a22226047d…
carpet-Neko-Addition-mc26.1.1-1.0.3.jar   自检残留:0  新mixin:2  md5=6e3e427361…
carpet-Neko-Addition-mc26.1.2-1.0.3.jar   自检残留:0  新mixin:2  md5=5368d17f79…
carpet-Neko-Addition-mc1.21.11-1.0.3.jar  自检残留:0  新mixin:2  md5=1c828a0267…
```

「自检残留 0」= 产物里没有 `VoidWarehouseBoxSelfCheck` 类；「新 mixin 2」= 两个新 mixin 都在 jar 里。

26.3 已部署到实机 `E:\Game\Minecraft\.minecraft\versions\RO26.3\mods\`，
与 `dist/` 产物 MD5 一致（`5c258f3e24da7036c3fdc54c8ffe92d9`）。

## 17. 行为变化（玩家视角）

- **装盒材料优先级统一**：两条出口都是「**先用仓库里的空潜影盒，不够的那些才用潜影壳 + 箱子**」。
  仓库里有空盒时，界面里点格子和聊天取货的行为完全一致。
- **提示文案不变**：材料确实不够时，两条出口仍然都提示
  「潜影壳或箱子不足，无法装盒（每盒需 潜影壳 ×2 + 箱子 ×1）」。
- **与 QuickShulker 共存**：装了 QuickShulker 的客户端，手持「虚空仓库」对空气右键
  打开的是虚空仓库（不再被合成界面顶掉）；**手持普通工作台时 QuickShulker 的功能完全不受影响**
  —— 我们的拦截只在物品名精确等于「虚空仓库」时生效，其它一切照旧。
- 规则 `voidWarehouse` 关闭时，所有入口原样交还原版 / 第三方（自检 C6 覆盖）。

---

# 第五部分：教训沉淀

1. **同一条业务规则只有一个实现。** 「装盒材料账」被复制成两份、只改了一份，
   于是同一个操作在聊天里能取、在界面里取不出。凡是「两处代码看起来在干同一件事」，
   就把它抽出来共用 —— 复制粘贴的分叉没有编译器拦着。

2. **改「钩子被抢」的问题，解法是换层，不是抢得更用力。**
   调 priority、换 `@At` 都还是在同一个坑里比谁手快，胜负取决于 JVM 启动顺序。
   正确做法是 `javap` 一路往上找 **Fabric / 第三方没碰过的那一层**（本轮最终落在
   `ServerGamePacketListenerImpl#handleUseItem`，Fabric 在那个类只 hook 了 pick 系列和 `handleInteract`）。

3. **「改完跑通一次」不等于修好。** 顺序依赖型的 bug 每次跑都可能换结果。
   凡是修「谁先执行」的问题，**必须连跑多次**确认稳定。

4. **查钩子冲突要查到 class 文件里的注解本身。**
   `javap -v` 打出 `RuntimeVisibleAnnotations`，能直接看到
   `method=["useItem"]` `at=@At("HEAD")` —— 比猜、比翻源码都可靠。
   （26.x 不混淆，fabric-api jar 里**没有** refmap，注解里的名字就是最终名字。）

5. **自检要走「真实链路」，不能绕。** 直接调 `gameMode.useItem(...)` 会跳过本轮修复的那一层，
   自检就变成了自证。要走 `player.connection.handleUseItem(new ServerboundUseItemPacket(...))`。

6. **断言要有反证组。** C3 断言「第三方回调没被调用」，必须有 C4 证明「同样的 flag 在别的情况下会被置位」，
   否则 `false` 可能只是「回调压根没注册成功」，断言等于没测。

7. **「先扣账还是先取物」这种顺序问题，在会移除容器元素的代码里是致命的。**
   `entries.remove(i)` 会让后面的索引整体前移，先前解析的 `index` 立刻失效。
   规则：**先按索引取走目标，再动会改变容器结构的东西**（自检 A5 组盯这条）。
