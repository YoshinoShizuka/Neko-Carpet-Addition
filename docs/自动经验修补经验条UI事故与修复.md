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

验收脚本之外的正式代码只改了一个方法：`neko.automending.AutoMending#mending`。

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

新增 `AutoMendingSelfCheck.java.template`，S 组把「鞘翅滑行 40 秒」整段跑一遍：

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
- 自检模板落盘：`AutoMendingSelfCheck.java.template`。

---

# 第二轮（2026-09-30 18:10 部署后复现）：真凶是 float32 浮点边界

> 第一轮修复部署后（MD5 `fed8b0b4…`，游戏 18:14 重启、确认加载的是修复版），
> 玩家复测反馈：**「跳经验条的问题又复现了」**。

## 8. 为什么第一轮的修复不够

第一轮修掉了「2 倍过度扣减」与「试探 1 点越界兜底」，并论证了 `spent ≤ budget` 恒成立。
**但那个不等式是在实数域成立的，而 Java 跑的是 float32。**

新仿真 `xp_float_edge.py` 扫描 49215 个「实机可能出现的进度形态」
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
- 新增仿真脚本：`xp_float_edge.py`（扫描越界率）、
  `xp_float_fix.py`（float32 vs double 预算对照）。
- 自检模板更新：`AutoMendingSelfCheck.java.template`（S 组 + T1/T2/T4）。
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

---

# 第三轮（终章）：真正根因不是本模组 —— 是 26.3 的「定位条」抢了经验条的 HUD 槽位

> 日期：2026-09-30 ｜ 用户复现条件：存档 `20260906`，角色 `Akirono`，**2752 级**，
> 经验条大约一半时触发自动修补，随后「进度条直接扣到零、等级不变」，
> 但**重新吸取经验后经验条恢复正常**。

前两轮修的是真实缺陷（过度扣减、float32 边界），但**症状没有消失**，
因为它其实一直不是「经验数值被扣穿」造成的视觉表现。这一轮先用证据把服务端彻底排除，
再把 HUD 的真正来源钉死。

## 14. 服务端被三重证据排除

### 14.1 数学上不可能归零

归零的唯一路径是 `setExperiencePoints(budget - spent)` 收到 `<= 0`，即 `spent >= budget`：

```
spent = actual * budget / repair            actual = min(repair, damage)
⇒ spent >= budget  ⇔  actual >= repair  ⇔  damage >= repair = 2 * budget
```

2752 级半满 ⇒ `needed = 24610` ⇒ `budget = 12305` ⇒ 需要**单件物品损坏值 ≥ 24610**。
而原版耐久上限最高的是下界合金镐（2031）、鞘翅（432）——**差了 12 倍以上，物理上不可能**。

### 14.2 存档 NBT 取证（游戏运行中两次采样）

`saves/20260906/players/data/1a9e05c1-…dat`：

| 时刻 | XpLevel | XpP | XpTotal |
|---|---|---|---|
| 第一次 | 2752 | 0.8139780759811401 | 35188967 |
| 第二次 | 2752 | 0.8015034794807434 | 35188709 |

- `XpP × 24610 = 20032.0004` ⇒ 精度内为整数 ⇒ **升级曲线是原版**（`floor` 取整性质成立），
  顺带排除 FGA `experienceLevelCost`（它只会返回 7 或 107，两种都算不出整数）。
- 两次采样间 `XpTotal` 只掉了 **258** 点 ⇒ 服务端进度条稳定在 **80%**，**根本没有被归零**。
- 装备实测：护甲 damage 2/4/5/8；即使磨到鞘翅满损 432，`spent` 也只有 216 点（≈0.88% 进度）。

> 附注：`XpTotal(35188709)` 比 `level 2752` 对应的累积经验高出约 155 万，看似异常，
> 实为**原版行为**——`Player#onEnchantmentPerformed` 只做 `experienceLevel -= cost`，
> **不动 `totalExperience`**。所以 `totalExperience` 从来不是进度条的权威来源，
> 本模组用 `experienceProgress` 是对的。

### 14.3 客户端链路是纯原版的

- 发包条件（`ServerPlayer#doTick`）：`totalExperience != lastSentExp` → 发
  `ClientboundSetExperiencePacket(progress, total, level)`；本模组每次修补都会改 `totalExperience`，必发。
- 收包（`ClientPacketListener#handleSetExperience`）→ `LocalPlayer#setExperienceValues(progress, total, level)`
  → **纯赋值，无 clamp、无重算**。
- 渲染（`ExperienceBar#extractBackground`）：`needed > 0` 时
  `w = (int)(experienceProgress * 183.0F)`，`w > 0` 才画绿色填充 —— **没有任何插值动画**。

⇒ 若服务端发 0.80，客户端不可能自己显示成空条。**所以问题必然出在「这一帧画了哪根条」。**

## 15. 根因：26.3 的定位条（LocatorBar）与经验条共用同一个 HUD 槽位

### 15.1 `Hud` 把「条」和「等级数字」拆成两次调用（javap -c 实证）

```java
ContextualInfo info = nextContextualInfoState();
if (info != contextualInfoBar.getFirst()) {
    contextualInfoBar = Pair.of(info, contextualInfoBars.get(info).get());
}
contextualInfoBar.getSecond().extractBackground(g, dt);                     // (A) 条本体
if (gameMode.hasExperience() && player.experienceLevel > 0) {
    ContextualBar.extractExperienceLevel(g, font, player.experienceLevel);   // (B) 等级数字
}
contextualInfoBar.getSecond().extractRenderState(g, dt);                     // (C)
```

- **(B) 等级数字是独立调用**：只要 `hasExperience() && experienceLevel > 0` 就画，
  **与当前是哪种条无关**。→ **这就是「等级还在、进度条没了」的直接解释。**
- **(A) 条本体**由 `contextualInfoBar` 决定，而它由：

### 15.2 槽位仲裁——「有其他人的话，默认显示定位条」

```java
private ContextualInfo nextContextualInfoState() {
    boolean hasWaypoints  = player.connection.getWaypointManager().hasWaypoints();
    boolean jumpable      = player.jumpableVehicle() != null;
    boolean hasExperience = gameMode.hasExperience();

    if (hasWaypoints) {
        if (jumpable && willPrioritizeJumpInfo())          return JUMPABLE_VEHICLE;
        if (hasExperience && willPrioritizeExperienceInfo()) return EXPERIENCE;  // ← 5 秒窗口
        return LOCATOR;                                                          // ← 否则定位条
    }
    if (jumpable)      return JUMPABLE_VEHICLE;
    if (hasExperience) return EXPERIENCE;
    return EMPTY;
}

private boolean willPrioritizeExperienceInfo() {
    return player.experienceDisplayStartTick + 100 > player.tickCount;   // 100 tick = 5 秒
}
```

`experienceDisplayStartTick` **只在** `LocalPlayer#setExperienceValues` 里被刷新，
且**仅当 `progress` 真的变了**：

```java
public void setExperienceValues(float progress, int total, int level) {
    if (progress != this.experienceProgress) this.setExperienceDisplayStartTickToTickCount();
    this.experienceProgress = progress; this.totalExperience = total; this.experienceLevel = level;
}
```

### 15.3 定位条空着的时候长什么样

```java
// LocatorBar#extractBackground
g.blitSprite(GUI_TEXTURED, LOCATOR_BAR_BACKGROUND, left, top, 182, 5);   // 只有背景，无填充
// LocatorBar#extractRenderState → 只为「追踪到的 waypoint」画点/箭头
```

**182×5 的背景尺寸与经验条背景完全一致，但没有任何填充** —— 屏幕上就是一条**空条**。

### 15.4 用户的实机现场（日志取证）

```
[18:53:44] Akirono[local:E:b921b6c9] logged in …        ← 用户
[19:34:20] OWHP[local] logged in …                      ← 另一个玩家加入
[19:35:19] OWHP[local] logged in …
```

`ClientWaypointManager#hasWaypoints()` = `!waypoints.isEmpty()`，由服务端下发的
`ClientboundTrackedWaypointPacket` 填充。**世界里有 `OWHP` ⇒ `hasWaypoints() == true`。**

而存档 `level.dat` 里**没有 `GameRules` 键**（全部默认），`GameRules` 注册处为：

```
302: ldc_w  "locator_bar"
308: iconst_1                     ← 默认值 true
309: registerBoolean(String, GameRuleCategory.PLAYER, boolean)
```

⇒ **定位条默认开启。** 引用 `LOCATOR_BAR` 的只有 4 个类，其中
**`net/minecraft/server/waypoints/ServerWaypointManager`** 就是服务端的下发开关。

### 15.5 把用户的三句话逐条对回去

| 用户描述 | 机制 |
|---|---|
| 等级还在 | 等级数字走 (B)，独立于哪种条 |
| 进度条「归零」 | `hasWaypoints() && !5秒窗口` ⇒ 槽位被 **LocatorBar** 占据，空背景无填充 |
| 「一半时触发自动修补，随即扣到零」 | 修补**扣经验** ⇒ 发 `ClientboundSetExperiencePacket` ⇒ 客户端 `progress` 变化 ⇒ 刷新 5 秒窗口 ⇒ **经验条先亮起（此时看到它在一半）**；窗口过后定位条收回槽位 ⇒ 看上去「直接扣到零」 |
| 重新吸取经验后恢复正常 | 吸经验同样刷新 5 秒窗口 ⇒ 经验条回到槽位 |

**结论：不是自动经验修补的 bug。** 本模组的经验数值在服务端始终正确（14.1–14.2 双重证明），
症状是 **26.3 新增的定位条会顶掉经验条槽位**这一原版行为，被「修补扣经验 ⇒ 经验条闪一下再被收回」
的节奏放大后，看起来像模组把条扣空了。

## 16. 处置

**根因不在本模组代码内，不动 `AutoMending.java`。** 用户侧二选一：

### 方案 A（推荐）：关掉定位条，经验条永久占位

```
/gamerule locator_bar false
```

服务端不再下发 waypoint ⇒ 客户端 `hasWaypoints()` 为假 ⇒ `nextContextualInfoState()`
回到 `EXPERIENCE` 分支。**代价：失去「其他玩家方向指示」功能。**

### 方案 B：保留定位条，换一种显示方式

- `LPCTools → tweaks/barTweaks → locatorBarUsesExpBackground = true`
  （定位条改用经验条背景）；
- 或 `expBarDisplaysLocatorPoints = true`。

（用户当前 `config/lpctools-LPCConfig.json` 里 barTweaks 五项**全为 `false`**，
即该模组目前完全没干预这两根条。）

### 排除清单（本轮逐一证伪，留档以免下次重复排查）

| 嫌疑 | 证伪依据 |
|---|---|
| 部署包陈旧 | 线上 MD5 与上轮交付一致，字节码为 double 预算版 |
| AMS `powerfulExpMending` | 默认 `false`，用户 `carpet.conf` 亦为 `false` |
| FGA `experienceLevelCost` | 只会返回 7/107，与 `XpP×24610` 为整数矛盾 |
| 本模组有客户端逻辑 | `fabric.mod.json` 无 client entrypoint，纯服务端 |
| 客户端不读 `experienceProgress` | `ExperienceBar` 明确 `(int)(experienceProgress*183.0f)` |
| **Gnetum HUD 缓存** | `gnetum$wrapInfoBar` 确实会跳过 `extractBackground`，但它只能造成**滞后**，**无法解释「吸经验就恢复」**；而定位条机制能同时解释全部四条现象 |
| `ImmediatelyFast` / `Ixeris` / `ModernUI` | 均未替换槽位仲裁；ModernUI 只接管字体 |
| LPCTools `barTweaks` | 五项全 `false`，且其语义是「互换两根条」而非清空 |

### 可直接给用户的一段话（复述口径）

> 这条空条其实不是经验条，是 26.3 新增的**定位条**：它和经验条共用屏幕上同一个位置。
> 只要世界里存在其他玩家（你的日志里 19:34 有 `OWHP` 进来过），HUD 默认显示定位条，
> 经验条**只在经验发生变化后的 5 秒内**临时抢回位置；等级数字是单独画的，所以它一直在。
> 自动修补会扣经验 ⇒ 经验条闪 5 秒（你恰好看到它在「一半」）⇒ 窗口一过定位条收回 ⇒ 看起来像被扣空了。
> 输入 `/gamerule locator_bar false` 就能让经验条永久显示回去。

---

# 第四轮（2026-09-30 20:30）：修补速度整体变慢 —— 预算口径的回退

第三轮定案之后玩家又报了一条新问题：

> 「经验修补的功能，在带有经验修补附魔的物品掉耐久度后，修补速度没之前的版本快了。」

追问后确认两点：**是「整体都比以前慢」**（而不是「经验条空了才停」），
并且**同意「允许消耗等级里的经验，速度优先」**。这两句话把方向定死了。

## 17. 取证：两版「一次修满一件」的能力完全相同（javap 逐条对比）

先把最像元凶的两个猜测排除掉：

| 猜测 | 取证 | 结论 |
|---|---|---|
| 触发频率被调慢了 | 反汇编 `dist/` 里 1.0.0~1.0.3 四个产物的 `AutoMendingExtension`：**全都是 `bipush 20`** | 排除 |
| GUGU 原版频率更高 | 反汇编本机 `carpet_gugu_addition-v1.1.2-mc26.1.2.jar` 的 `ServerPlayerMixin#tick`：条件是 `level().getGameTime() % 20 == 0` | 与原版同为 20 tick，排除 |

然后逐条比对 1.0.2 与 1.0.3 的 `AutoMending#mending` 字节码，**每次修补的耐久量完全一致**：

```
两版都是： repair = modifyDurabilityToRepairFromXp(level, stack, budget)   // ≈ 2 × budget
          actual = min(repair, stack.getDamageValue())
          stack.setDamageValue(damage - actual)
```

⇒ 速度差异**不可能**来自「每次修多少」，只能来自**「预算（budget）从哪来」**。

## 18. 根因：1.0.2 靠「扣穿 → 回弹」把进度条永远维持在 ~98%

| | 1.0.2（= GUGU 原版口径） | 1.0.3（上一轮的改动） |
|---|---|---|
| 预算 | `amount = 进度条存量`（空则给 1） | `budget = 进度条存量` |
| 扣减 | `giveExperiencePoints(-actual)` —— **actual 是耐久点数** | `setExperiencePoints(budget - spent)` |
| 副作用 | `actual > 进度条存量` 时**扣穿** → 等级 -1、**进度条被弹回 `1 + 负值` ≈ 99%** | 无 |
| 净效果 | **下一轮 budget 又是接近满条 ⇒ 永远修满、永不减速** | 进度条**只减不增** |

1.0.3 的问题链条是单调的：进度条只减不增 ⇒ 一旦 `2 × 进度条存量 < 物品损坏值`
（30 级时只要损坏超过 112 点就会发生）一次就修不满 ⇒ 预算越用越少 ⇒
`budget <= 0` **彻底停机**。玩家感受到的正是「整体都比以前慢」。

**必须点名的反省**：第一轮把「只花进度条里的经验」当成修复手段，理由是
`giveExperiencePoints` 的负值回弹会把进度条弹到 98%（脏状态）。
回弹确实是脏状态 —— 但**正确的结论是「用等级、但要精确记账」，而不是「不用等级」**。
不用等级就必然丢掉「永远修得动」这个能力，这就是本轮的回归。
（而第一轮真正要修的两件事 —— 2 倍过度扣减、以及后来定案的定位条 —— 都与是否动用等级无关。）

## 19. 修复

1. **预算改成「刚好够修满这一件」**：

   ```java
   int needXp = (damage + 1) / 2;      // 修满所需经验（1 经验 = 2 耐久，向上取整）
   int budget = Math.max(bar, needXp); // 进度条不够就往上借，保证一次修满
   ```

   取 `max` 而不是「进度条 + 固定一级」：后者在 30 级修 400 点损坏的鞘翅时只借到 168 点，
   仍然修不满（设计时自查发现，见自检 A 组）。上限只到「刚好修满」为止，所以也不会平白多花。

2. **支付换成 `spendExact`**（新增私有方法）：

   ```java
   spent <= bar  →  setExperiencePoints(bar - spent);
   否则          →  进度条清零 → 逐级 giveExperienceLevels(-1)
                    → 每级用 setExperiencePoints(n - remain) 写精确的整数刻度
                    （单次最多降 MAX_LEVELS_DRAWN = 8 级）
   ```

   这样等级可以被消耗，但**落点永远是整数经验刻度**，进度条不会变成负数，
   也就进不去原版那段回弹循环 —— **「速度回来了」和「进度条不乱跳」同时成立**。

3. **等级见底付不满时按实付折算耐久**，宁可少修，绝不出现「修了却不扣经验」。

4. **顺带修掉一个隐性空转**：`spent = actual × budget / repair` 是整数除法，
   `damage = 1` 时会得到 0，原代码直接 `return` ⇒ **只掉 1 点耐久的物品永远不被修**。现在保底扣 1 点。

## 20. 本轮用到的原版 API（26.3 javap 逐条核实，不猜）

| API | 字节码事实 |
|---|---|
| `ServerPlayer#setExperiencePoints(int)` | `Mth.clamp(amount/f, 0, (f-1)/f)`；值变了才置 `lastSentExp = -1` |
| `ServerPlayer#giveExperienceLevels(int)` | `levels == 0` 直接 return；负数照常生效；随后置 `lastSentExp = -1` |
| `Player#giveExperienceLevels(int)` | `IntMath.saturatedAdd`；**只在 level < 0 时**清 progress/total；`levels > 0` 才走升级音效分支 ⇒ 降级不发声 |
| `ServerPlayer#setExperienceLevels(int)` | 只写 `experienceLevel` + 脏标记，**不动 `totalExperience`** |
| `EnchantmentHelper#getRandomItemWith(...)` | 只遍历 `EquipmentSlot.VALUES`（装备槽），**不扫背包** |

## 21. 自检（真实 `runServer` 26.3，`checks=25 failures=0`）

```
鞘翅最大耐久 = 432
A1 旧算法（预算=进度条）：修好 2 点，剩余损坏 398，progress=0.0     ← 复刻玩家抱怨
A2 新算法：修好 400 点，剩余损坏 0，等级 30 → 28，progress=0.09803922
S1 30 级半条进度：56 点经验，修好 400/400 点，等级 30 → 28，progress=0.6372549
S4 2752 级（needed=24610）半条进度 0.5：修好 432/432 点，等级 2752 → 2752，
   progress=0.49122307，总经验 -216
S8 进度条清零、仅剩等级：100 → 99，修好 432/432 点，progress=0.7053206
S12 等级 0 + 进度条 0：剩余损坏 432                                ← 不修也不扣
S14 只有 3 点经验：修好 6 点（应 ≤ 6），剩余 426，progress=0.0      ← 严格 2:1，不白嫖
R1 反证：giveExperiencePoints 多扣 1 点 → 等级 30 → 29，进度 0.9906542
R4 新支付：等级 → 28，进度 0.6372549（needed=102），是否精确整数刻度 = true
T1 穷举完成：level 0~40，每点扫 ±2ulp，共 15405 个进度形态
T4 真实链路 20000 轮：末等级 29，修理 20000 次，单轮最大掉级 1，末进度 0.19626169
SMOKE RESULT automending/26.3 checks=25 failures=0
```

**A 组是「有牙」的关键**：光断言「修满」是没牙的（进度条够用时旧代码也修满）。
A1 用旧算法在**同一种进度形态**（`progress = 1/needed`）下只能修 **2 点**、
A2 用新算法修满 **400 点** —— 一失败一通过，才证明这条断言真的能分辨两种实现。

**R 组是「脏状态」的反证**：R1 证明 `giveExperiencePoints` 扣穿确实会掉级并把进度条弹到
`0.9906542`；R4 证明新支付在同一路径下落点仍是整数刻度、不弹条。

**T 组**：`f32(k/needed)` 附近的 ±2ulp 邻域共 15405 个形态，0 越界；T4 跑 20000 轮
「持续获得经验 + 持续滑行修补」抓累积漂移，进度条全程落在 `[0,1)`。

## 22. 行为变化（务必知悉）

- **会掉等级了**：进度条里的经验不够时自动向下动用等级。这是恢复旧速度的**唯一**途径
  （旧版正是靠这个把进度条维持在 98%），也是本轮与玩家确认过的选择（「速度优先」）。
- **但不会有脏状态**：落点始终是整数经验刻度，绝不会出现「进度条突然跳回 98%」。
- 规则文案（`carpet.rule.autoMending.desc`）已同步改成新口径。
