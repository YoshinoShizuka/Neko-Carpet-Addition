"""临时自检的安装 / 卸载（26.2 / 26.1.1 / 26.1.2）。

用法：
    python selfcheck_toggle.py install   把 26.3 验证过的 SelfCheck 复制过去并挂到 onTick
    python selfcheck_toggle.py remove    删掉文件并摘掉 hook

源文件是 CRLF，读写都用 newline="" 原样保留。
"""

import io
import os
import shutil
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# 本脚本放在 <仓库>/.workbuddy/tools/ 下，往上两级才是仓库根
MODERN = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "modern"))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")
TARGETS = ["26.2", "26.1.1", "26.1.2"]

HOOK_OLD = """    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse) {"""
HOOK_NEW = """    public void onTick(MinecraftServer server) {
        // 【临时自检】验证本轮优化，验证后删除
        VoidWarehouseSelfCheck.run(server);

        if (VoidWarehouseSettings.voidWarehouse) {"""


def crlf(text, s):
    """源文件可能是 CRLF 而字面量是 LF —— 统一成源文件的换行再匹配"""
    return s.replace("\n", "\r\n") if "\r\n" in text else s


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def install():
    src = os.path.join(MODERN, "26.3", PKG, "VoidWarehouseSelfCheck.java")
    if not os.path.exists(src):
        raise SystemExit("!! 26.3 的 VoidWarehouseSelfCheck.java 不存在，先恢复它")
    for ver in TARGETS:
        dst_dir = os.path.join(MODERN, ver, PKG)
        shutil.copyfile(src, os.path.join(dst_dir, "VoidWarehouseSelfCheck.java"))
        ext = os.path.join(dst_dir, "VoidWarehouseExtension.java")
        text = read(ext)
        if "VoidWarehouseSelfCheck" in text:
            print("%s: hook 已存在" % ver)
            continue
        old, new = crlf(text, HOOK_OLD), crlf(text, HOOK_NEW)
        if old not in text:
            raise SystemExit("!! %s: Extension 没找到 onTick 片段" % ver)
        write(ext, text.replace(old, new))
        print("%s: 已安装" % ver)


def remove():
    for ver in TARGETS:
        dst_dir = os.path.join(MODERN, ver, PKG)
        f = os.path.join(dst_dir, "VoidWarehouseSelfCheck.java")
        if os.path.exists(f):
            os.remove(f)
            print("%s: 已删除 SelfCheck" % ver)
        ext = os.path.join(dst_dir, "VoidWarehouseExtension.java")
        text = read(ext)
        if "VoidWarehouseSelfCheck" in text:
            write(ext, text.replace(crlf(text, HOOK_NEW), crlf(text, HOOK_OLD)))
            print("%s: 已摘掉 hook" % ver)


if __name__ == "__main__":
    {"install": install, "remove": remove}[sys.argv[1]]()
