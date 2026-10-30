#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「虚拟仓库配方扣料」从 26.3 同步到其余四个版本。

<h2>同步了什么</h2>
<ul>
  <li><b>common/</b>（{@code VoidWarehouseRecipes} / {@code VoidWarehouseSettings}）
      本来就被所有子工程 {@code implementation project(':common')} 引用，<b>不需要同步</b>；</li>
  <li>新文件 {@code VoidWarehouseRecipeBook.java}、{@code mixin/CraftResultMixin.java}；</li>
  <li>{@code VoidWarehouseData.java}（唯一被改的既有 java 文件）；</li>
  <li>{@code carpet_neko_addition.mixins.json}（加一行）、两份 lang（各加两个键）。</li>
</ul>

<h2>为什么这次可以整份覆盖</h2>
<p>与「9 合 1 兑换」那次不同：本轮改的东西<b>没有落在任何一个版本差异点上</b>
（{@code VoidWarehouseNav} 的玻璃板、{@code placeItemInBackInventory} 的 {@code Prediction}、
{@code ClickType} 这些一个都没碰）。核对过 {@code VoidWarehouseData.java} 与 26.3 的差异：
除了已知的两行（{@code Identifier} 的 import 与 {@code SavedDataType} 的 id 写法），
其余逐字节相同 —— 所以「先从 26.3 整份拷、再按已知差异就地改回去」比逐 hunk 重放更稳，
因为它顺带把「以后有人手工改过某一版」这种漂移也一起纠正了。</p>

<h2>两个必须处理的差异</h2>
<ol>
  <li><b>{@code VoidWarehouseData.java} / 1.21.11</b>：没有 {@code import net.minecraft.resources.Identifier}，
      {@code SavedDataType} 的 id 用字符串 {@code "carpet_neko_addition_void_warehouse"}；</li>
  <li><b>{@code VoidWarehouseRecipeBook.java} / 1.21.11</b>：{@code Recipe#assemble} 是两参
      （{@code assemble(T, HolderLookup.Provider)}），26.x 才收成一参。</li>
</ol>
<p>其余全部逐字节相同（换行符除外：26.3 是 LF，其余四个是 CRLF）。</p>

<h2>判据</h2>
<p>写完逐文件比对：四个 modern 版本必须与 26.3 <b>逐字节相同</b>（LF 归一后比），
1.21.11 必须等于「26.3 + 那两处已知改写」。任何一个不符就报错退出 ——
「我改过了」不能靠嘴说，要靠比对。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

CANON = "modern/26.3"
PKG = "src/main/java/neko/voidwarehouse"
RES = "src/main/resources"

MODERN_TARGETS = ["modern/26.2", "modern/26.1.2", "modern/26.1.1"]
LEGACY = "versions/1.21.11"
ALL_TARGETS = MODERN_TARGETS + [LEGACY]

# 逐字节相同（26.3 为准）的 java 文件
SAME_FILES = [
    "VoidWarehouseData.java",
    "VoidWarehouseRecipeBook.java",
    "mixin/CraftResultMixin.java",
]

# ---- 1.21.11 的已知改写：(26.3 原文, 1.21.11 应改成) ----
LEGACY_DATA_FIXES = [
    ("import net.minecraft.resources.Identifier;\n", ""),
    ('Identifier.fromNamespaceAndPath("carpet_neko_addition", "void_warehouse")',
     '"carpet_neko_addition_void_warehouse"'),
]

LEGACY_RECIPE_BOOK_FIXES = [
    ("ItemStack result = recipe.assemble(input);",
     "ItemStack result = recipe.assemble(input, level.registryAccess());"),
]

MIXIN_ANCHOR = '    "ChatInputMixin"\n'
MIXIN_REPLACED = '    "ChatInputMixin",\n    "CraftResultMixin"\n'

LANG_ANCHOR = '  "carpet.rule.concretePowderToGlass.name"'

LANG_ZH = (
    '  "carpet.rule.voidWarehouseCraft.name": "虚空仓库配方扣料",\n'
    '  "carpet.rule.voidWarehouseCraft.desc": "允许在成品缺货时按「玩家亲手合成过的那条配方」扣原料，'
    '直接取出成品：合成过一次活塞，之后就能用仓库里的 4 个圆石、3 个木板、1 个铁锭、1 个红石直接取活塞。'
    '只做一层（原料必须仓库里现成），配方本全服共用，合成台上的产物与原料一经取出即记录。依赖「虚空仓库」规则",\n'
)
LANG_EN = (
    '  "carpet.rule.voidWarehouseCraft.name": "Void Warehouse Recipe Crafting",\n'
    '  "carpet.rule.voidWarehouseCraft.desc": "Lets an out-of-stock item be handed over by spending '
    'the ingredients of the recipe the player has actually crafted before: craft one piston by hand '
    'and from then on 4 cobblestone, 3 planks, 1 iron ingot and 1 redstone from the warehouse hand over '
    'a piston. Single-layer only (ingredients must already be in stock), the recipe book is shared '
    'server-wide, and a recipe is recorded the moment the result is taken from a crafting grid. '
    'Requires the Void Warehouse rule",\n'
)


def read_lf(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write_back(path, text, crlf):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def canon(rel):
    text, _ = read_lf(os.path.join(ROOT, CANON, rel))
    return text


def apply_fixes(text, fixes, target, label):
    for old, new in fixes:
        if old not in text:
            raise SystemExit("!! %s / %s：找不到应当改写的那一段：%r" % (target, label, old))
        text = text.replace(old, new, 1)
    return text


def sync_java(target, legacy):
    for rel in SAME_FILES:
        body = canon(os.path.join(PKG, rel))
        if legacy:
            if rel == "VoidWarehouseData.java":
                body = apply_fixes(body, LEGACY_DATA_FIXES, target, rel)
            elif rel == "VoidWarehouseRecipeBook.java":
                body = apply_fixes(body, LEGACY_RECIPE_BOOK_FIXES, target, rel)
        path = os.path.join(ROOT, target, PKG, rel)
        _, crlf = (True, True) if target != CANON else (False, False)
        # 目标文件已存在时沿用它的换行；不存在时按版本惯例（26.3 LF、其余 CRLF）
        crlf = os.path.exists(path) and read_lf(path)[1] or target != CANON
        write_back(path, body, crlf)
        print("%s: java %s（%s）" % (target, rel, "CRLF" if crlf else "LF"))


def sync_mixins(target):
    path = os.path.join(ROOT, target, RES, "carpet_neko_addition.mixins.json")
    text, crlf = read_lf(path)
    if "CraftResultMixin" in text:
        print("%s: mixins.json 已包含 CraftResultMixin" % target)
        return
    if MIXIN_ANCHOR not in text:
        raise SystemExit("!! %s 的 mixins.json 找不到 ChatInputMixin 那一行" % target)
    write_back(path, text.replace(MIXIN_ANCHOR, MIXIN_REPLACED, 1), crlf)
    print("%s: mixins.json 已加入 CraftResultMixin" % target)


def sync_lang(target, name, block):
    path = os.path.join(ROOT, target, RES, "assets/carpet_neko_addition/lang", name)
    text, crlf = read_lf(path)
    if "voidWarehouseCraft.name" in text:
        print("%s: %s 已包含配方扣料的键" % (target, name))
        return
    if LANG_ANCHOR not in text:
        raise SystemExit("!! %s / %s 找不到锚点 concretePowderToGlass.name" % (target, name))
    write_back(path, text.replace(LANG_ANCHOR, block + LANG_ANCHOR, 1), crlf)
    print("%s: %s 已插入配方扣料的键" % (target, name))


def verify(target):
    """写完就验：modern 四版必须与 26.3 逐字节相同；1.21.11 必须是「26.3 + 已知改写」"""
    legacy = target == LEGACY
    bad = []
    for rel in SAME_FILES:
        expect = canon(os.path.join(PKG, rel))
        if legacy:
            if rel == "VoidWarehouseData.java":
                expect = apply_fixes(expect, LEGACY_DATA_FIXES, target, rel)
            elif rel == "VoidWarehouseRecipeBook.java":
                expect = apply_fixes(expect, LEGACY_RECIPE_BOOK_FIXES, target, rel)
        actual, _ = read_lf(os.path.join(ROOT, target, PKG, rel))
        if actual != expect:
            bad.append(rel)
    if bad:
        raise SystemExit("!! %s 校验失败：%s 与预期不一致" % (target, ", ".join(bad)))
    print("%s: 校验通过（%d 个 java 文件逐字节一致）" % (target, len(SAME_FILES)))


def main():
    targets = sys.argv[1:] or ALL_TARGETS
    for target in targets:
        print("=== %s" % target)
        sync_java(target, target == LEGACY)
        sync_mixins(target)
        sync_lang(target, "zh_cn.json", LANG_ZH)
        sync_lang(target, "en_us.json", LANG_EN)
        verify(target)


if __name__ == "__main__":
    main()
