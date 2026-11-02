#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""让「仓库终端」（改名为 虚空仓库 / void warehouse 的工作台）不再能存进虚空仓库。

用法：
    python .workbuddy/tools/patch_terminal_not_storable.py            # 五个版本全打
    python .workbuddy/tools/patch_terminal_not_storable.py 26.3       # 只打指定版本前缀

为什么要脚本而不是 cp：
  · 26.3 的行尾是干净的 CRLF；
  · 26.2 / 26.1.2 / 26.1.1 / 1.21.11 的行尾是历史遗留的 **CR CR LF**，
    而且行数本来就不同（26.3 多 11 行）。
  ⇒ 直接 cp 会把旧版本整文件换成 CRLF（每行都算改动），只能逐版本按原样还原行尾。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

VERSIONS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

# ---------------------------------------------------------------- 改动清单
# 每项 = (文件名, 说明, old, new)。old/new 一律用 LF，读写时按文件原样还原。

DATA_INSERT = (
    "VoidWarehouseData.java",
    "insert 拦终端",
    """    public boolean insert(ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return false;
        }
        if (VoidWarehouseShulker.isShulkerBox(incoming)) {
""",
    """    public boolean insert(ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return false;
        }
        // <b>仓库终端本身不是货物</b>：它是打开仓库的钥匙（见 {@link VoidWarehouseTrigger}）。
        // 收进来的话，仓库里就多了一格「取出来又能当终端用」的东西 —— 而它本身只是
        // 改过名的工作台，存进来没有任何收益，只会让玩家困惑「为什么能取出来却存不回去」。
        // 所以从<b>所有</b>入库路径一起挡掉：菜单存入、漏斗进货、方块掉落、打印机返还。
        if (VoidWarehouseTrigger.isVoidWarehouse(incoming)) {
            return false;
        }
        if (VoidWarehouseShulker.isShulkerBox(incoming)) {
""",
)

DATA_SHULKER = (
    "VoidWarehouseData.java",
    "拆盒前检查内容物里有没有终端",
    """        List<ItemStack> contents = VoidWarehouseShulker.contentsOf(box);
        for (ItemStack inside : contents) {
""",
    """        List<ItemStack> contents = VoidWarehouseShulker.contentsOf(box);
        // 盒子里装着仓库终端 → 整笔拒收：拆开就等于「绕过 insert 把终端收进仓库」。
        // 拆盒是「全成或全不成」的操作，所以必须在写任何一格之前判完 —— 判在循环里
        // 就只能把已经拆出来的几样留下，反而更难解释。
        for (ItemStack inside : contents) {
            if (VoidWarehouseTrigger.isVoidWarehouse(inside)) {
                return false;
            }
        }
        for (ItemStack inside : contents) {
""",
)

DATA_INSERTABLE = (
    "VoidWarehouseData.java",
    "isInsertable 加终端判定（漏斗快筛同口径）",
    """    public static boolean isInsertable(ItemStack stack) {
        return VoidWarehouseShulker.isShulkerBox(stack) || isStorable(stack);
    }
""",
    """    public static boolean isInsertable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // 仓库终端不收（与 {@link #insert} 同一口径）。放在这里是为了让漏斗的 drain
        // 少白跑一趟 insert，也让「上方容器整格搬」的循环不把终端吸进来又原样吐回去。
        if (VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return false;
        }
        return VoidWarehouseShulker.isShulkerBox(stack) || isStorable(stack);
    }
""",
)

MENU_REJECT = (
    "VoidWarehouseMenu.java",
    "reportReject 加终端分支",
    """        Component text;
        if (VoidWarehouseShulker.isShulkerBox(stack)) {
            text = this.msgBoxRejected();
        } else if (!VoidWarehouseData.isStorable(stack)) {
""",
    """        Component text;
        if (VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            text = this.msgTerminal();
        } else if (VoidWarehouseShulker.isShulkerBox(stack)) {
            text = this.msgBoxRejected();
        } else if (!VoidWarehouseData.isStorable(stack)) {
""",
)

MENU_MSG = (
    "VoidWarehouseMenu.java",
    "新增 msgTerminal 文案",
    """    private Component msgRejected() {
        return VoidWarehouseText.info(this.en,
                "带额外数据的物品（耐久 / 附魔 / 改名）拒收",
                "Rejected: items with extra data (durability / enchantments / custom names)");
    }
""",
    """    private Component msgRejected() {
        return VoidWarehouseText.info(this.en,
                "带额外数据的物品（耐久 / 附魔 / 改名）拒收",
                "Rejected: items with extra data (durability / enchantments / custom names)");
    }

    /**
     * 仓库终端被拒收。
     *
     * <p>单独一条文案，而不是复用「仓库已满」：那一句会让玩家以为清一清背包就能存进去，
     * 于是反复尝试。终端是<b>永远</b>存不进去的 —— 它是钥匙，不是货。</p>
     */
    private Component msgTerminal() {
        return VoidWarehouseText.info(this.en,
                "仓库终端本身不能存进仓库（它是打开仓库的钥匙）",
                "The warehouse terminal itself cannot be stored");
    }
""",
)

PATCHES = [DATA_INSERT, DATA_SHULKER, DATA_INSERTABLE, MENU_REJECT, MENU_MSG]


def detect_style(raw):
    """返回 (换行风格, 纯 LF 文本)。CR CR LF 的也要认出来。"""
    if b"\r\r\n" in raw:
        style = "\r\r\n"
    elif b"\r\n" in raw:
        style = "\r\n"
    else:
        style = "\n"
    # 吃掉全部 CR：既把 \r\r\n 压成 \n，也把干净的 \r\n 压成 \n
    return style, raw.decode("utf-8").replace("\r", "")


def apply_one(text, old, new):
    """返回 (新文本, 状态)。状态 ∈ applied / skipped / missing

    🔴 <b>必须先判 new 再判 old</b>。对「追加型」补丁（new = old + 新增一段）来说，
    改完之后 old <b>仍然在文本里</b> —— 先判 old 就会再追加一遍，得到两份重复的方法
    （本脚本第一次跑就踩了：26.3 出现两个 msgTerminal，编译直接报「已在类中定义」）。
    """
    if new in text:
        return text, "skipped"
    if old in text:
        return text.replace(old, new, 1), "applied"
    return text, "missing"


def patch_version(version):
    touched = 0
    problems = []
    for filename in ("VoidWarehouseData.java", "VoidWarehouseMenu.java"):
        path = os.path.join(ROOT, version, PKG, filename)
        if not os.path.isfile(path):
            problems.append("%s: 文件不存在 %s" % (version, filename))
            continue
        with io.open(path, "rb") as f:
            raw = f.read()
        style, text = detect_style(raw)
        before = text
        changed_here = []
        for name, desc, old, new in PATCHES:
            if name != filename:
                continue
            text, state = apply_one(text, old, new)
            if state == "applied":
                changed_here.append(desc)
            elif state == "missing":
                problems.append("%s/%s: 锚点没找到 —— %s" % (version, filename, desc))
        if text != before:
            with io.open(path, "wb") as f:
                f.write(text.replace("\n", style).encode("utf-8"))
            touched += 1
            print("  %s: %s（行尾 %s）改了 %d 处：%s"
                  % (version, filename, repr(style), len(changed_here), "；".join(changed_here)))
        else:
            print("  %s: %s 无需改动" % (version, filename))
    return touched, problems


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    targets = [v for v in VERSIONS if only is None or v.endswith(only)]
    if not targets:
        raise SystemExit("!! 没有匹配的版本：%s" % only)
    all_problems = []
    for version in targets:
        print("== %s" % version)
        _, problems = patch_version(version)
        all_problems.extend(problems)
    if all_problems:
        print("\n!! 有 %d 个问题：" % len(all_problems))
        for p in all_problems:
            print("   -", p)
        raise SystemExit(1)
    print("\n全部完成，无异常")


if __name__ == "__main__":
    main()
