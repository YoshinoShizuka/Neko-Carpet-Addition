"""把 26.3 上验证过的改动同步到 26.2 / 26.1.1 / 26.1.2。

分成两类处理：

  整体复制（这些文件各版本逐字相同，或差异仅限本轮改动）
      VoidWarehouseText.java       统一文案 / 配色出处
      VoidWarehouseData.java
      VoidWarehouseWithdraw.java
      VoidWarehousePageContainer.java
      VoidWarehouseNav.java        复制后按版本回退「染色玻璃」API

  局部 patch（文件里有各版本自己的 API 差异，不能整体覆盖）
      VoidWarehouseMenu.java       26.3 有 Prediction 重载，26.2/26.1.x 没有

各版本已知 API 差异（复制后要回退的部分）
  - 染色玻璃：26.2+ 用 Items.STAINED_GLASS_PANE.red()；26.1.x 用 Items.RED_STAINED_GLASS_PANE
  - placeItemBackInInventory(rest, Prediction.SERVER_ONLY)：仅 26.3

源文件换行在各版本之间不统一（26.2 是 LF，26.1.x 是 CRLF），
读写一律用 newline="" 原样保留，匹配字面量时先按目标文件统一换行。
"""

import io
import os
import shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODERN = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "modern"))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")
SRC = os.path.join(MODERN, "26.3", PKG)
TARGETS = ["26.2", "26.1.1", "26.1.2"]

COPY = [
    "VoidWarehouseText.java",
    "VoidWarehouseData.java",
    "VoidWarehouseWithdraw.java",
    "VoidWarehousePageContainer.java",
    "VoidWarehouseNav.java",
]

# 26.1.x 专属回退：染色玻璃还是独立字段，类注释里的说法也不一样。
# 顺序有讲究：注释整段必须排在代码单点替换【之前】—— 单点替换会连注释里的
# Items.STAINED_GLASS_PANE.red() 一起改掉，放到后面就匹配不上整段注释了。
OLD26X = [
    ("（26.3 里染色物品统一收进了\n"
     " * {@code ColorCollection}，所以是 {@code Items.STAINED_GLASS_PANE.red()}，\n"
     " * 没有 {@code Items.RED_STAINED_GLASS_PANE} 这个字段了）。点它是<b>完全的空操作</b>：",
     "（26.1.x 里染色物品还是独立字段，\n"
     " * 所以是 {@code Items.RED_STAINED_GLASS_PANE}；26.2 起才统一收进 {@code ColorCollection}）。\n"
     " * 点它是<b>完全的空操作</b>："),
    ("Items.STAINED_GLASS_PANE.red()", "Items.RED_STAINED_GLASS_PANE"),
]

# Menu 局部 patch：request 会关掉界面，必须早退，不能再 syncAllViewers
MENU_OLD = """                        VoidWarehouseData.Entry entry = this.entryAt(slotId);
                        if (entry != null && player instanceof ServerPlayer serverPlayer) {
                            VoidWarehouseWithdraw.request(serverPlayer, entry.template());
                        }
"""
MENU_NEW = """                        VoidWarehouseData.Entry entry = this.entryAt(slotId);
                        if (entry != null && player instanceof ServerPlayer serverPlayer) {
                            VoidWarehouseWithdraw.request(serverPlayer, entry.template());
                        }
                        // request 会主动把这个界面关掉（聊天栏被界面盖着就没法输入），
                        // 所以这里必须直接 return：菜单此刻已经不存在了，再往下走
                        // syncAllViewers 就是对着一个已关闭的菜单发包。
                        // 取完货由 VoidWarehouseWithdraw 自己把界面开回来（页码/开关都还在）。
                        return;
"""


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def crlf(text, s):
    """源文件可能是 CRLF 而字面量是 LF —— 统一成源文件的换行再匹配"""
    return s.replace("\n", "\r\n") if "\r\n" in text else s


def main():
    for ver in TARGETS:
        dst_dir = os.path.join(MODERN, ver, PKG)
        print("===== %s =====" % ver)

        for name in COPY:
            shutil.copyfile(os.path.join(SRC, name), os.path.join(dst_dir, name))
            print("  copied  %s" % name)

        if ver.startswith("26.1"):
            nav = os.path.join(dst_dir, "VoidWarehouseNav.java")
            text = read(nav)
            for old, new in OLD26X:
                o, n = crlf(text, old), crlf(text, new)
                if o not in text:
                    print("  [WARN] %s: Nav 没找到待回退片段: %r" % (ver, old[:40]))
                    continue
                text = text.replace(o, n)
            write(nav, text)
            print("  reverted Nav 染色玻璃 -> 26.1.x 写法")

        menu = os.path.join(dst_dir, "VoidWarehouseMenu.java")
        text = read(menu)
        if "syncAllViewers 就是对着一个已关闭的菜单发包" in text:
            print("  menu    already patched")
            continue
        o, n = crlf(text, MENU_OLD), crlf(text, MENU_NEW)
        if o not in text:
            raise SystemExit("!! %s: Menu 没找到待替换片段，手动处理" % ver)
        write(menu, text.replace(o, n))
        print("  patched VoidWarehouseMenu.java")


if __name__ == "__main__":
    main()
