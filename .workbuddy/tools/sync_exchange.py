#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「虚空仓库 9 合 1 兑换」从 26.3 同步到其余四个版本。

<h2>同步了什么</h2>
<ul>
  <li><b>common/</b>（{@code VoidWarehouseExchange} / {@code VoidWarehouseSettings}）
      本来就被所有子工程 {@code implementation project(':common')} 引用，<b>不需要同步</b>；</li>
  <li>新文件 {@code VoidWarehouseExchangeBootstrap.java}（26.2 / 26.1.1 / 26.1.2 / 1.21.11 各一份）；</li>
  <li>既有文件的改动，用 26.3 工作区的 {@code git diff} <b>逐 hunk 重放</b>。</li>
</ul>

<h2>为什么是「重放 hunk」而不是「整份覆盖」</h2>
<p>这四个版本与 26.3 的同一份文件<b>不是逐字节相同</b>：</p>
<ul>
  <li>26.1.1 / 26.1.2 / 1.21.11 的 {@code VoidWarehouseNav} 用 {@code Items.RED_STAINED_GLASS_PANE}
      （26.2 起才统一收进 {@code ColorCollection}）；</li>
  <li>26.2 / 26.1.1 / 26.1.2 的 {@code VoidWarehouseMenu} 的
      {@code placeItemBackInInventory} 没有 {@code Prediction} 参数；</li>
  <li>1.21.11 的 {@code VoidWarehouseMenu} 更进一步：{@code ClickType} 而不是
      {@code ContainerInput}；</li>
  <li>1.21.11 的 {@code VoidWarehouseData} 没有 {@code Identifier} import，
      {@code SavedDataType} 的 id 用的是字符串。</li>
</ul>
<p>整份覆盖会把这些版本差异全部抹掉，所以只重放「本次真正改了的那几段」。</p>

<h2>两个踩过的坑</h2>
<ol>
  <li><b>换行</b>：26.3 是 LF，其余版本是 CRLF。{@code patch} 直接打会全部匹配不上，
      所以先把目标读成 LF、重放完再按原样写回 CRLF。</li>
  <li><b>「已改过」的判据</b>：<b>不能</b>只看 {@code new in text} —— 本次改动里同一句
      {@code this.consume(entry, actual);} 在 5 个方法里都出现，只看 new 会把「另一处已经改过」
      误判成「这一处也改过了」，于是漏掉一整处（真踩到了）。
      正确判据是「{@code old} 还在 → 改；{@code old} 没了且 {@code new} 在 → 跳过；
      两个都没有 → 报错」。</li>
  <li>而且改完之后<b>整份文件对比</b>一次：版本差异为零的文件必须与 26.3 逐字节相同
      （行尾统一成 LF 再比），这才叫证据。</li>
</ol>
"""

import io
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

CANON = "modern/26.3"
PKG = "src/main/java/neko/voidwarehouse"
MODERN_TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2"]
LEGACY = "versions/1.21.11"
ALL_TARGETS = MODERN_TARGETS + [LEGACY]

# 直接整份复制的新文件（四个版本内容一致）
NEW_FILES = ["VoidWarehouseExchangeBootstrap.java"]

# 用「重放 26.3 的 diff hunk」同步的既有文件
HUNK_FILES = [
    "VoidWarehouseText.java",
    "VoidWarehousePageContainer.java",
    "VoidWarehouseNav.java",
    "VoidWarehouseMenu.java",
    "VoidWarehouseWithdraw.java",
    "VoidWarehouseExtractor.java",
    "VoidWarehouseExtension.java",
    "restock/PrinterTakeService.java",
    "VoidWarehouseData.java",
]

# 相对 26.3 逐字节相同（同步完必须一模一样，LF 归一后比）的文件
IDENTICAL_FILES = [
    "VoidWarehouseText.java",
    "VoidWarehousePageContainer.java",
    "VoidWarehouseWithdraw.java",
    "VoidWarehouseExtractor.java",
    "restock/PrinterTakeService.java",
    "VoidWarehouseData.java",
    "VoidWarehouseExchangeBootstrap.java",
]

# 带版本差异的文件：同步完必须同时满足「新功能在」+「版本差异还在」
VERSION_SPECIFIC = {
    "VoidWarehouseNav.java": {
        "required": ["int offerCount, boolean shulkerMode, boolean amountSort",
                     "其中 \" + offerCount + \" 种可用合成兑换取出"],
        "per_version": {
            "modern/26.2": ["Items.STAINED_GLASS_PANE.red()"],
            "modern/26.1.1": ["Items.RED_STAINED_GLASS_PANE"],
            "modern/26.1.2": ["Items.RED_STAINED_GLASS_PANE"],
            "versions/1.21.11": ["Items.RED_STAINED_GLASS_PANE"],
        },
    },
    "VoidWarehouseMenu.java": {
        "required": ["private void withdrawShulkerBox(VoidWarehouseData.DisplaySlot target",
                     "private void withdrawFrom(VoidWarehouseData.DisplaySlot target",
                     "this.data.applyExchange(exchange)"],
        "per_version": {
            "modern/26.2": ["placeItemBackInInventory(rest);"],
            "modern/26.1.1": ["placeItemBackInInventory(rest);"],
            "modern/26.1.2": ["placeItemBackInInventory(rest);"],
            "versions/1.21.11": ["placeItemBackInInventory(rest);", "ClickType input"],
        },
    },
    "VoidWarehouseExtension.java": {
        "required": ["VoidWarehouseExchangeBootstrap.tick(server)",
                     "VoidWarehouseExchangeBootstrap.invalidate()"],
        "per_version": {},
    },
}

# 1.21.11 的 VoidWarehouseData 与 26.3 的差异（两处，逐字列出，改完必须断言真的改到了）
LEGACY_DATA_FIXUPS = [
    ("import net.minecraft.resources.Identifier;\n", ""),
    ('Identifier.fromNamespaceAndPath("carpet_neko_addition", "void_warehouse")',
     '"carpet_neko_addition_void_warehouse"'),
]

failures = []


def fail(msg):
    failures.append(msg)
    print("  !! " + msg)


def read_lf(path):
    """读成 LF 文本，同时记下原本是不是 CRLF"""
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    crlf = "\r\n" in text
    return text.replace("\r\n", "\n"), crlf


def write_back(path, text, crlf):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def git_diff_hunks(rel_path):
    """把 26.3 某个文件的 git diff 拆成 [(old_block, new_block), ...]（LF，无行首标记）"""
    diff = subprocess.run(
        ["git", "diff", "-U3", "--", rel_path],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
    ).stdout
    hunks = []
    old_lines = None
    new_lines = None
    for line in diff.split("\n"):
        if line.startswith("@@"):
            if old_lines is not None:
                hunks.append(("\n".join(old_lines), "\n".join(new_lines)))
            old_lines, new_lines = [], []
            continue
        if old_lines is None:
            continue
        if line.startswith("\\"):      # "\ No newline at end of file"
            continue
        if line.startswith("+"):
            new_lines.append(line[1:])
        elif line.startswith("-"):
            old_lines.append(line[1:])
        elif line.startswith(" "):
            old_lines.append(line[1:])
            new_lines.append(line[1:])
        else:
            # 到 diff 的收尾了（空行 / 下一个 "diff --git"）
            break
    if old_lines is not None:
        hunks.append(("\n".join(old_lines), "\n".join(new_lines)))
    return hunks


def apply_hunks(target_rel, rel_in_pkg, hunks):
    path = os.path.join(ROOT, target_rel, PKG, rel_in_pkg)
    if not os.path.isfile(path):
        fail("找不到目标文件 " + path)
        return
    text, crlf = read_lf(path)
    applied = 0
    skipped = 0
    for old, new in hunks:
        if old in text:
            text = text.replace(old, new, 1)
            applied += 1
        elif new in text:
            skipped += 1
        else:
            fail("%s 的 hunk 既找不到 old 也找不到 new：\n----- old -----\n%s\n---------------"
                 % (target_rel + "/" + rel_in_pkg, old[:400]))
    write_back(path, text, crlf)
    print("  ok  %-18s %-32s 重放 %d，跳过 %d，共 %d"
          % (target_rel, rel_in_pkg, applied, skipped, len(hunks)))


def copy_with_fixups(target_rel, rel_in_pkg, fixups):
    src = os.path.join(ROOT, CANON, PKG, rel_in_pkg)
    if not os.path.isfile(src):
        fail("找不到规范文件 " + src)
        return
    body, _ = read_lf(src)
    for old, new in fixups:
        if old not in body:
            fail("版本补丁在规范文件里就找不到：%r" % old[:80])
            continue
        body = body.replace(old, new)
    dst = os.path.join(ROOT, target_rel, PKG, rel_in_pkg)
    write_back(dst, body, True)     # 这四个版本原来都是 CRLF
    print("  ok  %-18s %-32s 整份复制 + %d 处版本补丁"
          % (target_rel, rel_in_pkg, len(fixups)))


def verify_identical(target_rel, rel_in_pkg):
    canon, _ = read_lf(os.path.join(ROOT, CANON, PKG, rel_in_pkg))
    target, _ = read_lf(os.path.join(ROOT, target_rel, PKG, rel_in_pkg))
    if target != canon:
        fail("%s 与 26.3 的 %s 仍然不一致（应逐字节相同）" % (target_rel, rel_in_pkg))


def verify_version_specific(target_rel, rel_in_pkg, spec):
    target, _ = read_lf(os.path.join(ROOT, target_rel, PKG, rel_in_pkg))
    for needle in spec["required"]:
        if needle not in target:
            fail("%s 的 %s 缺少新功能：%s" % (target_rel, rel_in_pkg, needle))
    for needle in spec["per_version"].get(target_rel, []):
        if needle not in target:
            fail("%s 的 %s 丢掉了版本差异：%s" % (target_rel, rel_in_pkg, needle))


def main():
    print("[1/4] 新文件（整份复制）")
    for name in NEW_FILES:
        for target in ALL_TARGETS:
            copy_with_fixups(target, name, [])

    print("[2/4] 既有文件（重放 26.3 的 diff hunk）")
    for name in HUNK_FILES:
        hunks = git_diff_hunks(os.path.join(CANON, PKG, name))
        if not hunks:
            fail("26.3 的 %s 没有 diff —— 是不是已经提交过了？" % name)
            continue
        print("  %s: %d 个 hunk" % (name, len(hunks)))
        for target in MODERN_TARGETS:
            apply_hunks(target, name, hunks)
        if name == "VoidWarehouseData.java":
            # 1.21.11 的 Data 有两处版本差异会挡住 hunk 匹配，改用「整份复制 + 版本补丁」
            copy_with_fixups(LEGACY, name, LEGACY_DATA_FIXUPS)
        else:
            apply_hunks(LEGACY, name, hunks)

    print("[3/4] 逐字节比对（版本差异为零的文件）")
    for target in MODERN_TARGETS:
        for name in IDENTICAL_FILES:
            verify_identical(target, name)
            print("  ok  %-18s %-32s 与 26.3 逐字节相同" % (target, name))
    # 1.21.11 的 Data 只差那两处已知版本差异，单独验
    legacy_data, _ = read_lf(os.path.join(ROOT, LEGACY, PKG, "VoidWarehouseData.java"))
    canon_data, _ = read_lf(os.path.join(ROOT, CANON, PKG, "VoidWarehouseData.java"))
    for old, new in LEGACY_DATA_FIXUPS:
        canon_data = canon_data.replace(old, new)
    if legacy_data != canon_data:
        fail("1.21.11 的 VoidWarehouseData 与「26.3 + 两处版本补丁」不一致")
    else:
        print("  ok  %-18s %-32s 与「26.3 + 2 处版本补丁」逐字节相同" % (LEGACY, "VoidWarehouseData.java"))

    print("[4/4] 带版本差异的文件：新功能在 + 版本差异还在")
    for name, spec in VERSION_SPECIFIC.items():
        for target in ALL_TARGETS:
            verify_version_specific(target, name, spec)
            print("  ok  %-18s %s" % (target, name))

    print()
    if failures:
        print("失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("全部同步完成，断言全过。")


if __name__ == "__main__":
    main()
