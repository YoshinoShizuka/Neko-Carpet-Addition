#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「配方确认提示」自检装到 / 摘出 26.3。

用法：
    python install_prompt_selfcheck.py install
    python install_prompt_selfcheck.py remove

钩子挂在 VoidWarehouseExtension#onTick 的 noteServerTick 之后（任何提前 return 之前）。

🔴 这里比别的自检多一步：**跑之前把 `void_warehouse.dat` 挪走**。
配方本（learned_recipes）和仓库库存在同一个 .dat 里，而配方本在游戏里没有清空入口。
不挪的话，第二次跑自检时那些「新配方」早就在本里了 ⇒ shouldAsk 恒假 ⇒
提问分支根本不进 ⇒ B 组全红（或者更糟：被写成「已经记过」的假绿）。
换行符坑：26.3 的 VoidWarehouseExtension.java 是 CRLF，先归一 LF 匹配、写回按原样。
"""
import io, os, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

EXT = "modern/26.3/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java"
TEST_SRC = "modern/26.3/src/main/java/neko/voidwarehouse/RecipePromptSelfCheck.java"
TEMPLATE = os.path.join(SCRIPT_DIR, "RecipePromptSelfCheck.java.template")

DAT = ("modern/26.3/run/world/dimensions/minecraft/overworld/data/"
       "carpet_neko_addition/void_warehouse.dat")
DAT_BAK = DAT + ".promptbak"

HOOK_OLD = """        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。
        VoidWarehouseData.noteServerTick(server.getTickCount());
"""
HOOK_NEW = HOOK_OLD + """
        // 【临时自检】「配方要走问一句」的状态机与点击回路，跑完删除（finish() 会 server.halt 停服）
        RecipePromptSelfCheck.run(server);
"""

def read_lf(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()

def write_as_was(p, text, had_crlf):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n") if had_crlf else text)

def main():
    action = sys.argv[1]
    raw = read_lf(EXT)
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if action == "install":
        if "RecipePromptSelfCheck.run" in text:
            print("hook 已存在，跳过")
        else:
            assert HOOK_OLD in text, "hook 锚点不在（Extension 结构变了？）"
            text = text.replace(HOOK_OLD, HOOK_NEW, 1)
            write_as_was(EXT, text, had_crlf)
            print("hook 已挂")
        shutil.copyfile(TEMPLATE, TEST_SRC)
        print("RecipePromptSelfCheck.java 已安装")
        # 挪走 .dat（配方本 + 库存）
        if os.path.exists(DAT):
            if os.path.exists(DAT_BAK):
                os.remove(DAT)
                print(".dat 已在之前备份过，直接删（保留更早那份 .promptbak）")
            else:
                os.rename(DAT, DAT_BAK)
                print(".dat 已备份到 " + DAT_BAK)
        else:
            print(".dat 本来就不在（干净档），跳过")
    elif action == "remove":
        if "RecipePromptSelfCheck.run" in text:
            assert HOOK_NEW in text, "hook 内容与预期不符，人工核对"
            text = text.replace(HOOK_NEW, HOOK_OLD, 1)
            write_as_was(EXT, text, had_crlf)
            print("hook 已还原")
        else:
            print("hook 本就不在，跳过")
        if os.path.exists(TEST_SRC):
            os.remove(TEST_SRC)
            print("RecipePromptSelfCheck.java 已删除")
        if os.path.exists(DAT_BAK):
            if os.path.exists(DAT):
                os.remove(DAT)
            os.rename(DAT_BAK, DAT)
            print(".dat 已还原（自检写进去的假配方一并丢掉）")
    else:
        raise SystemExit("用法：install | remove")

if __name__ == "__main__":
    main()
