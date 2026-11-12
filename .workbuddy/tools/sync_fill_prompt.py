#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 26.3 定稿的两件事同步到另外四个版本。

    python sync_fill_prompt.py             # 同步全部
    python sync_fill_prompt.py check       # 只查锚点，不写文件

两件事：
  ① 配方确认提示（VoidWarehouseRecipePrompt + CustomClickMixin + 文案/数据层/Mixin 接线）
  ② Ctrl+Alt+左键 填满背包（fill 包 + VoidWarehouseWithdraw#fill + 菜单入口）

分工：
  · 五个版本在 HEAD 上<b>逐字节相同</b>的文件 → 直接<b>二进制复制</b>（Notice / Text /
    Withdraw / CraftResultMixin / 两个新类 + fill 包）；
  · HEAD 上就有差异的三个文件（Data / Menu / AutoMendingMod）→ 只做<b>定点插入</b>，
    绝不整份复制（那会把 1.21.11 的 `carpet_neko_addition_void_warehouse` 之类改回 26.3 的样子）；
  · mixins.json 只加一行；
  · FillNetwork 在 1.21.11 要把 `serverboundPlay()` 换成 `playC2S()`（fabric-api 改名）。

🔴 换行是最容易踩的一脚：`VoidWarehouseData` / `VoidWarehouseMenu` / `Nav` / `PageContainer`
这几份在 26.2 / 26.1.x / 1.21.11 里是 **CRCRLF**（`\\r\\r\\n`，早先某个同步脚本留下的），
而且**提交进仓库的就是这样**。整份重写它们会让 diff 炸成三千行，所以这里
「按目标文件自己的换行风格插入」，一个原有字节都不动。
"""
import io, os, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

SRC = "modern/26.3/src/main"
TARGETS = ["modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

VERBATIM = [
    "java/neko/voidwarehouse/VoidWarehouseRecipeNotice.java",
    "java/neko/voidwarehouse/VoidWarehouseText.java",
    "java/neko/voidwarehouse/VoidWarehouseWithdraw.java",
    "java/neko/voidwarehouse/mixin/CraftResultMixin.java",
    "java/neko/voidwarehouse/VoidWarehouseRecipePrompt.java",
    "java/neko/voidwarehouse/mixin/CustomClickMixin.java",
    "java/neko/voidwarehouse/fill/FillRequestPayload.java",
]
FILL_NETWORK = "java/neko/voidwarehouse/fill/FillNetwork.java"

DATA_KNOWS = '''    /**
     * 这条配方是不是<b>已经在</b>配方本里（只查，不改任何东西）。
     *
     * <p>「合成后要不要问一句」必须靠它判断，而<b>不能</b>先调 {@code learnWithResult}
     * 再读返回值 —— 那时候已经学进去了，问也晚了。三种情况靠它分流：
     * 已记过（走「早就记过」那条提示）、已满（走「满了」）、其余才提问。</p>
     */
    public boolean knows(VoidWarehouseRecipes.Recipe recipe) {
        return recipe != null && this.recipeTexts.contains(recipe.recipeId());
    }

'''

DATA_BOXES = '''    /**
     * 现有材料最多能凑出几个盒子（<b>空盒 + 用壳/箱现做的</b>），上限 {@code limit}。
     *
     * <p>给「Ctrl+Alt+左键 填满背包」用：那条路要先把「能装几盒」算出来再决定取多少，
     * 而不是像「按数量取货」那样先定数量再验材料 —— 后者在材料不够时会整单取消，
     * 但「填满」没有「玩家要了多少」这个前提，取消是不合适的（见
     * {@code VoidWarehouseWithdraw#fill}）。</p>
     *
     * <p>算法与 {@code planBoxMaterials} 必须一致：先吃空盒，不够的每盒 2 壳 + 1 箱。
     * 所以这里的可现做数 = {@code min(壳 / 2, 箱 / 1)}，与那边逐项乘回去等价。</p>
     */
    public long maxBoxesUpTo(long limit) {
        if (limit <= 0) {
            return 0L;
        }
        BigInteger empty = this.emptyBoxCount();
        BigInteger craftable = this.amountOf(Items.SHULKER_SHELL)
                .divide(BigInteger.valueOf(VoidWarehouseShulker.SHELL_COST))
                .min(this.amountOf(Items.CHEST)
                        .divide(BigInteger.valueOf(VoidWarehouseShulker.CHEST_COST)));
        return empty.add(craftable).min(BigInteger.valueOf(limit)).longValue();
    }

'''

MENU_FILL = '''    /**
     * Ctrl + Alt + 左键（由客户端识别后走 {@code fill_request} 通道发来）：把这一格的物品
     * <b>取出到背包放不下为止</b>。逻辑本身在 {@code VoidWarehouseWithdraw#fill}，
     * 这里只负责三件事：<b>校验 slotId</b>、<b>解析这一格是什么</b>、<b>取完刷新给所有人</b>。
     *
     * <h2>为什么校验放在菜单里</h2>
     * <p>{@code slotId} 是客户端说的，<b>必须当成不可信输入</b>。而「哪些 slotId 是仓库内容格」
     * 只有菜单自己知道（导航行从 {@code VoidWarehouseData#NAV_START} 开始、
     * 后面还有玩家背包格）。放在这里就与 {@code clicked} 用<b>同一套边界</b>，
     * 不会出现「点击能走的格子，这条通道能走到别的格子」这种错位。</p>
     *
     * <h2>为什么取完要 {@code syncAllViewers}</h2>
     * <p>仓库的每个查看者都共享同一份库存 —— 一个人取走 1728 个圆石，
     * 别人界面上的数量必须立刻跟着变。这与翻页/排序/取货是同一条收尾规矩。</p>
     */
    public void fillToBackpack(ServerPlayer player, int slotId) {
        // ① 只认第 1..5 行的内容格。负值、导航行、玩家背包格一律不动。
        if (slotId < 0 || slotId >= VoidWarehouseData.NAV_START) {
            return;
        }
        // ② 这一格现在到底是什么，只解析一次（数量排序 / 可兑条目都会让它随时间变）
        VoidWarehouseData.DisplaySlot target = this.slotAt(slotId);
        if (target.isEmpty()) {
            return;
        }
        // ③ 模式由<b>服务端</b>自己读（跟着这个玩家的界面走），不听客户端的
        VoidWarehouseWithdraw.fill(player, target.template(), this.pageContainer.shulkerMode());
        this.syncAllViewers(player);
    }

'''

MOD_IMPORT = "import neko.voidwarehouse.fill.FillNetwork;"
MOD_IMPORT_PROMPT = "import neko.voidwarehouse.VoidWarehouseRecipePrompt;"

MOD_INIT = '''
        // ⑤ 一键填满背包（Ctrl+Alt+左键）：客户端识别手势、服务端只收一个 slotId。
        // 有副作用（扣仓库、动背包），50ms 限流；纯原版客户端永远不发这条包。
        FillNetwork.init();'''

MOD_INIT_PROMPT = """
        // ⑥ 配方确认提示的「本局开关」要跟着登录走：不接下线钩子，`[总是记住]` 就变成
        //    「永远不再问」，而玩家没有任何入口能改回来（没有指令、没有按钮）。
        VoidWarehouseRecipePrompt.init();"""

MIXIN_LINE = '    "CustomClickMixin",'


def eol_of(text):
    """目标文件自己的换行风格（优先识别 CRCRLF —— 那几份文件确实是这样的）"""
    if "\r\r\n" in text:
        return "\r\r\n"
    if "\r\n" in text:
        return "\r\n"
    return "\n"


def insert_before(text, anchor, block, tag, where):
    if tag in text:
        return text, "已存在"
    eol = eol_of(text)
    for v in (eol, "\n", "\r\n"):
        a = anchor.replace("\n", v)
        if a in text:
            return text.replace(a, block.replace("\n", v) + a, 1), "已插入"
    raise AssertionError(where + " 的锚点不在：" + anchor.split("\n")[0][:40])


def insert_after_line(text, line, block, tag, where):
    if tag in text:
        return text, "已存在"
    for v in (eol_of(text), "\n", "\r\n"):
        l = line.replace("\n", v)
        if l in text:
            idx = text.index(l) + len(l)
            return text[:idx] + block.replace("\n", v) + text[idx:], "已插入"
    raise AssertionError(where + " 的行不在：" + line[:40])


def main():
    check_only = len(sys.argv) > 1 and sys.argv[1] == "check"
    for ver in TARGETS:
        print("== " + ver + " ==")
        # ① 二进制复制（一个字节都不改，换行跟着源文件）
        for rel in VERBATIM:
            src = os.path.join(SRC, rel)
            dst = os.path.join(ver, "src/main", rel)
            if not check_only:
                d = os.path.dirname(dst)
                if d and not os.path.isdir(d):
                    os.makedirs(d)
                shutil.copyfile(src, dst)
            print("   [copy] " + rel)

        # ② FillNetwork：1.21.11 换 fabric-api 的注册表名
        src = os.path.join(SRC, FILL_NETWORK)
        dst = os.path.join(ver, "src/main", FILL_NETWORK)
        with io.open(src, "r", encoding="utf-8", newline="") as f:
            body = f.read()
        if ver == "versions/1.21.11":
            assert "serverboundPlay()" in body
            eol = eol_of(body)
            body = body.replace("serverboundPlay()", "playC2S()")
            body = body.replace("26.x（6.3.x）叫 {@code playC2S()}",
                                "26.x（6.3.x）叫 {@code serverboundPlay()}（本文件是 1.21.11 的写法）")
            print("   [copy] " + FILL_NETWORK + "（serverboundPlay → playC2S）")
        else:
            print("   [copy] " + FILL_NETWORK)
        if not check_only:
            d = os.path.dirname(dst)
            if d and not os.path.isdir(d):
                os.makedirs(d)
            with io.open(dst, "w", encoding="utf-8", newline="") as f:
                f.write(body)

        # ③ Data
        p = os.path.join(ver, "src/main/java/neko/voidwarehouse/VoidWarehouseData.java")
        with io.open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        t, a = insert_before(t, "    /**\n     * 同一个产物已经记下的配方，", DATA_KNOWS,
                             "public boolean knows(", "Data")
        t, b = insert_before(t, "    /**\n     * 这些材料的库存够不够。", DATA_BOXES,
                             "public long maxBoxesUpTo(", "Data")
        print("   [data] knows=" + a + " maxBoxesUpTo=" + b)
        if not check_only:
            with io.open(p, "w", encoding="utf-8", newline="") as f:
                f.write(t)

        # ④ Menu
        p = os.path.join(ver, "src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java")
        with io.open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        t, c = insert_before(t, "    /**\n     * 翻页。页码是<b>每个玩家各自</b>的", MENU_FILL,
                             "public void fillToBackpack(", "Menu")
        print("   [menu] fillToBackpack=" + c)
        if not check_only:
            with io.open(p, "w", encoding="utf-8", newline="") as f:
                f.write(t)

        # ⑤ AutoMendingMod
        p = os.path.join(ver, "src/main/java/neko/automending/AutoMendingMod.java")
        with io.open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        t, d = insert_after_line(t, "import neko.voidwarehouse.VoidWarehouseExtension;",
                                 "\n" + MOD_IMPORT, MOD_IMPORT, "Mod")
        t, e = insert_after_line(t, "SearchNetwork.init();", MOD_INIT, "FillNetwork.init();", "Mod")
        t, d2 = insert_after_line(t, "import neko.voidwarehouse.VoidWarehouseExtension;",
                                  "\n" + MOD_IMPORT_PROMPT, MOD_IMPORT_PROMPT, "Mod")
        t, e2 = insert_after_line(t, "FillNetwork.init();", MOD_INIT_PROMPT,
                                  "VoidWarehouseRecipePrompt.init();", "Mod")
        print("   [mod] import=" + d + " init=" + e + " promptImport=" + d2 + " promptInit=" + e2)
        if not check_only:
            with io.open(p, "w", encoding="utf-8", newline="") as f:
                f.write(t)

        # ⑥ mixins.json
        p = os.path.join(ver, "src/main/resources/carpet_neko_addition.mixins.json")
        with io.open(p, "r", encoding="utf-8", newline="") as f:
            t = f.read()
        t, g = insert_after_line(t, '    "CraftResultMixin",', "\n" + MIXIN_LINE, MIXIN_LINE,
                                 "mixins.json")
        print("   [mixin] " + g)
        if not check_only:
            with io.open(p, "w", encoding="utf-8", newline="") as f:
                f.write(t)


if __name__ == "__main__":
    main()
