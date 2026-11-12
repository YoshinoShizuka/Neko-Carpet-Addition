# -*- coding: utf-8 -*-
"""把本轮两条功能 + 一条修复 + 测试记录写进 CHANGELOG 的 1.0.17 段（插在该段开头）。"""
import io

P = "CHANGELOG.md"

ADDED = '''#### 配方存入前的聊天确认（`voidWarehouseCraft`）

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

'''

FIXED = '''#### 取货时不再把物品放进副手 / 盔甲槽

「按数量取货」与「填满背包」放产物时用的是 `Inventory#getContainerSize()`，而 26.3 那个值是 **43**（36 格 + 副手 + 身体盔甲 + 鞍）—— 背包塞满时物品会落到副手、甚至盔甲格里。现在统一按 `Inventory.INVENTORY_SIZE`（36）算(Withdrawals used the 43-slot container size and could place items into the offhand or armour slots; only the 36 player slots are used now.)。

'''

TESTING = '''- 配方确认提示 **50 条断言 × 5 版本 = 250 条全绿**（`RecipePromptSelfCheck`，真服务端 + 真 `ServerPlayer`）：mixin 注入 4、只问不记 7、按钮契约 10、点击回路 18、容量与越界 11(Fifty assertions per version, all green.)。
  - 🔴 **第一组必须是「mixin 真的挂上了」**：编译通过什么都不能说明 —— `ServerCommonPacketListenerImpl` 懒加载，没有玩家连接就没人碰它，注入失败会**一声不响**。判据是反射到目标类里数方法，而且必须按**名字子串**（Mixin 会把 handler 改名成 `handler$<hash>$carpetAutomending$onCustomClick`，写 `equals` 必假红）(Reflection must match by substring: Mixin prefixes handler names.)。
  - 🔴 **`B2 合成后配方本里没有它`** 是这一整轮的核心断言：改回「合成就记」立刻变红(B2 is the assertion that encodes the whole feature.)。
  - **payload 是从组件里抠出来喂回去的**：把聊天行里那三个按钮的 `ClickEvent.Custom` 摘下、原样交给 `handleClick` —— 客户端发出的就是这个 NBT，喂进去能走通才说明契约是通的(The payloads are harvested from the message component and fed back, exactly what a client sends.)。
  - **先在干净档上跑**：配方本在游戏里没有清空入口，第二次跑时那些「新配方」早就在本里了 ⇒ 提问分支根本不进 ⇒ B 组全红。所以跑之前把 `.dat` 挪走(`install_prompt_selfcheck.py` 负责)(The recipe book has no in-game reset, so the harness moves the save aside first.)。
- 填满背包 **35 条断言 × 5 版本 = 175 条全绿**（`FillSelfCheck`）：容量算法 6、常规模式 8、盒模式 14、菜单入口 6(Thirty-five assertions per version, all green.)。
  - 每条断言同时咬**三样**：背包里真有多少、仓库真扣了多少、有没有多出盒子。只咬一头的话扣账错了照样能绿(Each assertion pins inventory contents, warehouse deduction and box count at once.)。
  - 期望值全是**手算**的物理量：36 格 × 64 = 2304；圆石一盒 27×64 = 1728，36 格 × 1 盒 = 62208。不写「和实现算出来的一样」(All expectations are hand-computed physical quantities.)。
  - 🔴 **`getContainerSize()` 是 43 不是 36**：第一版容量算法直接沿用，实测算出 2752（= 43 × 64）—— 多出来的 7 格是副手、身体盔甲和鞍。这条错会同时污染「填满背包」和「按数量取货」，见上方 Fixed(`getContainerSize()` is 43; the first version computed 2752 instead of 2304.)。
'''

raw = io.open(P, encoding="utf-8", newline="").read()
crlf = "\r\n" in raw
t = raw.replace("\r\n", "\n")

for header, block, tag in [
    ("### ✨ Added & Improved / 新增与优化\n\n", ADDED, "配方存入前的聊天确认"),
    ("### 🐛 Fixed / 修复\n\n", FIXED, "取货时不再把物品放进副手"),
    ("### 🧪 Testing / 测试\n\n", TESTING, "RecipePromptSelfCheck"),
]:
    assert header in t, "锚点不在：" + header
    if tag in t:
        print("已存在，跳过：" + tag)
        continue
    # 只插在 1.0.17 那一段（文件里就是第一个出现的位置）
    t = t.replace(header, header + block, 1)
    print("已插入：" + tag)

io.open(P, "w", encoding="utf-8", newline="").write(t.replace("\n", "\r\n") if crlf else t)
print("写回完成 crlf=", crlf)
