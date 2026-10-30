# Carpet Neko Addition

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/actions/workflows/build.yml/badge.svg)](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-26.3%20%7C%2026.2%20%7C%2026.1.2%20%7C%2026.1.1%20%7C%201.21.11-2ea043)
![Side](https://img.shields.io/badge/side-%E7%BA%AF%E6%9C%8D%E5%8A%A1%E7%AB%AF-informational)

纯服务端 Carpet 扩展，面向技术生存与生电场景。共七条规则：**自动经验修补**、**虚空仓库**（全服共享、跨维度的分页仓库，配漏斗双向自动化）、**虚空仓库补货**、**虚空仓库 9 合 1 兑换**、**虚空仓库配方扣料**、**方块掉落物直接进虚空仓库**、**混凝土粉末烧制染色玻璃**。全部默认关闭，用 `/carpet` 逐条开启。客户端可以是原版。

## 规则

| 规则 | 默认 | 一句话 |
| --- | --- | --- |
| `autoMending` | `false` | 每秒把一件带经验修补的物品修满，先花经验条、再动等级 |
| `voidWarehouse` | `false` | 铁砧改名的工作台成为全服共享仓库，一种物品一格、数量无上限 |
| `voidWarehouseRestock` | `false` | 允许 litematica-printer 缺料时直接从仓库领一组（**依赖** `voidWarehouse`） |
| `voidWarehouseExchange` | `false` | 成品缺货时改用 N 合 1 配方的原料顶替，也可拆上级找零（**依赖** `voidWarehouse`） |
| `voidWarehouseCraft` | `false` | 成品缺货时按玩家亲手合成过的配方扣原料，直接把成品取出来（**依赖** `voidWarehouse`） |
| `voidWarehouseBlockDrops` | `false` | 玩家挖掉的方块，掉落物不进世界、直接进仓库（**依赖** `voidWarehouse`） |
| `concretePowderToGlass` | `false` | 普通熔炉把 16 种混凝土粉末烧成对应颜色的染色玻璃 |

分类为 `neko` / `survival`，用法 `/carpet <规则> true`，`/carpet list` 查看全部规则。

### 虚空仓库

- 铁砧把**工作台**重命名为 `虚空仓库`（精确匹配、区分大小写），**手持右键**任意目标即可开界面
- 要把它当终端摆到世界里，**潜行 + 右键方块**；之后右键那个方块就能开仓库，不用再拿在手上
- **一种物品 = 一格，数量无上限**（内部 `BigInteger`，真任意精度）；页数随物品种类增长，也没有上限
- 全服唯一一份、跨维度共享（存在主世界 `SavedData`），重启不丢；排列按创造模式物品栏顺序，与存取历史无关
- 左键取 1 个 / 右键取一组，**都直接进背包**；格子里只画图标，真实数量看鼠标提示
- 导航行：`47` 潜影盒模式（取出给装满的盒、存入把盒拆开）、`48` / `50` 翻页、`49` 页码、`51` 数量排序
- **漏斗双向自动化**：朝终端摆放的漏斗 = 入库（无速率上限，玩家手动放过的物品成为白名单，分类物每格留 1 个）；**终端压在漏斗正上方** = 出料（按漏斗 5 格里的分类物发货，分类物永不外流）
- **入库筛选**：只要非空就收。带数据组件的物品（受损工具、附魔、改名、药水、成书）**不拒收**，各占一格；要不要放行某个物品由玩家在漏斗里自己过滤（分类物白名单）

### 虚空仓库 9 合 1 兑换

- 只在**成品本身没货**时才兑换：仓库里有铁锭就老实扣铁锭，一个原料都不动
- 扣减优先级：目标物品 → 族内更小的单位（下级合成）→ 族内更大的单位（拆整块，需要时多拆一块并找零退库）→ 单向边的上游
- **绝不刷物**：每次成功兑换都满足 `Σ(数量 × 价值) == 需要数 × 目标价值`，自检逐笔校验（含随机 fuzz）
- 比例是每条边自己的属性，引擎里没有写死 9：26.3 共 47 条边（含 4:1 的 2×2 配方与 3:1 的骨粉 ← 骨头）
- 只走**原版真实存在的配方**：启动时用 `RecipeManager` 反查复核，两个物品缺一即丢边、单边缺失则降级；`/reload` 重算

### 虚空仓库配方扣料

- **先学会，才能用**：在合成台（含背包 2×2）里成功合成一次，这次用到的原料与产物就被记进**配方本**
- 之后取这件物品时若**成品没货**，直接按记下的配方扣仓库里的原料，把成品给你 —— 等价于替你做掉这一步合成
- **原料不够会往下钻**（最多 4 层）：取活塞要 3 块木板、木板没货但仓库里有原木，就扣原木做木板 —— 只在**已学过的配方**之间钻，不会凭空出现你没做过的东西
- 配方本**全服共用一份**（和仓库一样），配方随存档持久化，重启不丢
- 和 9 合 1 的分工：9 合 1 只会原版那几十条 9:1 / 4:1 的换算；本规则能覆盖**任意**配方（活塞、漏斗、侦测器……）
- 优先级：真实库存 → 9 合 1 可兑 → 配方可合成。同一件物品**不会同时出现两格**
- 界面里标「（可合成）」，鼠标提示写清「可合成 N 个，用 圆石 64 个 ＋ …」；一次动作能拿多少与真账同源，不会出现「说 64 实给 63」
- 特殊配方（染色、烟火、地图缩放、成书复制等产物带数据组件的）**不记录** —— 学会了也只会给一件白板物品

### 方块掉落物直接进虚空仓库

- 玩家**挖掉**的方块，掉落物不进世界，直接进仓库 —— 不占背包格子，也不受背包满不满影响
- 掉落表、附魔、时运、精准采集**全部照原版算**，算出几个就收几个（拦的是掉落物的唯一出口 `ServerLevel#addFreshEntity`，不是某个方块）
- 入库口径与漏斗完全一致：空潜影盒整盒占一格、带数据组件的按物品 + 组件归并
- **聊天栏会说一声**：`[虚空仓库] 挖到 3 个 泥土、12 个 圆石，已直接入库` —— 与补货提示同一套配色（灰正文 + 亮绿数字）。同一种物品合并报数；一条最多列 4 种、再多就折成「N 种共 M 个」；同一玩家**每秒最多一条**，连挖不会刷屏
- **只认玩家挖的**：TNT、苦力怕、活塞拆的、怪物掉落的、玩家丢的都照原样进世界；经验球不是物品，也不收
- **绝不吞物品**：仓库没开、存档读不出、入库失败、任何异常 —— 一律照原样掉在地上
- 关着的时候连上下文都不立，`addFreshEntity` 的额外代价只剩一次 `null` 读

### 虚空仓库补货

给 [litematica-printer](https://github.com/Yur1Ca/litematica-printer) 用的通道（`carpet_neko_addition:printer_query`）：建造缺料时直接从共享仓库领一组。

**默认关是有意的** —— 开着就等于「任何装了对应打印机的玩家都能动用全服共享库存」，这个授权该由服主主动打开。

### 混凝土粉末烧制染色玻璃

16 种混凝土粉末在**普通熔炉**里烧成对应颜色的染色玻璃，数值与原版「沙子 → 玻璃」一致（0.1 经验 / 200 tick）。高炉与烟熏炉不在覆盖范围内。

规则名**故意**与 Carpet DDS Addition 的 `smeltConcretePowderToGlass` 不同：Carpet 的 `SettingsManager#addCarpetRule` 遇重名会直接抛异常，沿用原名意味着「两个模组同装 = 服务端起不来」。

## 文档

- [更新日志](CHANGELOG.md)

细节与踩坑记录都在 `docs/`：
- [多版本架构说明](docs/多版本架构说明.md)
- [虚空仓库 9 合 1 兑换](docs/%E8%99%9A%E7%A9%BA%E4%BB%93%E5%BA%93%209%20%E5%90%88%201%20%E5%85%91%E6%8D%A2.md)
- [虚空仓库配方扣料](docs/%E8%99%9A%E7%A9%BA%E4%BB%93%E5%BA%93%E9%85%8D%E6%96%B9%E6%89%A3%E6%96%99.md)
- [方块掉落物直接进虚空仓库](docs/%E6%96%B9%E5%9D%97%E6%8E%89%E8%90%BD%E7%89%A9%E7%9B%B4%E6%8E%A5%E8%BF%9B%E8%99%9A%E7%A9%BA%E4%BB%93%E5%BA%93.md)
- [混凝土粉末烧制染色玻璃规则移植](docs/%E6%B7%B7%E5%87%9D%E5%9C%9F%E7%B2%89%E6%9C%AB%E7%83%A7%E5%88%B6%E6%9F%93%E8%89%B2%E7%8E%BB%E7%92%83%E8%A7%84%E5%88%99%E7%A7%BB%E6%A4%8D.md)
- [投影材料列表显示虚空仓库库存](docs/%E6%8A%95%E5%BD%B1%E6%9D%90%E6%96%99%E5%88%97%E8%A1%A8%E6%98%BE%E7%A4%BA%E8%99%9A%E7%A9%BA%E4%BB%93%E5%BA%93%E5%BA%93%E5%AD%98.md)
- [功能审计与纯服务端可行性评估](docs/%E5%8A%9F%E8%83%BD%E5%AE%A1%E8%AE%A1%E4%B8%8E%E7%BA%AF%E6%9C%8D%E5%8A%A1%E7%AB%AF%E5%8F%AF%E8%A1%8C%E6%80%A7%E8%AF%84%E4%BC%B0.md)

## 安装

1. 服务端装好 **Fabric Loader ≥ 0.16.0**（对应 MC 版本）
2. 把下面三个 jar 放进 `mods/`：
   - **Fabric API**（必需）
   - **Carpet**（版本号需 ≥ 对应的 Minecraft 版本）
   - **本模组**，按 MC 版本选 `carpet-Neko-Addition-mc<版本>-<模组版本>.jar`（从 [Releases](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/releases/latest) 下载）
3. 启动后用 `/carpet <规则> true` 逐条开启
4. 客户端可以原版直连；要用打印机补货，客户端另装 `litematica-printer`，并在服务端开 `voidWarehouseRestock`

> 旧版 `carpet-automending`（包 `carpetaddition`）的仓库数据不兼容，升级相当于换一个新仓库。

## 支持版本

当前构建与发布目标共 5 个：`26.3` · `26.2` · `26.1.2` · `26.1.1` · `1.21.11`

| MC 版本 | 子工程 | 工具链 |
| --- | --- | --- |
| 26.3 / 26.2 / 26.1.2 / 26.1.1 | `modern/<版本>` | 不混淆，`net.fabricmc.fabric-loom`，Java 25 |
| 1.21.11 | `versions/1.21.11` | 混淆，`fabric-loom-remap` + Mojang 官方映射，字节码目标 Java 21 |

共享的纯逻辑（设置、翻译、算法）在 `common/`，各版本以 `implementation project(':common')` 引用。新增一个版本 = 复制一份目录 + 改它的 `gradle.properties` + 在 `settings.gradle` 里 `include` 一行。

## 构建

- **JDK 25**（Minecraft 26.3 的最低 Java SE 25）
- **Gradle ≥ 9.7.0**（Loom 1.18.2 的硬要求；wrapper 已固定为 9.7.0，无需另装）

**逐版本单独构建**，一次只跑一个子工程：

```bat
.\gradlew.bat :modern:26.3:build :modern:26.3:dist
```

产物在 `build/libs/`，`dist` 任务会另复制一份到仓库根的 `dist/`（`1.21.11` 没有 `dist` 任务，从 `build/libs/` 取即可）。开发时可 `.\gradlew.bat runServer` 起一个带 Carpet 的测试服务端。

> 不要一次性 `.\gradlew.bat build`：五个版本会挤进同一个 JVM，TinyRemapper 在 JDK 25 上会崩。CI 用 matrix 逐版本跑，原因相同。

## 许可证

MIT，完整文本见 [LICENSE](LICENSE)。

## 反馈

请通过 [GitHub Issues](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/issues) 反馈。
