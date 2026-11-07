#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""让「结构化直接放置」不再依赖 Servux（五版本传播）。

背景：`servux-fabric-*.jar` 的 fabric.mod.json 声明 "environment": "server"，
Fabric Loader 在客户端（含单人集成服务器）会静默跳过它。原实现把
`printer_place_state_v1` 的登记包在 `PrinterPlaceServuxBridge.available()` 里，
于是单人环境该通道永不登记 → 客户端 canSend=false → 每个方块都退回
「补货到背包→手上放」。

本脚本做三件事（五版本逐份改）：
  1. 用内置解码版 `PrinterPlaceServuxBridge` 覆盖各版本（Servux 在场时才借它的解码器）；
  2. `PrinterPlaceNetwork.init()` 无条件登记 state 通道；
  3. `PrinterPlaceService` 去掉 `!available()` 这道拒绝。

用法：
    python .workbuddy/tools/patch_direct_place_without_servux.py check
    python .workbuddy/tools/patch_direct_place_without_servux.py apply
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse", "restock")
VERSIONS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]
SOURCE = "modern/26.3"
BRIDGE = "PrinterPlaceServuxBridge.java"
NETWORK = "PrinterPlaceNetwork.java"
SERVICE = "PrinterPlaceService.java"

SERVICE_OLD = ("        if (structured != null && (!PrinterPlaceServuxBridge.available() "
               "|| !PrinterPlaceServuxBridge.hasPermission(player))) {")
SERVICE_NEW = "        if (structured != null && !PrinterPlaceServuxBridge.hasPermission(player)) {"

GATE_HEAD = "        if (PrinterPlaceServuxBridge.available()) {\n"
GATE_TAIL = "        }\n"


def path(rel, name):
    return os.path.join(ROOT, rel, PKG, name)


def read_lf(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write_back(p, text, crlf):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n") if crlf else text)


def patch_network(text):
    """去掉包在 state 通道登记外面的 if，内部两行各前移 4 个空格。"""
    start = text.find(GATE_HEAD)
    if start < 0:
        return text, False
    body_start = start + len(GATE_HEAD)
    end = text.find(GATE_TAIL, body_start)
    if end < 0:
        raise SystemExit("!! %s 的 if 块没有闭合" % NETWORK)
    inner = text[body_start:end]
    if "PrinterPlaceStatePayload" not in inner:
        raise SystemExit("!! %s 的 if 块内容不是 state 通道登记" % NETWORK)
    lines = [ln[4:] if ln.startswith("    ") else ln for ln in inner.split("\n")]
    return text[:start] + "\n".join(lines) + text[end + len(GATE_TAIL):], True


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode not in ("check", "apply"):
        raise SystemExit(__doc__)

    bridge_text, _ = read_lf(path(SOURCE, BRIDGE))
    if "withRequested" not in bridge_text:
        raise SystemExit("!! %s 的桥接不是内置解码版，先改 %s" % (SOURCE, BRIDGE))
    changed = []
    for rel in VERSIONS:
        b = path(rel, BRIDGE)
        n = path(rel, NETWORK)
        s = path(rel, SERVICE)
        net_text, net_crlf = read_lf(n)
        svc_text, svc_crlf = read_lf(s)

        if mode == "check":
            print("%-18s bridge=%s  gate=%s  service=%s" % (
                rel,
                "内置" if "withRequested" in read_lf(b)[0] else "旧版",
                "有" if GATE_HEAD in net_text else "无",
                "旧版" if SERVICE_OLD in svc_text else ("新版" if SERVICE_NEW in svc_text else "??")))
            continue

        write_back(b, bridge_text, True)
        patched, done = patch_network(net_text)
        if not done:
            if "PrinterPlaceStatePayload.TYPE, PrinterPlaceNetwork::handleState" not in patched:
                raise SystemExit("!! %s 找不到 state 登记块" % n)
        else:
            write_back(n, patched, net_crlf)
        if SERVICE_OLD in svc_text:
            write_back(s, svc_text.replace(SERVICE_OLD, SERVICE_NEW, 1), svc_crlf)
        elif SERVICE_NEW not in svc_text:
            raise SystemExit("!! %s 找不到 available() 拒绝分支" % s)
        changed.append(rel)

    if mode == "apply":
        for rel in changed:
            print("%-18s 已更新 %s / %s / %s" % (rel, BRIDGE, NETWORK, SERVICE))


if __name__ == "__main__":
    main()
