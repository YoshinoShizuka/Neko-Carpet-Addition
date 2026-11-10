#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 A2 存储格式自检装到 / 摘出 26.3。

用法：
    python install_a2_selfcheck.py install [ver]
    python install_a2_selfcheck.py remove  [ver]

ver 默认 26.3；其余版本写 26.2 / 26.1.2 / 26.1.1 / 1.21.11。

钩子挂在 VoidWarehouseExtension#onTick 的 noteServerTick 之后（任何提前 return 之前）。
换行符坑：VoidWarehouseExtension.java 是 CRLF，先归一 LF 匹配、写回按原样。
"""
import io, os, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

VER = "26.3"
PREFIX = "versions/" if VER == "1.21.11" else "modern/"
EXT = PREFIX + VER + "/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java"
TEST_SRC = PREFIX + VER + "/src/main/java/neko/voidwarehouse/A2StorageSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, "A2StorageSelfCheck.java.template")

HOOK_OLD = """        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。
        VoidWarehouseData.noteServerTick(server.getTickCount());
"""
HOOK_NEW = HOOK_OLD + """
        // 【临时自检】A2 存储格式（配方本逐条拆分），跑完删除（finish() 会 server.halt 停服）
        A2StorageSelfCheck.run(server);
"""

MARK = "A2StorageSelfCheck.run"


def read_lf(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write_as_was(p, text, had_crlf):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n") if had_crlf else text)


def main():
    global VER, PREFIX, EXT, TEST_SRC
    action = sys.argv[1]
    if len(sys.argv) > 2:
        VER = sys.argv[2]
        PREFIX = "versions/" if VER == "1.21.11" else "modern/"
        EXT = PREFIX + VER + "/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java"
        TEST_SRC = PREFIX + VER + "/src/main/java/neko/voidwarehouse/A2StorageSelfCheck.java"
    raw = read_lf(EXT)
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if action == "install":
        if MARK in text:
            print("hook 已存在，跳过")
        else:
            assert HOOK_OLD in text, "hook 锚点不在（Extension 结构变了？）"
            text = text.replace(HOOK_OLD, HOOK_NEW, 1)
            write_as_was(EXT, text, had_crlf)
            print("hook 已挂")
        shutil.copyfile(TEMPLATE, TEST_SRC)
        print("A2StorageSelfCheck.java 已安装 -> " + TEST_SRC)
    elif action == "remove":
        if MARK in text:
            assert HOOK_NEW in text, "hook 内容与预期不符，人工核对"
            text = text.replace(HOOK_NEW, HOOK_OLD, 1)
            write_as_was(EXT, text, had_crlf)
            print("hook 已还原")
        else:
            print("hook 本就不在，跳过")
        if os.path.exists(TEST_SRC):
            os.remove(TEST_SRC)
            print("A2StorageSelfCheck.java 已删除")
    else:
        raise SystemExit("用法: install | remove")


if __name__ == "__main__":
    main()
