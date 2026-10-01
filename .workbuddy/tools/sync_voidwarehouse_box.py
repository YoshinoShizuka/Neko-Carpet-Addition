#!/usr/bin/env python3
"""把「虚空仓库两个 bug 的修复」同步到其余四个版本。

用法： python .workbuddy/tools/sync_voidwarehouse_box.py

改的是这两件事：
  1. 潜影盒模式取出要「优先消耗仓库里的空潜影盒」
     - VoidWarehouseWithdraw.java 五版本内容一致 → 整文件复制
     - VoidWarehouseMenu.java   各版本有真差异（ContainerInput / ClickType、
       Prediction.SERVER_ONLY、VoidWarehouseText.info）→ 只能打补丁贴那两段
  2. 对空气右键被 QuickShulker 抢走
     - 新增两个 mixin（签名已用 javap 逐一核对五版本一致）→ 整文件复制
     - 注册进各自的 carpet_neko_addition.mixins.json
"""
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "modern/26.3"
TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2", "versions/1.21.11"]

MIXIN_FILES = [
    "src/main/java/neko/voidwarehouse/mixin/ServerGamePacketListenerImplMixin.java",
    "src/main/java/neko/voidwarehouse/mixin/ServerPlayerGameModeUseItemMixin.java",
]

OLD_CHECK = """        // ① 潜影壳 / 箱子够不够？不够就整次拒绝，只发提示
        if (this.data.amountOf(Items.SHULKER_SHELL).compareTo(SHELL_NEED) < 0
                || this.data.amountOf(Items.CHEST).compareTo(CHEST_NEED) < 0) {
            this.message(player, MSG_NO_BOX_PARTS);
            return;
        }
"""

NEW_CHECK = """        // ① 盒子材料够不够？—— 优先消耗仓库里现成的空潜影盒，不够的那些才动用潜影壳 + 箱子。
        //    必须与「聊天按数量取货」用同一份算法（VoidWarehouseData.planBoxMaterials），
        //    否则玩家会遇到「库里有空盒，却在界面里被要求补潜影壳」的分裂行为。
        VoidWarehouseData.BoxMaterials plan = this.data.planBoxMaterials(1);
        if (!this.data.hasBoxMaterials(plan)) {
            this.message(player, MSG_NO_BOX_PARTS);
            return;
        }
"""

OLD_PAY = """        // ③ 盒子已经进背包了，这才扣账：物品 + 潜影壳 + 箱子（两样都在 ① 里验过，不会失败）
        this.data.take(index, fill);
        this.data.takeItem(Items.SHULKER_SHELL, VoidWarehouseShulker.SHELL_COST);
        this.data.takeItem(Items.CHEST, VoidWarehouseShulker.CHEST_COST);
"""

NEW_PAY = """        // ③ 盒子已经进背包了，这才扣账。三步都不能失败（① 已验过库存），但顺序有讲究：
        //    take(index) 必须排在最前 —— 扣空盒 / 壳 / 箱移除了别的格子会让 entries 前移，
        //    若先扣材料再按 index 取物，index 就可能已经指向别的物品了。
        this.data.take(index, fill);
        this.data.consumeBoxMaterials(plan);
"""


def patch(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        print(f"    [skip] {label}（已是新版）")
        return text
    if old not in text:
        print(f"    [WARN] {label}：找不到锚点")
        return text
    print(f"    patch {label}")
    return text.replace(old, new, 1)


def sync(target: str) -> None:
    print(f"=== {target} ===")
    base = ROOT / target

    # 1. VoidWarehouseWithdraw：五版本内容一致
    rel = "src/main/java/neko/voidwarehouse/VoidWarehouseWithdraw.java"
    shutil.copyfile(SRC / rel, base / rel)
    print(f"    copy  {rel}")

    # 2. 两个新 mixin
    #    26.3 的 ServerboundUseItemPacket 是 record（访问器 hand()），
    #    26.2 / 26.1.x / 1.21.11 还是普通类（getHand()）—— 已用 javap 逐一核对。
    for rel in MIXIN_FILES:
        dst = base / rel
        shutil.copyfile(SRC / rel, dst)
        if target != "modern/26.3":
            text = dst.read_text(encoding="utf-8")
            text = text.replace("packet.hand()", "packet.getHand()")
            dst.write_text(text, encoding="utf-8", newline="\n")
            print(f"    copy  {rel}（getHand 变体）")
        else:
            print(f"    copy  {rel}")

    # 3. Menu 打补丁
    menu = base / "src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java"
    text = menu.read_text(encoding="utf-8")
    text = patch(text, OLD_CHECK, NEW_CHECK, "Menu: 空盒优先校验")
    text = patch(text, OLD_PAY, NEW_PAY, "Menu: 空盒优先扣账")
    menu.write_text(text, encoding="utf-8", newline="\n")

    # 4. mixins.json 注册
    mj = base / "src/main/resources/carpet_neko_addition.mixins.json"
    text = mj.read_text(encoding="utf-8")
    if "ServerGamePacketListenerImplMixin" in text:
        print("    [skip] mixins.json（已注册）")
        return
    text = text.replace('    "ServerPlayerGameModeUseItemMixin",',
                        '    "ServerPlayerGameModeUseItemMixin",\n    "ServerGamePacketListenerImplMixin",', 1)
    mj.write_text(text, encoding="utf-8", newline="\n")
    print("    patch mixins.json")
    print()


if __name__ == "__main__":
    for t in TARGETS:
        sync(t)
    print("done. 接下来跑 .workbuddy/tools/sync_restock.py 同步 VoidWarehouseData，再逐个 build。")
