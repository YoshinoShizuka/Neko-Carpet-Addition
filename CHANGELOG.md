# 更新日志 / Changelog

> **版本号只代表「规则集变了」**：只有新增一条 carpet 规则才升号；修 bug、改算法、补自检、改文档一律**原地覆盖当前版本**。
> 所以同一个版本号下的 jar 可能比首次发布更新，靠文件名旁的 md5 区分。

## Carpet Neko Addition 1.0.15

### ✨ Added & Improved / 新增与优化

#### 虚空仓库配方扣料（`voidWarehouseCraft`）

成品没货时，按玩家**亲手合成过**的配方直接从仓库扣原料，把成品给你 —— 等价于替你做掉这一步合成(Added `voidWarehouseCraft`: deduct ingredients from the shared warehouse using recipes the players have actually crafted.)。

- **先学会，才能用**：在合成台（含背包 2×2）里成功合成一次，这次用到的原料与产物就记进**配方本**(Recipes are learned by crafting once; nothing is available before that.)。
- **原料不够会往下钻，最多 4 层**：取活塞要 3 块木板、木板没货但仓库里有原木，就扣原木做木板 —— 只在**已学过的配方**之间钻，不会凭空出现你没做过的东西(Recursive up to 4 levels, restricted to learned recipes.)。
- 配方本**全服共用一份**，随存档持久化，重启不丢(The recipe book is server-wide and persists with the save.)。
- 和 9 合 1 的分工：9 合 1 只覆盖原版那几十条 `9:1` / `4:1` 换算，本规则能覆盖**任意**配方（活塞、漏斗、侦测器……）(9-in-1 only handles vanilla ratio conversions; this rule covers arbitrary recipes.)。
- 优先级：真实库存 → 9 合 1 可兑 → 配方可合成；同一件物品**不会同时出现两格**(Priority: real stock → 9-in-1 → craftable; an item never occupies two slots.)。
- 界面标「（可合成）」，鼠标提示写清「可合成 N 个，用 圆石 64 个 ＋ …」；一次能拿多少与真账同源，不会出现「说 64 实给 63」(The slot tooltip shows the exact ingredient breakdown; the offered amount matches the real accounting.)。
- 特殊配方（染色、烟火、地图缩放、成书复制等产物带数据组件的）**不记录** —— 学会了也只会给一件白板物品(Special recipes producing data-component items are not learned.)。

### ⚡ Performance / 性能

- 可兑表（`exchangeOffers`）的重算从「谁先读谁付钱」改成**按 tick 合并**：同一 tick 内无论多少人刷界面，只会重算一次(Coalesced offer-list rebuilds to once per tick.)。
  - 20 人同 tick 打开仓库的峰值：**30.573 ms → 1.990 ms**（占 50 ms tick 的 61.1% → 4.0%）。
  - 同 tick 内第 2~21 人命中缓存：**0.0046 ms**；20 人稳态 0.110 ms。
  - tick 戳取服务端 `getTickCount()`，不自增 —— 自增在 `onTick` 停摆时会**永不重算**，比慢更糟。
- 单次重算本身仍是 1.6 ms 量级且**与库存量级无关**（1.0.12 收窄上界、1.0.13 压紧生产方向的成果保持有效）(Single rebuild stays ~1.6 ms and is independent of stock size.)。

### 🐛 Fixed / 修复

- 修复「仓库里明明有原木，取活塞却说做不出来」：材料快照只取了表层的木板，没带上深一层的原木(Fixed false negatives caused by a shallow material snapshot that missed deeper ingredients.)。
- 修复 `markChanged()` 整体置空缓存导致的高负载抖动：界面刷新是「每格都可能问一次」的路径，N 人同 tick = N 次重算线性叠加、无上界(Fixed unbounded cache invalidation amplified by concurrent GUI refreshes.)。
- 修复同一产物有多条已学配方（如橡木版与白桦版活塞）时「界面写可合成 64、右键只给 1」：执行侧改为与界面同一口径，取做得最多的那条配方(Fixed the offer amount disagreeing with the actual take when several learned recipes share one output.)。

### 🧪 Testing / 测试

- 新增全功能系统测试：真服务端 **192 条断言全绿、0 失败**，覆盖六条规则、九类交互链路与多人压测(Added an end-to-end system test suite: 192 assertions, 0 failures, on a real server.)。
- 本轮修复新增 5 条「合并契约」断言（同 tick 不重算 / 下一 tick 立刻反映库存变化 / 关规则立刻清空）(Added 5 assertions pinning the per-tick coalescing contract.)。

### 📦 Builds / 构建产物

| MC 版本 | 文件 | 体积 | md5 |
| --- | --- | --- | --- |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.15.jar` | 198,495 B | `66ff87d1cff284d884551ed97eca617c` |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.15-sources.jar` | 209,790 B | `a83946bc18b567c508638e152466ab9b` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.15.jar` | 198,448 B | `dae3e6cd5eeb50f4d09f57c59485b70d` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.15-sources.jar` | 209,886 B | `d473fb480f5dca2ea20bba418bf45ddd` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.15.jar` | 201,698 B | `5ec6b01f274e6ac4481a706d8764d761` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.15-sources.jar` | 210,903 B | `7809737faa7b0826c620cb4a7edc3678` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.15.jar` | 201,700 B | `38deec1579b8be5e1292c29c49ea720a` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.15-sources.jar` | 210,903 B | `571dadc569adf9b62109af7fb8fffb56` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.15.jar` | 200,156 B | `9cbab1c5ad69035c60c0b15aecf28660` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.15-sources.jar` | 211,616 B | `c62bbf0ae8bd45a4a43e541053e06f35` |

> 只需要能跑的 jar：下 `-1.0.15.jar` 那五个，按服务端的 MC 版本选一个。`-sources.jar` 是给开发者的源码包，可不下。

### ⚠️ 升级提示 / Upgrade Notes

- 15 个 mixin、六个规则名、配置键与存档格式均未变，**直接替换 jar 即可**，不需要 `/reload` 之外的重置动作。
- `voidWarehouseCraft` 依赖 `voidWarehouse`，默认关闭；配方本从空开始，玩家需要在新版本里正常合成一次才会学到。
- 旧版 `carpet-automending`（包 `carpetaddition`）的仓库数据不兼容，升级相当于换一个新仓库。

* * *

## Carpet Neko Addition 1.0.14

### 🐛 Fixed / 修复

- 修复打开仓库界面后服务端卡顿直至假死：可兑表的上界计算里有两处重复计数，加上 `drain` 的 `units` 空转，单次规划被放大到不可接受(Fixed GUI-open stutter caused by double counting in bound computation plus a spinning `drain` loop.)。

## Carpet Neko Addition 1.0.13

### ✨ Added & Improved / 新增与优化

- 兑换表新增 16 条 `4:1` 原料族（2×2 配方），边数 31 → 47(Added 16 four-to-one ingredient families, 31 → 47 edges.)。
- 新增 `3 骨粉 ← 1 骨头`(Added bone meal ← bone.)。
- `/reload` 后按 `RecipeManager` 复核边是否仍成立(Edges are revalidated against the vanilla recipe manager on reload.)。

## Carpet Neko Addition 1.0.12

### 🐛 Fixed / 修复

- 修复打开仓库后 TPS 下降：上界收窄为「按生产方向可达」的物品，不再把整张表的所有物品都算一遍(Fixed TPS drop after opening the warehouse by narrowing bounds to production-reachable items.)。

## Carpet Neko Addition 1.0.11

### ✨ Added & Improved / 新增与优化

- 支持多原料配方（`5 海晶碎片 + 4 海晶棱镜 → 海晶灯`）(Added multi-ingredient recipes.)。

## Carpet Neko Addition 1.0.10

### ✨ Added & Improved / 新增与优化

- N 合 1 兑换泛化，加入海晶石族（`4:1`）(Generalized N-to-1 exchange; added the prismarine family.)。

## Carpet Neko Addition 1.0.9

### 🐛 Fixed / 修复

- 拆上级只拆「用得完的整块」，不再多拆再找零(Only whole blocks that are fully consumed get broken down.)。
- `planMax` 不再二分，数量改为由库存直接推导(The plan maximum is derived from stock instead of bisection.)。

## Carpet Neko Addition 1.0.8

### 🐛 Fixed / 修复

- 可兑数量改由本族库存推导，不再使用凭空常数(The offered amount is derived from family stock instead of a hard-coded constant.)。
- 操作行的「右键：兑换 64 个」同步修正；鼠标提示单行化(Tooltip cleanup.)。

## Carpet Neko Addition 1.0.7

### 🐛 Fixed / 修复

- 潜影盒模式下可兑条目只给一组，鼠标提示写明单价(Shulker mode only offers one stack; the tooltip states the unit price.)。

## Carpet Neko Addition 1.0.6

### ✨ Added & Improved / 新增与优化

- 新增规则 `voidWarehouseExchange`（虚空仓库 9 合 1 兑换）：成品缺货时改用 N 合 1 配方的原料顶替，也可拆上级找零；每次成功兑换都满足 `Σ(数量 × 价值) == 需要数 × 目标价值`，绝不刷物(Added `voidWarehouseExchange` with a conservation invariant enforced on every trade.)。

## Carpet Neko Addition 1.0.5

### ✨ Added & Improved / 新增与优化

- 新增只读查询通道 `carpet_neko_addition:printer_query`：`litematica-printer` 的材料列表「已有数量」= 背包 + 虚空仓库(The projector material list now counts warehouse stock.)。

## Carpet Neko Addition 1.0.4

### 🐛 Fixed / 修复

- 出料漏斗速率对齐入库漏斗（整格搬 + 清冷却）(Output hopper throughput aligned with the input hopper.)。

## Carpet Neko Addition 1.0.3

### ✨ Added & Improved / 新增与优化

- 新增规则 `concretePowderToGlass`：16 种混凝土粉末在普通熔炉里烧成对应颜色的染色玻璃（0.1 经验 / 200 tick）(Added `concretePowderToGlass`.)。
- 染色潜影盒入库拆包时归还对应染料(Dyed shulker boxes return their dye when unpacked.)。
- 聊天提示统一为三段式配色，前缀固定 `[虚空仓库]`(Unified chat prefix and colours.)。

### 🐛 Fixed / 修复

- 修复补货提示的物品名在服务端取到英文 id（服务器上显示 `dirt`）(Fixed server-side item names rendering as raw ids.)。

## Carpet Neko Addition 1.0.2

### 🐛 Fixed / 修复

- 修掉「玩家清空仓库后仓库永久不可用」：空壳闸门改成一次性判定(The empty-shell gate is now evaluated once.)。
- 入库漏斗对掉落物无上限吸取；修掉 `setItem` 静默吞料(Unbounded item pickup; fixed silent item loss.)。
- 堵死「读到空壳就静默覆盖存档」的数据丢失路径(Closed a silent save-overwrite path that could lose data.)。

## Carpet Neko Addition 1.0.1

### ✨ Added & Improved / 新增与优化

- 虚空仓库：铁砧改名的工作台成为全服共享、跨维度的分页仓库，一种物品一格、数量无上限（`BigInteger`），配漏斗双向自动化与潜影盒模式。
- 自动经验修补 `autoMending`：每秒把一件带经验修补的物品修满，先花经验条、再动等级。

### ✨ 多版本 / Multi-version

- 首度接入 26.2 / 26.1.2 / 26.1.1 三个子工程。

## Carpet Neko Addition 1.0.0

- 首个 26.3 版本。
