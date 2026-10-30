#!/usr/bin/env python3
"""把「方块掉落进仓库」的聊天提示同步到五个版本。

做两件事：
  1. 把 modern/26.3 的 `VoidWarehouseBlockDrops.java` 整份复制到其余四个版本
     （这个类本来就是五版本同源的，改一处 = 改五处）；
  2. 在每个版本的 `VoidWarehouseExtension#onTick` 里挂上 `VoidWarehouseBlockDrops.tick(server)`。

铁律（本项目踩过多次）：
  - `old` 在就改，且必须只命中 1 次；
  - `old` 没了、`new` 在 → 跳过（重复跑安全）；
  - 两个都没有 → 直接报错，绝不瞎写；
  - 逐文件保留原换行风格（26.3 的 Java 是 LF，其余 CRLF，core.autocrlf=true）；
  - 收尾过一遍逐字节比对：改完的文件必须真的包含 new，且不含残留的 old。
"""
import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")
SRC_VERSION = "modern/26.3"
NAME = "VoidWarehouseBlockDrops.java"
TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

ANCHOR = "        VoidWarehouseData.noteServerTick(server.getTickCount());\n"

INSERT = """        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 「方块掉落物直接进虚空仓库」的聊天提示：按玩家汇总、每秒最多一条（见 VoidWarehouseBlockDrops#tick）。
        // 必须在「规则关着就提前 return」之前 —— 规则刚被关掉时，攒下的那几条也要发出去。
        VoidWarehouseBlockDrops.tick(server);
"""


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write(path, text, crlf):
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def main():
    src = os.path.join(ROOT, SRC_VERSION, PKG, NAME)
    if not os.path.isfile(src):
        raise SystemExit("!! 源文件不存在：%s" % src)
    body, _ = read(src)
    if "VoidWarehouseBlockDrops" not in body:
        raise SystemExit("!! 源文件不像 VoidWarehouseBlockDrops")

    for target in TARGETS:
        dst = os.path.join(ROOT, target, PKG, NAME)
        # 源版本自己跳过（否则会把刚写好的文件再读一遍写回去，虽然无害）
        if os.path.normpath(dst) != os.path.normpath(src):
            _, dst_crlf = read(dst)
            write(dst, body, dst_crlf)
            print("%s: 已复制 %s（%s）" % (target, NAME, "CRLF" if dst_crlf else "LF"))

        ext = os.path.join(ROOT, target, PKG, "VoidWarehouseExtension.java")
        text, crlf = read(ext)
        if "VoidWarehouseBlockDrops.tick(server)" in text:
            print("%s: hook 已存在，跳过" % target)
        elif ANCHOR in text:
            if text.count(ANCHOR) != 1:
                raise SystemExit("!! %s 的锚点命中了 %d 次" % (target, text.count(ANCHOR)))
            write(ext, text.replace(ANCHOR, INSERT, 1), crlf)
            print("%s: 已挂上 hook（原换行 %s）" % (target, "CRLF" if crlf else "LF"))
        else:
            raise SystemExit("!! %s 里找不到 noteServerTick 锚点" % target)

    # ---- 收尾门禁：逐字节比对
    print("---- 校验")
    for target in TARGETS:
        dst = os.path.join(ROOT, target, PKG, NAME)
        got, _ = read(dst)
        if got != body:
            raise SystemExit("!! %s 的 %s 与源文件不一致" % (target, NAME))
        ext, _ = read(os.path.join(ROOT, target, PKG, "VoidWarehouseExtension.java"))
        if ext.count("VoidWarehouseBlockDrops.tick(server)") != 1:
            raise SystemExit("!! %s 的 hook 数量不是 1" % target)
        # 注意：INSERT 里包含 ANCHOR 那一行，所以这里只能断言「恰好 1 次」，
        # 不能断言 0 —— 断言 0 会在正确替换后误报。
        if ext.count(ANCHOR) != 1:
            raise SystemExit("!! %s 的 noteServerTick 行数不是 1（疑似重复插入）" % target)
    print("五版本 %s 逐字节一致，hook 各 1 处" % NAME)


if __name__ == "__main__":
    main()
