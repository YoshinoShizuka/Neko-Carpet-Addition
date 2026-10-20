#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「纯服务端契约 + 普通漏斗开销」审计探针装到 26.3 / 摘掉。

用法：
    python install_pure_server_audit.py install
    python install_pure_server_audit.py remove

只装 26.3：S 组验的是「专用服务端能跑」，五版本的 mixin 目标类与菜单类型
逐字节同构（见 sync_*.py 的同步结论），P 组测的是同一份注入代码，
所以结论可以直接推广，不必五版本各跑一遍。

⚠️ 26.3 的 `VoidWarehouseExtension.java` 是 CRLF，读写一律先归一成 LF 再按原样写回。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

TARGET = "modern/26.3"
NAME = "PureServerAudit.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

HOOK_OLD = """    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {"""

HOOK_NEW = """    public void onTick(MinecraftServer server) {
        // 【临时审计探针】纯服务端契约 + 普通漏斗开销，测完删除
        PureServerAudit.run(server);

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

    path = extension_path()
    text, crlf = read_lf(path)
    if HOOK_NEW in text:
        print(".. 钩子已存在，跳过")
    elif HOOK_OLD in text:
        write_back(path, text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
        print("OK 钩子已装")
    else:
        raise SystemExit("!! 找不到 onTick 锚点，请检查 VoidWarehouseExtension.java")
    print("OK 探针已装到 %s/%s" % (TARGET, NAME))


def remove():
    path = os.path.join(ROOT, TARGET, PKG, NAME)
    if os.path.isfile(path):
        os.remove(path)
        print("OK 探针源码已删")

    path = extension_path()
    text, crlf = read_lf(path)
    if HOOK_NEW in text:
        write_back(path, text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
        print("OK 钩子已摘")
    elif HOOK_OLD in text:
        print(".. 钩子已摘过，跳过")
    else:
        raise SystemExit("!! 还原失败：onTick 里既没有钩子也不是原样，请手工检查")


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "install"
    if action == "install":
        install()
    elif action == "remove":
        remove()
    else:
        raise SystemExit(__doc__)
