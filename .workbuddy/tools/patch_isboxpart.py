#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「盒子材料不参与装盒」（isBoxPart）同步到另外四个版本。

用法：
    python patch_isboxpart.py

三份改动：
  1. `VoidWarehouseWithdraw.java` —— 五份本来就逐字节相同 ⇒ 二进制复制；
  2. `VoidWarehouseShulker.java` —— 新增 isBoxPart（三种变体：26.2/26.3、26.1.x、1.21.11）；
  3. `VoidWarehouseMenu.java` —— 取一盒那个分支的判据加上 isBoxPart。

🔴 只做**定点替换**，按目标文件自己的换行写：26.2 / 26.1.x / 1.21.11 的 Menu 是 CRCRLF，
   整份重写会让 diff 炸成几千行。所以这里用字节级 replace，替换串里只含「同一行的内容」，
   新增文本的换行一律取目标文件自己的 EOL。
"""
import io
import os
import shutil
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

SRC = "modern/26.3/src/main/java/neko/voidwarehouse"
SHULKER_TARGETS = [
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
]
WITHDRAW_TARGETS = [
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseWithdraw.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseWithdraw.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseWithdraw.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseWithdraw.java",
]
MENU_TARGETS = [
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java",
]

BOXABLE_ANCHOR = (
    b"    public static boolean isBoxable(ItemStack template) {\r\n"
    b"        return template != null && !template.isEmpty() && !isShulkerBox(template);\r\n"
    b"    }\r\n"
)
BOXABLE_ANCHOR_LF = BOXABLE_ANCHOR.replace(b"\r\n", b"\n")

MENU_OLD = b"        if (!VoidWarehouseShulker.isBoxable(template)) {"
MENU_NEW = (b"        if (!VoidWarehouseShulker.isBoxable(template)"
            b" || VoidWarehouseShulker.isBoxPart(template)) {")


def eol_of(raw: bytes) -> bytes:
    """目标文件自己用的换行：优先识别 26.x 那批 CRCRLF。"""
    if b"\r\r\n" in raw:
        return b"\r\r\n"
    if b"\r\n" in raw:
        return b"\r\n"
    return b"\n"


def read(path):
    with open(path, "rb") as f:
        return f.read()


def write(path, raw):
    with open(path, "wb") as f:
        f.write(raw)


def insert_box_part(path):
    raw = read(path)
    if b"isBoxPart" in raw:
        print("  = 已有 isBoxPart，跳过 " + path)
        return True
    anchor = BOXABLE_ANCHOR if BOXABLE_ANCHOR in raw else BOXABLE_ANCHOR_LF
    if anchor not in raw:
        print("  ✗ 找不到 isBoxable 锚点 " + path)
        return False
    if raw.count(anchor) != 1:
        print("  ✗ isBoxable 锚点不唯一（%d 处）%s" % (raw.count(anchor), path))
        return False
    eol = eol_of(raw)
    block_lines = [
        "",
        "    /**",
        "     * 这种物品本身是不是<b>做盒子的材料</b>（潜影壳 / 箱子）。",
        "     *",
        "     * <h2>为什么这两个也要单独挡一道</h2>",
        "     * <p>装盒的材料账（{@link VoidWarehouseData#planBoxMaterials}）是从<b>仓库</b>扣",
        "     * {@link #SHELL_COST} 壳 + {@link #CHEST_COST} 箱，而两个取货口都是",
        "     * <b>先把货取走、再扣材料</b>（顺序不能反：扣材料会移除别的格子、让下标前移）。</p>",
        "     *",
        "     * <p>于是「目标格本身就是潜影壳 / 箱子」时出现了<b>材料与货共源</b>：取走整整一盒容量",
        "     * （潜影壳 1728 个）之后，仓库里那 {@code 2} 个壳恰好归零，",
        "     * {@code consumeBoxMaterials} 再也扣不到 —— 它只会留一条 WARN，而这盒子是实打实",
        "     * 交到玩家手上的。算一笔账：仓库 1728 壳 + 1 箱、0 空盒，盒模式点壳那一格 → 拿到",
        "     * 「装着 1728 壳的盒子」，送回仓库变回 1728 壳 + <b>1 个空盒</b>。净效果 =",
        "     * <b>1 个箱子（8 木板）换 1 个潜影盒</b>，可无限循环；而原版要 2 壳 + 1 箱。</p>",
        "     *",
        "     * <p>所以这两样<b>一律不装盒</b>：盒模式下点壳 / 箱的格子按散货给，与「潜影盒不套盒」",
        "     * 是同一类处理 —— {@link #pack} 绕过了原版 {@code canFitInsideContainerItems}，",
        "     * 凡是「盒子自己的材料」都不能从「正要装进盒子的那批货」里出。</p>",
        "     */",
        "    public static boolean isBoxPart(ItemStack template) {",
        "        if (template == null || template.isEmpty()) {",
        "            return false;",
        "        }",
        "        return template.is(Items.SHULKER_SHELL) || template.is(Items.CHEST);",
        "    }",
        "",
    ]
    block = eol.join(line.encode("utf-8") for line in block_lines)
    write(path, raw.replace(anchor, anchor + block, 1))
    print("  ✓ isBoxPart 已插入 " + path)
    return True


def patch_menu(path):
    raw = read(path)
    if MENU_NEW in raw:
        print("  = 已有 isBoxPart 判据，跳过 " + path)
        return True
    if raw.count(MENU_OLD) != 1:
        print("  ✗ 菜单锚点不唯一（%d 处）%s" % (raw.count(MENU_OLD), path))
        return False
    write(path, raw.replace(MENU_OLD, MENU_NEW, 1))
    print("  ✓ 菜单判据已加 isBoxPart " + path)
    return True


def main():
    ok = True
    src_withdraw = os.path.join(ROOT, SRC, "VoidWarehouseWithdraw.java")
    print("[1] Withdraw（五份逐字节相同 ⇒ 二进制复制）")
    for rel in WITHDRAW_TARGETS:
        shutil.copyfile(src_withdraw, os.path.join(ROOT, rel))
        print("  ✓ " + rel)

    print("[2] Shulker（三种变体，各自定点插入）")
    for rel in SHULKER_TARGETS:
        ok = insert_box_part(os.path.join(ROOT, rel)) and ok

    print("[3] Menu（取一盒那个分支的判据）")
    for rel in MENU_TARGETS:
        ok = patch_menu(os.path.join(ROOT, rel)) and ok

    if not ok:
        print("失败：有文件没打上补丁")
        return 1
    print("全部完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
