# 更新日志 / Changelog

> **版本号只代表「规则集变了」**：只有新增一条 carpet 规则才升号；修 bug、改算法、补自检、改文档一律**原地覆盖当前版本**。
> 所以同一个版本号下的 jar 可能比首次发布更新，靠文件名旁的 md5 区分。

## Carpet Neko Addition 1.0.17

> **这一版正式发布「虚空仓库直接放置」。规则来自 [Yur1Ca](https://github.com/Yur1Ca)（Hana）的 [PR #1](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/pull/1)**，配套客户端是同一位维护的 [litematica-printer](https://github.com/Yur1Ca/litematica-printer)。
> 1.0.16 的发布产物里**没有**这条规则 —— PR #1 在 1.0.16 发布之后才合入，所以这是唯一一次「因为新增一条 Carpet 规则」而升号，符合本文件的版本号约定。
> **This release ships `voidWarehouseDirectPlace`**, contributed by [Yur1Ca](https://github.com/Yur1Ca) (Hana) in [PR #1](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/pull/1), paired with their [litematica-printer](https://github.com/Yur1Ca/litematica-printer). The 1.0.16 artifacts predate that PR, hence the version bump.

### ✨ Added & Improved / 新增与优化

#### 配方存入前的聊天确认（`voidWarehouseCraft`）

合成**不再**默默写进配方本 —— 合成那一刻只发一句问话，玩家点一下才真的记(This rule now asks before recording; nothing is written until the player clicks.)。

- **三个按钮**：`[记住]` 记这一条、`[不要]` 本次登录内不再问这一条、`[总是记住]` 记下来并本次登录内不再问任何配方(Remember this one / skip it / stop asking for the rest of the session.)。
- 🔴 **一条指令都不加**：按钮走原版的「自定义点击」（`ClickEvent.Custom` + `ServerboundCustomClickActionPacket`，1.21.6 起**原版客户端自己就会发**），服务端用 `CustomClickMixin` 接住。所以既不用注册指令，也不需要客户端模组(Vanilla's custom-click action carries the payload; a server-side mixin handles it, so no commands are registered.)。
- **令牌一次一换、只认本人**：令牌是每位玩家每次提问随机生成，玩家身份从连接里取 —— 载荷里没有任何 UUID，伪造一条包只能点自己的待确认(Identity comes from the connection; the payload only holds a random token.)。
- **同一条只问一次**：Shift 连合同一条配方不会刷屏；每人同时最多挂 3 条，超了丢最旧的；60 秒不点自动过期，点过期的那条会明确回一句「已经处理过了」(One pending per recipe, at most three per player, 60-second lifetime; a stale click says so instead of doing nothing.)。
- **不问的四种情况**：已经在配方本里、配方本满了、本局点过 `[总是记住]`、本局对这条点过 `[不要]` —— 这四种直接走老路，文案与节流一个字不变(Four cases fall back to the previous behaviour.)。
- ⚠️ **`[不要]` / `[总是记住]` 只活在本局**：不落盘，重新登录即恢复一条条询问；切换时会在聊天里明写「重新登录后恢复」(Both switches are in-memory only and reset on the next login.)。

#### Ctrl + Alt + 左键：把这一格的物品取出到背包放不下为止

常规模式给**纯散货**，潜影盒模式给**满盒**，都是「能装多少装多少」(Fill the inventory with that slot's item — loose stacks in normal mode, full shulker boxes in box mode.)。

- 🔴 **原版协议里没有修饰键**：容器点击只有 7 种输入（`PICKUP / QUICK_MOVE / SWAP / CLONE / THROW / QUICK_CRAFT / PICKUP_ALL`），一个都带不了 Ctrl / Alt。所以手势由客户端识别，再走一条专用通道 `carpet_neko_addition:fill_request`（载荷只有一个 `int slotId`）(Vanilla container clicks carry no modifiers, so the gesture is recognised client-side and sent over a dedicated channel.)。
- **服务端不信客户端的任何说法**：是哪件物品由服务端从那一格解析、能装多少由服务端按真实背包现算、玩家身份由连接里取。菜单不是 `VoidWarehouseMenu`、`slotId` 落在导航行或背包格、那一格解析不出物品 —— 一律丢弃(The server re-derives item, capacity and identity; malformed requests are dropped.)。
- **三个上限取最小**：背包放得下多少 / 仓库有多少 / 盒模式下材料够做几盒。所以它**不会**走 9 合 1 兑换补齐 —— 「有多少给我多少」，与「按数量取货」明确要 N 个是两回事(Three caps: inventory room, stock on hand, box materials — the exchange path is deliberately not used.)。
- **常规模式一个盒子都不装**：玩家要的是能顺手拿的一片散货，悄悄给成盒子会跟预期对不上(Normal mode never packs anything into boxes.)。
- **盒模式不够一整盒就不给**，并明确报出「一盒 1728，现有 1000」，而不是塞一个不满的盒子(In box mode a partial box is refused, with both numbers spelled out.)。
- ⚠️ 需要客户端模组发这条通道；纯原版客户端点不出这个手势，其它行为一个字节不变(The gesture needs the client mod; vanilla clients are unaffected.)。

#### 虚空仓库直接放置（`voidWarehouseDirectPlace`）

打印机缺料时**不再把一整组塞进背包**，而是从仓库预扣**一件**、直接放到目标位置。手上不会出现物品，打印完背包里也不会剩下成堆零头(The printer places blocks straight from the warehouse instead of restocking a full stack into the inventory.)。

- **零净变化的预扣**：流程是「从仓库预扣 1 件 → 临时塞进当前快捷栏格 → 走原版 `BlockItem#place` → 没放成的原样还回仓库」（`Withdrawal#returnUnplaced`）。结束之后那一格与背包跟放置前完全一致(One item is reserved, placed through the vanilla primitive, and returned if the placement does not happen.)。
- **走的是原版放置原语**，不是自己往世界里写方块 —— 朝向、半砖上下、楼梯拐角、栅栏连接、含水状态、红石灯亮灭、铁轨的 `shape` / `powered` / `waterlogged` 全部按原版算(Vanilla placement decides the block state, so orientation, slabs, stairs, fences and fluidlogged states all match hand-placing.)。
- **半砖合并**：对着底/顶台阶点缺失的那一面，一件预扣物直接给出双台阶 —— 与手动放完全同理(The slab merge case is handled with the same single reserved item.)。
- **不接的方块**：床 / 门 / 双格植物 / 潜影盒（一件预扣物放不出目标状态，或会额外消耗掉玩家自己的东西）；以及任何不是「裸 `BlockItem`」的物品（水桶、船、矿车……原版放置会改容器或产出多件）(Beds, doors, double plants, shulker boxes and non-plain `BlockItem` subclasses are refused on the server, since one reserved item cannot produce their target state.)。
- **和手动放一样受规矩约束**：非生存模式、`mayBuild` 为假、界面没关、手臂够不着、目标位置已被别人改过（`expectedStateId` 不符）—— 任一条不满足就整笔撤回，**仓库零消耗**(Every refusal path rolls the reservation back; the warehouse is never charged.)。
- 🔴 **回调里要重新校验**：`UseBlockCallback` 可能在放置前改世界或换手上的物品，所以进入放置原语之前会再读一次目标方块状态与主手物品，绝不拿旧快照放(World state is re-read after the interaction callback; a stale snapshot is never placed.)。
- 默认 `false`，**依赖** `voidWarehouse` + `voidWarehouseRestock`：没有仓库、或没开补货通道，它什么都不做(Disabled by default; requires both rules.)。
- 客户端要装 `litematica-printer`，并在其配置里打开 `voidWarehouseDirectPlace`(Enable the matching client option — the server side alone does nothing.)。

#### 直接放置的扣料提示

直放以前是**没有任何回执**的：方块凭空出现、背包不动，玩家能看到的唯一变化是仓库里少了东西，而那要打开界面才知道。同一条路上的「取货」会明确报一句，直放现在也报(Direct placement now reports what it took from the warehouse, in the same style as restocking.)。

- **文案**：`[虚空仓库] 放置消耗 64 个 石头（仓库剩余 998213 个）` —— 与取货/收料提示同一套配色（前缀 `[虚空仓库]` 亮金 + 正文浅灰 + 数字亮绿）(Same colour scheme as the restock notice.)。
- 🔴 **不能一放一条**：实机 26.3 日志里单个 200 tick 窗口下发过 **334 次**直放，峰值约 **34 次/秒** —— 一次一条就是每秒滚 34 行，玩家自己的聊天记录会被彻底顶掉，比不给提示还糟(A raw per-block notice would scroll 34 lines per second.)。所以：
  - **先攒后发**：每次放置只记账（物品 + 数据组件相同就累加数量），不立刻发(Records are accumulated, not sent immediately.)；
  - **同一玩家每秒最多一条**：窗口内新到的继续累加，下一条一并报数 —— 不是延迟通知，是把「这一秒放下去的方块」合成一句(At most one line per second per player, coalescing the whole window.)；
  - **一条最多列 4 种**：再多折成「N 种共 M 个」，免得一条提示比聊天框还长(At most four kinds are listed; more collapse into a single total.)。
- **库存是发消息那一刻现读的**，不是逐块快照：口径为「物品 + 数据组件」，与提示里的分组同一口径，所以报出来的数正好对得上玩家打开仓库看到的那一行(Stock is read at send time, on the same item+components basis as the grouping.)。
- **只报真正扣掉的**：被拒、过期、没货、规则关 —— 这些情况 `consumed` 为 0（预扣物已原样还回仓库），一条提示都不发(Only real deductions are reported.)。
- 物品名保持可翻译组件原样发出，**绝不**在服务端 `getString()`：专用服务器没人注入语言表，那样中文客户端会看到英文物品名(Item names stay as translatable components so the client localises them.)。

#### 配方本上限 512 → 2048 条，存档改为一配方一元素

配方本以前把**全部配方拼成一个字符串**存在 `learned_recipes` 里，上限压在 512 条。这个数字不是性能上限，是 NBT 的 65535 字节上限倒推出来的一层保险 —— 而它真正的问题有两个：512 条本身没有任何物理依据（原版就有约 1500 条配方，认真玩一个档就可能填满），且**填满之后的「不再记录」是静默的**(The recipe book now stores one string per recipe instead of one joined blob, and the cap moves from 512 to 2048.).

- **真正的墙是 975 条**：`StringTag` 走 `DataOutputStream#writeUTF`，长度字段 u16、数的是 modified UTF-8 字节。实测平均 **67.2 字节/条**，所以 975 条（65,512 B）能写、976 条（65,596 B）就超 —— 上限精确卡在这里(The real ceiling of the old format was 975 recipes, not 512.).
- 🔴 **撞上去不报错，只是悄悄变空**：26.3 的 `NbtIo$StringFallbackDataOutput#writeUTF` 捕获 `UTFDataFormatException` 之后执行的是 `super.writeUTF("")` —— **键还在、内容长度变成 0**。实测把一个 70,000 字节的字符串写盘，文件只剩 **10 字节**。所以「查一下 `contains(key)` 还在不在」会误判成一切正常，判据只能是**读回来还剩几条**(The failure is silent: the key survives with an empty value, so only the read-back count reveals it.).
- **改成一配方一元素**：新字段 `learned_recipes_lines`，`ListTag` 的元素计数是 int，单个元素几十字节，65535 这堵墙再也碰不到(The format is now a list of per-recipe strings.).
- **codec 自己写，不用 `Codec.STRING.listOf()`**：后者全成或全不成 —— 实测混进一个 `IntTag` 就 `Not a string` 整份失败，进而整个 `VoidWarehouseData` 解码失败、上层当成「文件不存在」静默造空仓库并覆盖写盘，那正是本模组历史上最严重的事故形态。`RecipeLinesCodec` 逐元素独立解析，坏的那个元素只丢它自己，整表永远成功；底层 `decodeAll` 的逐行宽容照旧(A hand-written codec keeps the old per-item tolerance instead of the all-or-nothing behaviour of `Codec.listOf()`.).
- **旧字段只读不写**：`learned_recipes_lines` 存在就用它，不存在才读 `learned_recipes` 并按 `\n` 切开迁移；写盘时旧字段那一路的 getter 恒 `Optional.empty()`，`optionalFieldOf` 于是一个字节都不写。老档**升级无需操作**，第一次写盘自动换键(Legacy saves migrate on first write, with no manual step.).
- **顶层类型错时不宽容**：被人手工改成字符串之类的情况让解码失败，交给空壳闸门拒绝服务 —— 宁可让服主看见一个明确错误，也不把「整本配方凭空消失」伪装成一次正常读档(A wrong top-level type still fails loudly.).
- **撞顶现在会说话**：达到 2048 条时服务端 `WARN` 一次（不是每学一条报一次 —— 满本是持续状态，会把日志刷穿）(Hitting the cap now logs a single warning.).
- **验证**：`A2CodecTest` **35 条断言**（纯 JVM + 真 `NbtIo`）、`A2StorageSelfCheck` **27 条断言**（真服务端，26.3 与 1.21.11 各跑一次）、既有 `SystemTest` **209 条断言**全部零回归，三套合计 **271 条全绿**。实测 2048 条落盘 **5,604 字节**（配方原文 127,914 B，压缩比 23.6:1）、5000 条落盘 13,225 字节（24.5:1）(271 assertions green across three suites; 2048 recipes compress to about 5.6 KB on disk.).
- **压测同时揪出一条更严重的同源缺陷**：满本时 **21%** 的配方被误判成「做不出」而整格从界面消失，同一批还吃掉 `exchangeOffers` 重建的 **93%** 时间。已在同一版的「🐛 修复」段落里修掉 —— 按本文件的版本号约定，修 bug 不升号(This round's stress test also uncovered a severe defect — one fifth of the recipe book silently vanished from the UI — fixed in the same release; bug fixes do not bump the version.)。
  - 完整数据、瓶颈归因与五版本对比见 `docs/配方本满本并发测试报告.md`(Full data and root-cause analysis are in the stress-test report.)。

#### 配方本开始说话了：记住了 / 快满了 / 已满

配方本以前是**完全静默**的：学会了没有、撞没撞上限，玩家只能自己打开界面数格子猜。撞上限尤其糟 —— 合成照做，但那条配方没记，等哪天真要用了才发现界面里没有这一格，而那时已经想不起来是哪一次没记上(The recipe book used to be silent; a full book dropped recipes without a word.).

- **记住一条就报一句，句尾直接给出用量**：`[虚空仓库] 已记住配方：2 桦木木板 → 4 木棍，目前配方数量：1,845 / 2,048。` 原料数量和产物数量都写 —— 同产物的不同配方产物数可能不一样，只写原料会让人误以为产物相同；用量接在同一行是因为「又记住一条」的下一句通常就是「还能记多少」，拆成两行等于每条提示都变两行(One line per newly learned recipe, with both counts and the book usage appended.)。
- **同产物还有别的走法时一并列出**（这条是重点）：「2 橡木木板 → 木棍」与「2 桦木木板 → 木棍」在仓库里是**两条独立配方** —— 从合成格记录的是玩家实际放的那一种，不做 tag 归一 —— 所以新学桦木那条会接一行 `└ 还能这样合：2 橡木木板 → 4 木棍；…`。玩家由此知道这不是替换，是多了一条路(When the same output already has other recipes, they are listed on a second line.).
- **最多列 5 条**，其余折成「另有 N 条」；按 `recipeId` 排序之后再截断 —— 顺序不稳的话同一件事两次提示会列出不同的配方，玩家会以为配方本自己变了(At most five are listed, sorted stably, with the rest collapsed into a count.)。
- **快满预警**：用到上限的 90%（2048 条时是 1844 条）报一次，且一个玩家只报一次(A single near-full warning at 90%.)。
- **满了**：边沿必报一次，之后每 30 秒最多一次 —— 满本之后玩家每合成一次都会撞上，不节流就是每合一次刷两行(Edge-triggered once, then at most every 30 seconds.)。
- 🔴 **「重复合成」原先是完全静默的，实机测试把它判成了 bug**：玩家在配方本已装 1844 条的档里连着合了 8 次东西，配方本**确实**长到了 1852 条（功能完全正常），但聊天栏一个字都没有，于是被当成「合成后没有提示」报了上来 —— 因为他撞的全是「这条已经记过」和「本子已经满了」两个分支。**「什么都没说」和「功能没生效」在玩家眼里一模一样**，这条设计从根上就不成立(Duplicate crafts were silent by design; a real-world test report showed that silence is indistinguishable from a dead feature, so it now speaks once per player.).
  - 现在「已经记过」**每个玩家说一次**：`这条配方之前已经记过了，不会重复记录。` 说完就闭嘴 —— 重复合成毕竟是常态，每合一次报一次就是刷屏(One line per player, then quiet.).
  - 「规则没开」仍然彻底静默：玩家压根没打算用这个功能，不该打扰(`DISABLED` stays fully silent.)。
  - 这条不是靠读代码发现的：存档里那 8 条新配方证明扣料、落盘、mixin 全都正常，**只有发消息这一段没有产出**，于是事后无从判断是「设计如此」还是「坏了」(The save file proved the pipeline worked end to end — only the message was missing, which is exactly why it was unreadable as a bug report.)。
- 🔴 **记录配方时抛的异常以前被一声不响地吞掉**：旁路记录失败绝不能影响合成本身（这条不变），但「照样吞」不等于「一声不响」—— 上面那次排查里，日志里一个字都没有，无法把「配方记下了、提示没发出去」与「一切正常」区分开。现在留一条 `WARN`（带堆栈），**前 3 条之后就闭嘴**，否则一个每次都抛的坏配方会把日志刷穿(The mixin still swallows exceptions from the side-channel, but now logs the first three.).
- 🔴 **「同产物已有配方」必须在 `learn` 之前取**：learn 之后新配方已经进了本子，再取就会把它自己列进「已有」，提示里出现「还能这样合：2 桦木木板 → 4 木棍」—— 刚学的那条自己列自己。这个顺序写反了**不会报错**，只是提示多一条 —— 所以它被固化进 `VoidWarehouseData#learnCapturingOthers`，由自检直接断言交出来的快照里没有刚学的那条(The pre-learn snapshot is captured inside the data layer, which makes the ordering testable.).
- `learn` 的返回值从「只有 `boolean`」扩出一个 `LearnResult`（`LEARNED` / `DUPLICATE` / `FULL` / `DISABLED`），旧的 `learn` 保留原语义、既有调用点一行未改 —— 三种 `false` 挤在一个布尔里，调用方永远只能猜(The boolean `learn` is kept; a new `LearnResult` explains why a recipe was rejected.).
- **物品名保持可翻译组件**发出，绝不在服务端 `getString()`：专用服务器没人注入语言表，那样中文客户端会看到英文名(Item names stay translatable components.).
- **不新增规则、不升版本号**：提示跟着 `voidWarehouseCraft` 走 —— 规则开着才有配方本，才有提示，语义自洽(No new rule; the notices follow the existing craft rule.).
- **验证**：`VoidWarehouseCraftSelfCheck` 新增 P 组 **48 条** + Q 组 **3 条**断言（166 → 217），五次变异全部咬住 —— 把「先取后学」调反、`LIST_LIMIT` 放到 100、`FULL_REPEAT_TICKS` 归零、把「已记过」改回静默、让 `canHasTranslations` 返回空表，分别打出 2 / 2 / 2 / 1 / 3 条红(51 new assertions; five mutations each broke exactly the intended checks.)。
- **Q 组的判据不是「界面上看得见」，而是「扩展自己交出了键」**：`canHasTranslations(lang)` 对 `zh_cn` / `en_us` 各查 6 条规则的 12 个键，缺一个就红。查「界面显示」是没有牙的 —— 那几个键历史上是被另一个扩展顺带注册的，界面照样显示(The assertion checks that the extension itself supplies the keys, not that the UI happens to render them.)。

#### 每条规则各自把翻译契约实现完整：`canHasTranslations`

`voidWarehouse*` 这六条规则的规则名与描述一直都能显示，但**显示的原因不是它们自己的代码**：`assets/carpet_neko_addition/lang/<lang>.json` 这一份文件被 `AutoMendingExtension#canHasTranslations` 顺带交给了 Carpet，两套规则共用了同一份 lang 文件，于是虚空仓库的 12 个键被「邻居」注册了(The six rules were showing their names only because another extension happened to register the same lang file.).

- 🔴 **Carpet 的机制是「解析那一刻快照」**：`SettingsManager` 构造函数第一件事就是 `updateLanguage()`，遍历每个扩展调 `canHasTranslations(lang)` 并进翻译表；随后 `parseSettingsClass` 把 `Translations.trOrNull("carpet.rule.<名字>.desc")` 的结果**拷进 `ParsedRule.description`** —— 之后换语言也不会重新取(Carpet snapshots rule descriptions at parse time, not at display time.)。
- **所以扩展不实现这个钩子，规则就是「没有描述」**，而且不会有任何报错。以前能显示，只是因为另一条规则先注册了同一份文件 —— 这是一条**没有人写下来的隐式依赖**：哪天 autoMending 那个扩展被拆出去、被条件注册、或者换了 lang 文件，虚空仓库这六条会一起失去描述，且无从查起(The dependency was implicit: nothing in the void-warehouse code said it needed that lang file.)。
- **改法**：`VoidWarehouseExtension` 自己实现 `canHasTranslations`，直接复用 `TranslationLoader.load(lang)`。「每条规则各自一个 `CarpetExtension`」这条规矩本来就要求各自把契约实现完整，所以这不算新机制，只是把欠的补上(Each extension now fulfils the contract itself.)。
- ⚠️ **诚实记录：这份报告在纯服务端没能复现**。探针读 `ParsedRule.description`，**340 / 340 条规则都有描述**，六条 `voidWarehouse*` 全部非空，`zh_cn` 与 `en_us` 都一样。所以「英文模式下没有描述」的现场**不是**「钩子缺失」造成的 —— 补上钩子是对的（消除隐式依赖），但它**不能证明**用户遇到的那个现象已经修好。剩下的可能是：多条 Carpet 系扩展（`carpetgui` / `carpet-org-addition` / `carpet-ams-addition` / `carpet-tis-addition` / `gugle-carpet-addition` / `carpet-igny-addition`）争抢同一张翻译表，或界面走的是自己的一套读取路径，需要一个现场截图或 `/carpet` 输出才能定位(The hook was missing and is now implemented, but a server-side probe shows all 340 rules already had descriptions, so this is not yet proof that the user's symptom is gone.).
- **验证**：Q 组 3 条断言（扩展已注册 + `zh_cn` / `en_us` 各 12 个键齐全）；变异测试「让 `canHasTranslations` 返回空表」→ 3 条全红。

### ⚡ Performance / 性能

#### 满本重建 123.88 → 22.17 ms：不再为每个分支复制一份账本（`voidWarehouseCraft`）

- **现象**：满本 1236 条配方、每种原料上万时，`exchangeOffers` 单次重建要 **90 – 124 ms**，是 50 ms tick 预算的 2 – 2.5 倍。玩家在仓库里连续取放时，每个 tick 都要付一次 ⇒ 稳定掉 tick(The full recipe-book rebuild took 90–124 ms per call, 2–2.5× the 50 ms tick budget.)。
- **根因不在「算了几条配方」**：离线归因（复刻 `collectCraftOffers` 的口径，算出的产物数 **746** 与服务端打出的 `offers=746` 一致）显示 `stockClosure` 只占 **2.7%**、`maxCraftable` 占 **82.7%**。再往下切：5,393 次探针里 **`Model.copy()` 被调用 164,505 次**（平均每探针 30 次），每次都要重建一个 40~60 项的 `HashMap` 外加两个 `LinkedHashMap`；**37% 的配方（324 条，各 16~17 次探针）吃掉 78% 的时间**(The cost was per-probe allocation, not the number of recipes.)。
- **改法**：`Model` 从「每个分支复制一份」改成「**记旧值 + 回滚**」。这不是「写逆操作」—— 那条路以前被否过，理由是对的：漏一处账就错。这里不推导任何反向运算，只是每次写 map 前把 `(哪本账, 键, 旧值)` 压进撤销日志、回滚时倒序贴回；而「所有写都必须过日志」不靠自觉，靠三个 map 字段全部 `private`、只暴露带日志的写方法来**强制** —— 想绕过日志直接写 map，当场编译不过。附带三处：`pool` 改成库存的**惰性覆盖层**（探针不再每次复制一份库存）、探针**关掉扣料账本**（`feasible` 只问摆不摆得下）、小整数算术走 `long`。
- **正确性怎么保证**：把改前 / 改后两份源码分别编译，喂同一语料**对拍 7,416 行**输出（1236 条 `maxCraftable` + 每条 5 个目标量的 `plan`，含 `consumed` / `surplus` 的完整内容），SHA-256 **逐字节相同**。压测侧的指纹也一致：`capSum` 从 18,610,821 一字不差地复现(A byte-identical 7,416-line diff against the previous implementation, plus an unchanged `capSum`.).
- **结果**：五版本满本重建 **123.88 / 95.91 / 93.37 / 90.77 / 94.24 → 22.17 / 25.09 / 18.05 / 18.15 / 22.88 ms**（3.8 – 5.6×），全部低于 50 ms；单条 `maxCraftable` P99 从 1,650 µs 降到 **75 µs**、max 从 5,247 µs 降到 **336 µs**；规模曲线由超线性回到近线性（600 → 1236 条：改前涨 4.4 倍，改后涨 2.1 倍）。`A2` 判据**五版本首次全绿** —— `A2StressTest` 34 条 × 5、`SystemTest` 209 条 × 5、`CraftSelfCheck` 138 条 × 4，合计 **1,767 条断言零失败**(The A2 tick-budget assertion passes on all five versions for the first time.).
- ⚠️ **原定的 R2 路线是错的，记在这里免得下一个人再走**：设计文档写的是「按页算 / 只脏化受影响的产物集」。实测「按页算」最多省 15%（闭包 3.25 ms + 部分 `plan`），却要动展示顺序（U2）与分页总数 —— 代价远大于收益。（**「只脏化受影响的产物集」这个估计错了**，它其实省 93%，见下方 Performance 节的第三条 —— R3 就是这么做的。）「减少探针数」同样走不通：324 条多探针配方的**真值全部落在上界的 1/2 以上**（上界估得很紧），二分 16 轮已接近信息论下限。**先归因，再优化**(The originally planned caching route was measured to be worth <15%; the real multiplier was per-probe allocation.).
- 顺带补上 **sources jar 不含 `common/` 共享层源码**的老缺口：五个子工程的 `sourcesJar` 现在把 `common` 的源码一并打进去 —— 此前打开 sources jar 根本找不到真正在跑的那份算法(The sources jar now bundles the shared `common/` sources.).

#### 满本重建再降一个数量级：22.2 → 0.93 ms，只重算受影响的产物（`voidWarehouseCraft`）

- **现象**：R2 之后满本（1236 条配方 / 746 个产物）单次重建 22 ms，已经进了 50 ms 预算，但玩家在仓库里连续取放时每个 tick 都要付一次 —— 稳态吃掉 44% 的 tick 预算。而这 22 ms 里**绝大多数是白算的**：取走 1 个圆石，只有 13 个产物的账会变(The full rebuild still cost 22 ms per tick of activity; 733 of 746 craftable entries were recomputed for nothing.)。
- **原定路线被实测否掉**：设计文档的 R3 是「`maxCraftable` / `stockFor` 按 `(recipeId, stockVersion)` 缓存到 tick 级」。实测**命中率 0.0%** —— 一次取货必然改库存、必然推进版本，真实操作序列里「同一版本内再问同一条配方」根本不存在（60 次取货、65 次调用、重复 0 次）。真正在重复的是**整张表**。
- **改法**：三个能改库存的原语（`consume` / `produce` / `put`）各记一笔「哪件物品变了」；每个产物在配方本换版时算一张**依赖集**（自己 ＋ 它每条候选配方整条闭包里的物品）；改动发生时只重算「依赖集里含脏物品」的产物。实测按物品平均只波及 **13.0 / 746 个产物（1.7%）**，中位数 1~5 个，最坏 `oak_log` 184 个（24.7%）—— 最坏也不会退化成整批(The dirty set is tracked at the three stock-mutating primitives; a per-product dependency set limits recomputation to the entries a change can actually affect.)。
- **不动分页、不动展示序**：表还是那张完整的表（746 项，该是空的仍是空），只是没重算结果不会变的项 —— 于是当初担心的「怎么在不全算的前提下知道总页数」根本不存在。顺序稳定靠 `LinkedHashMap`：整批按配方遍历序填充，增量走 `put`、对已有键不改位置，所以「同一份库存、同一份配方本」无论走过多少次增量，遍历顺序都和整批一致。
- **顺手多一条捷径**：`Container#setChanged()` 会调 `markChanged()`，而它「什么都没改」。脏集合为空就直接沿用上一份表 —— 满本这条路从 **13.57 ms 降到 0.000 ms**。判据必须带上「清空标志」：`clearContent()` 把库存整体清空却不逐件记账，少了这一项就会把上一份还带着已不存在物品的表当新表返回（这条是写完自检才想到的，M9 专为它补）。
- **正确性怎么保证**：新增自检 **M 组 11 条**，给每次改动都配一份「整批重算」的对照 —— 原地 `clearContent()` 再灌回同一份库存（配方本一个字不动），把可兑表按字面量摊平**逐字节比对，顺序照原样不排序**。另做**两次变异测试**证明这组有牙：去掉依赖集里的「产物自己」→ **M3/M4 当场红**；去掉快捷路径的清空判据 → **M9 当场红**(An 11-assertion incremental-vs-full equivalence group, validated by two mutation tests that each turn it red.)。
- **结果**：五版本满本重建 **22.17 / 25.09 / 18.05 / 18.15 / 22.88 → 0.93 / 1.17 / 1.07 / 0.75 / 1.46 ms**（15.7 – 24.2×）；满本「标脏但没变」13.57 → **0.000 ms**。**五版本 14 组套件、1,816 条断言零失败**，而正确性指纹一字未动：判 0 条数仍是 **0/17/43/43**、`lost=0`、`exact=1236 · under=0 · unreachable=0`、货架 `missing=0`、展示序 **727 项**一致、满本 `offers` 仍是 **746/671/652/652/652**(All five versions pass, and every correctness fingerprint is unchanged.)。
- ⚠️ **配方本换版后的第一次重建仍是整批**（`max_ms` 23–73 ms，暖机下比稳态多 3~8 ms），因为依赖集要整张重建。仍在 50 ms 预算内，但余量比稳态小一个数量级。（**下一条把它压到 20.2 ms** —— 那时量出整批的大头不在依赖集，而在「每次探针重新展开一棵配方树」。）
- ⚠️ **R2 里那句「脏化集合最多省 15%」是错的，已在设计文档里更正**：当时把「按页算」和「脏化集合」归成一类估，「按页算」确实只值 15%，「脏化集合」实测省 93%（733/746）。教训是**估算必须在真实调用序列上量**，不能在脑子里把两条代价结构不同的路线归为一类 —— 那比不做估算更危险，它会让人理直气壮地跳过唯一有效的那条。

#### 满本整批重建 45.4 → 22.3 ms：把配方树编译成下标，模拟态改用可复位草稿纸（`voidWarehouseCraft`）

- **现象**：R3 之后稳态增量已经是 1 ms 级，但**配方本换版后的第一次重建仍然是整批**（`max_ms` 23 – 73 ms）。这条路不是只在开服时走一次：玩家在界面里学一条新配方、或把仓库里的东西存进配方本，下一 tick 就得把整棵树重算一遍(The first rebuild after the recipe book changes version was still a full batch; it is hit whenever a recipe is learned or stored.).
- **根因是「每次探针都重新展开一棵树」**：`plan` / `maxCraftable` 每一层都靠 `Map<String, List<Recipe>>` 现查，每条配方的每个原料槽位现查 `List<ItemStack>`、现算候选 id 字符串。满本 866 条走闭包的配方各自铺一个 40 项 `map`，`amount` 回调合计 **7,478 次** —— 而这一 tick 里库存一个字节都没变，问的都是同一批量(The cost was re-expanding the tree and re-reading the same stock on every probe.)。
- **改法（AE2 的结构，语义照搬）**：`VoidWarehouseRecipes.Tree` 在配方本换版时**编译一次** —— 每个物品一个槽位（`slotOf`）、每条配方一个 `RecipeNode`（原料槽位的候选压成 `int[]` 下标、`multiParts` 预先分流、`forcedSlots` 预解析），之后 `plan` / `maxCraftable` 全走数组，不再碰任何 `Map` 与字符串。库存仍按**一条配方一份闭包**打成 `BigInteger[]`（`stockArray(RecipeNode, amount)`）。模拟状态放进可复用的 `Scratch`（AE2 `CraftingSimulationState` 的角色）：世代戳 + 撤销日志，复位只是 `epoch++`，不再给每个分支复制账本(A precompiled recipe tree of slot indices, a per-recipe closure array, and a reusable simulation scratch.).
- **用户问的那句「先序遍历配方树、到叶子看够不够」正是现在的语义**，所以功能一个字都没动：`Tree` 只是把「问一次查一次」换成「先把下标编好」，遍历顺序、候选顺序、`MAX_DEPTH` / `MAX_CLOSURE` 的截断顺序、`maxCraftable` 的二分判据、`deltas` 的净变化口径全部照旧(No behaviour change: same traversal, same truncation order, same binary-search predicate and the same net-change convention for `deltas`.).
- **正确性怎么保证**：还是那台对拍台 —— 改前 / 改后两份源码喂同一语料**对拍 7,416 行**，SHA-256 仍是 `089d6787…8c82e1`，**逐字节相同**。真服务端读数也一致：`offers=747`、`sink=5976` 与改前一字不差（探针先学一条语料外配方把配方本顶到换版，所以是 747 而不是 746）(A byte-identical 7,416-line diff, plus unchanged `offers` and `sink` readings.).
- **结果**：真服务端同口径探针（同一个 `R4BatchProbe`，先后跑新旧代码，各三轮取中位）满本整批 **45.42 → 22.25 ms（2.04×）**，增量 1.41 → 1.60 ms；离线归因台 39.53 → 21 ms 量级（`maxCraftable` 单项 22.79 → 12 ms 量级）。收益全部来自「下标化」与「草稿纸复用」——**没有**动「一条配方一份闭包」这条语义线(The full-batch rebuild halved on a real server; the win is the index-based tree plus the reusable scratch, not a different stock snapshot.).
- ⚠️ **这一版中途试过「整批只取一份全树快照、所有产物共用」**：确实更快（22.25 → 20.22 ms，1,223 次回调），但它**改变了语义** —— 同名产物的多条配方共用一份闭包时，深度预算会互相挤占，深层配方算出来的数量变小。审计当场抓到 1–11 条 `*_cushion` 配方与上一版不一致，于是整条快照路线撤回，见下方 Fixed 那条。**教训：把「回调次数从 7,478 降到 1,223」当成纯收益，是因为只看了次数、没看它读的是不是同一批槽位**(A shared full-tree snapshot was tried and reverted: it was faster but changed the answers for deeply nested recipes.).
- **闭包比预想小得多**：`Probe2` 在真实语料上逐条配方数了闭包规模（满本 866 条配方，分布在 746 个产物下），**最大只有 40 个槽位**（`magenta_harness`），分布 `0:596 / 8:88 / 16:71 / 24:89 / 32:21 / 40:1` —— 离 `MAX_CLOSURE=256` 的上限还有六倍余量。所以「单次取货只取闭包」这条捷径是安全的：闭包外的部分本来就必须读成 0(Closures top out at 40 slots, far below `MAX_CLOSURE`, which is what makes the per-withdrawal path safe.).
- ⚠️ **`Tree` 必须与配方本同生共死**：`recipesByOutput` 换版而 `Tree` 没换 ⇒ `RecipeNode` 指向已经不存在的槽位。五个版本全部改成在 `rebuildRecipes()` 与构造函数里同步重建 `craftTree` + `craftScratch`；`usable(tree, scratch)` 另做长度校验，长度不符就新建，防跨版本档读到越界下标(The tree and the scratch are rebuilt together with the recipe book, and a length check guards against stale sizes.).

### 🐛 Fixed / 修复

#### 装满的潜影盒当分类物：要么白送盒内 1728 件，要么把盒里的东西倒进虚空（`voidWarehouse`）

出料漏斗里放一个**装满圆石的潜影盒**当分类物、下方接箱子：漏斗每 tick 从仓库取 1 个「潜影盒」塞进箱子，但塞进去的是**那只满盒本身**（盒内 1728 个圆石），而仓库只按「1 个空盒」或「2 壳 + 1 箱」收钱。净效果 = **2 壳 + 1 箱换 1728 个圆石**，20 次/秒(The extraction hopper handed out the full shulker box it was using as a classifier: one empty box was charged, 1728 items came out, every tick.)。

- **根因**：`VoidWarehouseData#withdraw` 第 ② 段（成品没货 → 9 合 1 兑换 / 按已学配方扣料）交出去的是 `sample.copyWithCount(n)` —— **把调用方手里那个样板整份复制出去**。而仓库按「一格一种形态」存东西，装满的盒子**根本不可能是一个格子**（`insert` 会把它拆成内容物 + 2 壳 + 1 箱，只有空盒才整盒入库），所以 `find(满盒)` 永远不命中，出料路径必然落进这一段。合成层只认 `(物品, 数量)`，数据组件一个都复现不出来 —— 拿 sample 复制就等于把它身上挂着的东西白送一遍：盒内 1728 件、`CUSTOM_NAME`（名字无限复制）、`BEES` / `CHARGED_PROJECTILES`。
- 🔴 **这个 bug 修错过一次，方向正好相反**：第一版改成交**纯净空盒** —— 账平了、自检 25 条全绿，但满盒进漏斗、空盒出来，那 1728 件被倒进了虚空。**「没有凭空多出东西」不等于「没有丢东西」**，第一版自检只咬了「不许白送」这一头，那个方向的全绿毫无意义。玩家要的也不是这个，是「盒子怎么进就该怎么出」。
- **最终口径：盒子怎么进就怎么出**。判据是「身上有没有挂着**实物**」——
  - 有（潜影盒 `CONTAINER`、弩 `CHARGED_PROJECTILES`、收纳袋 `BUNDLE_CONTENTS`）→ **原样交出那一件**，代价是盒里那些实物也按「物品 + 数据组件」逐份扣掉（`payloadOf` / `takePayload`，**先全验、再全扣**）；付不出就**整笔拒绝**，绝不交一个空盒充数。
  - 没有（空盒、改过名的、附魔的）→ 仍交**纯净产物**，与 `produce()` 的「找零本来就是合成产物，不可能带附魔或改名」同一口径：改名与附魔没有材料账可对，照抄就是凭空复制。
- **回滚也要把实物退回去**：`Withdrawal` 多带一份 `payload`，`returnUnplaced` 反向执行兑换计划之后按份退回。少这一步，下游一拒收就凭空少掉一整盒（而外盒的材料却退了回来）。
- 🔴 **五版本的容器组件方法名不同，这是本轮唯一的分叉**：26.3 三种都叫 `itemCopies()` / `nonEmptyItemCopyStream()`；26.2 与 26.1.x 的收纳袋是 `itemCopyStream()`；1.21.11 **没有 `ContainerComponent` 接口**，盒子是 `nonEmptyStream()`、弩是 `getItems()`。第一版照 `ContainerComponent` 写，1.21.11 当场编译不过。
- **行为变化（要说清楚）**：漏斗里放满盒当分类物，现在**原样出真盒**，但每 tick 要扣「1 个空盒（或 2 壳 + 1 箱）**加上**盒内那 1728 件」；盒里的东西不足一盒时**一件都不出**、一个字节都不扣。玩家的库存会很快见底 —— 这不是 bug，是账算对了。
- **防复发**：自检 **35 条**（`SpecimenDupeSelfCheck`，临时工具，验证完已摘），每一组同时咬**形态 / 扣了什么 / 总数守恒**三件事 —— 反证组（仓库真存满盒时不许额外收钱）与拒绝组（付不出时一件都不许出）分别堵住上面两个方向。**变异实测**把 ② 段退回「一律交纯净产物」→ **14 条当场红**，反证组保持绿(Thirty-five assertions pinning shape, deduction and conservation at once; a mutation that restores the clean-product shortcut turns fourteen of them red.)。

#### 同一产物的多条配方共用一份闭包，深层配方会「有原料却算成做不了」（`voidWarehouseCraft`）

- **怎么发现的**：交付前按玩家要求做覆盖审计（离线 1,236 条配方 × 9 种库存分布），把「闭包快照」与「全树快照」的答案逐条对比，发现 1 – 11 条配方两者不一致，全部落在 `*_cushion` 这类四层链上。再拿 R3 源码跑**同一个判据**：R3 只有 0 – 10 条不一致 —— 多出来的那几条正是本轮引入的(This regression was found by the pre-release audit, not by luck.)。
- **根因**：第一版把「一条配方一份闭包」改成了「一个产物一份闭包」（遍历该产物的**所有**配方）。闭包收集用全局 `seen` 去重：先扫到的那条配方把中间产物标成「已访问」，轮到另一条配方时，即便该物品刚出现在第 0 层也不会再往下钻 —— 深度预算被先扫到的分支吃掉，闭包比逐条取更浅。`blue_cushion` 正好有两条配方（`3× blue_wool_slab` 与 `1× blue_dye + 1× white_cushion`），后者的 `wool → string` 链整段丢掉。
- **症状**：仓库里明明有原料（尤其是只剩最深层那几种的时候），界面显示「做不了」或可做数量偏小。这正是玩家最常报的一类。
- **改法**：`Tree#stockArray` **只接受 `RecipeNode`**，遍历起点是「这一条配方」的原料（depth=0），与 R3 的 `stockClosure` 逐条对齐；产品级的重载**直接删掉**（留着就是下一次踩同一个坑的入口），旧签名 API 改用 `arrayOf(Map)` 铺数组。三处调用点（单次取货 `craftStock`、批量 `computeCraftOffer`、增量分支）全部改成逐条取(The closure is now taken per recipe, never per product.)。
- **验收**：① 离线审计 D 组（闭包 vs 全树）的差异**配方集合**与 R3 逐条相同（9 种库存下合计 21 次差异，全部是同一批 `*_cushion`）；② 新增 H 组把「数组闭包」与「旧 `Map` 闭包」逐键比对 **8 万余条断言**，全同；③ 7,416 行对拍 SHA-256 仍是 `089d6787…8c82e1`。
- **代价**：整批重建从「共用一份全树快照」的 20.22 ms 回到 **22.25 ms** —— 买回来的是「答案与上一版一模一样」。这个交换必须做：20.22 那版在 `*_cushion` 上给出的数与玩家上一版看到的**不同**。
- **防复发**：`CraftSelfCheck` 新增 **R 组 7 条**，刻意造出「一个产物两条配方、深层物品在第 4 层 vs 第 0 层」的形状。变异实测（把闭包退回按产物并集）**R3 / R5 / R7 三条当场红** —— 其中 R7 就是「有原料却做不出来」那句原话(Seven new assertions pin the per-recipe closure; a mutation that restores the shared closure turns three of them red.).

#### 盒模式下取「潜影壳 / 箱子」会白得外盒（材料账与货同源）

仓库里放 **1728 个潜影壳 + 1 个箱子**、0 个空盒，盒模式点壳那一格：拿到「1 个装着 1728 个壳的盒子」，把盒子送回仓库 → 变回 1728 个壳 + **1 个空盒**。净效果 = **1 个箱子（8 木板）换 1 个潜影盒**，可无限循环 —— 而原版要 2 壳 + 1 箱(In box mode, withdrawing shells or chests paid for the outer box out of the very stacks being taken, so one chest bought a whole shulker box over and over.)。

- **根因是顺序**：`hasBoxMaterials` 在**取货之前**验（那 2 壳 + 1 箱那时还躺在仓库里），`consumeBoxMaterials` 在**取货之后**扣（壳已经跟着货一起被取空）。中间没有任何一步把「这批货马上就要走」算进材料账。菜单点格子那条更糟 —— 变异测试实测「盒子给了、货只扣了 2 个壳」（1728 → 1726）(The material check ran before the withdrawal and the deduction after it, so the goods could pay for their own box.)。
- **改法是「预留」，不是「禁用」**：`VoidWarehouseShulker#isBoxPart` 标出壳 / 箱；`VoidWarehouseData` 新增 `materialStock` / `hasBoxMaterials(plan, leaving, count)` / `maxBoxesUpTo(limit, sample)` 与 `canMakeBoxes`（二分，判据对盒数单调不增）—— **材料账先把「马上要离库的那批货」刨掉，再看够不够**。三处取货口都换这套口径：`fill` 用带预留的盒数、`executeCore` 验三参版、菜单 `withdrawShulkerBox` 先把交付量收一点(The stack being packed is reserved out of the material account; the box is still given, but its cost must come from stock outside that batch.)。
- **两种结果**：库存刚好只够一盒容量时（1728 壳 + 1 箱）**一盒都不给**并报「盒子材料不够」—— 盒子钱付不出，不该白送；库存宽裕时照给满盒，材料随货一起扣（20000 壳 + 100 箱 → 11 满盒，壳扣 19030、箱扣 11）。菜单点一格更细：1728 壳 + 1 箱 → 给一个装 **1726** 个壳的盒子，另扣 2 壳 + 1 箱，账是平的(At the boundary the box is refused; otherwise it is full and the materials are charged.)。
- **中途改过一次方案**：第一版把这两样**一律挡在装盒之外**（点一下给散货）。玩家要求保留「一盒壳」的能力，于是改成现在的预留版 —— 三处 `noPacking` 判据撤回，换成材料账预留(The first fix simply refused to pack those items; the shipped fix keeps the box and reserves the materials.)。
- **是不是本轮引入的**：不是。盒模式上线时这条路就在，本轮只是把它从「按数量取货输 1728」缩成一次点击。E 组 **18 条断言** + 变异实测（摘掉预留后 **10 条同时红**）钉住三处口径。

#### 取货时不再把物品放进副手 / 盔甲槽

「按数量取货」与「填满背包」放产物时用的是 `Inventory#getContainerSize()`，而 26.3 那个值是 **43**（36 格 + 副手 + 身体盔甲 + 鞍）—— 背包塞满时物品会落到副手、甚至盔甲格里。现在统一按 `Inventory.INVENTORY_SIZE`（36）算(Withdrawals used the 43-slot container size and could place items into the offhand or armour slots; only the 36 player slots are used now.)。

#### 满本时 21% 的配方从界面消失（`maxCraftable` 线性退让 → 二分）

配方本装满之后，**五分之一的东西在界面上根本不存在** —— 不是灰的、不是写 0，是那一格压根不出(With a full recipe book, one fifth of the craftable items were simply absent from the UI.).

- **现象**：26.3 学满 1236 条配方、每种原料 10,000 个时，`maxCraftable` 对 **260 条（21.0%）** 返回 0。而这 260 条**全都是假阴性** —— 同一条配方走 `plan(1)`（只跑一次模拟）100% 成功。其余四版本各丢 269 条（21.8%）(All 260 false negatives: every one of them succeeds through `plan(1)`.).
- **按规模扫，是渐变不是突变**：
  | 配方条数 | 100 | 300 | 600 | 900 | 1236 |
  | --- | --- | --- | --- | --- | --- |
  | 判 0（26.3） | 0 | 5 | 57 | 92 | **260** |
  | 其中假阴性 | 0 | 5 | 57 | 92 | **260** |
  | 占比 | 0.0% | 1.7% | 9.5% | 10.2% | **21.0%** |

  触发条件不是「配方复杂」，而是**数值够大** —— 300 条档命中的是 `bone_meal` / `coal` / `coal_block` 这种单层配方(The trigger is magnitude, not complexity: the 300-recipe rung already loses single-ingredient recipes.).
- **根因**：`maxCraftable` 先用 `boundCap` / `availOf` 估一个**宽松上界**（明知共享原料时会重复计数），再从 `cap` 起**每次减 1** 重跑整棵 `simulate`，上限 `MAX_VERIFY = 64`；64 次都不成 → 返回 0(The old code walked down one step at a time from the loose upper bound, capped at 64 attempts, then gave up and returned zero.)。
- 🔴 **「相对误差小」不等于安全**：上界只比真值高 **6%~33%**，但真值本身在万位（`activator_rail` 上界 18,333 次 / 真值 16,667 次）⇒ **绝对差 471 ~ 114,081 次，P50 = 3,328**。所以 64 次不是「偶尔不够」，而是**在这批配方上永远不够**（100% 的误判配方都需要 > 64 次退让）(The relative error is small, but the absolute gap is in the thousands — 64 steps could never reach it.).
- **调大常量没有出路**：按 P50 要 3,328 次探针（**慢 52 倍**），按最坏要 114,081 次（单条 **4.0 秒**）(Raising the constant is not an option: the median recipe would need 3,328 probes.).
- **换算法**：`simulate(recipe, crafts)` 对 `crafts` 单调，而旧代码**本来就在依赖**这条单调性（「从上界往下找第一个摆得下的，就当成最大值」本身就是「可行集是区间」）。所以二分**不引入任何新假设**，只是把线性扫描换成对数查找(Monotonicity was already assumed by the old scan, so the binary search introduces no new assumption.).
  - **快路径保留**：上界本身摆得下就直接返回 —— 实测 **79%** 的配方走这条，仍只要 1 次模拟。否则每条都要多付 `⌈log₂⌉` 次探针，白贵一个数量级(The fast path is kept: 79% of recipes fit the bound and still cost exactly one simulation.)。
  - **慢路径二分** `[0, cap-1]`：`⌈log₂(cap+1)⌉` 次探针，**与虚高多少无关**。`cap = 18,333` 也只要 15 次(The slow path costs 15 probes regardless of how loose the bound is.)。
  - **每次探针拿崭新的 `Model` 与崭新的 `MAX_NODES` 预算**：探针之间必须互不影响，否则前一次挖深会让后一次因预算耗尽而假失败，单调性前提当场作废(Each probe gets a fresh model and a fresh node budget.).
  - 万一某个配方因贪心分支选择而在某处非单调，二分只会**低估**（保守），绝不**高估** —— 高估是「显示能取、点下去失败」，那才是不能接受的方向(If monotonicity ever fails, the search can only under-report, never over-report.).
- **修复后（26.3，同一台机器、同一判据）**：
  | 指标 | 修复前 | 修复后 |
  | --- | --- | --- |
  | 满本判 0 条数 | 260（21.0%） | **0** |
  | 其中假阴性 | 260 | **0** |
  | 与 `plan` 二分真值逐条相等 | —（976 条相等 / 260 条判少） | **1236 / 1236** |
  | 报出来的数取不出来（不可达） | — | **0** |
  | 界面虚拟格 | 651 | **746**（+95） |
  | 满本 `exchangeOffers` 重建 | 323.80 ms | **122.81 – 162.34 ms**（两次运行） |
  | 单条 `maxCraftable` P99 / max | 6,627 / ~18,000 µs | **2,847 / 7,793 µs** |

  虚拟格由 651 变 746 恰好等于「1042 种产物 − 296 种同时也是原料」，**一条不多一条不少** —— 货架终于和配方本对上了(The shelf count now equals distinct products minus dual-role ones, exactly.).
- ⚠️ **一处诚实的退让**：单条 P50 从 11 µs 升到 19 µs。原因是「上界虚高 ≤ 15 次」的那批小配方，线性退让几次就中，二分却固定付 `⌈log₂⌉` 次探针。用中位贵 8 µs 换尾部省 3.8 ms，这个方向是对的(The median got 8 µs slower while the tail got 3.8 ms faster — a deliberate trade.).
- ⚠️ **仍未达标（本轮）**：满本重建 **123 – 162 ms** 依然超过 50 ms 的 tick 预算（修复前是它的 4.5~9.6 倍，现在是 2.5~3.2 倍）。当时判断这是**另一个问题**（每次库存变化都把 1236 条全部重算），需要单独一轮 —— 后续归因证明那个判断也不准，真正的瓶颈是每次探针的账本复制，已在同一版本的「⚡ Performance」节修掉，降到 18 – 25 ms(The remaining gap was closed later in the same version by the performance fix.).
- **验证**：真服务端 `A2StressTest` 五版本 `checks=34`，只剩 **`A2`（tick 预算）** 一条红 —— 那是另一个问题，见下。逐组：E 组梯度全档 `lost=0`（26.2 起的版本还剩 17/43 条判 0，但 `lost` 为 0，那些是真的做不出）；F 组 `under=0 unreachable=0 notMultiple=0`，26.3 是 `exact=1236`、其余四版 `exact=1235`（差的 1 条是可行集非单调，见 `docs/配方本满本并发测试报告.md` §10.5）；G 组货架对账全档 `missing=0`。既有 `SystemTest` **209 条 × 5 版本零回归**。另有一份纯 JVM 离线 A/B 台（不进仓库）用同一语料把「老算法 vs 二分」逐条对比，`oldZero` 复现出服务端同一组数字 0/5/57/92/260，`exact` 全档等于配方总数(Pure-JVM A/B harness reproduced the server's numbers exactly.).
- **溯源**：`git log -S "MAX_VERIFY"` 指向 `ac74e48 feat(voidwarehouse): voidWarehouseCraft`，配方扣料规则落地那一刻就在 —— **不是 A2 引入的**。A2 只是把配方本扩大到能装下 1236 条，第一次有人把这台机器开到它从没到过的转速上(The defect predates the recipe-book expansion; that change only made it reachable.)。
- 只有 `common/` 一处改动（五版本共享层），外加五份 `VoidWarehouseData` 的注释订正(One change in the shared `common/` layer plus comment fixes in five copies.).

#### 结构化直放不再依赖 Servux（单人存档里整条规则完全不生效）

实机 26.3 单人存档里，三条规则都开着、打印机四项配置也全开，直放却**一条消息都不发**，每一块都退回「取走 64 个 … 补进背包、手上放」(On a singleplayer 26.3 world the rule looked dead: every block fell back to restocking into the inventory.)。

- **根因不在协议，在模组元数据**：`servux-fabric-26.3-0.12.2.jar` 的 `fabric.mod.json` 写着 `"environment": "server"`，Fabric Loader 在客户端（**含单人集成服务器**）会**直接跳过**它，而且不报错。实机日志 183 个模组里根本没有 servux，也没有任何加载失败告警(The bundled Servux jar declares `environment: server`, so Fabric Loader silently skips it on the client, integrated server included.)。
- **于是整条通道静默断掉**：`PrinterPlaceServuxBridge.available()` 为假 → `bind()` 第一行 `isModLoaded("servux")` 直接 `return null`（唯一不打日志的分支）→ `printer_place_state_v1` 的类型登记与接收端都不执行 → 客户端 `canSend` 为假 → `stateChannel=false` → 放置执行器 `route()` 返回 `INVENTORY` → 每一块都走取货补背包(The state channel was never registered, so every request degraded to the inventory route.)。
- **日志硬证据**：`[WarehouseDirectPlace] capabilities enabled=true stateChannel=false`。`enabled=true` 说明 hello 握手、三条规则、通道 id 全没问题，只差 state 这一条；而 `bind()` 里唯独 `isModLoaded` 那条是裸 `return null`，其余分支都会 `warn` —— "Servux placement API is unavailable" 全程没出现，正好反证就是它(The diagnostics line pinned it to the state channel alone.)。
- **修法：结构化状态改由本模组自己算**，Servux 在场时才借它的解码器交叉验证。把它的两张表镜像进来（22 项白名单、`WATERLOGGED` / `POWERED` 两项黑名单，均按 0.12.2 字节码逐项核对），新增 `withRequested()` 直接从语义属性表算目标状态；`API == null` 时直接返回它，不再有「算不出来」这条路(The server now derives the block state itself, mirroring Servux's two tables verified against its bytecode.)。
- **半砖特意跳过 `SlabType.DOUBLE`**：一件预扣物品放不出两个半砖；Servux 的 easy-place 也是留给第二遍去合成，内置解析不能在这点上和它分叉(The semantic path skips `SlabType.DOUBLE` so it stays consistent with Servux.)。
- `PrinterPlaceNetwork.init()` 的 state 通道改成**无条件登记**；`PrinterPlaceService` 去掉 `!available()` 那道拒绝；`hasPermission()` 在没有 Servux 时放行 —— 权限本来就该由 carpet 规则 + `mayBuild` / `mayInteract` 决定(The state channel is registered unconditionally and the Servux-presence gate is gone.)。
- **验证**（真服务端 `runServer` 跑 `PrinterDirectPlaceSelfCheck`）：26.3 **106 条断言全绿**（原 102 + 新增 L9 双半砖不下发 / L10 基线守卫 / L11 state 通道进广播集合 / L12 三条上行通道齐全）(106 assertions green on a real server.)。
- **反证一**：`bind()` 强制 `return null` 模拟单人 → diag 打 `false`，L3–L10 仍全绿 ⇒ 内置解析与 Servux 回环**等价**，不是「换了个算法」(Forcing `bind()` to null keeps the main group green, proving the built-in path is equivalent.)。
- **反证二**：把 state 登记重新包进 `if (false)` → `failures=2`，正好 L11 / L12 翻红 ⇒ 这两条确实咬住本次故障，不是摆设(Wrapping the registration back in `if (false)` flips exactly those two assertions.)。

#### 交互距离检查重复测量，导致直放被整笔拒绝（[PR #2](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/pull/2)）

1.0.17 发布后收到的第一份外部修复，来自配套客户端 [litematica-printer](https://github.com/Yur1Ca/litematica-printer) 的维护者 [Yur1Ca](https://github.com/Yur1Ca)（Hana）：直放在某些摆位上被服务端判为 `REJECTED`，根源是准入判断里**同一件事被量了两遍，而且两遍的阈值口径不同**(PR #2 removes a duplicated reach check from the placement gate.).

- **被删掉的那条**：`player.getEyePosition().distanceToSqr(payload.hit().getLocation()) > Math.pow(player.blockInteractionRange() + 1.0, 2)` —— 量的是**眼睛到命中点**的距离(The removed line measured eye-to-hit-point.).
- **留下的那条**：`allowedPosition()` 里的 `player.isWithinBlockInteractionRange(pos, 1.0)`。按 javap 的真实实现，它量的是**眼睛到方块 AABB 最近点**的距离，阈值同样是 `blockInteractionRange() + 1.0`(The surviving check measures eye-to-block-AABB with the same range.).
- **命中点必然落在方块 AABB 内** ⇒ 前者恒 `≤` 后者，**只会更严，永远不会先放行**。它不是第二道防护，而是同一道闸门的更严格副本；删掉它不降低任何安全性，只去掉了「客户端瞄准点稍偏就被误拒」这条假阴性(Since the hit point always lies inside the AABB, the removed check could only be stricter — never a second line of defence.).
- **验证**：五版本产物逐个 `javap` 复核，`PrinterPlaceService` 里 `distanceToSqr` 调用数归零、`allowedPosition` 内 `isWithinBlockInteractionRange` 仍在（26.3 / 26.2 / 26.1.2 / 26.1.1 为可读名，1.21.11 经 remap 后是 `method_56093`）(Verified per version by javap after remapping.).
- **CI 五版本全绿后合并**（merge commit `302207f`）；本节列出的本版资产即由该提交的 CI 构建替换(All five versions went green before the merge; the assets listed here are that merge commit's CI build.).

#### 同一件东西占两格：可兑表的去重从来没生效过（`voidWarehouseCraft` + `voidWarehouseExchange`）

两条规则都开着时，一件物品会在界面上出现**两次** —— 一格写「可兑」、一格写「可合成」，玩家会以为能拿两份。去重代码一直都在，只是它比的是一套对不上的 id(When both rules are enabled, one item could occupy two cells because the dedup compared ids from two different naming conventions.).

- **复现路径**：9 合 1 表里本来就有 `iron_ingot → iron_block`（9:1）。玩家再学一条「9 个铁锭 → 1 个铁块」的配方，仓库里放 18 个铁锭、0 个铁块，两条路**都**认为可以给出 `iron_block`(A recipe that duplicates an existing 9:1 conversion makes both paths offer the same item.).
- **根因是两套 id 口径**：9 合 1 表的 id 只留 path（`iron_block`），配方本的是全命名空间（`minecraft:iron_block`）。去重写的是 `taken.contains(offer.itemId())` —— `Set<String>` 拿这两种拼法比，**恒为 `false`**，整段去重从落地那天起就是死代码(The dedup compared a bare path against a fully-qualified id, so it never matched.)。
- 🔴 **两处注释合起来把这个 bug 说成了设计**：`Recipe.output` 写着「`minecraft:` 前缀已去掉，与 9 合 1 表的口径一致」（不实，它来自 `Identifier#toString`），`ExchangeOffer.itemId` 写着「两者只在各自内部使用，不互相查表，所以不必统一」（不实，`exchangeOffers()` 天天拿它们互相比）。两处都已订正(Two comments described the opposite of what the code did; both are corrected.).
- **为什么一直没暴露**：语料 1042 个产物里有 **65** 个落在 9 合 1 表内，但 `voidWarehouseExchange` **默认是 `false`** —— 关着的时候 9 合 1 一条都不产，`taken` 本来就是空集，连「恒 `false`」都测不出来(With the exchange rule off by default, the dead comparison was invisible.).
- **改法**：只在**一处**归一（`exchangeOffers()` 是唯一同时看得见两种口径的地方），先补命名空间再走 `VoidWarehouseRecipeBook.itemOf`（`tryParse`，两种都认），翻不出来的原样留着(One normalisation point, done at the only place that sees both conventions.).
- 🔴 **不能拿 `Identifier#withDefaultNamespace` 当兜底**：它只吃裸 path，喂全名会拼成 `minecraft:minecraft:iron_block` 并**当场抛 `IdentifierException`**。第一版就是这么写的，把整组自检炸成日志里一句「自检抛异常」(`withDefaultNamespace` throws on an already-qualified id.).
- **只影响两条规则都开的情况**：只开配方扣料时 `taken` 是空集，滤不掉任何东西 —— 五个版本 `A2StressTest` 的 `UI offers` 与本轮改动前**逐字相同**（738 / 663 / 644 / 644 / 644）(Single-rule setups are unaffected, verified byte-for-byte.).
- 🔴 **`CRAFT_DIRTY_LIMIT = 96` 那条分支补了直接单测**：它没有任何可观测的行为差异，所以「造数据比结果」这条路根本测不出来。判据改成**往私有缓存泼一条错值、看它有没有被纠正**：脏物品恰好 96 种时走增量 → 错值原样留着；97 种时走整批 → 错值被纠正。一对读数把阈值钉死在原地，同时证明那段循环体不是死代码(The threshold branch is now covered by poisoning a private cache entry and watching whether it gets recomputed.).
  - 配套四条防线：填充物按 `craftDeps` 的并集当排除集（不硬编码物品名）、阈值反射读产品常量（不抄第二份）、断言缓存是同一个 map 对象、潜影盒不进填充物（`insert` 会拆盒，脏集合 `size` 会失真）。
- **验证**：`VoidWarehouseCraftSelfCheck` 新增 **N 组 12 条断言**（M 组之后），四版本各 **161 条**。**变异测试**把去重改回旧写法 → `N1 实测 2 格` / `N2 重复：iron_block / minecraft:iron_block` 当场红，改回来 `failures=0`(A mutation test confirms the new assertions bite.).
- **五版本 1,864 条断言零失败**：`CraftSelfCheck` 161×4 + `SystemTest` 209×5 + `A2StressTest` 35×5。完整数据见 `docs/配方本满本并发测试报告.md` §14(1864 assertions green across five versions.).
- ⚠️ **`SystemTest` 的 `sink=` 会变**（9,795,079 → 9,901,959）：`sink` 是非负数之和，去重只会让它**变小**，所以这个变化不可能来自本次修复 —— 是新增的 N 组往测试存档里多学了 3 条配方。它因此不再是跨版本指纹，能比的只有断言(`sink` moved for an unrelated reason and is no longer a cross-version fingerprint.).

- 🔴 **1.0.17 的 26.3 产物误带了两行调试打印**：`VoidWarehouseTrigger` 的 `isVoidWarehouse` 与 `interact` 各留了一条 `System.out.println("[VW-DEBUG] …")`（排查「改名工作台右键打不开」时插的探针）。源码回滚了，**dist 却没重出**，于是发出去的 jar 与 `-sources.jar` 都带着 —— 玩家每次右键手持物品都会往日志里写一行。已按当前源码重出 26.3 两个产物（jar 480,799 → 480,312 B，sources 533,773 → 533,646 B），其余四个版本的产物里从来没有这两行（逐个 `grep -a` 验过）(The shipped 1.0.17 26.3 artifacts carried two stray debug printlns; both are rebuilt from the current source, and a byte-level grep confirms the other four versions never had them.)。
  - **教训**：`grep -c "DEBUG"` 查源码得到 0 **不能**证明产物干净 —— 要先回滚源码、再**重跑 dist**，最后拿 `unzip -p jar …class | grep -a` 咬产物本身（本轮就是这么抓到的）(Checking the source is not enough; grep the artifact.).

### 🧪 Testing / 测试

- **出料漏斗满盒分类物：35 条断言全绿**（2026-11-15，`SpecimenDupeSelfCheck`，真服务端 + 真漏斗 tick）：Z 组 14（不走漏斗，直接打三条分支 + 两条拒绝路径）、A 组 5 tick、B 组 5 tick、C 组反证、D 组空仓库、E 组付不出。**三代口径的读数都留在** `docs/出料漏斗满盒分类物刷物品事故与修复.md` §2：白送版 `checks=23 failures=6`、倒进虚空版 `checks=25 failures=0`、最终版 `checks=35 failures=0`(Three generations of the fix, three sets of readings.)
  - 🔴 **「全绿」本身证明不了什么**：中间那一版把盒内物品改成交纯净空盒，25 条全绿 —— 因为它只咬了「不许白送」。最终每一组同时咬三件事：交出去的**形态**、仓库**扣了什么**、**总数守恒**。
  - 🔴 **变异实测 14 条红**：把 ② 段退回「一律交纯净产物」，`Z1 / Z3 / Z4 / Z6 / Z11 / Z12 / Z13 / Z14 / A2 / A4 / B2 / B5 / E1 / E2` 同时红，其中 `E1 / E2` 抓的是「付不出也照样给了 5 个空盒」。**C 组保持绿** —— 精确匹配那条路没被碰到，反证组是独立的。
  - 🔴 **测具自己差点冤枉被测代码**：两个场地共用同一个全局仓库 ⇒ 两个漏斗每 tick 都在取货，读数像重复扣费；`amountOf(Item)` 只认纯净条目 ⇒ 数满盒恒为 0；`onTick` 跑在漏斗 tick 之前 ⇒ `< N` 会少算最后一 tick。三条都写进文档 §6(Two concurrent hoppers, a wrong counting helper and an off-by-one window — all in the harness, none in the product.)
- 修复后回归 **1,379 条断言零失败**（全部真服务端）：`SpecimenDupeSelfCheck` **35** + `UnstackableOutputSelfCheck` **22** + `FillSelfCheck` **53** + `VoidWarehouseCraftSelfCheck` **224**（以上均 26.3）+ `SystemTest` **209 × 5 版本**（26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11）(1,379 assertions green across five versions after the fix.)
- 算法改造 + 回归修复后的完整验证 **1,322 条断言零失败**（2026-11-13）：26.3 跑满三套 —— `SystemTest` **209**、`CraftSelfCheck` **224**（新增 R 组 7 条）、`FillSelfCheck` **53**；26.2 / 26.1.2 / 26.1.1 / 1.21.11 各 `SystemTest` **209**。扣料语义另由 7,416 行对拍兜底（改前 / 改后 **SHA-256 相同**）(1,322 assertions green after the rewrite and the regression fix.).
  - **离线覆盖审计 104,707 条断言**（独立台 `C:/tmp/audit`，不进仓库）：A 全覆盖 **5,988** 条（每条配方都能在树里找到、挂在正确产物下、槽位往返一致、产物下的配方条数一致）、B 自给自足 **5,686** 条（只给「这条配方一次所需的原料」，`maxCraftable ≥ 1` 且 `plan` 给得出计划；实测 1,164 条给 1 倍就够、72 条要 2 倍 —— 那 72 条是配方内同一个物品出现在多个原料位）、C 必要性 **2,383** 条（把某个叶子原料清 0，必须退到 0 且 `plan` 返回 null）、D 闭包 vs 全树 **9** 条（每个库存分布一条，内部覆盖 1,236 条配方）、E 空库存 **2,472** 条、F 单调 **1,236** 条、G 草稿纸复用 **5** 条（正序 / 逆序 / 随机三遍，与「每条新建」逐条相同）、H 数组闭包 vs 旧 `Map` 闭包 **86,928** 条(A 104,707-assertion offline audit — from "every recipe is in the tree" to "the reusable scratch leaks nothing".).
  - 🔴 **A 组没有一条「丢配方」**：1,236 条配方全部能在 `Tree` 里找到，且树里的配方条数与语料**逐条相等**（反向也查了：不能凭空多出配方）。B 组才是「有原料就能做」那句抱怨的机器版本 —— 它比「界面上看得见」更强：直接问「只给这些原料，最多能做几个」。
  - 🔴 **`CraftSelfCheck` 的条数是 224，不是早前记的 214 / 217**：**条数以服务端打出的 `checks=` 为准**，按旧数算的总数会少算(Counts come from the harness output, not from memory.).
  - 顺序是「先对拍、再审计、最后构建」：对拍台与审计台都只跑 `common/`，几分钟一轮；反过来先构建再发现语义变了，五个版本要重跑一遍(Verify offline first, build second.).
- 配方确认提示 **51 条断言 × 5 版本 = 255 条全绿**（`RecipePromptSelfCheck`，真服务端 + 真 `ServerPlayer`）：接线 5、只问不记 9、按钮契约 10、点击回路 27(Fifty-one assertions per version, all green.)。
  - 🔴 **第一组必须是「mixin 真的挂上了」**：编译通过什么都不能说明 —— `ServerCommonPacketListenerImpl` 懒加载，没有玩家连接就没人碰它，注入失败会**一声不响**。判据是反射到目标类里数方法，而且必须按**名字子串**（Mixin 会把 handler 改名成 `handler$<hash>$carpetAutomending$onCustomClick`，写 `equals` 必假红）(Reflection must match by substring: Mixin prefixes handler names.)。
  - 🔴 **`B2 合成后配方本里没有它`** 是这一整轮的核心断言：改回「合成就记」立刻变红(B2 is the assertion that encodes the whole feature.)。
  - **payload 是从组件里抠出来喂回去的**：把聊天行里那三个按钮的 `ClickEvent.Custom` 摘下、原样交给 `handleClick` —— 客户端发出的就是这个 NBT，喂进去能走通才说明契约是通的(The payloads are harvested from the message component and fed back, exactly what a client sends.)。
  - **先在干净档上跑**：配方本在游戏里没有清空入口，第二次跑时那些「新配方」早就在本里了 ⇒ 提问分支根本不进 ⇒ B 组全红。所以跑之前把 `.dat` 挪走(`install_prompt_selfcheck.py` 负责)(The recipe book has no in-game reset, so the harness moves the save aside first.)。
- 填满背包 **53 条断言 × 5 版本 = 265 条全绿**（`FillSelfCheck`）：容量算法 6、常规模式 8、盒模式 15、菜单入口 6、**盒子材料预留 18**(Fifty-three assertions per version, all green.)。
- 🔴 **E 组（18 条）两个方向都咬**：不只要证「不会白送盒」，还要证「盒子照给」—— 库存刚好一盒容量时一盒都不给、库存宽裕时照给 11 满盒且材料随货扣干净、菜单那盒装 1726 个（为材料留 2 个壳）。只写「没有盒子」的话，把功能整个删掉也能全绿(The group pins both refusal and delivery, so deleting the feature cannot pass.)。
- **第一版 E 组是假绿**：仓库只放壳、不放箱子 ⇒ 旧写法先在「盒子材料不够」退出 ⇒ 「没有盒子」靠错误的理由通过。补上箱子后才有判别力。另外 `fill` 自己的分支守在 `executeCore` 之前，两条守卫互相掩护 —— 所以必须单独用反射直接打 `executeCore(allowBox=true)`（`E17/E18`），否则「按数量取货」这条最初的口子会无声复发。
- **变异实测**：把三处预留退回「不预留」→ `E3 / E5 / E10 / E11 / E13–E18` **10 条同时红**（`实测 1 盒`、`壳 0 / 箱 0`、`盒里装 1726 → -1`），装回去 53/53 全绿。26.3 与 1.21.11 各 53/53；`SystemTest` 209 条零回归(Removing the reservation turns exactly those ten red; both ends of the version range stay green.)。
  - 每条断言同时咬**三样**：背包里真有多少、仓库真扣了多少、有没有多出盒子。只咬一头的话扣账错了照样能绿(Each assertion pins inventory contents, warehouse deduction and box count at once.)。
  - 期望值全是**手算**的物理量：36 格 × 64 = 2304；圆石一盒 27×64 = 1728，36 格 × 1 盒 = 62208。不写「和实现算出来的一样」(All expectations are hand-computed physical quantities.)。
  - 🔴 **`getContainerSize()` 是 43 不是 36**：第一版容量算法直接沿用，实测算出 2752（= 43 × 64）—— 多出来的 7 格是副手、身体盔甲和鞍。这条错会同时污染「填满背包」和「按数量取货」，见上方 Fixed(`getContainerSize()` is 43; the first version computed 2752 instead of 2304.)。
- 🔴 **这两套的条数以服务端打出的 `checks=` 为准，不数源码**：三个按钮共用同一个 `for`、`B0` 有三处，所以静态 `grep -c 'check("'` 数出来是 54 / 36，实测是 **51 / 35**。第一版 CHANGELOG 把分组数写成了「4 / 7 / 10 / 18 / 11」——那是按印象凑的，四组全错还多出一个不存在的组。判据只能跑一遍读日志(Assertion counts come from the harness output, not from grepping the source.)。
- 配方存储与原料兑换的完整覆盖测试 **65 条断言 × 5 版本 = 325 条全绿**（`CraftExchangeAudit`，真服务端、真 `ServerPlayer`、真存档）：持久化 19（含**真落盘**）、兑换数量 18、20 人并发 12、账平 16(A 65-assertion audit over five targets, 325 checks total, all green on a real server.).
- **真落盘**单独成组（P7a–h）：把当前存档标脏 → `saveAndJoin()` → 从游戏自己写出的 `.dat` 读回来，配方文本按序逐条相同、库存逐格相同（26.3 实测 **1,865 条**：磁盘 == 内存）。P1–P6 只证明编解码器跟自己对得上，碰不到目录规则、外层 `data` 包装和写盘时机 —— 而玩家问的是「关服再开服还在不在」(The disk round trip reads back the file the game itself wrote, which the codec-only round trip cannot prove.)。
- 🔴 **「文件在」不等于「这次写的」**：旧档也在。唯一能证明「刚刚真落盘」的是 **mtime**，所以加了一条 `P7c 这个档是刚刚写的（mtime 距今 < 120 秒）`。少了它，在 1.21.11 上读到一份三周前的 48 字节空档也会全绿 —— 第一版就是这么假绿的(The mtime check is what distinguishes a fresh write from a stale file.).
- 两种目录布局都得认：≤1.21.x 是 `world/data/carpet_neko_addition_void_warehouse.dat`，26.x 是 `world/dimensions/minecraft/overworld/data/carpet_neko_addition/void_warehouse.dat`。写死单一路径，1.21.11 必然假红(Both storage layouts must be probed explicitly.).
- **吞材料专项：没有吞。** 判据不是「写死给几个」而是**等价守恒** —— 「仓库少掉的等价 == 玩家拿到的等价」，少了就是吞、多了就是造。199 份计划逐份对平：9 合 1 扫 159 组（11 个可计价族，50 份给出计划）+ 配方扣料扫 160 组（149 份给出计划）。另加两条：**不跨族串味**（取铁锭不动金锭）、**全程无负数**(No item swallowing: 199 plans, each conserving value exactly; no cross-family leakage, no negatives.).
- **`deltas` 是净变化，交付量已经含在里面**：4 铁块 + 5 铁锭取 9 铁锭，`deltas` 只有 `iron_block −1`（拆块 +9 与交付 −9 相抵）。所以「执行后铁锭归零」是**错的**断言 —— 那要求仓库凭空少 9 个。第一版审计就是按「扣库存」的直觉写的，报了 3 条假红(`deltas` is a net change; the delivered amount is already inside it.)。
- 20 人并发四组场景账全平：30 木板各取 1 → 20 个全拿到、**只做 5 次合成**（余量复用，耗 10 木板）；9 木板 → 16 成 / 4 空手 / 剩 1 未被吃；40 木板各取 4 → 80 个；20 木板 → 10 人各 4(Twenty real players, four scenarios, all balanced.).
- 边界五条：取 65 夹到 **64**；63 木板取 64 只做 **16 次**（扣 32、剩 31，给足即停）；8 铁锭取 1 铁块**取不到且原样 8**；`want=0/-1` 不动库存；1 木板取不到。
- 三条**真风险**已量出但未改代码：① 规划与执行若拆到两个 tick，清空库存后执行旧 plan ⇒ 木棍 0→3（**凭空造物**），当前调用图不可达；② 数据结构非线程安全，真 8 线程 × 1,600 次调用异常 **5 ~ 1,284 次**（单线程服务端不触发）；③ `Entry#subtract` 把负值静默夹到 0（审计能抓成活平被抹，但现场无提示）(Three real risks measured but intentionally left alone; see `docs/配方存储与原料兑换覆盖测试.md`.).
- 三次写错期望值的教训已归档：把「取出」当成「扣库存」、把「给足即停」当成「料不够就少给」、把「余量复用」当成「一人一份料」—— 九条假红全是我的语义错，产品行为是对的(Nine false failures were all my own semantic misreadings.).

### 📦 Builds / 构建产物

| MC 版本 | 文件 | 体积 | md5 |
| --- | --- | --- | --- |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.17.jar` | 482,168 B | `2cdb0acb33b81dbb7977830e7106c71a` |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.17-sources.jar` | 537,031 B | `d719fb4de3a17597304707de5601f308` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.17.jar` | 482,320 B | `e19d7611e157f2cc022ef40d5c511d1f` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.17-sources.jar` | 537,346 B | `d6f961a2bf8eae28d1d49599f93c204c` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.17.jar` | 485,582 B | `8ed5b07921650b61872e945e7a251b34` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.17-sources.jar` | 538,356 B | `9c894e06b7b52004e693610ce52a6dfc` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.17.jar` | 485,583 B | `a5d298c46c1d7c9357b1f2cee5067964` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.17-sources.jar` | 538,355 B | `a9faf9e5914242c5be866fb6ffcb4d29` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.17.jar` | 482,671 B | `a5b056bb76ee6f83a7b0b4672739d42a` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.17-sources.jar` | 538,147 B | `317f8880dc1e945bc03f367e73a85999` |

> **本表已按 2026-11-15 20:03 的本地全量构建整表刷新**（五版本逐个 `TARGET_MC_VERSIONS=<ver> ./gradlew :<proj>:build` + 手动 `cp` 到 `dist/`，非 CI 构建）。v1.0.17 **第十六次原地替换**：规则集、配置键、规则名仍然一个字都没动。这一版改的是「出料漏斗拿装满的潜影盒当分类物」的结算口径 —— 盒子**原样交出**，但盒里的实物也要按「物品 + 数据组件」从仓库扣，付不出就整笔拒绝。配方本落盘格式、扣料口径、可兑表内容逐字节不变。主 jar 482,168 / 482,320 / 485,582 / 485,583 / 482,671 B（比上一次发布 +1,638 ~ +1,678 B：新增 `payloadOf` / `takePayload` 与 `Withdrawal` 的实物退还），sources jar 大 2,084 ~ 2,138 B。md5 与之前十五次发布都不同，请按上表核对(This table was refreshed against the local full build of 2026-11-15; the v1.0.17 assets were replaced in place a sixteenth time — a filled shulker box used as a classifier is now handed out verbatim while its contents are charged to the warehouse. Recipe storage format and every decision rule are byte-identical.)。

> ✅ **sources jar 现在含共享层源码**（R2 起）：`common/` 那三份 `.java`（`VoidWarehouseRecipes` / `VoidWarehouseExchange` / `VoidWarehouseSettings`）已经打进去了 —— 此前只打版本树，打开 sources jar 根本找不到真正在跑的那份扣料算法。抽检方式：`unzip -l` 应能看到 `neko/voidwarehouse/VoidWarehouseRecipes.java`(The sources jar now bundles the shared `common/` sources; this gap was closed in R2.)。
> 只需要能跑的 jar：下 `-1.0.17.jar` 那五个，按服务端的 MC 版本选一个。`-sources.jar` 是给开发者的源码包，可不下。

### ⚠️ 升级提示 / Upgrade Notes

- **新增一条 Carpet 规则**（`voidWarehouseDirectPlace`，默认 `false`），规则集变了，所以版本号升到 1.0.17。配置键与已有规则名**均未变**，直接替换 jar 即可；但**配方本的存档格式在本版内变过一次**，见下一条(One new rule; no config-key change — just swap the jar. The recipe-book storage format did change once within this version, see below.)。
- 🔴 **配方本的存档格式变了（同版本号内）**：`learned_recipes`（全部配方拼成一个字符串）→ `learned_recipes_lines`（一条配方一个元素）。老档**升级零操作**，第一次写盘时自动迁移；但**降级不行** —— 新版本写过的档，旧版本读不到配方（它只认旧键，而那键已经没了）。要回退版本请先备份存档。顺带把配方本上限从 **512 条放宽到 2048 条**，撞顶时服务端会 `WARN` 一次(The recipe-book field changed from a joined string to a per-recipe list; upgrades migrate on first write, downgrades lose the recipe book, so back up first. The cap also moves from 512 to 2048.).
- **直放要三条一起开**：服务端 `voidWarehouse` + `voidWarehouseRestock` + `voidWarehouseDirectPlace`，客户端在 `litematica-printer` 配置里也打开 `voidWarehouseDirectPlace`。少一条就退回「补货到背包、手上放」(All three server rules plus the client option are required.)。
- **两条新功能都不新增规则**，所以版本号仍是 1.0.17：配方确认提示挂在 `voidWarehouseCraft` 下（开了就默认「问了才记」），填满背包挂在 `voidWarehouse` 下（服务端不需要额外开关）。**Ctrl+Alt+左键要客户端也更新**到 `neko-voidwarehouse-search` 0.2.0，纯原版客户端点不出这个手势、其余行为一个字节不变(Both features ride existing rules, so the version stays 1.0.17; the fill gesture additionally needs the client mod 0.2.0.).
- **不再需要 Servux**。装了也不会冲突，只是变成「借它的解码器做交叉验证」的次要角色；之前因为它被 Fabric Loader 静默跳过而失效的存档，换 jar 后直接就能用(Servux is no longer required; if present it is only used for cross-checking.)。
- **半砖合并需要配套的打印机版本**：客户端侧要含 [Yur1Ca/litematica-printer#29](https://github.com/Yur1Ca/litematica-printer/pull/29)（2026-11-07 已合并进上游 `master`）。截至本版发布，最新的 `Beta3.0`（2026-11-03）**还不含**这个修复，用它时台阶仍会退回取货；用 `master` 自编或等下一个打印机构建(The slab merge case needs the companion printer fix from PR #29, merged upstream on 2026-11-07 but not yet in `Beta3.0`.)。
- **服务端不再为「Servux 缺席」拒绝结构化直放**，所以以前日志里那句 `inventory:servux-state-channel-missing` 不会再出现；取而代之的是 `capabilities enabled=true stateChannel=true`(The `servux-state-channel-missing` fallback reason is gone.)。
- **v1.0.17 的产物换过十六次**：2026-11-08 换成含 [PR #2](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/pull/2) 的 CI 构建；2026-11-10 换成含「配方本逐条列表」的本地全量构建；同日再换成含「`maxCraftable` 二分」的本地全量构建；同日又换成含「`Model` 去复制（性能）」与「sources jar 含共享层源码」的本地全量构建；同日再换成含「可兑表增量重算」的本地全量构建；同日再换成含「可兑表去重口径修正」的本地全量构建；同日最后换成含「配方本聊天提示」的本地全量构建；2026-11-12 换成含「重复合成不再静默 + 翻译契约」的本地全量构建；同日再换成含「用量接在已记住那行尾」的本地全量构建；2026-11-13 换成含「预编译配方树 + 可复位草稿纸」的本地全量构建；同日再换成含「逐条配方闭包（修掉深层配方少算）」的本地全量构建；同日又重出过一次 26.3 的两个产物（去掉误带的 `[VW-DEBUG]` 调试打印）；2026-11-15 换成含「出料漏斗满盒分类物交纯净产物」的本地全量构建；同日再换成含「满盒原样交出 + 盒内实物计价」的本地全量构建（前一版把盒内物品倒进了虚空）。十六次都**没改版本号**，只是字节变了（其中两次只替换了部分版本的产物，未单列）。已经下过的按上方 md5 表核一下；不一样就是旧的，重下即可(The v1.0.17 assets were replaced in place sixteen times — PR #2, the per-recipe string list, the binary search, the ledger-copy removal, the incremental offer rebuild, the dedup-convention fix, the recipe-book chat notices, the duplicate-notice plus translation-contract fix, the book-usage suffix on the learned line, the precompiled recipe tree, the per-recipe closure fix, the rebuilt 26.3 artifacts without the stray debug printlns, the clean-product extraction fix and its replacement with the verbatim-plus-contents-pricing one — with the version number unchanged.)。

* * *

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
- **入库端暂不支持「拿不可堆叠物当分类物」**：真服务端实测（14 条断言）—— **不带分类物**的入库漏斗照常收这类物品（20 个在同一 tick 进完），卡的**只有**「当分类物」这一种组合（格子物理上只能装 1 个，标本占住就腾不出位置给货，上游每 tick 白跑一轮取放）。三条路线（直取旁路 / 客户端叠加 / 余量归零）的改动点、风险与验收见 `docs/入库漏斗支持不可堆叠物品-路线对照.md`(Hopper intake still cannot *use* unstackable items as classifiers — measured on a real server; plain intake of such items works fine. Three candidate approaches are documented with their exact change points and risks.)

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
#### 仓库终端本身能被当成货物存进仓库（`voidWarehouse`）

把改名成「虚空仓库 / Void Warehouse」的工作台放进仓库，仓库里就多了一把钥匙 —— 它既不是货、也不该占一格分类，还能被当成普通工作台取走去合成(The warehouse terminal — a crafting table renamed 虚空仓库 / Void Warehouse — could be stored like ordinary cargo.)。

- **判定与触发共用同一个谓词**：直接复用 `VoidWarehouseTrigger#isVoidWarehouse(stack)`（工作台 + 有自定义名 + 中文精确「虚空仓库」或英文忽略大小写 `Void Warehouse`）。**能被用来打开仓库的那一件，就是不能入库的那一件**，不存在第二套命名规则会漂移(The refusal reuses the exact predicate that opens the warehouse, so the two can never drift apart.)。
- **闸门放在唯一的共同出口**：`VoidWarehouseData#insert` 开头直接 `return false`。界面存入、漏斗 `drain`、方块掉落物、打印机余料返还四条入库路径全经过这里，一处拦截四处生效(The gate sits in `insert`, the single funnel every intake path shares.)。
- 🔴 **潜影盒是绕过口**：`insert` 遇到非空盒会拆成内容物，把终端装进盒子送来就绕过了主闸门。`unpackShulkerBox` 拆盒前先扫一遍内容物，含终端则**整笔拒收** —— 不拆开、也不退半个盒子，与「装盒前先问 `isBoxable`」是同一种防绕过的思路(A terminal hidden inside a shulker box is refused as a whole before unpacking.)。
- **漏斗快筛同口径**：`isInsertable` 也返回 `false`，免得漏斗每 tick 白跑一轮「以为能收、其实收不进」(The hopper pre-filter uses the same predicate, so it no longer wastes a tick per item.)。
- **界面里给一句人话**：`仓库终端本身不能存进仓库（它是打开仓库的钥匙）` / `The warehouse terminal itself cannot be stored`，走既有的 `reportReject` 分流，与潜影盒、白名单外物品同一套提示路径、同一套配色(The refusal is explained through the existing reject-notice path.)。
- **「打开界面期间」不必单独实现**：三处判定都在 `insert` 这一层，与界面开没开无关 —— 界面里存入、漏斗灌、掉落物吸、打印机退，四种时机撞的是同一道闸门(No special case is needed for "while the menu is open": all four intake timings hit the same gate.)。
- 五版本（26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11）逐处对称改动，**不动存档格式、规则集与网络协议**，直接替换 jar 即可(Symmetric change across all five targets; no save-format, rule-set or protocol change.)

#### 中键取物被记成「余料」、一关仓库界面就被收回（客户端 `litematica-printer`）

中键 pick block 从仓库取到的物品，被客户端的**余料返还账本**记成了一笔「打印耗材」；玩家一关掉容器界面，`tick()` 恢复发货，这一笔就被当成余料退回仓库 —— 手上一空，看起来像「刚拿到的东西凭空没了」。**本修复全在客户端模组侧，本仓库的 jar 无改动**(Middle-click pick-block deliveries were booked as printer leftovers and got swept back into the warehouse the moment the menu closed. The fix is entirely client-side.)

- **根因：三个补货适配器里只有这一个没按来源分流**。`TakeItOutAdapter` / `ChestTrackerAdapter` 都已有 `Source.PICK_BLOCK` 分支，`VoidWarehouseRestockAdapter#status()` 却一到货就 `recordDelivery(...)`（判据只有「回执 OK」，没问来源）—— 同一个中键在三条补货路线上有三种语义(Only this adapter ignored the request source; the other two already branched on it.)
- **为什么恰好是「打开 / 关闭界面」那一刻**：`VoidWarehouseReturnController#tick()` 在 `player.containerMenu != player.inventoryMenu`（有界面开着）时**直接 `return` 不发货**。所以界面一开就不退、一关就退，现象与玩家描述逐字对上(The return controller deliberately never ships while a container is open, which is why the loss showed up exactly when the menu closed.)
- **修法**：`recordDelivery` 改为接收 `request`，`PICK_BLOCK` 只推进基线、不记账(The signature now takes the request; pick-block deliveries only advance the baseline.)
- **基线仍然必须推进**：它记的是「这一批到货了多少」的参照。不推进的话，下一批真打印会把这一批 pick block 的增量一起算成自己的到货量，变成另一笔错账(The baseline must still advance, otherwise the next printing batch would claim this delivery as its own.)
- **语义统一**：中键要的是**玩家自己要用的**东西，不是打印耗材。仓库两条能力（取货 / 返还）都在，只是 pick block 不再进返还账本(All three restock paths now agree that pick-block deliveries are not printing material.)

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
- 仓库终端不可入库的自检 **18 条断言**（`TerminalNotStorableSelfCheck`，真 26.3 服务端）：A 组三种写法（中文 / 英文原名 / 英文小写 + 首尾空格）各配一条对照（普通工作台、带别的名字的工作台、无自定义名的工作台照常入库）；B 组潜影盒绕过（壳里装终端、箱里装终端**整笔拒收**，对照组普通盒子照常拆开入库）；C 组漏斗路径（`drain` 不吞、`isInsertable` 同步返回 `false`）(18 assertions on a real server covering the three spellings with their controls, the shulker-box bypass, and the hopper path.)
- **反证**：把三处判定临时改成 `false &&`，同一套自检 `failures=10` —— A1–A4、B1–B4、C1–C2 全红，而 8 条对照组仍然全绿，证明这些断言咬的确实只有新路径(Negative control: disabling only the new predicate flips exactly 10 assertions while the 8 controls stay green.)
- 顺手记一个**断言口径坑**：`amountOf(Item)` 只认**无组件**的那一格，而三个带不同名字的工作台各自占一格，所以「一笔都没进去」必须用 `amountExact(stack)` 逐笔核对。用 `amountOf` 写的断言在反证时会**该红不红** —— 第一版就是这么写出 3 条空断言的(One measurement trap worth recording: `amountOf` only sees the component-free slot, so the checks had to be rewritten against `amountExact`.)
- 客户端侧的判据用 **3 条单测**咬死（`VoidWarehouseReturnLedgerTest`）：`PICK_BLOCK` 不记账、其余八种来源仍记账、**新增来源必须显式归类**（记账集合写成字面量，将来加一个 `Source` 会立刻翻红）。**反证**把判定改回「无条件记账」→ `3 tests completed, 2 failed`，而「其余来源仍记账」那条**仍然是绿的** —— 两组断言各咬一个方向，改错任一边都会被抓到。26.3 全量单测 **678 条全绿**（121 个测试类）(The client-side rule is pinned by 3 unit tests plus a roll-back counter-experiment; the full 678-test suite stays green.)

### 📦 Builds / 构建产物

| MC 版本 | 文件 | 体积 | md5 |
| --- | --- | --- | --- |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.16.jar` | 392,433 B | `41c0147589b4d72e81c169a8c9a5fe04` |
| 26.3 | `carpet-Neko-Addition-mc26.3-1.0.16-sources.jar` | 412,772 B | `16296c23c4f1f06bcc2b13f06d26717e` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.16.jar` | 392,544 B | `0ac7caf7684b6aec5fd02c057b3d9760` |
| 26.2 | `carpet-Neko-Addition-mc26.2-1.0.16-sources.jar` | 413,188 B | `6aa4bb150c3e621293e96eb8afa96448` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.16.jar` | 395,813 B | `c42da56d949e609066aec5abb293ad7e` |
| 26.1.2 | `carpet-Neko-Addition-mc26.1.2-1.0.16-sources.jar` | 414,220 B | `eeeb04ea294bb4883e56416012a6e936` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.16.jar` | 395,815 B | `fb1b79cc4095a1a6cf85c08c4aac3f8c` |
| 26.1.1 | `carpet-Neko-Addition-mc26.1.1-1.0.16-sources.jar` | 414,219 B | `298a951c6dbd30c70c3b853f24f4d24e` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.16.jar` | 393,837 B | `4ba692810a41c62fe0940351426be8f1` |
| 1.21.11 | `carpet-Neko-Addition-mc1.21.11-1.0.16-sources.jar` | 414,355 B | `12d738d7c55639f1071f51d9d1e34204` |

> 只需要能跑的 jar：下 `-1.0.16.jar` 那五个，按服务端的 MC 版本选一个。`-sources.jar` 是给开发者的源码包，可不下。

### ⚠️ 升级提示 / Upgrade Notes

- mixin 从 13 条增到 15 条（新增 `ServerLevelAddEntityMixin`、`ServerPlayerGameModeDestroyMixin`），规则名、配置键与存档格式均未变，**直接替换 jar 即可**(Two new mixins; no config or data-format change, just swap the jar.)。
- 新规则默认关闭：`/carpet voidWarehouseBlockDrops true`，且 `voidWarehouse` 也要开着。
- **同版本号原地更新**：这条规则的聊天提示在 1.0.16 首次发布之后改过两次（先加提示，再去掉「已直接入库」、改成报仓库现有数量），加上盒模式刷盒子修复、这次的不可堆叠物出料、以及 2026-11-02 把搜索通道铺到 26.2 / 26.1.2 / 26.1.1 / 1.21.11（修复搜索框不显示，Issue #1），1.0.16 的 jar 已经变过几次 —— 以文件名旁的 md5 为准（本表已按最新 CI 构建整表刷新）(This release was updated in place several times, most recently shipping the search channel to 26.2 / 26.1.2 / 26.1.1 / 1.21.11 on 2026-11-02; compare the md5 next to the file name.)。
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
