# 自动经验修补「经验条显示异常」事故与修复（附：全部规则默认改为关闭）

> 日期：2026-09-30 ｜ 影响版本：26.3 / 26.2 / 26.1.1 / 26.1.2 / 1.21.11（五版本同步）
> 关联：本文第二部分把模组全部 Carpet 规则的默认值改为 `false`

## 1. 现象

玩家反馈：

> 在启用自动经验修补后，玩家使用鞘翅滑行消耗经验补充带有经验修补附魔属性的鞘翅，
> 会导致经验 UI 那边显示异常，具体表现是**只显示经验等级，而不显示该等级处于多少进度
> 晋升到下一等级**。

## 2. 取证

### 2.1 先确认「经验同步」这条链路没问题（javap -c）

排查第一个怀疑对象：是不是服务端扣了经验却没同步给客户端。

`ServerPlayer#giveExperiencePoints` 的字节码只有三行有效逻辑：

```
0: iload_1
1: ifne 5        // amount == 0 直接返回
5: invokespecial Player.giveExperiencePoints:(I)V
10: aload_0
11: iconst_m1
12: putfield lastSentExp:I     // ← 只打「脏」标记，并不立即发包
```

真正的发包在 `ServerPlayer#doTick`：

```
487: getfield totalExperience
491: getfield lastSentExp
495: if_icmpeq 532               // 相等就跳过
498: ... new ClientboundSetExperiencePacket(experienceProgress, totalExperience, experienceLevel)
529: invokevirtual ServerGamePacketListenerImpl.send
```

**结论：同步链路是好的**——扣经验会置脏，下一 tick 必定把 `(progress, total, level)` 三件套发下去。
所以问题不在「没发」，而在「发下去的那个 `experienceProgress` 本身就不正常」。

### 2.2 关键：原版的「负值回弹」循环

`Player#giveExperiencePoints`（javap -c 实证）：

```java
increaseScore(amount);
experienceProgress += (float) amount / (float) getXpNeededForNextLevel();
totalExperience = Mth.clamp(totalExperience + amount, 0, Integer.MAX_VALUE);

while (experienceProgress < 0.0F) {                  // ← 负值回弹
    float f = experienceProgress * getXpNeededForNextLevel();   // needed 是【旧】等级的
    if (experienceLevel > 0) {
        giveExperienceLevels(-1);
        experienceProgress = 1.0F + f / getXpNeededForNextLevel();   // needed 是【新】等级的
    } else {
        giveExperienceLevels(-1);
        experienceProgress = 0.0F;
    }
}
while (experienceProgress >= 1.0F) { /* 升段 */ }
```

**只要一次性扣掉的经验超过进度条里的存量，进度条就会变成负数，然后被这个循环
「等级 -1、进度条弹到 `1 + 负值` ≈ 98%`」。** 这本身不算错（附魔台花经验也是这个表现），
但自动修补是**每 20 tick 无条件跑一次**的——鞘翅滑行时耐久持续掉，于是每秒钟都会触发一次
「扣穿 → 降级 → 进度条弹回 98%」，玩家看到的就是锯齿状、完全反映不出真实进度的经验条。

### 2.3 原版权威实现：消耗的经验要按比例反算

`ExperienceOrb#repairPlayerItems`（javap -c，这是原版自己修装备的那条路）：

```
38: EnchantmentHelper.modifyDurabilityToRepairFromXp(...)   → repair(局部变量5)
51: Math.min(repair, getDamageValue())                      → actual(局部变量6)
66: setDamageValue(getDamageValue() - actual)
74: iload_2                 // amount
75: iload 6                 // actual
77: iload_2                 // amount
78: imul                    // actual * amount
79: iload 5                 // repair
81: idiv                    // (actual * amount) / repair
82: isub                    // remainder = amount - actual * amount / repair
```

即：**真正消耗的经验 = `actual * amount / repair`**，剩下的 `remainder` 递归去修下一件
（或还给玩家）。而 `repair_with_xp` 的效果是 `multiply 2.0`
（`data/minecraft/enchantment/mending.json` 实证），也就是 1 经验 = 2 耐久。
而本模组此前扣的是 **`actual`（修好的耐久点数）**，等于把经验当成耐久在扣 ——
**整整 2 倍的过度扣减**。

### 2.4 数值仿真（严格复刻上面的字节码语义）

场景：等级 30（`needed=112`）、进度 0.5、鞘翅滑行每秒掉 20 点耐久。

| 秒 | A 现状 进度 / 等级 | C 修复后 进度 / 等级 |
|---|---|---|
| 1 | 0.143 / 30 | 0.321 / 30 |
| 2 | **0.963 / 29** ← 弹回 | 0.232 / 30 |
| 3 | 0.776 / 29 | 0.143 / 30 |
| 4 | 0.589 / 29 | 0.054 / 30 |
| 5 | 0.402 / 29 | 0.009 / 30 |
| 6 | 0.215 / 29 | 0.009 / 30 |
| 7 | 0.028 / 29 | 0.009 / 30 |
| 8 | **0.99 / 28** ← 弹回 | 0.009 / 30 |
| **30 秒后** | **等级 24（掉 6 级），进度 min 0.0 / max 0.99** | **等级 30（不掉级），进度 0.321→0.009 平滑单调递减** |

## 3. 根因（两个，叠加在一起）

1. **过度扣减**：扣的是 `actual`（耐久点数）而不是 `actual * budget / repair`（经验），
   `repair = 2 × budget` 时**恰好 2 倍**。这让扣减极易超过进度条存量。
2. **「试探等级」兜底必然越界**：原代码在进度条为 0 且还有等级时，把预算设成 1 点
   （`amount = 1`）想让扣减「自然吃掉等级里的存量」——
   但扣 1 点同样会让进度条变负，**必然**触发 2.2 的降级回弹。

两者叠加 ⇒ 每次 `mending()` 都可能把 `experienceProgress` 推成负数 ⇒
等级每秒往下掉、进度条每秒弹回 ~98% ⇒ **「只看得见等级在变，看不出真实进度」**。

## 4. 修复

`.workbuddy` 之外的正式代码只改了一个方法：`neko.automending.AutoMending#mending`。

```java
// 1) 预算 = 进度条里现有的经验。不用 totalExperience，也不再用「试探 1 点」兜底。
int budget = (int) Math.floor(player.experienceProgress * (float) player.getXpNeededForNextLevel());
if (budget <= 0) return;

Optional<EnchantedItemInUse> optional = EnchantmentHelper.getRandomItemWith(
        EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
if (optional.isEmpty()) return;

ItemStack stack = optional.get().itemStack();
int repair = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), stack, budget);
if (repair <= 0) return;                       // 别拿它当除数

int actual = Math.min(repair, stack.getDamageValue());
if (actual <= 0) return;
stack.setDamageValue(stack.getDamageValue() - actual);

// 2) 真正消耗 = budget × (实修耐久 / 预算能修的耐久)，与原版线性反算一致
int spent = actual * budget / repair;          // 恒有 spent ≤ budget
if (spent > 0) player.giveExperiencePoints(-spent);
```

**不变量（可以证明，不只是"看起来对"）**：
`spent ≤ budget = floor(progress × needed)`，所以

```
progress_after = progress - spent/needed
              ≥ progress - floor(progress × needed)/needed
              = (progress × needed - floor(progress × needed)) / needed
              ≥ 0
```

⇒ **`experienceProgress` 永远不会变负 ⇒ 负值回弹分支永远进不去 ⇒ 这个方法不会改动
`experienceLevel`。** 进度条只会平滑减少，直到条里的经验花完为止。

> 行为变化：进度条空了之后**不再**去动等级里的经验。这是有意的——"扣穿进度条"正是本 bug 的
> 成因，想让它彻底不出现就只能不越界。玩家一旦获得经验（条里有了存货）自动修补就会继续工作。
> 该口径也写进了规则的 `.desc`（中英双语）。

## 5. 自检（真实 `runServer`，26.3）

新增 `.workbuddy/tools/AutoMendingSelfCheck.java.template`，S 组把「鞘翅滑行 40 秒」整段跑一遍：

```
S1 起始：等级 30（needed=112） 进度 0.5 进度条经验=56
S1 滑行 40 秒后：等级 30 进度 0.0 累计修耐久 112 累计耗经验 56
S7 反证：多扣 1 点后 等级 30 → 29，进度 0.9906542
SMOKE RESULT automending/26.3 checks=10 failures=0
```

| 断言 | 结果 |
|---|---|
| S2 修补全程等级恒定不变（旧代码会掉级） | ✅ |
| S3 `experienceProgress` 全程落在 `[0,1)` | ✅ |
| S4 等级没被消耗（仍为 30） | ✅ |
| S5 耗经验 == 修耐久 / 2（无 2 倍过度扣减） | ✅ 112 耐久 / 56 经验 = 精确 2:1 |
| S6 确实修了东西（不是全程空转） | ✅ |
| **S7/S8 反证**：手工多扣 1 点 ⇒ 等级 30→29、进度弹到 **0.9906542** | ✅ |

**S7/S8 是特意加的反证组**：手工构造一次"越界扣减"，确认「扣穿 ⇒ 掉级 + 进度条弹回 99%」
这个机制真实存在。没有它，S2/S3 全绿也可能只是因为断言本身没牙
（这个项目已经吃过「自检全绿、实机复现」的亏）。

## 6. 全部规则默认值改为关闭

| 规则 | 文件 | 改前 | 改后 |
|---|---|---|---|
| `autoMending` | `common/.../AutoMendingSettings.java` | `false` | `false`（本来就是） |
| `voidWarehouse` | `common/.../VoidWarehouseSettings.java` | `true` | **`false`** |
| `voidWarehouseRestock` | 五个版本的 `restock/VoidWarehouseRestockSettings.java` | `true` | **`false`** |

- 三处 javadoc 一并更新：`VoidWarehouseSettings` 里「按需求『默认开启』」的说法已删除，
  统一改成「本模组全部规则默认 `false`，由服主显式开启，避免装上就悄悄改变原版行为」。
- `VoidWarehouseRestockSettings` 的「默认值的取舍」重写：默认关，因为开着的规则意味着
  **任何装了对应打印机客户端的玩家都能从全服共享仓库领物品**，那是需要服主主动知情的授权。
- 自检模板不受影响：它们本来就显式 `VoidWarehouseSettings.voidWarehouse = true`（已核对）。

## 7. 交付

- 五版本 build 全绿（26.3 / 26.2 / 26.1.1 / 26.1.2 / 1.21.11），`dist/` 已刷新。
- 26.3 部署 `E:\Game\Minecraft\.minecraft\versions\RO26.3\mods\`，MD5 见当轮提交说明。
- 临时自检已清理：`AutoMendingSelfCheck.java` 删除、`AutoMendingExtension#onTick` 还原。
- 自检模板落盘：`.workbuddy/tools/AutoMendingSelfCheck.java.template`。
