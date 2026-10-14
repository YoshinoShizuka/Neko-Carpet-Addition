#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把自检模板装到 26.3（其它四个版本走 install_exchange_selfcheck.py）。

用法：
    python install_selfcheck_263.py install
    python install_selfcheck_263.py remove

26.3 是「规范版本」——模板直接落到源码目录，不需要 1.21.11 那两处重命名。

⚠️ 换行符坑（踩过一次）：26.3 的 `VoidWarehouseData.java` 是 LF，
但 `VoidWarehouseExtension.java` 是 **CRLF**（被 Edit 写回 + git 归一）。
所以挂/摘 hook 一律先读到 LF 再匹配，写回时按原样恢复 —— 否则 `HOOK_OLD not in text`
直接报「hook 不在」。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

TARGET = "modern/26.3"
NAME = "VoidWarehouseExchangeSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

HOOK_OLD = """    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {"""

HOOK_NEW = """    public void onTick(MinecraftServer server) {
        // 【临时自检】验证本轮 9 合 1 兑换，验证后删除
        VoidWarehouseExchangeSelfCheck.run(server);

        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {"""


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
    if "VoidWarehouseExchangeSelfCheck" in ext_text:
        print("%s: hook 已存在，跳过" % TARGET)
        return
    if HOOK_OLD not in ext_text:
        raise SystemExit("!! %s 的 Extension 没找到 onTick 片段" % TARGET)
    write_back(ext, ext_text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("%s: 已挂上 hook（原换行 %s）" % (TARGET, "CRLF" if crlf else "LF"))


def remove():
    f = os.path.join(ROOT, TARGET, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
        print("%s: 已删除 %s" % (TARGET, NAME))
    ext = extension_path()
    ext_text, crlf = read_lf(ext)
    if "VoidWarehouseExchangeSelfCheck" not in ext_text:
        print("%s: hook 本来就没有" % TARGET)
        return
    if HOOK_NEW not in ext_text:
        raise SystemExit("!! %s 的 hook 片段找不到（换行符对不上？）" % TARGET)
    write_back(ext, ext_text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("%s: 已摘掉 hook" % TARGET)


if __name__ == "__main__":
    {"install": install, "remove": remove}[sys.argv[1]]()
