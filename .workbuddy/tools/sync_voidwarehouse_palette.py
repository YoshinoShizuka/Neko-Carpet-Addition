# -*- coding: utf-8 -*-
"""把「物品格提示配色」改动从 26.3 同步到其余四个版本。

改了两处：
  1. VoidWarehouseText.java —— 新增 RGB 常量（LABEL_RGB / VALUE_RGB / ACTION_RGB）
     与 line(String,int) / pair(...) 两个方法。五版本内容完全一致 ⇒ 整文件复制。
  2. VoidWarehouseData.java —— buildDisplay 里的颜色：数量行改分段着色、操作行改青色。
     五版本这一段完全相同 ⇒ 精确文本替换（1.21.11 的差异在文件别处，不在这一段）。

每处替换都断言「确实变了」，静默失效比崩掉更可怕。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_TREE = "modern/26.3"
TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2", "versions/1.21.11"]
REL = "src/main/java/neko/voidwarehouse"

WHOLE_FILES = ["VoidWarehouseText.java"]

OLD_BLOCK = """            // ---- 数据行：数量 +（够多时的）盒 / 组 / 个换算 ----
            lore.add(VoidWarehouseText.line("数量：" + format(amount), VoidWarehouseText.VALUE));
            String packing = describePacking(amount, max);
            if (packing != null) {
                lore.add(VoidWarehouseText.line(packing, VoidWarehouseText.VALUE));
            }

            // ---- 操作行：两种模式各说各的，绝不混着显示 ----
            if (boxMode) {
"""

NEW_BLOCK = """            // ---- 数据行：数量 +（够多时的）盒 / 组 / 个换算 ----
            // 「数量：」标签用浅灰、数字用亮金 —— 分段着色。玩家扫 tooltip 找的是「有多少」，
            // 让数字跳出来、标签退到背景里；整行一个颜色时两者会互相抢视线。
            // 亮金 #FFD700 不在原版 16 色枚举里（GOLD 偏橙），所以走 RGB 重载。
            lore.add(VoidWarehouseText.pair("数量：", VoidWarehouseText.LABEL_RGB,
                    format(amount), VoidWarehouseText.VALUE_RGB));
            String packing = describePacking(amount, max);
            if (packing != null) {
                lore.add(VoidWarehouseText.line(packing, VoidWarehouseText.VALUE_RGB));
            }

            // ---- 操作行：两种模式各说各的，绝不混着显示 ----
            // 整行统一青色（ACTION_RGB）：与上面金色的数字行形成明确分层，
            // 一眼分得出「哪行是数据、哪行是能做的事」。
            if (boxMode) {
"""


def edit_data(text, label):
    n = text.count(OLD_BLOCK)
    assert n == 1, f"{label}: 数据行块出现 {n} 次（期望 1）"
    text = text.replace(OLD_BLOCK, NEW_BLOCK)

    old_action = "VoidWarehouseText.ACTION)"
    n = text.count(old_action)
    assert n == 5, f"{label}: ACTION 出现 {n} 次（期望 5）"
    text = text.replace(old_action, "VoidWarehouseText.ACTION_RGB)")

    leftover = text.count("VoidWarehouseText.VALUE)")
    assert leftover == 0, f"{label}: 还有 {leftover} 处旧 VALUE)"
    return text


def main():
    src_dir = ROOT / SRC_TREE / REL

    for name in WHOLE_FILES:
        src = (src_dir / name).read_text(encoding="utf-8")
        assert "VALUE_RGB" in src, f"{name}: 源文件里没有 VALUE_RGB，确认 26.3 已改"
        for t in TARGETS:
            dst = ROOT / t / REL / name
            before = dst.read_text(encoding="utf-8")
            if before == src:
                print(f"  = {t}/{name} 已一致，跳过")
                continue
            dst.write_text(src, encoding="utf-8")
            print(f"  > {t}/{name} 整文件复制")

    for t in TARGETS:
        path = ROOT / t / REL / "VoidWarehouseData.java"
        text = path.read_text(encoding="utf-8")
        if "VALUE_RGB" in text and OLD_BLOCK not in text:
            print(f"  = {t}/VoidWarehouseData.java 已改过，跳过")
            continue
        path.write_text(edit_data(text, f"{t}/VoidWarehouseData.java"), encoding="utf-8")
        print(f"  > {t}/VoidWarehouseData.java 替换完成")

    print("\n同步完成。下一步：逐版本构建 TARGET_MC_VERSIONS=<ver> ./gradlew :<proj>:build dist")


if __name__ == "__main__":
    sys.exit(main())
