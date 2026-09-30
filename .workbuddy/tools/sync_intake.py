#!/usr/bin/env python3
"""把 26.3 的「入库漏斗无上限吸取」+「闸门一次性判定」同步到其余版本。

用法： python .workbuddy/tools/sync_intake.py

改动涉及 5 个文件：
  VoidWarehouseHopper.java        新增 intake() / suckEntitiesUnlimited() / absorbEntity()
  mixin/HopperBlockEntityMixin.java  HEAD 改调 intake、新增 pushItemsTick 的 TAIL 清零、删掉失效的 suckInItems 接管
  VoidWarehouseHopperFilter.java    resetCooldown 的 javadoc（说清为什么必须放在 TAIL）
  VoidWarehouse.java               空壳闸门改成「只判一次」+ guardChecked 字段
  VoidWarehouseData.java           loadedSuspicious 字段（闸门结论在构造时定死）

1.21.11 的 VoidWarehouseData 走手工移植：它用的是 officialMojangMappings，
与 26.x 只差两处（少一个 Identifier import、SavedDataType 的 id 是 String），
整文件盲拷会编译失败。
"""
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "modern/26.3" / "src/main/java/neko/voidwarehouse"

TARGETS = ["modern/26.1.1", "modern/26.1.2", "modern/26.2", "versions/1.21.11"]

FILES = [
    "VoidWarehouseHopper.java",
    "mixin/HopperBlockEntityMixin.java",
    "mixin/ContainerClassifierMixin.java",  # 契约层主闸门（Container#canPlaceItem），Lithium 也绕不过
    "mixin/SlotMixin.java",
    "VoidWarehouseHopperFilter.java",
    "VoidWarehouseClassifier.java",
    "VoidWarehouse.java",
]

# mixins.json 里必须登记的 mixin 名（写在 HopperBlockEntityMixin 之后）。
# 新加 mixin 时：先确认源 json 里有没有，再补进这里；否则目标版本 build 出来的 jar
# 会「有 class 但没挂上」，静默失效——这正是上一轮 ContainerClassifierMixin 漏同步的教训。
MIXIN_ORDER_ANCHOR = '    "HopperBlockEntityMixin",\n'
MIXIN_INSERT = '    "ContainerClassifierMixin",\n'

# 1.21.11 / VoidWarehouseData.java 的手工补丁（与上一轮同款，别乱改顺序）
DATA_PATCH_OLD = 'import net.minecraft.resources.Identifier;\n'
DATA_PATCH_OLD2 = 'Identifier.fromNamespaceAndPath("carpet_neko_addition", "void_warehouse"),\n'
DATA_PATCH_NEW2 = '"carpet_neko_addition_void_warehouse",\n'


def copy(target_root: str, rel: str) -> None:
    src = SRC / rel
    dst = ROOT / target_root / "src/main/java/neko/voidwarehouse" / rel
    if not src.exists():
        print(f"    [WARN] 源文件缺失: {rel}")
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)
    print(f"    copy  {rel}")


def sync_mixins_json(target_root: str) -> None:
    """把新 mixin 补进目标版本的 mixins.json（幂等：已存在就跳过）。"""
    dst = ROOT / target_root / "src/main/resources/carpet_neko_addition.mixins.json"
    if not dst.exists():
        print(f"    [WARN] mixins.json 缺失: {dst}")
        return
    text = dst.read_text(encoding="utf-8")
    if "ContainerClassifierMixin" in text:
        print("    skip  mixins.json（已含 ContainerClassifierMixin）")
        return
    if MIXIN_ORDER_ANCHOR not in text:
        print("    [WARN] mixins.json：找不到 HopperBlockEntityMixin 锚点，未改动")
        return
    text = text.replace(MIXIN_ORDER_ANCHOR, MIXIN_ORDER_ANCHOR + MIXIN_INSERT, 1)
    dst.write_text(text, encoding="utf-8")
    print("    patch mixins.json（补 ContainerClassifierMixin）")


def port_data_file(target_root: str) -> None:
    """从 26.3 复制 VoidWarehouseData.java 并打上 1.21.11 的两处补丁。"""
    src = SRC / "VoidWarehouseData.java"
    dst = ROOT / target_root / "src/main/java/neko/voidwarehouse/VoidWarehouseData.java"
    text = src.read_text(encoding="utf-8")
    before = text
    text = text.replace(DATA_PATCH_OLD, "")
    text = text.replace(DATA_PATCH_OLD2, DATA_PATCH_NEW2)
    if text == before:
        print("    [WARN] VoidWarehouseData.java：两处补丁都没打上，检查锚点是不是改了")
    else:
        print("    patch VoidWarehouseData.java（去 Identifier import + id 改 String）")
    dst.write_text(text, encoding="utf-8")


def main() -> None:
    for target in TARGETS:
        print(f"=== {target} ===")
        base = ROOT / target
        if not base.is_dir():
            print(f"    [ERROR] 目录不存在: {base}")
            continue
        for rel in FILES:
            copy(target, rel)
        sync_mixins_json(target)
        if target.endswith("1.21.11"):
            port_data_file(target)
        else:
            copy(target, "VoidWarehouseData.java")
        print()

    print("done. 接下来逐个 build 验证（1.21.11 的 getItemsAtAndAbove / addItem 签名要重点看）。")


if __name__ == "__main__":
    main()
