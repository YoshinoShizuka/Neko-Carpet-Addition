# -*- coding: utf-8 -*-
"""
对比两版「预算」算法在 float32 边界上的安全性。

现状（有 bug）：
    int budget = (int) Math.floor(player.experienceProgress * (float) needed);
    ← 右边整个是 float32 运算。p*needed 的真实值若落在整数下方半个 ulp 内，
      float32 乘法会向上舍入到那个整数，floor 取到比真实值大 1 的 budget，
      扣完 progress 变成 -3e-8 → 触发 Player#giveExperiencePoints 的负值回弹
      → 等级 -1、进度条弹到 ~100%。

修复（本脚本验证）：
    int budget = (int) Math.floor((double) player.experienceProgress * needed);
    ← p 是 float32，转 double 无损；needed 是 int，转 double 无损；
      乘积 p*needed < 2^37，double(53 位有效数字) 可精确表示 → floor 精确
      → budget <= 真实 p*needed，恒成立 → 扣完 progress >= 0。

用法：python xp_float_fix.py
"""
import struct
import random

_PACK_F = struct.Struct("<f")
_PACK_I = struct.Struct("<I")


def f32(x):
    return _PACK_F.unpack(_PACK_F.pack(x))[0]


def _bits(x):
    return _PACK_I.unpack(_PACK_F.pack(x))[0]


def _from_bits(b):
    return _PACK_F.unpack(_PACK_I.pack(b & 0xFFFFFFFF))[0]


def next_up(x):
    b = _bits(x)
    return _from_bits(1) if x == 0.0 else (_from_bits(b + 1) if x > 0 else _from_bits(b - 1))


def next_down(x):
    b = _bits(x)
    return _from_bits(0x80000001) if x == 0.0 else (_from_bits(b - 1) if x > 0 else _from_bits(b + 1))


def needed(level):
    if level <= 15:
        return 2 * level + 7
    if level <= 30:
        return 5 * level - 38
    return 9 * level - 158


def give_new_progress(p, spent, n):
    """复刻 giveExperiencePoints 的 progress += (float)(-spent)/(float)needed"""
    return f32(p - f32(f32(spent) / f32(n)))


def probe_old(p, n):
    """现状：(float)p * (float)n —— 全程 float32"""
    p = f32(p)
    budget = int(f32(p * f32(n)) // 1)
    if budget <= 0:
        return 0, p
    return budget, give_new_progress(p, budget, n)


def probe_new(p, n):
    """修复：(double)p * n —— double 精确"""
    p = f32(p)
    budget = int((p * n) // 1)          # Python float 即 double，等价 Java (double)p * n
    if budget <= 0:
        return 0, p
    return budget, give_new_progress(p, budget, n)


LEVELS = list(range(0, 61)) + [80, 100, 200, 500]


def build_points(level):
    n = needed(level)
    cand = set()
    for j in range(n + 1):
        p0 = f32(j / n)
        cand.add(p0)
        cand.add(next_up(p0))
        cand.add(next_down(p0))
    return n, sorted(x for x in cand if 0.0 <= x < 1.0)


for name, probe in (("现状 float32 预算", probe_old), ("修复 double 预算", probe_new)):
    total, bad = 0, []
    for level in LEVELS:
        n, pts = build_points(level)
        for p in pts:
            b, np_ = probe(p, n)
            total += 1
            if b > 0 and np_ < 0.0:
                if len(bad) < 12:
                    bad.append((level, n, float(p), b, float(np_)))
    print("=" * 74)
    print("%s ：扫描 %d 个 k/n±1ulp 边界点，越界 %d 个" % (name, total, len(bad)))
    for h in bad:
        print("   level=%3d needed=%3d p=%r budget=%3d -> newp=%r" % h)
    print()

# 随机点对照
print("=" * 74)
rnd = random.Random(7)
for name, probe in (("现状 float32 预算", probe_old), ("修复 double 预算", probe_new)):
    total, bad = 0, 0
    for level in LEVELS:
        n = needed(level)
        for _ in range(20000):
            p = f32(rnd.random())
            b, np_ = probe(p, n)
            total += 1
            if b > 0 and np_ < 0.0:
                bad += 1
    print("%s ：随机 %d 点，越界 %d 个" % (name, total, bad))
