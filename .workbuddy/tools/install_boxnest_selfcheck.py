#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「盒模式刷盒子」的复现自检装到 26.3 / 摘掉。

用法：
    python .workbuddy/tools/install_boxnest_selfcheck.py install
    python .workbuddy/tools/install_boxnest_selfcheck.py remove

自检类落在 modern/26.3 的 neko.voidwarehouse 包下，由 VoidWarehouseExtension#onTick
第一行触发；跑完 runServer 看日志里的 `SMOKE RESULT vw-boxnest/26.3 ...`。
⚠️ 验证完必须 remove，否则临时类会进产物 jar。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

TARGET = "modern/26.3"
NAME = "BoxNestingSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")
CLASS = "BoxNestingSelfCheck"

HOOK_OLD = """    public void onTick(MinecraftServer server) {
"""

HOOK_NEW = """    public void onTick(MinecraftServer server) {
        // 【临时自检】盒模式刷盒子事故的复现，验证后删除
        BoxNestingSelfCheck.run(server);

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
    print("%s: 已写入 %s（%d 行）" % (TARGET, NAME, body.count("\n") + 1))

    ext = extension_path()
    ext_text, crlf = read_lf(ext)
    if CLASS in ext_text:
        print("%s: hook 已存在，跳过" % TARGET)
        return
    if HOOK_OLD not in ext_text:
        raise SystemExit("!! %s 的 Extension 没找到 onTick 开头" % TARGET)
    write_back(ext, ext_text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("%s: 已挂上 hook（原换行 %s）" % (TARGET, "CRLF" if crlf else "LF"))


def remove():
    f = os.path.join(ROOT, TARGET, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
        print("%s: 已删除 %s" % (TARGET, NAME))
    ext = extension_path()
    ext_text, crlf = read_lf(ext)
    if CLASS not in ext_text:
        print("%s: hook 本来就没有" % TARGET)
        return
    if HOOK_NEW not in ext_text:
        raise SystemExit("!! %s 的 hook 片段找不到（换行符对不上？）" % TARGET)
    write_back(ext, ext_text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("%s: 已摘掉 hook" % TARGET)


if __name__ == "__main__":
    {"install": install, "remove": remove}[sys.argv[1]]()
