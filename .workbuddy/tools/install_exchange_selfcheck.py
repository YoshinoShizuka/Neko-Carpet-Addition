#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""临时自检（9 合 1 兑换）在五个版本上的安装 / 卸载。

用法：
    python install_exchange_selfcheck.py install
    python install_exchange_selfcheck.py remove

源文件是 CRLF 之外的那一份规范（26.3，LF），其余版本都是 CRLF —— 读写都先归一成 LF
再写回原样，避免匹配不上（踩过）。
1.21.11 只有一处编译差异：{@code ContainerInput} 叫 {@code ClickType}（已 javap 核对）。
"""

import io
import os
import shutil
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

CANON_VERSION = "modern/26.3"
TARGETS = [
    "modern/26.2",
    "modern/26.1.1",
    "modern/26.1.2",
    "versions/1.21.11",
]

NAME = "VoidWarehouseExchangeSelfCheck.java"

# 1.21.11 的两处版本差异（都 javap 核对过，不是猜的）：
#   ① Mojang 映射里 ContainerInput 叫 ClickType；
#   ② 1.21.11 的 Recipe#assemble 多一个 HolderLookup.Provider 参数（26.x 去掉了），
#      传 server.registryAccess() 正好（RegistryAccess 是 Provider 的子接口）。
LEGACY_RENAMES = [
    ("net.minecraft.world.inventory.ContainerInput", "net.minecraft.world.inventory.ClickType"),
    ("found.get().value().assemble(input)",
     "found.get().value().assemble(input, server.registryAccess())"),
]

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


def extension_path(target):
    return os.path.join(ROOT, target, PKG, "VoidWarehouseExtension.java")


def install():
    src = os.path.join(ROOT, CANON_VERSION, PKG, NAME)
    if not os.path.isfile(src):
        raise SystemExit("!! 26.3 的 %s 不存在" % NAME)
    body, _ = read_lf(src)

    for target in TARGETS:
        text = body
        if target.endswith("1.21.11"):
            for old, new in LEGACY_RENAMES:
                if old not in text:
                    raise SystemExit("!! 1.21.11 的重命名找不到：%s" % old)
                text = text.replace(old, new)
        write_back(os.path.join(ROOT, target, PKG, NAME), text, True)

        ext = extension_path(target)
        ext_text, crlf = read_lf(ext)
        if "VoidWarehouseExchangeSelfCheck" in ext_text:
            print("%s: 文件已装、hook 已存在" % target)
            continue
        if HOOK_OLD not in ext_text:
            raise SystemExit("!! %s 的 Extension 没找到 onTick 片段" % target)
        write_back(ext, ext_text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
        print("%s: 已安装" % target)


def remove():
    for target in TARGETS:
        f = os.path.join(ROOT, target, PKG, NAME)
        if os.path.exists(f):
            os.remove(f)
            print("%s: 已删除 %s" % (target, NAME))
        ext = extension_path(target)
        ext_text, crlf = read_lf(ext)
        if "VoidWarehouseExchangeSelfCheck" in ext_text:
            if HOOK_NEW not in ext_text:
                raise SystemExit("!! %s 的 hook 片段找不到" % target)
            write_back(ext, ext_text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
            print("%s: 已摘掉 hook" % target)


if __name__ == "__main__":
    {"install": install, "remove": remove}[sys.argv[1]]()
