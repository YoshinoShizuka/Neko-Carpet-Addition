# 更新日志 / Changelog

> **版本号只代表「规则集变了」**：只有新增一条 carpet 规则才升号；修 bug、改算法、补自检、改文档一律**原地覆盖当前版本**。
> 所以同一个版本号下的 jar 可能比首次发布更新，靠文件名旁的 md5 区分。

## Carpet Neko Addition 1.0.16

### ✨ Added & Improved / 新增与优化

#### 出料漏斗支持不可堆叠物品（标本 + 旁路）

矿车、水桶、**潜影盒**、床、破损工具这类「堆叠上限 1」的物品，以前当出料分类物时**一个都出不来** —— 格子里那 1 个既是「货」又是「分类物」，搬走就没分类、不搬就没货(Hopper output now supports unstackable items such as minecarts, buckets and shulker boxes.)。

- **标本 + 旁路**：格子里那 1 个**永不搬走**（它同时是分类声明和玩家看到的图标），出货改由仓库**直连下游** —— 每 tick 从仓库取 1 个、用原版 `addItem` 直接塞给下游容器，完全不碰漏斗格子(The slot item becomes a specimen that is never moved; goods travel straight from the warehouse to the target container.)。
- **「分类物看不见」顺带解决**：标本永远在格子里，原版漏斗界面直接就能画出这个物品，**不需要额外渲染、网络通道或客户端模组**(The specimen stays visible in the vanilla hopper GUI with no client mod.)。
- **速率 1 个/tick（20 个/秒）**：是「一次只能搬一件」的物品在物理上的上限，仍是原版漏斗（2.5 个/秒）的 8 倍(One item per tick, 8× faster than vanilla hoppers.)。
- 🔴 **给 `addItem` 的必须是副本**：它会就地 shrink 传入的堆叠，而撤销是按「原本要送几个」算的 —— 直接喂原件，下游一拒收就把这 1 个蒸发了(A copy must be passed to `addItem`, otherwise a refused delivery silently loses the item.)。
- **下游不收就整笔撤回仓库**（`Withdrawal#returnUnplaced`）：9 合 1 兑换那条路也一并反向执行，不丢不复制(Refused deliveries are rolled back into the warehouse, exchange-based withdrawals included.)。
- **`pull` 一个字没改**：格子恒为 1/1，`room` 天然是 0(`pull` is untouched.)。
- **顺带把潜影盒纳入出料范围**：它本来就是 `maxStackSize = 1`，之前同样卡在这条限制上(Shulker boxes now work as output classifiers too.)。
- 不新增 Carpet 规则，**版本号不变**，直接替换 jar 即可(No new rule; drop-in replacement.)。
- **验证**：26.3 真服务端 **22 条断言全绿**，含「圆石对照组行为一字不变」与「连做 3 轮每轮结果完全一致」；反证只回滚标本分支，A/B/E 主组翻红 10 条、对照组仍绿（详见 `docs/出料漏斗支持不可堆叠物品.md`）(22 assertions on a real server, plus a roll-back counter-experiment where exactly the main group flips red.)。
- **入库端仍不支持**：入库的物品必须真的进仓库、格子必然被搬空 —— 那一侧还没做(Hopper *intake* still cannot use unstackable classifiers.)

#### 方块掉落物直接进虚空仓库（`voidWarehouseBlockDrops`）

玩家**挖掉**的方块，掉落物不进世界、直接进共享仓库(Block drops go straight into the shared warehouse instead of landing on the ground.)。

- **拦的是掉落物的唯一出口**：`ServerLevel#addFreshEntity` 的 HEAD。所有方块掉落物都要经过这一个方法，所以不用逐个方块改掉落逻辑 —— 附魔、时运、精准采集、草方块掉种子、蜜块粘方块，全部照原版算完再入库(Intercepting the single funnel every block drop passes through, so vanilla loot rules stay completely untouched.)。
- **只认玩家挖的**：上下文由 `ServerPlayerGameMode#destroyBlock` 的 HEAD / RETURN 圈出。TNT、苦力怕、活塞拆的、怪物掉落的、玩家丢弃的都不收；经验球不是物品，也不收(Only blocks actually broken by a player are affected.)。
- **入库口径和漏斗完全一致**：空潜影盒整盒占一格、带数据组件的按物品 + 组件归并(Drops merge exactly the way the hopper intake does.)。
- **聊天栏会说一声，而且只说一句**：`[虚空仓库] 挖到 3 个 泥土（仓库共 128 个）、12 个 圆石（仓库共 64 个）` —— 与补货提示同一套配色（灰正文 + 亮绿数字）。除了「挖到几个」，还报**收完之后仓库里一共有多少**：数量在拼提示那一刻现读（口径 = 物品 + 数据组件，正好对上仓库里的那一格），所以连挖几种时报的都是发消息那一刻的真值。同一种物品合并报数、一条最多列 4 种（再多折成「N 种共 M 个」）、同一玩家**每秒最多一条**，连挖不会刷屏；非空潜影盒标「（已拆开）」且**不报库存**——它入库时被拆成了内容物 + 壳/箱，盒子本身没进仓库(A chat summary in the same style as the restock notice, including how many of that item the warehouse now holds, coalesced per player to at most one line per second.)。
- **绝不吞物品**：仓库没开、存档读不出、`insert` 返回 false、任何异常 —— 每一条失败路径都照原样掉在地上，不存在「东西凭空没了」(Never swallows items: every failure path falls back to vanilla behaviour.)。
- 🔴 **上下文泄漏兜底**：HEAD / RETURN 不是 `try/finally`。万一 `destroyBlock` 抛了异常，RETURN 那份不会执行、上下文会永久挂在 static 上，之后**世界里每一个物品实体都会被吸进仓库**。所以 `begin` 记下当次服务端 tick，`collect` 只认**同一 tick**，最坏影响被压到「本次 tick 的剩余部分」(Tick-stamped context stops a leaked context from eating unrelated drops.)。
- 默认 `false`，**依赖** `voidWarehouse`：没有仓库就什么都不做(Disabled by default and requires `voidWarehouse`.)。
- 规则名**故意**不同于上游 [Carpet-Org-Addition](https://github.com/fcsailboat/Carpet-Org-Addition) 的 `blockDropsDirectlyEnterInventory`：`SettingsManager#addCarpetRule` 遇重名直接抛异常，同名意味着两个附加包同装就起不来(Named differently from the upstream rule to avoid a carpet rule-name clash.)。

#### 打印机余料退回虚空仓库（`printer_return` 通道）

打印机从仓库补到的是**一整组**材料，用不完的一直占着格子 —— 投影材料超过三十种时背包会被这些零头塞满，之后就再也装不下新材料，只能卡在缺料上(Printer leftovers now go back to the warehouse, so a schematic with dozens of material types no longer fills the inventory with partly used stacks.)。

- **方向与取货对称**：`printer_return`（C2S）/ `printer_return_result`（S2C），一次往返退一整批槽位，不再一格一个包(Symmetric with restocking, and one round trip returns a whole batch of slots.)。
- 🔴 **只收槽位号，不收物品**：客户端说「第 12 格不要了」，服务端**自己去读**第 12 格，读到什么就把什么入库。客户端从头到尾没有机会描述物品内容，所以一条被改过的包最多只能「把玩家自己的东西挪进玩家自己的仓库」，**不可能凭空刷物资** —— 这条能力与漏斗、拆潜影盒完全同一量级(The server reads its own inventory slot instead of trusting client-supplied stacks, so this channel cannot create items.)。
- 🔴 **潜影盒整类挡掉**：`insert` 遇到非空盒子会拆成「壳 + 箱子 + 内容物」。放在自动返还路径上就等于「发一个槽位号就把玩家的盒子不可逆地拆了」，所以这里与取货的拒绝清单保持同一口径(Shulker boxes are refused outright: inserting one would unpack it irreversibly.)。
- **不需要回滚**：`insert` 收下时清零传入堆叠、拒收时原样不动，所以每一格只能按「先入库、拿到 `true` 才清空槽位」走，不存在「仓库收了、背包还留着」的中间态(No rollback path is needed: insert either takes the stack or leaves it untouched.)。
- **限流与取货分开记账**：返还紧跟着取货发生，共用一张 50ms 表会让两件事互相拖后腿(Separate throttle tables per direction.)。
- **明细走独立通道**：`printer_return_result` 仍然只回「状态码 + 实收总量」，逐物品明细另开一条 `printer_return_detail` —— 每个物品的**件数**与**入库做完之后的仓库剩余量**（`itemIds` / `counts` / `remainings` 三份平行清单，最多 36 条）。剩余量只有服务端知道 —— 客户端看不到仓库，靠背包件数推算会在玩家手动存取之后立刻错掉(Per-item counts and the post-insert warehouse stock travel on their own channel, because only the server can know the stock.)。
- **退完在聊天栏说一句**：`[虚空仓库] 余料已退回 64 个 泥土（仓库共 128 个）、32 个 圆石（仓库共 256 个）` —— 与补货/收料提示同一套配色。只报成功的那一批（`RULE_OFF`/`THROTTLED`/`NOTHING` 不发，那些情况玩家没有「东西去哪了」的疑问）；超过 4 种折成「N 种共 M 个」；剩余量取不到时只丢括号(A chat notice per successful batch, showing what went back and what the warehouse now holds.)。
- 🔴 **明细为什么单独一条通道**：把清单**追加**到 `printer_return_result` 末尾最省事，但那样**新客户端配旧服务端会解码失败**（旧服务端只发三个数，新客户端读清单读到一半没字节）。拆开之后两边各自 `canSend` 判定，四种新旧组合里**没有一种会解错包**：新+新有完整提示，新服务端+旧客户端只发回执，旧服务端+新客户端只销账不报提示(Kept as a separate payload so any client/server version pairing stays wire-compatible.)。
- 🔴 **两端要逐行一致**：服务端原件与客户端镜像的 `CODEC`、状态码、`record` 字段顺序必须一致；判据是脚本 `python .workbuddy/tools/check_return_protocol_parity.py --printer ../litematica-printer`，不是人眼比对(The mirror classes must stay byte-equivalent; parity is checked by a script.)。
- 复用 `voidWarehouse` + `voidWarehouseRestock` 两条规则，**没有新增 Carpet 规则**，所以版本号不变；客户端那侧另有开关 `voidWarehouseReturn`（默认开）(Reuses the two existing rules; a client-side toggle can disable it entirely.)。
- 客户端策略（详见 `docs/打印机余料退回虚空仓库.md`）：收工（工作总开关关掉）时整批退回；打印中空槽 ≤ 4 退到回到 8；空槽 ≤ 1 时连「正在使用」也不再保护。只退**仓库给的那部分**，玩家自己背包里的材料一律不动(The client only returns what the warehouse itself delivered, and skips the material currently being requested.)。

#### 虚空仓库搜索框（`search_hello` / `search_catalog` / `search_apply` 三通道，配套独立客户端模组 `voidwarehouse-search`）

仓库的种类一多（全物品存档 1657 种 ≈ 37 页），靠翻页找东西就是大海捞针 —— 现在装上配套客户端模组后，界面上方多出一个搜索框，**输入中文、拼音（全拼 / 首字母）或注册名**都行：`铁锭`、`tieding`、`td`、`iron_ingot` 都能筛出铁锭那一格(An optional client mod adds a search box on top of the warehouse GUI; Chinese, full pinyin, initials and registry names all match.)。

- 🔴 **服务端 GUI 一个字节没变**：还是那个纯原版 6 行大箱子 —— 布局、导航行、点击语义、同步机制全部不动，搜索只是把「第 n 页装谁」换成了「匹配集合里的第 n 页装谁」，与创造模式物品栏搜索同构。纯原版客户端看不到任何变化(The vanilla chest GUI is untouched; filtering only changes which entries fill each page.)。
- **中文匹配只能在客户端做**：专用服务器上没有 `zh_cn.json`，服务端把「铁锭」翻译成中文这件事在原理上不成立。所以方向是**目录下发**：`search_hello`（C2S，空包，打开界面时探测）→ `search_catalog`（S2C，仓库现有物品 id 目录，全物品存档 ≈ 30 KB）→ 客户端用本地语言文件 + jpinyin 建索引筛出匹配集合 → `search_apply`（C2S）回传，服务端只按 `Item` 身份做集合求交(The catalog travels to the client, which owns the language files and does the pinyin matching; the server only intersects by item identity.)。
- **过滤是会话级状态**：存进每个玩家自己的 `VoidWarehousePageContainer`（内存），**不持久化** —— 关掉界面过滤自然消失，重开是干净的一页；页码回第一页。可兑虚拟条目同样参与过滤与目录(The filter lives per open menu and is never persisted; exchange offers participate too.)。
- 🔴 **客户端吞键是安全底线**：26.3 的 `EditBox` 只消费编辑键，数字键会穿透到容器的 `SWAP` 逻辑 —— 聚焦搜索框时按 `1-9` 会把快捷栏整组存进仓库。客户端模组在 `keyPressed` 的 HEAD 把聚焦期间的全部按键拦下（javap -c 实证后逐条核对），ESC = 退出搜索框，再按一次才关界面(While the box is focused the client swallows every key press at HEAD; otherwise digits would trigger the hotbar-swap-into-warehouse path.)。
- 🔴 **四组合版本兼容**：三条都是独立通道，服务端 `canSend` 逐个判定 —— 旧服务端 + 新客户端 = 搜索框自动隐藏；新服务端 + 无客户端 = 没人发包，零开销；新旧客户端 + 新服务端各走各的(Any version pairing degrades gracefully; vanilla clients are unaffected.)。
- **限流三张分表**：hello 100ms（打开界面 + 点框刷新）、apply 50ms（紧跟输入）、目录体量上限 4096 种；apply 里的未知 id 静默丢弃，发不存在的物品什么都改变不了(Per-channel throttle tables, catalog capped at 4096 kinds, unknown ids ignored.)。
- **不新增 Carpet 规则**：沿用 `voidWarehouse` 开关做闸门，规则集、配置键与存档格式都没变，直接替换 jar 即可；配套客户端模组 `voidwarehouse-search 0.1.0`（首版只出 26.3，与 HUB 客户端版本对齐）需与服务端 jar 成对更新(No new rule; the client mod must be updated in step with the server jar.)。
- 🔴 **两个 jar 的 payload 类逐字节镜像**：`neko.voidwarehouse.search` 三个 payload 在服务端与客户端模组各一份（同 FQCN 双份、Fabric knot 只加载一份），内容不一致 = 一边编的包另一边解不开 —— 本次以 md5 逐字节比对通过(The three payload classes are byte-identical mirrors in both jars.)。
- 客户端侧细节（吞键位置、渲染接线、尾随防抖）见客户端模组仓库；本侧改动集中在 `neko/voidwarehouse/search/`（5 个文件）与 `VoidWarehousePageContainer` 的过滤视图(Server-side changes are contained in the search package plus a filter view in the page container.)。

#### 完整英文适配（双语版，方案 B）

英文玩家从「完全无法使用」变成「全套可用」：此前触发名只认「虚空仓库」精确匹配，英文玩家连界面都打不开(Complete English localisation: English players can now trigger, read and use everything.)。

- **语言检测**：客户端登录时上报的 `ClientInformation.language()`（`zh_cn` / `en_us`…）随时可从 `ServerPlayer` 读到，玩家改语言重进后自动生效。判定口径 = 以 `en` 开头即英语；拿不到真实上报的场合（自检假玩家）一律按中文兜底(Language detection reads the locale the client reports at login; anything starting with `en` gets English.)。
- **触发名接受英文别名**：工作台改名为 "void warehouse"（忽略大小写、容忍首尾空白）也能触发；中文名「虚空仓库」维持原判 —— 精确、区分大小写、不做 trim。规则名与判定逻辑都不变(The trigger also accepts "void warehouse", case-insensitive and trimmed; the Chinese name keeps its exact-match rule.)。
- **GUI 标题跟随触发名**：玩家把工作台改成什么名字，打开的界面就叫什么；按-数量取货结束重开界面时按玩家语言给对应标题(The GUI title follows the name the player typed on the anvil.)。
- **全部玩家可见文案双语化**：物品格 tooltip（数量 / 操作行 / 盒组换算）、「可兑」条目的账单与操作行、导航行（翻页 / 潜影盒模式 / 数量排序 / 页码统计）、空位占位符、界面与取货的全部聊天提示、挖到提示、打印机补货提示 —— 英文玩家看到完整的英文界面与 `[Void Warehouse]` 前缀，中文玩家看到的和从前逐字节相同(All player-visible text — tooltips, nav icons, placeholders, chat notices, printer restock notices — now has an English set with a `[Void Warehouse]` prefix; Chinese output is byte-identical to before.)。
- 🔴 **展示缓存按语言分键**：仓库格的 tooltip 缓存在共享的 `Entry` 上，多玩家共享 —— 缓存按语言各存一份（中 / 英两份），数量一变四份全废；物品名保持可翻译组件原样，永远由客户端本地化(Per-entry display caches are keyed by language, so two players sharing one warehouse each get their own locale.)。
- **服务器日志与启动自检说明保持中文**：那是给服务器管理员看的，不跟玩家语言走(Server logs stay Chinese — they are for admins, not players.)。
- **不新增 Carpet 规则**：规则集、配置键与存档格式都没变，直接替换 jar 即可(No new rule, no config or data-format change; just swap the jar.)。

### 🐛 Fixed / 修复

#### 盒模式取「潜影盒」那一格会造出「盒子套盒子」，送回仓库后越刷越多（`voidWarehouse`）

盒模式打开后点仓库里的**潜影盒**格子，交出的是一个**装着 N 个潜影盒的潜影盒**；把它送回仓库，拆出来是 `N 个空盒 + 2 个潜影壳 + 1 个箱子` —— 可这一格原本只有 N 个盒子。来回一遍就凭空多出价值一整盒的材料，反复来回可无限增长(In box mode, taking a shulker-box cell produced a box full of shulker boxes; sending it back yielded a free box worth of materials on every round trip.)。

- 🔴 **根因一：`pack` 不认「不能装盒的东西」**。潜影盒在 `VoidWarehouseShulker#pack` 眼里只是一件普通物品，而它是直接 `set(CONTAINER, ...)` 造盒子，**绕过了**原版 `ShulkerBoxBlock#canFitInsideContainerItems` 那道「潜影盒不能装进潜影盒」的检查。取 5 个空盒就交出一个装着 5 个空盒的盒子（实收 6 个）(Pack bypassed the vanilla no-nesting check and built an illegal box-in-box.)。
- 🔴 **根因二：外盒的材料账与「取出」同源，取货先把它扣空**。外盒要 1 个空盒（`planBoxMaterials` 空盒优先），可当目标格**就是那个空盒格**时，`take()` 会先把这一格取空、连格子一起移除，随后 `consumeBoxMaterials` 的 `takeEmptyBoxes` 只能扣到 0 —— 返回值没人看，注释还写着「少扣了说明被并发抢走了，不必在意」。于是外盒一分钱不付(The outer box's material came from the very cell being taken, so once take() emptied that cell the charge silently became zero.)。
- **修法：盒子本来就是盒子，不再装一道盒**。新增 `VoidWarehouseShulker#isBoxable`，两条取货口（界面盒模式 `withdrawShulkerBox`、聊天按数量取货 `execute`）遇到目标格本身是潜影盒时改走「散盒取出」—— 不 `pack`、不扣盒材料，与普通模式同一口径（每次 1 个）(A new isBoxable gate makes both withdraw paths hand out loose boxes instead of packing them.)。
- **材料账不再「少扣就算」**：`consumeBoxMaterials` 把少扣的空盒按原版配方折算成 `2 潜影壳 + 1 箱子` 补扣，真补不上才留 WARN —— 同类漏账将来即使从别的路径冒出来，也只会表现为「玩家多花壳箱」，不会再凭空多出盒子(The material charge now tops up any shortfall at crafting value, so this class of leak cannot reappear as free boxes.)。
- **两条路都修，五版本同步**：`VoidWarehouseShulker` / `VoidWarehouseMenu` / `VoidWarehouseData` / `VoidWarehouseWithdraw` 各打一处补丁（26.2 的潜影盒类与 26.3 逐字节一致，整文件复制）(Both paths fixed in all five versions.)。
- 未新增 Carpet 规则、未改存档格式与网络协议：直接替换 jar 即可(No new rule, no save-format or protocol change; just swap the jar.)。

### 🧪 Testing / 测试

- 真服务端系统验收 **209 条断言**全绿(209 assertions on a real dedicated server, all green.)，其中双语组 **17 条**：`zh_cn` / `en_us` 的语言检测、触发别名五连（中文精确 / 英文忽略大小写 / 首尾空白 / 中文名带空格不触发 / 未改名不触发）、GUI 标题跟随触发名、同一份仓库在两种语言菜单下的导航行与 tooltip（`上一页` ↔ `Prev Page`、`数量：` ↔ `Count:`）、取货与挖到提示的两套前缀与措辞（`[虚空仓库] 已取出` ↔ `[Void Warehouse] Withdrew`）。⚠️ 自检假玩家的 `ClientInformation` 从 `createDefault()`（`en_us`）改为显式 `zh_cn` —— 不改的话语言检测会把全部自检玩家判成英文、17 条中文文案断言当场翻车(Test bots now explicitly report `zh_cn`; the default `createDefault()` is `en_us` and would flip every chat assertion.)。
- 聊天提示单独成组（G 组 17 条）：累计关系（同物品相加 / 不同物品并列 / 超 4 种折算）、限流判据、拼出来的文本本身（前缀、顿号、数量），以及**库存数字是否等于 `amountExact`**、潜影盒与折算情形是否**故意不报**库存、`null` 数据时是否照发。文本断言一律避开物品名 —— 专用服务器上 `getHoverName()` 按 `en_us` 解析，咬名字等于咬环境(A dedicated group pins the chat coalescing rules and the warehouse-count reporting, and avoids asserting on localized item names.)。
- 用真 `ServerPlayer` **真挖方块**，同时看三件事：仓库 +1、世界里**没有**多出物品实体、玩家背包也没多 —— 少看任何一项都抓不到「既入库又掉地上」这种复制 bug(The test digs for real and checks all three outcomes, which is the only way to catch dupe bugs.)。
- **反证**：把两条 mixin 从 `mixins.json` 里临时摘掉，同一套自检 `failures=7`，其中「世界里没有多出物品实体」打出「dirt 实体 2 个，仓库 0」—— 证明这些断言咬的是真代码(Negative control: removing the mixins makes 7 assertions fail.).
- 返还回执真服务端自检 **23 条断言**，**五个版本**全绿(23 assertions, green on all five targets.)：单槽与多槽的「仓库 +N / 槽位清空 / 件数」、潜影盒**一格不动**、越界与空槽逐个跳过、规则关闭时 `RULE_OFF` 且仓库零变化、空清单 `BAD_REQUEST`。三条新口径各有专组：
  - **同一种跨两格**（泥土 64 + 泥土 32）必须合成一条明细、件数 96，且剩余量是 **96 而不是 64** —— 「边退边读」会报中间值，这是这个特性最容易错的地方(Reading the stock while inserting would report a mid-batch value; a dedicated case pins it to the final 96.)；
  - **编解码往返**：明细包（`nonce` + 三份清单）编码再解码必须逐项相同（含 `1,234,567,890` 这种大数，确认 `VAR_LONG` 没被截断）；回执包也验往返，并**用反射钉住它只有 3 个字段** —— 明细一旦被人塞回去，新客户端配旧服务端就会解错包(Codec round trip for both payloads, plus a reflection guard that the receipt stays three fields.)；
  - **剩余量与仓库自己的账一致**：拿 `amountExact` 当外部标准比对回执里的数，避免「客户端和提示互相对得上、却都和仓库对不上」(The reported stock is compared against the warehouse's own ledger as an external oracle.)。
- 客户端提示另有 **8 条单测**（`VoidWarehouseReturnNoticeTest`）：单种/多种的分隔、千分位、超过 4 种折算、剩余量缺失（`-1`）时只丢括号。文本断言一律用解析不出来的 id 当物品名，绕开注册表 —— JUnit 里没有客户端语言表(The client-side wording is pinned by 8 unit tests that avoid the item registry.)。
- 自检用的是**真 `ServerPlayer` + 真仓库存档**，测试前后把塞进仓库的量原样取回，开发存档不会被动过(The check uses a real player and real warehouse data, and restores the warehouse afterwards.)。
- 盒模式刷盒子事故的**复现 + 回归自检 25 条**（`BoxNestingSelfCheck`，真 26.3 服务端）：修复前 `checks=25 failures=4` —— 打出「交出盒子 1 个，里面套着潜影盒 5 个；仓库空盒 5→0」「送回后空盒=5，壳=2，箱=1」「来回 3 轮后壳=8，箱=4」；修复后 `failures=0`，来回 3 轮空盒稳定在 5 个、壳箱一个不多(The dupe is reproduced and pinned by 25 assertions: 4 failures before the fix, 0 after, with the box count stable across round trips.)。
- 该组自带**对照组**：盒模式点**圆石**格 → 交出装满 1728 个的盒子 → 送回 1000 个余料，断言「圆石 3456→1728→2728、盒子拆成 2 壳 1 箱」—— 修复前后都是绿的，证明红只出在「目标格本身是潜影盒」这一条路，不是断言写歪(A control group on a normal material cell stays green both before and after, proving the failure is specific to the shulker-box cell.)。
- **反证**：只把聊天取货口那一处改回旧代码重跑，A/B 两组仍是绿的，C 组 3 条当场翻红（`套着 27 个盒子`、背包只有 4 个盒堆、送回后多出 2 壳 1 箱）—— 证明断言咬的是两条路各自的代码(Negative control: reverting only the chat path flips exactly its three assertions.)
- 搜索功能的验证：服务端 **26.3 真机冒烟**（`Done` 正常启动、与 TIS / Org / FGA 等其它 carpet 附加包同装无冲突、零 ERROR）+ 三个 payload 与客户端模组的**逐字节 parity**；拼音匹配 **11 条单测**在客户端模组仓库（`SearchIndexTest`：中文名 / 全拼 / 首字母 / 注册名 / 空查询 = 清除过滤 / 未知 id 静默跳过，`铁锭=td`、`铁块=tk` 这类词典事实直接咬死）。客户端吞键与渲染接线需真机客户端验证(Server smoke on 26.3, byte-level payload parity, and 11 client-side unit tests; key-swallowing needs in-game verification.)。

### 📦 Builds / 构建产物

| MC 版本 | 文件 | 体积 | md5 |
| --- | --- | --- | --- |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.16.jar` | 392,177 B | `42e5c303250e7f25e90fa59ccde6c8de` |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.16-sources.jar` | 412,363 B | `7f2df66b52d4c3172670622ef86b2323` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.16.jar` | 381,793 B | `d1d80d4c99d3d6a6b1ff71acac1b58cb` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.16-sources.jar` | 399,506 B | `b2f762724db979000b856448aff5f34b` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.16.jar` | 385,062 B | `857ed151b3f6e0853439f31a3af27e69` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.16-sources.jar` | 400,523 B | `f8956da39b575bb1e17587ca7aa90638` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.16.jar` | 385,064 B | `f532e6d57fec8e49da170fafae3c2680` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.16-sources.jar` | 400,522 B | `06a1850a47b95a83d5e78358c6c8f40e` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.16.jar` | 383,310 B | `b1a8e2fa2ca4c7605b2f270063534e96` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.16-sources.jar` | 400,690 B | `aa8f7f00c8a03db7e5f201bd6b7cfcf1` |

> 只需要能跑的 jar：下 `-1.0.16.jar` 那五个，按服务端的 MC 版本选一个。`-sources.jar` 是给开发者的源码包，可不下。

### ⚠️ 升级提示 / Upgrade Notes

- mixin 从 13 条增到 15 条（新增 `ServerLevelAddEntityMixin`、`ServerPlayerGameModeDestroyMixin`），规则名、配置键与存档格式均未变，**直接替换 jar 即可**(Two new mixins; no config or data-format change, just swap the jar.)。
- 新规则默认关闭：`/carpet voidWarehouseBlockDrops true`，且 `voidWarehouse` 也要开着。
- **同版本号原地更新**：这条规则的聊天提示在 1.0.16 首次发布之后改过两次（先加提示，再去掉「已直接入库」、改成报仓库现有数量），加上盒模式刷盒子修复、以及这次的不可堆叠物出料，1.0.16 的 jar 已经变过几次 —— 以文件名旁的 md5 为准（本表已刷新）(This release was updated in place several times; compare the md5 next to the file name.)。
- **盒模式取潜影盒的行为变了**：以前（错得）交出一个「装着 N 个潜影盒的潜影盒」，现在直接交出 1 个潜影盒（与普通模式一致），且不消耗额外的空盒 / 壳 / 箱。已经刷出来的嵌套盒、已经攒多的壳与箱不会自动回收(In box mode, taking a shulker-box cell now hands out one plain box instead of a box full of boxes.)。
- 反方向提醒：开这条规则之后**挖掘产出不再落在地上**。靠「挖掉 → 掉落物被水流冲走」的生电收集结构会失效，开之前先确认你的收集系统不依赖世界里的掉落物。
- 新增两条通道 `printer_return` / `printer_return_result`（服务端注册；客户端幂等补位）。**没有新增 Carpet 规则**，规则集、配置键与存档格式都没变，直接替换 jar 即可；老版本打印机不认识这两条通道也不会受影响（服务端发送前会 `canSend`）(Two new channels, no new rule, no config or data-format change.)。
- 新增三条搜索通道 `search_hello` / `search_catalog` / `search_apply`。**没有新增 Carpet 规则**，规则集、配置键与存档格式都没变，直接替换 jar 即可；不装客户端模组时这三条通道没有任何人使用(Three new search channels, no new rule; harmless without the client mod.)。

* * *

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

- 13 个 mixin、六个规则名、配置键与存档格式均未变，**直接替换 jar 即可**，不需要 `/reload` 之外的重置动作。
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
