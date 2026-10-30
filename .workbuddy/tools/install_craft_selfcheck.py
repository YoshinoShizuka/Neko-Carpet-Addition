#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「配方学习 + 配方扣料」自检装到 26.3 / 26.2 / 26.1.2 / 26.1.1。

用法：
    python install_craft_selfcheck.py install [版本 ...]
    python install_craft_selfcheck.py remove  [版本 ...]
    python install_craft_selfcheck.py install           # 四个 modern 版本全装

⚠️ 26.3 与其它三个版本的 `ResultSlot` / `Recipe` API 不同，模板里唯一的差异是
`recipe.assemble(input)` 的参数个数（26.x 无 registryAccess）。1.21.11 另外还要
`minecraft:` → `ResourceLocation` 的重命名，那一份单独维护，本脚本不管。

**注意**：本自检会在 `VoidWarehouseExtension#onTick` 里挂一行，
验证完必须 `remove`，否则会跟着发版。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

NAME = "VoidWarehouseCraftSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

DEFAULT_TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1"]

HOOK_OLD = """        VoidWarehouseData.noteServerTick(server.getTickCount());
"""

HOOK_NEW = """        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 【临时自检】验证本轮「配方学习 + 配方扣料」，验证后删除
        VoidWarehouseCraftSelfCheck.run(server);
"""


def read_lf(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write_back(path, text, crlf):
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def extension_path(target):
    return os.path.join(ROOT, target, PKG, "VoidWarehouseExtension.java")


def install(target):
    if not os.path.isfile(TEMPLATE):
        raise SystemExit("!! 模板不存在：%s" % TEMPLATE)
    body, _ = read_lf(TEMPLATE)
    # 版本差异：1.21.x 的 assemble 要 registryAccess，26.x 不要。四个 modern 版本一致，直接落。
    write_back(os.path.join(ROOT, target, PKG, NAME), body, False)
    print("%s: 已写入 %s（%d 行）" % (target, NAME, body.count("\n") + 1))

    ext = extension_path(target)
    ext_text, crlf = read_lf(ext)
    if "VoidWarehouseCraftSelfCheck" in ext_text:
        print("%s: hook 已存在，跳过" % target)
        return
    if HOOK_OLD not in ext_text:
        raise SystemExit("!! %s 的 Extension 没找到 onTick 片段（换行符对不上？）" % target)
    write_back(ext, ext_text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("%s: 已挂上 hook（原换行 %s）" % (target, "CRLF" if crlf else "LF"))


def remove(target):
    f = os.path.join(ROOT, target, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
        print("%s: 已删除 %s" % (target, NAME))
    ext = extension_path(target)
    ext_text, crlf = read_lf(ext)
    if "VoidWarehouseCraftSelfCheck" not in ext_text:
        print("%s: hook 本来就没有" % target)
        return
    if HOOK_NEW not in ext_text:
        raise SystemExit("!! %s 的 hook 片段找不到（换行符对不上？）" % target)
    write_back(ext, ext_text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("%s: 已摘掉 hook" % target)


if __name__ == "__main__":
    action = sys.argv[1]
    targets = sys.argv[2:] or DEFAULT_TARGETS
    for t in targets:
        {"install": install, "remove": remove}[action](t)
