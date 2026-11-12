#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「配方本填充器」（生成满配方测试存档用）装到 / 摘出 26.3。

用法：
    python install_recipe_seed.py install
    python install_recipe_seed.py remove

⚠️ 只在 26.3 上用：配方枚举走的是 26.x 的 `RecipeDisplay` 那一套，其它版本 API 不同。
**验证完必须 remove**，否则临时工具会跟着发版（`build` 时进产物 jar）。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
TARGET = "modern/26.3"
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

NAME = "VoidWarehouseRecipeSeed.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

HOOK_OLD = """        VoidWarehouseData.noteServerTick(server.getTickCount());
"""

HOOK_NEW = """        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 【临时工具】生成「配方本已填满」的测试存档，用完删除
        VoidWarehouseRecipeSeed.run(server);
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


def extension_path():
    return os.path.join(ROOT, TARGET, PKG, "VoidWarehouseExtension.java")


def install():
    if not os.path.isfile(TEMPLATE):
        raise SystemExit("!! 模板不存在：%s" % TEMPLATE)
    body, _ = read_lf(TEMPLATE)
    write_back(os.path.join(ROOT, TARGET, PKG, NAME), body, False)
    print("已写入 %s（%d 行）" % (NAME, body.count("\n") + 1))

    ext = extension_path()
    ext_text, crlf = read_lf(ext)
    if "VoidWarehouseRecipeSeed" in ext_text:
        print("hook 已存在，跳过")
        return
    if HOOK_OLD not in ext_text:
        raise SystemExit("!! Extension 里没找到 onTick 片段（换行符对不上？）")
    write_back(ext, ext_text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("已挂上 hook（原换行 %s）" % ("CRLF" if crlf else "LF"))


def remove():
    f = os.path.join(ROOT, TARGET, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
        print("已删除 %s" % NAME)
    ext = extension_path()
    ext_text, crlf = read_lf(ext)
    if "VoidWarehouseRecipeSeed" not in ext_text:
        print("hook 本来就没有")
        return
    if HOOK_NEW not in ext_text:
        raise SystemExit("!! hook 片段找不到（换行符对不上？）")
    write_back(ext, ext_text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("已摘掉 hook")


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("install", "remove"):
        raise SystemExit("用法: python install_recipe_seed.py install|remove")
    {"install": install, "remove": remove}[sys.argv[1]]()
