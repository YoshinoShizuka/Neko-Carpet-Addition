#!/usr/bin/env python3
"""把「搜索通道」自检装到 / 摘出五个版本。

用法：
    python install_search_channel_selfcheck.py install [目标 ...]
    python install_search_channel_selfcheck.py remove  [目标 ...]

⚠️ 与环境联动：hook 锚点是 VoidWarehouseExtension#onTick 里的 noteServerTick 那一行。
验证完必须 remove，否则会跟着发版。模板五版本通用（不碰版本差异类型）。
"""
import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse", "search")
EXT_PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

NAME = "SearchChannelSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, NAME + ".template")

DEFAULT_TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]


def resolve(target):
    """接受版本号（26.3 / 1.21.11）或完整目录（modern/26.3）。"""
    if target.startswith("modern/") or target.startswith("versions/"):
        return target
    if target == "1.21.11":
        return "versions/1.21.11"
    return "modern/" + target

HOOK_OLD = """        VoidWarehouseData.noteServerTick(server.getTickCount());
"""

HOOK_NEW = """        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 【临时自检】验证搜索通道服务端侧业务逻辑，验证后删除
        neko.voidwarehouse.search.SearchChannelSelfCheck.run(server);
"""

VER_PLACEHOLDER = "@VER@"


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write(path, text, crlf):
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def ext_path(target):
    return os.path.join(ROOT, target, EXT_PKG, "VoidWarehouseExtension.java")


def install(target):
    if not os.path.isfile(TEMPLATE):
        raise SystemExit("!! 模板不存在：%s" % TEMPLATE)
    body, _ = read(TEMPLATE)
    if VER_PLACEHOLDER not in body:
        raise SystemExit("!! 模板里找不到 %s 占位符" % VER_PLACEHOLDER)
    body = body.replace(VER_PLACEHOLDER, target.rstrip("/").split("/")[-1])
    if VER_PLACEHOLDER in body:
        raise SystemExit("!! %s 没被替换干净" % VER_PLACEHOLDER)
    write(os.path.join(ROOT, target, PKG, NAME), body, False)
    print("%s: 已写入 %s（%d 行）" % (target, NAME, body.count("\n") + 1))

    ext = ext_path(target)
    text, crlf = read(ext)
    if "SearchChannelSelfCheck" in text:
        print("%s: hook 已存在，跳过" % target)
        return
    if text.count(HOOK_OLD) != 1:
        raise SystemExit("!! %s 的 noteServerTick 锚点命中 %d 次" % (target, text.count(HOOK_OLD)))
    write(ext, text.replace(HOOK_OLD, HOOK_NEW, 1), crlf)
    print("%s: 已挂上 hook（原换行 %s）" % (target, "CRLF" if crlf else "LF"))


def remove(target):
    f = os.path.join(ROOT, target, PKG, NAME)
    if os.path.exists(f):
        os.remove(f)
    ext = ext_path(target)
    text, crlf = read(ext)
    if HOOK_NEW not in text:
        print("%s: 没有 hook，跳过" % target)
        return
    if text.count(HOOK_NEW) != 1:
        raise SystemExit("!! %s 的 hook 命中 %d 次" % (target, text.count(HOOK_NEW)))
    write(ext, text.replace(HOOK_NEW, HOOK_OLD, 1), crlf)
    print("%s: 已摘掉 hook" % target)


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("install", "remove"):
        raise SystemExit(__doc__)
    action = sys.argv[1]
    targets = sys.argv[2:] or DEFAULT_TARGETS
    for target in targets:
        (install if action == "install" else remove)(resolve(target))


if __name__ == "__main__":
    main()
