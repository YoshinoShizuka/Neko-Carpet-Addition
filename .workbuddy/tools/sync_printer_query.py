#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「材料列表库存来源 = 虚空仓库」的服务端通道同步到其余四个版本。

新增四份文件（都在 neko/voidwarehouse/restock 包）：
  1. PrinterQueryPayload.java        —— C2S：我要问这些物品的库存
  2. PrinterQueryResultPayload.java  —— S2C：这些物品各有多少
  3. PrinterQueryService.java        —— 纯逻辑：遍历仓库、生成快照
  4. PrinterQueryNetwork.java        —— 收发 + 限流

另改一处：
  5. AutoMendingMod.java             —— 增加 PrinterQueryNetwork.init()

版本差异只有一处：1.21.11 的 fabric-api 把
  PayloadTypeRegistry.serverboundPlay() / clientboundPlay()
叫  PayloadTypeRegistry.playC2S()        / playS2C()
（与既有的 PrinterRestockNetwork 完全同款差异），脚本里打补丁。

顺手修掉一个既有缺陷：26.2 / 26.1.1 / 26.1.2 / 1.21.11 的 AutoMendingMod.java 里
`import neko.cooking.CookingExtension;` 与 `CookingExtension.init();` 各重复了 3 遍
（上一轮同步脚本「在 old 后面追加」造成的，old 没被删掉）。
本轮把 AutoMendingMod 整体改写为 26.3 的规范内容，一次清零。

每处写入都断言「写完之后内容确实对了」，不通过就报错退出 —— 静默失效比崩掉更可怕。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

PKG = "src/main/java/neko/voidwarehouse/restock"
MOD = "src/main/java/neko/automending/AutoMendingMod.java"

CANON = "modern/26.3"
MODERN_TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2"]
LEGACY_TARGET = "versions/1.21.11"
ALL_TARGETS = MODERN_TARGETS + [LEGACY_TARGET]

NEW_FILES = [
    "PrinterQueryPayload.java",
    "PrinterQueryResultPayload.java",
    "PrinterQueryService.java",
    "PrinterQueryNetwork.java",
]

# 1.21.11 的网络 API 重命名（26.x → 1.21.11）
LEGACY_RENAMES = [
    ("PayloadTypeRegistry.serverboundPlay()", "PayloadTypeRegistry.playC2S()"),
    ("PayloadTypeRegistry.clientboundPlay()", "PayloadTypeRegistry.playS2C()"),
]

failures = []


def fail(msg):
    failures.append(msg)
    print("  !! " + msg)


def read(path):
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def sync_new_files():
    print("[1/2] 新增的四份文件")
    for name in NEW_FILES:
        src = os.path.join(ROOT, CANON, PKG, name)
        if not os.path.isfile(src):
            fail("找不到源文件 " + src)
            return
        body = read(src)

        # 26.x 三个版本：原样复制
        for target in MODERN_TARGETS:
            dst = os.path.join(ROOT, target, PKG, name)
            write(dst, body)
            if read(dst) != body:
                fail("复制后内容不一致：" + dst)

        # 1.21.11：打上网络 API 重命名
        legacy = body
        changed = 0
        for old, new in LEGACY_RENAMES:
            if old in legacy:
                legacy = legacy.replace(old, new)
                changed += 1
        write(os.path.join(ROOT, LEGACY_TARGET, PKG, name), legacy)

        # 断言：需要改的那两行确实改了（PrinterQueryNetwork 是唯一用到这两个 API 的文件）
        if name == "PrinterQueryNetwork.java":
            if changed != len(LEGACY_RENAMES):
                fail("1.21.11 的 %s 只替换到 %d/%d 处 API 名"
                     % (name, changed, len(LEGACY_RENAMES)))
            # 只咬「带类名前缀」的真代码；javadoc 里为了说明版本差异会同时提到两边的名字，
            # 那种提及不该被当成漏改（断言咬到注释是踩过的坑）。
            for old, new in LEGACY_RENAMES:
                if old in legacy:
                    fail("1.21.11 的 %s 仍然残留真代码：%s" % (name, old))
            if "PayloadTypeRegistry.playC2S()" not in legacy:
                fail("1.21.11 的 %s 没改成 playC2S" % name)
            if "PayloadTypeRegistry.playS2C()" not in legacy:
                fail("1.21.11 的 %s 没改成 playS2C" % name)
        else:
            # 其余三份压根不碰网络 API，重命名必须是空操作
            if changed != 0:
                fail("%s 不该出现网络 API 名，却改了 %d 处" % (name, changed))

        print("  ok  " + name)


def sync_mod_entry():
    print("[2/2] AutoMendingMod.java")
    canonical = read(os.path.join(ROOT, CANON, MOD))

    # 规范内容本身必须先自检：重复 import / 重复 init 都是这份文件的历史病灶
    if canonical.count("import neko.cooking.CookingExtension;") != 1:
        fail("26.3 的 AutoMendingMod 自身 import 数不对")
    if canonical.count("CookingExtension.init();") != 1:
        fail("26.3 的 AutoMendingMod 自身 init 调用数不对")
    if "PrinterQueryNetwork.init();" not in canonical:
        fail("26.3 的 AutoMendingMod 没有 PrinterQueryNetwork.init()")

    for target in ALL_TARGETS:
        dst = os.path.join(ROOT, target, MOD)
        write(dst, canonical)
        text = read(dst)

        if text != canonical:
            fail("写入后内容不一致：" + dst)
        # 这四条就是本轮要根治的历史缺陷，写完之后必须只剩一份
        if text.count("import neko.cooking.CookingExtension;") != 1:
            fail("%s 的 CookingExtension import 不是恰好 1 份" % target)
        if text.count("CookingExtension.init();") != 1:
            fail("%s 的 CookingExtension.init() 不是恰好 1 次" % target)
        if text.count("PrinterQueryNetwork.init();") != 1:
            fail("%s 的 PrinterQueryNetwork.init() 不是恰好 1 次" % target)
        if text.count("PrinterRestockNetwork.init();") != 1:
            fail("%s 的 PrinterRestockNetwork.init() 不是恰好 1 次" % target)
        print("  ok  " + target)


def main():
    sync_new_files()
    sync_mod_entry()

    print()
    if failures:
        print("失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        sys.exit(1)
    print("全部同步完成，断言全过。")


if __name__ == "__main__":
    main()
