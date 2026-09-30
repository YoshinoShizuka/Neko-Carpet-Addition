# -*- coding: utf-8 -*-
"""
验证 autoMending 的「浮点越界」假设（纯标准库，用 struct 精确模拟 Java float32）。

背景：AutoMending 当前保证 spent <= budget，其中
    budget = (int) Math.floor(player.experienceProgress * (float) getXpNeededForNextLevel())
    spent  = actual * budget / repair        (整数除法, <= budget)
    player.giveExperiencePoints(-spent)

数学上 progress - spent/needed >= 0，看似安全。但 Java 里
    experienceProgress += (float)(-spent) / (float)needed;
是 **float32** 运算，且「预算」那一步的 float32 乘法可能向上舍入，
使 floor 结果比真实的 p*n 大 1 —— 于是扣完 progress 变成 -1e-7 这种极小负数，
直接踩进 Player#giveExperiencePoints 的负值回弹循环（等级 -1、进度条弹到 ~100%）。

用法：python xp_float_edge.py
"""
import struct

_PACK_F = struct.Struct("<f")
_PACK_I = struct.Struct("<I")


def f32(x: float) -> float:
    """把 Python double 舍入成 float32（等价于 Java 的 (float) 转换结果）"""
    return _PACK_F.unpack(_PACK_F.pack(x))[0]


def _bits(x: float) -> int:
    return _PACK_I.unpack(_PACK_F.pack(x))[0]


def _from_bits(b: int) -> float:
    return _PACK_F.unpack(_PACK_I.pack(b & 0xFFFFFFFF))[0]


def next_up(x: float) -> float:
    """float32 意义上 x 的下一个更大值"""
    b = _bits(x)
    if x == 0.0:
        return _from_bits(1)
    return _from_bits(b + 1) if x > 0 else _from_bits(b - 1)


def next_down(x: float) -> float:
    b = _bits(x)
    if x == 0.0:
        return _from_bits(0x80000001)
    return _from_bits(b - 1) if x > 0 else _from_bits(b + 1)


def needed(level: int) -> int:
    """复刻 Player#getXpNeededForNextLevel（26.3 字节码里的三段常量 7/2, 37/5, 112/9）"""
    if level <= 15:
        return 2 * level + 7
    if level <= 30:
        return 5 * level - 38
    return 9 * level - 158


def probe(p: float, level: int, n: int):
    """返回 (budget, newp)；newp < 0 即为越界（会触发负值回弹）"""
    p = f32(p)
    budget = int(f32(p * f32(n)) // 1)          # Java: (int)Math.floor(p * (float)n)
    if budget <= 0:
        return 0, p
    # 复刻 giveExperiencePoints 的 progress += (float)(-spent) / (float)needed
    newp = f32(p - f32(f32(budget) / f32(n)))
    return budget, newp


LEVELS = list(range(0, 61)) + [80, 100, 200, 500]


def scan_points(points, level, n):
    out = []
    for p in points:
        p = f32(p)
        if not (0.0 <= p < 1.0):
            continue
        b, np_ = probe(p, level, n)
        if b > 0 and np_ < 0.0:
            out.append((float(p), b, float(np_)))
    return out


print("=" * 78)
print("扫描 1：progress 落在 k/n 网格 + 各自 ±1 ulp 邻域（实机最常见形态）")
print("=" * 78)
total, hits = 0, []
for level in LEVELS:
    n = needed(level)
    if n <= 0:
        continue
    cand = set()
    for j in range(n + 1):
        p0 = f32(j / n)
        cand.add(p0)
        cand.add(next_up(p0))
        cand.add(next_down(p0))
    pts = sorted(cand)
    total += len(pts)
    hits += [(level, n) + h for h in scan_points(pts, level, n)]

print("扫描点 %d 个，越界 %d 个（占比 %.4f%%）"
      % (total, len(hits), len(hits) / total * 100 if total else 0))
for h in hits[:25]:
    print("  level=%3d needed=%3d  p=%r  budget=%3d  -> newp=%r" % h)


print()
print("=" * 78)
print("扫描 2：随机 progress（模拟「获得经验 → 自动修补」长期累积后的任意浮点值）")
print("=" * 78)
try:
    import random
    rnd = random.Random(20260930)
    have_rnd = True
except Exception:
    have_rnd = False

total2, hits2, sample_bad = 0, 0, []
if have_rnd:
    for level in LEVELS:
        n = needed(level)
        if n <= 0:
            continue
        arr = [f32(rnd.random()) for _ in range(20000)]
        total2 += len(arr)
        got = scan_points(arr, level, n)
        hits2 += len(got)
        if len(sample_bad) < 15:
            sample_bad += [(level, n) + g for g in got[:3]]
print("扫描点 %d 个，越界 %d 个（占比 %.4f%%）"
      % (total2, hits2, (hits2 / total2 * 100) if total2 else 0))
for h in sample_bad[:15]:
    print("  level=%3d needed=%3d  p=%r  budget=%3d  -> newp=%r" % h)


print()
print("=" * 78)
print("扫描 3：「进度条快空」时的越界（剩 1~3 点经验，实机滑行常见）")
print("=" * 78)
total3, hits3 = 0, 0
for level in LEVELS:
    n = needed(level)
    if n <= 0:
        continue
    for k in range(1, 4):
        p0 = f32(f32(k) / f32(n))
        pts = [p0, next_up(p0), next_down(p0)]
        total3 += len(pts)
        got = scan_points(pts, level, n)
        hits3 += len(got)
        for g in got:
            print("  level=%3d needed=%3d 剩 %d 点经验  p=%r  budget=%3d  -> newp=%r"
                  % (level, n, k, g[0], g[1], g[2]))
print("-> 越界 %d / %d" % (hits3, total3))
