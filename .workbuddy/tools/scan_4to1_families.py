#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 minecraft-merged.jar 的原版配方全集里核对 4:1 原料族。

只做一件事：把「N 个同一种原料 -> 1 个成品」的单原料配方抽出来，
然后回答两个问题：
  1. 给定的 16 条候选边，在原版里到底存不存在、比例是不是严格 4:1；
  2. 每条边的反方向（1 拆 N）在不在，决定 combineRecipe / splitRecipe 怎么填。

用法：
  python scan_4to1_families.py <merged.jar> [--all]
    不带 --all 只打印候选表；带 --all 额外打印全部 4:1 单原料配方。
"""
import json
import re
import sys
import zipfile
from collections import defaultdict

# 本轮要核对的 16 条候选（small, big）—— 必须逐条被原版配方证实
CANDIDATES = [
    ("string", "white_wool"),
    ("quartz", "quartz_block"),
    ("snowball", "snow_block"),
    ("clay_ball", "clay"),
    ("amethyst_shard", "amethyst_block"),
    ("rabbit_hide", "leather"),
    ("sand", "sandstone"),
    ("red_sand", "red_sandstone"),
    ("brick", "bricks"),
    ("nether_brick", "nether_bricks"),
    ("resin_brick", "resin_bricks"),
    ("glowstone_dust", "glowstone"),
    ("magma_cream", "magma_block"),
    ("honeycomb", "honeycomb_block"),
    ("pointed_dripstone", "dripstone_block"),
    ("sulfur_spike", "sulfur"),
]


def norm(item):
    """去掉命名空间，统一成裸 id。"""
    if not isinstance(item, str):
        return None
    return item.split(":")[-1]


def single_ingredient(recipe):
    """把一条配方折成 (原料id, 原料总数, 成品id, 成品数)。

    只认「所有格子都是同一种物品」的配方；混料返回 None。
    """
    rtype = recipe.get("type", "")
    counts = defaultdict(int)

    if rtype == "minecraft:crafting_shaped":
        grid = recipe.get("pattern") or []
        keys = recipe.get("key") or {}
        for row in grid:
            for ch in row:
                if ch == " ":
                    continue
                if ch not in keys:
                    return None
                spec = keys[ch]
                # 1.21+ 的 key 值可以直接是数组（多选一）
                if isinstance(spec, list):
                    for s in spec:
                        iid = norm(s.get("item") if isinstance(s, dict) else s)
                        if iid:
                            counts[iid] += 1
                elif isinstance(spec, dict):
                    iid = norm(spec.get("item"))
                    if iid:
                        counts[iid] += 1
                else:
                    iid = norm(spec)
                    if iid:
                        counts[iid] += 1
    elif rtype == "minecraft:crafting_shapeless":
        for ing in recipe.get("ingredients") or []:
            # ⚠️ 元素既可能是字符串、对象，也可能是数组 —— 只当字符串处理会漏判
            if isinstance(ing, list):
                for s in ing:
                    iid = norm(s.get("item") if isinstance(s, dict) else s)
                    if iid:
                        counts[iid] += 1
            elif isinstance(ing, dict):
                iid = norm(ing.get("item"))
                if iid:
                    counts[iid] += 1
            else:
                iid = norm(ing)
                if iid:
                    counts[iid] += 1
    else:
        return None

    if len(counts) != 1:
        return None
    (ingredient, total), = counts.items()

    result = recipe.get("result") or {}
    if isinstance(result, list):
        if len(result) != 1:
            return None
        result = result[0]
    rid = norm(result.get("id") or result.get("item"))
    rcount = int(result.get("count", 1))
    if not rid:
        return None
    return ingredient, total, rid, rcount


def load(jar):
    """返回 (forward, backward)。

    forward[(small, big)] = [(count, recipe_id), ...]   N small -> 1 big
    backward[(big, small)] = [(count, recipe_id), ...]  1 big -> N small
    """
    forward = defaultdict(list)
    backward = defaultdict(list)
    with zipfile.ZipFile(jar) as z:
        names = [n for n in z.namelist()
                 if n.startswith("data/minecraft/recipe") and n.endswith(".json")]
        for name in names:
            rid = name.rsplit("/", 1)[-1][:-5]
            try:
                recipe = json.loads(z.read(name).decode("utf-8"))
            except Exception:
                continue
            parsed = single_ingredient(recipe)
            if not parsed:
                continue
            ingredient, total, rid_out, rcount = parsed
            if ingredient == rid_out:
                continue
            if rcount == 1 and total >= 2:
                forward[(ingredient, rid_out)].append((total, rid))
            elif total == 1 and rcount >= 2:
                backward[(rid_out, ingredient)].append((rcount, rid))
    return forward, backward, len(names)


def main():
    jar = sys.argv[1]
    show_all = "--all" in sys.argv
    forward, backward, nfiles = load(jar)
    print(f"### {jar}")
    print(f"    配方文件数 = {nfiles}，单原料「向上」配方 = {len(forward)} 条，"
          f"单原料「向下」配方 = {len(backward)} 条\n")

    if show_all:
        print("=== 全部 4:1 单原料配方（N=4，成品数 1） ===")
        rows = sorted([(s, b, c, r) for (s, b), lst in forward.items()
                       for (c, r) in lst if c == 4])
        for s, b, c, r in rows:
            print(f"    {s:24s} -> {b:24s} {c}:1   {r}")
        print()

    print("=== 16 条候选逐条核对 ===")
    ok = 0
    for small, big in CANDIDATES:
        fw = forward.get((small, big), [])
        bw = backward.get((big, small), [])
        four = [r for c, r in fw if c == 4]
        status = "OK " if four else "MISS"
        if four:
            ok += 1
        print(f"  [{status}] {small:20s} -> {big:20s} "
              f"forward={[(c, r) for c, r in fw]}  reverse={[(c, r) for c, r in bw]}")
        if four and len(fw) > 1:
            print(f"         ⚠️ 同一对物品还有别的比例：{fw}")
    print(f"\n  候选核实：{ok}/{len(CANDIDATES)} 条在原版里存在且（至少一条）为 4:1")


if __name__ == "__main__":
    main()
