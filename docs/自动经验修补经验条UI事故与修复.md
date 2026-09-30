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

---

# 第二轮（2026-09-30 18:10 部署后复现）：真凶是 float32 浮点边界

> 第一轮修复部署后（MD5 `fed8b0b4…`，游戏 18:14 重启、确认加载的是修复版），
> 玩家复测反馈：**「跳经验条的问题又复现了」**。

## 8. 为什么第一轮的修复不够

第一轮修掉了「2 倍过度扣减」与「试探 1 点越界兜底」，并论证了 `spent ≤ budget` 恒成立。
**但那个不等式是在实数域成立的，而 Java 跑的是 float32。**

新仿真 `.workbuddy/tools/xp_float_edge.py` 扫描 49215 个「实机可能出现的进度形态」
（`k/needed` 及其 ±1 ulp），**2.58%（1270 个）会越界**：

```
level=2  needed=11  p=0.45454543828964233  budget=5  -> newp=-2.98e-08
```

逐步骤拆开看，float32 里实际发生的是：

| 步骤 | float32 里实际发生的事 |
| --- | --- |
| `p` = `f32(5/11)` 的 nextDown | `p = 0.45454543828964233` |
| `(float)p * (float)11` | 真实积是 `4.99999982`，但 **float32 只有 24 位有效数字，把它舍入成 `5.0`** |
| `Math.floor(5.0)` | `budget = 5` ← **比真实值 `4.99999982` 大了 1** |
| `progress += (float)(-5) / (float)11` | `0.45454543828964233 - 0.45454546809196472 = -2.98e-8` |
| `while (progress < 0.0F)` | **等级 -1，进度条弹到 `1 + f/needed` ≈ 100%** |

**结论：「扣穿」根本不需要算错经验 —— 一次 float32 舍入就够了。**

## 9. 为什么第一轮自检全绿（自检的盲区在哪）

第一版 T 组只扫了 `(float)k/(float)n` 这些「干净」进度点，实测**旧算法在它们上面是 0 越界**，
反证因此失败。原因很有意思：

- **干净点 `f32(k/needed)` 是安全的**：即便 `p*needed` 被 float32 舍入，也是往「更大」的方向，
  `floor` 得到的预算仍 ≤ 真实值。
- **危险的是它的 nextDown 邻域**：`p` 比 `f32(k/needed)` 小一个 ulp，可 `p*needed` 的 float32
  舍入仍然落到整数 `k` 上 —— 预算就此比真实值大 1。

那实机 `progress` 会不会漂移进这个邻域？**会。** 第一轮修复用的
`giveExperiencePoints(-spent)` 是 **float32 减法**，每修一次就把 `progress` 推离
`k/needed` 网格一点点。漂移几轮后落进危险邻域，下一次修补立刻扣穿。
**这就是「修好了又复现」的完整闭环。**

> 教训：**自检只测「干净输入」等于没测**。凡是跟浮点记账有关的地方，必须扫 ulp 邻域，
> 并且要模拟真实链路的**累积漂移**，而不是单次调用。

## 10. 第二轮修复（两道防线）

### 防线一：预算用 double 算 —— 消除 float32 乘法的向上舍入

```java
int budget = (int) Math.floor((double) player.experienceProgress * (double) needed);
```

`experienceProgress` 是 float32，转 double 无损；`needed` 是 int，转 double 无损；
乘积 `p*needed < 2^37`，double（53 位有效数字）**可以精确表示** —— `floor` 结果恒 ≤ 真实值。
仿真对照：同一批 49020 个边界点，**float32 预算 12 个越界样本，double 预算 0 个**。

### 防线二：写回改用原版 `setExperiencePoints` —— 原版自带 clamp

```java
player.setExperiencePoints(budget - spent);
player.totalExperience = Math.max(0, player.totalExperience - spent);
```

反汇编 `ServerPlayer#setExperiencePoints(int)`（26.3）：

```java
public void setExperiencePoints(int amount) {
    float f = getXpNeededForNextLevel();
    float max = (f - 1.0F) / f;
    float p = Mth.clamp((float)amount / f, 0.0F, max);   // ← 物理上不可能写出负数
    if (p == this.experienceProgress) return;
    this.experienceProgress = p;
    this.lastSentExp = -1;                                // ← 顺手打脏标记，doTick 会发包同步
}
```

为什么这才是对的 API（对比 `giveExperiencePoints`）：

| | `giveExperiencePoints(-spent)` | `setExperiencePoints(remain)` |
| --- | --- | --- |
| 负数保护 | ❌ 有「负值回弹」：`progress < 0` ⇒ 等级 -1 + 条弹满 | ✅ `Mth.clamp` 到 `[0, 1-1/needed]` |
| 客户端同步 | 置 `lastSentExp = -1` | 同样置 `lastSentExp = -1` |
| 总经验 `totalExperience` | 自动更新 | 不更新 → 手动补一行 |
| 副产物 | 每次都是 float32 减法，**会漂移** | 把 progress 规整回精确的 `f32(k/needed)`，**从根上消除漂移** |

最后一条是重点：因为干净点永不越界，**每次都用 `setExperiencePoints` 把进度条重置回
`k/needed` 形态，就等于永久性地离开了危险邻域**。

## 11. 逐版本 API 核对（javap / mappings，不猜）

| 版本 | `ServerPlayer#setExperiencePoints(int)` | 实现 |
| --- | --- | --- |
| 26.3 / 26.2 / 26.1.1 / 26.1.2 | 存在 | `Mth.clamp(amount/f, 0, (f-1)/f)` + `lastSentExp = -1`（四版字节码逐条一致） |
| 1.21.11 | 存在（`class_3222` 的 `method_14228`，official 名 `a`） | 同样调 `bgj.a:(FFF)F`（`Mth.clamp`）+ 置 `cZ = -1`，**与 26.x 逐字节同构** |

> 1.21.11 是混淆 jar，方法名从 `loom.mappings.1_21_11.../mappings.jar` 里的
> `mappings/mappings.tiny` 查得（`class_3222` = ServerPlayer，`method_14228` = setExperiencePoints）。
> 顺带确认 `totalExperience`（`field_12582`）是 public 字段，五版本均可直接写。

## 12. 自检（第二轮，真实 `runServer` 26.3）

T 组重写为**扫 ulp 邻域 + 真实链路**：

```
T1 穷举完成：level 0~40，每点扫 ±2ulp，共 15405 个进度形态
    修复后越界 0 个；旧算法越界 191 个        ← 反证终于有牙
T4 真实链路 20000 轮：等级 30 → 30，修理 20000 次，末进度 0.0
S1 起始：等级 30（needed=112） 进度 0.5 进度条经验=56
S1 滑行 40 秒后：等级 30 进度 0.0 累计修耐久 112 累计耗经验 56
S7 反证：多扣 1 点后 等级 30 → 29，进度 0.9906542
SMOKE RESULT automending/26.3 checks=15 failures=0
```

- **T1**：15405 个进度形态（含 ±2 ulp）跑真实 `mending()` 并连修 3 轮 —— 修复后 0 越界；
  同一批点用旧算法跑出 **191 个越界**，反证成立。
- **T4**：连续 20000 轮「获得经验 + 滑行受损 + 自动修补」，等级 30 → 30 **从未掉级**，
  进度条全程合法 —— 专门抓 float32 减法的累积漂移。
- S 组保持第一轮的常规场景与反证（`S7` 仍能复现「扣穿 ⇒ 掉级 + 条弹 99%」）。

## 13. 交付（第二轮）

- 修复文件：`AutoMending.java`（五版本内容一致，已 `cp` 分发）。
- 新增仿真脚本：`.workbuddy/tools/xp_float_edge.py`（扫描越界率）、
  `.workbuddy/tools/xp_float_fix.py`（float32 vs double 预算对照）。
- 自检模板更新：`.workbuddy/tools/AutoMendingSelfCheck.java.template`（S 组 + T1/T2/T4）。
- 五版本 build 全绿，`dist/` 刷新；26.3 部署 `RO26.3\mods\`，
  **MD5 `26049b6932cf2877b73bfca08e853062`**（dist 与 mods 一致）。
  字节码反查确认线上包：`giveExperiencePoints` 引用数 **0**、`setExperiencePoints` **1**、
  `totalExperience` 访问 **2**、预算处为 `i2d → dmul → d2i`（double 乘法），**无 `fmul`**。
- 临时自检已清理（`AutoMendingSelfCheck.java` 删除、`AutoMendingExtension#onTick` 还原）。

### 行为变化（需要知悉）

- **进度条会精确停在「整数经验点」上**：`setExperiencePoints` 按经验点数设置进度，
  所以 `progress` 恒为 `k/needed` 形态（视觉上与原先无差异，误差 < 1/needed）。
- **仍然不会去动等级里的经验**：进度条空了就停手。这是彻底消除「扣穿」的代价，
  也是第一轮就说明过的口径。
