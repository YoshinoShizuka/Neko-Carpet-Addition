#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「虚空仓库直接放置」的自检装到某个版本 / 摘掉。

用法：
    python .workbuddy/tools/install_printer_direct_place_selfcheck.py install            # 默认 26.3
    python .workbuddy/tools/install_printer_direct_place_selfcheck.py install 1.21.11
    python .workbuddy/tools/install_printer_direct_place_selfcheck.py remove 1.21.11

跑完 runServer 看日志里的 `SMOKE RESULT printer-direct-place/<ver> ...`。
⚠️ 验证完必须 remove，否则临时类会进产物 jar。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

# 版本 -> (子工程目录, 日志里显示的版本号)
TARGETS = {
    "26.3": ("modern/26.3", "26.3"),
    "26.2": ("modern/26.2", "26.2"),
    "26.1.2": ("modern/26.1.2", "26.1.2"),
    "26.1.1": ("modern/26.1.1", "26.1.1"),
    "1.21.11": ("versions/1.21.11", "1.21.11"),
}

TARGET = None
VERSION = None
NAME = "PrinterDirectPlaceSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")
CLASS = "PrinterDirectPlaceSelfCheck"
VER_PLACEHOLDER = "@VER@"

HOOK_OLD = """    public void onTick(MinecraftServer server) {
"""

HOOK_NEW = """    public void onTick(MinecraftServer server) {
        // 【临时自检】虚空仓库直接放置，验证后删除
        neko.voidwarehouse.restock.PrinterDirectPlaceSelfCheck.run(server);

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


def target_path():
    # 自检类放在 restock 子包里（要访问 PrinterPlaceSession 的包内方法 retainedResults）
    return os.path.join(ROOT, TARGET, PKG, "restock", NAME)


def install():
    if not os.path.isfile(TEMPLATE):
        raise SystemExit("!! 模板不存在：%s" % TEMPLATE)
    body, _ = read_lf(TEMPLATE)
    body = body.replace(VER_PLACEHOLDER, VERSION)
    write_back(target_path(), body, False)
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
    f = target_path()
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


def main():
    global TARGET, VERSION
    if len(sys.argv) < 2 or sys.argv[1] not in ("install", "remove"):
        raise SystemExit(__doc__)
    ver = sys.argv[2] if len(sys.argv) > 2 else "26.3"
    if ver not in TARGETS:
        raise SystemExit("!! 不认识的版本：%s（可选 %s）" % (ver, ", ".join(TARGETS)))
    TARGET, VERSION = TARGETS[ver]
    install() if sys.argv[1] == "install" else remove()


if __name__ == "__main__":
    main()
