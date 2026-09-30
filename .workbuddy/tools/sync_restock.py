#!/usr/bin/env python3
"""把 modern/26.3 里的打印机补货（restock）改动同步到其余版本。

用法： python .workbuddy/tools/sync_restock.py

做的事（每个目标版本一份）：
  1. 整包复制 neko/voidwarehouse/restock/{4 个文件}
  2. 复制 VoidWarehouseData.java（26.x 之间内容完全一致，只多了 takeStack）
  3. 复制 AutoMendingMod.java / VoidWarehouseExtension.java（只多了注册入口）
  4. 补丁：build.gradle 加 fabric-api 依赖、gradle.properties 加 fabric_version、
     fabric.mod.json 的 depends 加 "fabric-api": "*"、两份 lang 加新规则文案
"""
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

TARGETS = {
    "26.1.1": {"root": "modern/26.1.1", "fabric_version": "0.145.4+26.1.1"},
    "26.1.2": {"root": "modern/26.1.2", "fabric_version": "0.155.3+26.1.2"},
    "26.2": {"root": "modern/26.2", "fabric_version": "0.161.0+26.2"},
    "1.21.11": {
        "root": "versions/1.21.11",
        "fabric_version": "0.141.6+1.21.11",
        # 1.21.11 的数据层其实和 26.x 同构（都是 SavedDataType + Codec），
        # 全文件只差两处：少一个 Identifier import、SavedDataType 的 id 是 String 而非 Identifier。
        # 但这两处差异让「整文件盲拷」会编译失败，所以 VoidWarehouseData 仍走手工移植（见文档）。
        "whole_files": [
            # VoidWarehouse 只用通用 API（MinecraftServer/ServerLevel/NBT/DimensionType），
            # 1.21.11 走 officialMojangMappings，这些名字一致，可以整文件复制。
            "src/main/java/neko/voidwarehouse/VoidWarehouse.java",
            "src/main/java/neko/automending/AutoMendingMod.java",
            # AutoMending：只依赖 ServerPlayer / EnchantmentHelper / EnchantmentEffectComponents
            # / ItemStack，五个版本（含 1.21.11 的 Mojang 官方映射）名字完全一致，可整文件复制。
            "src/main/java/neko/automending/AutoMending.java",
            "src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java",
            "src/main/resources/assets/carpet_neko_addition/lang/zh_cn.json",
            "src/main/resources/assets/carpet_neko_addition/lang/en_us.json",
        ],
    },
}

SRC = ROOT / "modern/26.3"

# 整文件复制（内容在 26.x 之间一致，只是多了打印机的那几段）
WHOLE_FILES = [
    # VoidWarehouse：本轮加了「空壳闸门」+ 写盘前 .bak 快照。
    # 它只用 MinecraftServer / ServerLevel / ServerPlayer / NBT / DimensionType +
    # LevelResource，五个版本（含 1.21.11 的 Mojang 官方映射）这些名字完全一样，
    # 所以可以整文件复制。这是防「静默清空」的关键文件，必须五个版本一起到位。
    "src/main/java/neko/voidwarehouse/VoidWarehouse.java",
    "src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
    # VoidWarehouseText：聊天栏提示（[信息] 前缀 + 千分位）也要在别的包里用，
    # 所以 info()/count() 由 package-private 提成了 public。五个版本内容完全一致。
    "src/main/java/neko/voidwarehouse/VoidWarehouseText.java",
    "src/main/java/neko/automending/AutoMendingMod.java",
    # AutoMending：自动经验修补的经验记账。它【不在】common 里，而是每个版本一份，
    # 所以改了必须五版本一起同步 —— 否则会出现「26.3 修好了、别的版本还是旧的 2 倍扣减」。
    # 只用 ServerPlayer / EnchantmentHelper / ItemStack，五个版本内容本来就必须一致。
    "src/main/java/neko/automending/AutoMending.java",
    "src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java",
    "src/main/resources/assets/carpet_neko_addition/lang/zh_cn.json",
    "src/main/resources/assets/carpet_neko_addition/lang/en_us.json",
]

RESTOCK_FILES = [
    "PrinterTakePayload.java",
    "PrinterTakeResultPayload.java",
    "PrinterTakeService.java",
    "PrinterRestockNetwork.java",
    "VoidWarehouseRestockSettings.java",
]

# 逐版本排除：这些文件在 26.x 之间一致，但 1.21.11 有版本特定的写法，
# 整包复制会把已经调好的东西改坏。
#
# PrinterRestockNetwork：Fabric API 的注册入口在 1.21.x 叫 playC2S()/playS2C()，
#   26.x（fabric-api 6.3.x）改名成了 serverboundPlay()/clientboundPlay()。
#   无脑覆盖 → 1.21.11 直接编译失败。
SKIP_PER_TARGET = {
    "1.21.11": {"PrinterRestockNetwork.java"},
}

FABRIC_DEP_IMPL = (
    '    // Fabric API：只需要 networking 那一部分，但 fabric-api 的方便之处就在于\n'
    '    // 一个坐标把整套拉下来、版本自动对齐（不必逐个模块指定版本号）。\n'
    '    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"\n'
)

FABRIC_DEP_MOD = (
    '    // Fabric API：1.21.x 需要 remap，用 modImplementation。\n'
    '    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"\n'
)

FABRIC_PROP = (
    '\n'
    '# Fabric API：打印机联动的 C2S/S2C 数据包要用它的 networking 抽象。\n'
    '# 版本号从 Fabric maven-metadata 里挑「for Minecraft <本版本>」的最新一个。\n'
    '# 这只是普通依赖，运行时需要玩家另行安装 Fabric API，所以在 fabric.mod.json\n'
    '# 里还写了一条 depends，服务端缺桩时会在加载阶段给出明确提示。\n'
    'fabric_version={version}\n'
)


def patch_once(text: str, anchor: str, addition: str, already_marker: str, label: str) -> str:
    """在 anchor 之后插入 addition；已经插过（含 already_marker）就原样返回。"""
    if already_marker in text:
        print(f"    [skip] {label}（已存在）")
        return text
    if anchor not in text:
        print(f"    [WARN] {label}：找不到锚点 -> {anchor!r}")
        return text
    print(f"    patch {label}")
    return text.replace(anchor, anchor + addition, 1)


def sync(target_name: str, cfg: dict) -> None:
    print(f"=== {target_name} ===")
    base = ROOT / cfg["root"]
    if not base.is_dir():
        print(f"    [ERROR] 目录不存在: {base}")
        return

    # 1. restock 包
    dst_dir = base / "src/main/java/neko/voidwarehouse/restock"
    dst_dir.mkdir(parents=True, exist_ok=True)
    skip = SKIP_PER_TARGET.get(target_name, set())
    for name in RESTOCK_FILES:
        src = SRC / "src/main/java/neko/voidwarehouse/restock" / name
        # 自检是临时的，不同步
        if not src.exists():
            print(f"    [skip] {name}（源不存在）")
            continue
        if name in skip:
            print(f"    [skip] {name}（{target_name} 有版本特定写法，不覆盖）")
            continue
        shutil.copyfile(src, dst_dir / name)
        print(f"    copy  restock/{name}")

    # 2. 整文件覆盖
    for rel in cfg.get("whole_files", WHOLE_FILES):
        src = SRC / rel
        if not src.exists():
            print(f"    [WARN] 源文件缺失: {rel}")
            continue
        dst = base / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        print(f"    copy  {rel}")

    # 3. build.gradle 加依赖
    bg = base / "build.gradle"
    text = bg.read_text(encoding="utf-8")
    addition = FABRIC_DEP_MOD if target_name.startswith("1.") else FABRIC_DEP_IMPL
    text = patch_once(text, "dependencies {\n", addition, "fabric-api", "build.gradle: fabric-api 依赖")
    bg.write_text(text, encoding="utf-8")

    # 4. gradle.properties 加 fabric_version
    gp = base / "gradle.properties"
    text = gp.read_text(encoding="utf-8")
    text = patch_once(text, "\n# ===== Mod 元信息",
                      FABRIC_PROP.format(version=cfg["fabric_version"]),
                      "fabric_version=", "gradle.properties: fabric_version")
    gp.write_text(text, encoding="utf-8")

    # 5. fabric.mod.json 加 depends
    fm = base / "src/main/resources/fabric.mod.json"
    text = fm.read_text(encoding="utf-8")
    if '"fabric-api"' in text:
        print("    [skip] fabric.mod.json:depends（已存在）")
    else:
        for anchor in ('"carpet": ">=26.3"', '"carpet": ">=26.2"', '"carpet": ">=26.1"',
                       '"carpet": ">=1.21.11"', '"carpet": "*"'):
            if anchor in text:
                text = text.replace(anchor, anchor + ',\n    "fabric-api": "*"', 1)
                print("    patch fabric.mod.json:depends")
                break
        else:
            print("    [WARN] fabric.mod.json：找不到 depends 锚点")
    fm.write_text(text, encoding="utf-8")

    print()


if __name__ == "__main__":
    for name, cfg in TARGETS.items():
        sync(name, cfg)
    print("done. 接下来请逐个 ./gradlew :modern:<v>:build 验证。")
