#!/usr/bin/env python3
"""给五个版本的两份语言文件加 `voidWarehouseBlockDrops` 的 name / desc。

判据按项目老规矩：键已在 → 跳过；键不在 → 插在 `voidWarehouseCraft.desc` 之后（保持仓库规则聚在一起），
插完必须能过 JSON 解析、且没有重复键。逐文件保留自己的换行风格。
"""
import io
import json
import sys

TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]
BASE = "%s/src/main/resources/assets/carpet_neko_addition/lang/%s.json"

ZH = {
    "carpet.rule.voidWarehouseBlockDrops.name": "虚空仓库方块掉落",
    "carpet.rule.voidWarehouseBlockDrops.desc":
        "玩家挖掉方块时产生的掉落物不再掉在地上，直接进虚空仓库（既不进世界，也不进背包）。"
        "只认「玩家挖掉的方块」：爆炸、怪物死亡、玩家丢弃的掉落物照原样留在地上，经验球不受影响。"
        "仓库读不出来时照原样掉落，绝不静默消失；潜影盒按与漏斗入库相同的方式拆开（空盒整盒入库）。"
        "依赖「虚空仓库」",
}
EN = {
    "carpet.rule.voidWarehouseBlockDrops.name": "Void Warehouse Block Drops",
    "carpet.rule.voidWarehouseBlockDrops.desc":
        "Block drops from a block a player breaks go straight into the Void Warehouse instead of "
        "falling on the ground - they never enter the world and never enter the player's inventory. "
        "Only player-broken blocks count: explosion drops, mob drops and thrown items stay where they "
        "are, and experience orbs are untouched. If the warehouse cannot be read, drops fall normally "
        "and are never silently swallowed. Shulker boxes are unpacked exactly like hopper insertion "
        "(empty boxes are stored whole). Requires the Void Warehouse rule",
}

ANCHOR = '"carpet.rule.voidWarehouseCraft.desc"'


def patch(path, texts):
    raw = io.open(path, "rb").read()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8")
    if '"carpet.rule.voidWarehouseBlockDrops.name"' in text:
        return "skip"
    lines = text.split("\r\n") if crlf else text.split("\n")
    at = None
    for i, line in enumerate(lines):
        if line.strip().startswith(ANCHOR):
            at = i
            break
    if at is None:
        raise SystemExit("[FAIL] %s: 找不到锚点 %s" % (path, ANCHOR))
    if not lines[at].rstrip().endswith(","):
        lines[at] = lines[at].rstrip() + ","
    # 插入块的下一行原本是谁：若是收尾的 "}"，最后一条就不带逗号；否则必须带
    tail_is_brace = lines[at + 1].strip().startswith("}")
    add = [
        '  "carpet.rule.voidWarehouseBlockDrops.name": %s,' % json.dumps(texts["carpet.rule.voidWarehouseBlockDrops.name"], ensure_ascii=False),
        '  "carpet.rule.voidWarehouseBlockDrops.desc": %s%s' % (
            json.dumps(texts["carpet.rule.voidWarehouseBlockDrops.desc"], ensure_ascii=False),
            "" if tail_is_brace else ","),
    ]
    # 插在锚点之后；若锚点原本是文件的最后一项（没有内容跟在后面），后一行会变成 "}"，需要补逗号
    lines[at + 1:at + 1] = add
    out = ("\r\n" if crlf else "\n").join(lines)
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    return "ok"


def verify(path):
    text = io.open(path, encoding="utf-8").read()
    data = json.loads(text)  # 解析失败直接抛
    if text.count('"carpet.rule.voidWarehouseBlockDrops.name"') != 1:
        raise SystemExit("[FAIL] %s: name 键重复或缺失" % path)
    if "carpet.rule.voidWarehouseBlockDrops.desc" not in data:
        raise SystemExit("[FAIL] %s: desc 键缺失" % path)
    if not data["carpet.rule.voidWarehouseBlockDrops.desc"].strip():
        raise SystemExit("[FAIL] %s: desc 是空的" % path)


def main():
    for target in TARGETS:
        for lang, texts in (("zh_cn", ZH), ("en_us", EN)):
            path = BASE % (target, lang)
            state = patch(path, texts)
            verify(path)
            print("%-16s %-6s %s" % (target, lang, state))
    return 0


if __name__ == "__main__":
    sys.exit(main())
