#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把任一临时工具类装到 / 摘出指定版本的 VoidWarehouseExtension#onTick。

用法：
    python install_stress_tool.py install <ClassName> [ver]
    python install_stress_tool.py remove  <ClassName> [ver]

ver 默认 26.3；其余写 26.2 / 26.1.2 / 26.1.1 / 1.21.11。
批量：ClassName 传 all，一次装/摘本目录下 STRESS_CLASSES 里的全部类。

模板约定：.workbuddy/tools/<ClassName>.java.template
          复制到 <prefix>/<ver>/src/main/java/neko/voidwarehouse/<ClassName>.java

钩子挂在 noteServerTick 之后（任何提前 return 之前，否则 tick 计数不走就永远不触发）。

换行符：Extension 通常已是干净 CRLF，但仓库里存在 \\r\\r\\n 的历史遗留文件，
所以这里一律 raw.replace('\\r','') 归一、写回按探测到的原风格还原 ——
只 replace('\\r\\n','\\n') 会在下一行留下孤立 \\r，锚点全灭。
"""
import io
import os
import shutil
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

HOOK_OLD = (
    "        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。\n"
    "        VoidWarehouseData.noteServerTick(server.getTickCount());\n"
)

# 批量模式用；顺序即执行顺序（同一个 onTick 里按插入顺序调用）
STRESS_CLASSES = ["RecipeCorpusExport", "A2StressTest"]


def paths(ver, cls):
    prefix = "versions/" if ver == "1.21.11" else "modern/"
    ext = prefix + ver + "/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java"
    src = prefix + ver + "/src/main/java/neko/voidwarehouse/" + cls + ".java"
    return ext, src


def read_raw(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def detect_style(raw):
    crlf = raw.count("\r\n")
    cr = raw.count("\r")
    lf = raw.count("\n")
    if cr > crlf:
        return "crlf2"
    if crlf > 0 and crlf == lf:
        return "crlf"
    return "lf"


def write_like(p, text, style):
    if style == "crlf2":
        text = text.replace("\n", "\r\r\n")
    elif style == "crlf":
        text = text.replace("\n", "\r\n")
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def hook_block(cls):
    return HOOK_OLD + (
        "\n        // 【临时压测工具】%s，跑完删除（内部会 server.halt 停服）\n"
        "        %s.run(server);\n" % (cls, cls)
    )


def install_one(ver, cls):
    ext, src = paths(ver, cls)
    template = os.path.join(SCRIPT_DIR, cls + ".java.template")
    assert os.path.exists(template), "模板不存在: " + template
    raw = read_raw(ext)
    style = detect_style(raw)
    text = raw.replace("\r", "")
    if cls + ".run(server);" in text:
        print("  [%s] hook 已存在，跳过" % cls)
    else:
        assert HOOK_OLD in text, "[%s] hook 锚点不在（Extension 结构变了？）" % cls
        text = text.replace(HOOK_OLD, hook_block(cls), 1)
        write_like(ext, text, style)
        print("  [%s] hook 已挂 (EOL=%s)" % (cls, style))
    shutil.copyfile(template, src)
    print("  [%s] 已安装 -> %s" % (cls, src))


def remove_one(ver, cls):
    ext, src = paths(ver, cls)
    raw = read_raw(ext)
    style = detect_style(raw)
    text = raw.replace("\r", "")
    if cls + ".run(server);" in text:
        assert hook_block(cls) in text, "[%s] hook 内容与预期不符，人工核对" % cls
        text = text.replace(hook_block(cls), HOOK_OLD, 1)
        write_like(ext, text, style)
        print("  [%s] hook 已还原" % cls)
    else:
        print("  [%s] hook 本就不在，跳过" % cls)
    if os.path.exists(src):
        os.remove(src)
        print("  [%s] %s.java 已删除" % (cls, cls))


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: install|remove <ClassName|all> [ver]")
    action = sys.argv[1]
    cls = sys.argv[2]
    ver = sys.argv[3] if len(sys.argv) > 3 else "26.3"
    if action not in ("install", "remove"):
        raise SystemExit("用法: install|remove <ClassName|all> [ver]")

    os.chdir(ROOT)
    targets = STRESS_CLASSES if cls == "all" else [cls]
    print("== %s %s @ %s ==" % (action, ",".join(targets), ver))
    for name in targets:
        (install_one if action == "install" else remove_one)(ver, name)


if __name__ == "__main__":
    main()
