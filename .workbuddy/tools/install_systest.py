#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 SystemTest（系统验收）装到 / 摘出任意一个版本。

用法：
    python install_systest.py <ver> install
    python install_systest.py <ver> remove
    python install_systest.py all remove          # 五版本一起清

<ver> ∈ 26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11

钩子挂在 VoidWarehouseExtension#onTick 的 noteServerTick 之后（任何提前 return 之前）。
五版本的 Extension 与 Data 源码是逐字节相同的副本，所以锚点只有一份。

1.21.11 差异（模板只保留 26.x 写法，安装时做定点替换）：
    ContainerInput                → ClickType
    SingleRecipeInput             → （同）
    ClientInformation             → （同，构造参数个数不同 → 见 PORT 里的整行替换）
    CommonListenerCookie.createInitial(profile, false)  → （1.21.11 无此重载）
换行符坑：五版本的 VoidWarehouseExtension.java 都是 CRLF，先归一 LF 匹配、写回按原样。
"""
import io, os, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

TEMPLATE = os.path.join(SCRIPT_DIR, "SystemTest.java.template")
ALL = ["26.3", "26.2", "26.1.2", "26.1.1", "1.21.11"]


def proj(ver):
    return ("versions/" if ver == "1.21.11" else "modern/") + ver


def ext_path(ver):
    return proj(ver) + "/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java"


def test_path(ver):
    return proj(ver) + "/src/main/java/neko/voidwarehouse/SystemTest.java"


HOOK_OLD = """        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。
        VoidWarehouseData.noteServerTick(server.getTickCount());
"""
HOOK_NEW = HOOK_OLD + """
        // 【临时系统测试】全模组验收，跑完删除（finish() 会 server.halt 停服）
        SystemTest.run(server);
"""

# 26.2 起才有的 ColorCollection 写法（模板沿用 26.2/26.3 拼法）
#   Items.DYED_SHULKER_BOX.pick(BROWN) → Items.BROWN_SHULKER_BOX
#   Items.DYE.pick(BROWN)              → Items.BROWN_DYE
PORT_OLD_COLOR = [
    ('Items.DYED_SHULKER_BOX.pick(DyeColor.BROWN)', 'Items.BROWN_SHULKER_BOX'),
    ('Items.DYE.pick(DyeColor.BROWN)', 'Items.BROWN_DYE'),
]

# 1.21.11 额外差异（模板只保留 26.x 写法，安装时做定点替换）
# 注：ContainerInput 是 26.x 独有类型，但模板里并没用它（import 已删），故这里只需颜色项。
PORT_12111 = PORT_OLD_COLOR + []


def read_lf(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write_as_was(p, text, had_crlf):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n") if had_crlf else text)


def install(ver):
    raw = read_lf(ext_path(ver))
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "SystemTest.run" in text:
        print("  hook 已存在，跳过")
    else:
        assert HOOK_OLD in text, "hook 锚点不在（%s 的 Extension 结构变了？）" % ver
        write_as_was(ext_path(ver), text.replace(HOOK_OLD, HOOK_NEW, 1), had_crlf)
        print("  hook 已挂")

    src = read_lf(TEMPLATE)
    if ver == "1.21.11":
        table = PORT_12111
    elif ver in ("26.1.2", "26.1.1"):
        table = PORT_OLD_COLOR
    else:
        table = []
    for a, b in table:
        assert a in src, "%s 的移植串不在模板里：%s" % (ver, a)
        src = src.replace(a, b)
    with io.open(test_path(ver), "w", encoding="utf-8", newline="") as f:
        f.write(src)
    print("  SystemTest.java 已安装（%d 行）" % src.count("\n"))


def remove(ver):
    raw = read_lf(ext_path(ver))
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "SystemTest.run" in text:
        assert HOOK_NEW in text, "hook 内容与预期不符（%s），人工核对" % ver
        write_as_was(ext_path(ver), text.replace(HOOK_NEW, HOOK_OLD, 1), had_crlf)
        print("  hook 已还原")
    else:
        print("  hook 本就不在，跳过")
    p = test_path(ver)
    if os.path.exists(p):
        os.remove(p)
        print("  SystemTest.java 已删除")


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: install_systest.py <%s|all> install|remove" % "|".join(ALL))
    ver, action = sys.argv[1], sys.argv[2]
    vers = ALL if ver == "all" else [ver]
    for v in vers:
        print("[%s]" % v)
        if action == "install":
            install(v)
        elif action == "remove":
            remove(v)
        else:
            raise SystemExit("动作只能是 install / remove")


if __name__ == "__main__":
    main()
