#!/usr/bin/env python3
"""把「方块掉落物直接进虚空仓库」自检装到 26.3 / 26.2 / 26.1.2 / 26.1.1。

用法：
    python install_blockdrops_selfcheck.py install [版本 ...]
    python install_blockdrops_selfcheck.py remove  [版本 ...]

⚠️ 与环境联动：hook 锚点是 `VoidWarehouseExtension#onTick` 里的 `noteServerTick` 那一行
（`onTick` 开头改过之后要同步这个脚本）。验证完必须 remove，否则会跟着发版。
"""
import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

NAME = "VoidWarehouseBlockDropsSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

DEFAULT_TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1"]

HOOK_OLD = """        VoidWarehouseData.noteServerTick(server.getTickCount());
"""

HOOK_NEW = """        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 【临时自检】验证「方块掉落直接进虚空仓库」，验证后删除
        VoidWarehouseBlockDropsSelfCheck.run(server);
"""

VER_PLACEHOLDER = "@VER@"


def read_lf(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write_back(path, text, crlf):
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def ext_path(target):
    return os.path.join(ROOT, target, PKG, "VoidWarehouseExtension.java")


def install(target):
    if not os.path.isfile(TEMPLATE):
        raise SystemExit("!! 模板不存在：%s" % TEMPLATE)
    body, _ = read_lf(TEMPLATE)
    # 结论行里的版本号由模板占位符替换，免得每个版本的日志都印着 26.3
    if VER_PLACEHOLDER not in body:
        raise SystemExit("!! 模板里找不到 %s 占位符" % VER_PLACEHOLDER)
    body = body.replace(VER_PLACEHOLDER, target.rstrip("/").split("/")[-1])
    if VER_PLACEHOLDER in body:
        raise SystemExit("!! %s 没被替换干净" % VER_PLACEHOLDER)
    write_back(os.path.join(ROOT, target, PKG, NAME), body, False)
    print("%s: 已写入 %s（%d 行）" % (target, NAME, body.count("\n") + 1))

    ext = ext_path(target)
    text, crlf = read_lf(ext)
    if "VoidWarehouseBlockDropsSelfCheck" in text:
        print("%s: hook 已存在，跳过" % target)
        return
    if HOOK_OLD not in text:
        raise SystemExit("!! %s 的 Extension 没找到 noteServerTick 锚点" % target)
    write_back(ext, text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("%s: 已挂上 hook（原换行 %s）" % (target, "CRLF" if crlf else "LF"))


def remove(target):
    f = os.path.join(ROOT, target, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
        print("%s: 已删除 %s" % (target, NAME))
    ext = ext_path(target)
    text, crlf = read_lf(ext)
    if "VoidWarehouseBlockDropsSelfCheck" not in text:
        print("%s: hook 本来就没有" % target)
        return
    if HOOK_NEW not in text:
        raise SystemExit("!! %s 的 hook 片段找不到" % target)
    write_back(ext, text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("%s: 已摘掉 hook" % target)


if __name__ == "__main__":
    action = sys.argv[1]
    targets = sys.argv[2:] or DEFAULT_TARGETS
    for t in targets:
        {"install": install, "remove": remove}[action](t)
