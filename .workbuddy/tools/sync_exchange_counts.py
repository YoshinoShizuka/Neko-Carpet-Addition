#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把五个版本的 VoidWarehouseExchangeBootstrap javadoc 里的计数同步到 47 条 / 88 个物品。

判「已改过」用三态（见 MEMORY.md 的同步脚本铁律）：
  old 在 → 改；old 没了且 new 在 → 跳过；都没有 → 报错。
收尾再做一遍逐字节比对：old 必须为 0 处、new 必须为 1 处。
"""
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

TARGETS = [
    "modern/26.3/src/main/java/neko/voidwarehouse/VoidWarehouseExchangeBootstrap.java",
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseExchangeBootstrap.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseExchangeBootstrap.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseExchangeBootstrap.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseExchangeBootstrap.java",
]

# (old, new)
PAIRS = [
    ("（30 条单原料边 + 1 条多原料配方 / 56 个物品）",
     "（47 条单原料边 + 1 条多原料配方 / 88 个物品）"),
    ("静态表里声明了多少条边（26.3 版本是 30）",
     "静态表里声明了多少条边（26.3 版本是 47）"),
    ("兑换表只描述原版那 29 条配方",
     "兑换表只描述原版那 47 条配方"),
    ("正常服主不需要知道内部有 30 条边",
     "正常服主不需要知道内部有 47 条边"),
]


def main():
    problems = []
    for rel in TARGETS:
        path = ROOT / rel
        text = io.open(path, encoding="utf-8", newline="").read()
        orig = text
        report = []
        for old, new in PAIRS:
            if old in text:
                text = text.replace(old, new)
                report.append("改")
            elif new in text:
                report.append("跳")
            else:
                problems.append(f"{rel}: 找不到 old 也找不到 new -> {old!r}")
                report.append("错")
        if text != orig:
            io.open(path, "w", encoding="utf-8", newline="").write(text)
        print(f"  {rel.split('/')[1]:10s} {''.join(report)}")

    # 逐字节收尾校验：改完的文件里 old 必须一处不剩、new 必须正好一处
    for rel in TARGETS:
        text = io.open(ROOT / rel, encoding="utf-8", newline="").read()
        for old, new in PAIRS:
            if old in text:
                problems.append(f"{rel}: 收尾校验失败，old 仍在：{old!r}")
            if text.count(new) != 1:
                problems.append(f"{rel}: new 出现 {text.count(new)} 次（应为 1）：{new!r}")

    if problems:
        print("\n!! 同步失败：")
        for p in problems:
            print("   " + p)
        return 1
    print("\nOK 五版本 Bootstrap javadoc 计数已同步为 47 条 / 88 个物品，逐字节校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
