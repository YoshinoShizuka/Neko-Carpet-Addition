# -*- coding: utf-8 -*-
"""把本轮三处改动从 26.3 同步到其余四个版本。

改动清单：
  1. VoidWarehouseText.java                 —— 新增 INFO_PREFIX / RESTOCK_PREFIX 两个前缀常量、
                                               info(String, MutableComponent) 与 body(Component)。
                                               五版本内容一致 ⇒ 整文件复制。
  2. VoidWarehouseData.java                 —— 盒模式物品格的「消耗：潜影壳 ×N + 箱子 ×M」那行删掉。
                                               五版本一致 ⇒ 打补丁（1.21.11 只在别处有真差异）。
  3. restock/PrinterRestockNetwork.java     —— notifyTaken：前缀换「虚空仓库 」、剩余数量改亮绿、
                                               物品名不再 getString()（保持可翻译组件）。
                                               26.x 三版本一致 ⇒ 整文件复制；
                                               1.21.11 的 javadoc 与 playC2S/playS2C 有真差异 ⇒ 只打补丁。

两个铁律：
  · 整文件复制要**保留目标文件原本的行尾**（Python 默认会把 \n 翻译成 \r\n）；
  · 每处替换都断言「前后确实变了」，静默失效比崩掉更可怕。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC_TREE = "modern/26.3"
TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2", "versions/1.21.11"]
MIDDLE = ["modern/26.2", "modern/26.1.1", "modern/26.1.2"]
REL = "src/main/java/neko/voidwarehouse"

# ---------------------------------------------------------------- 行尾工具


def read(path):
    """读成「行尾统一为 \\n」的文本，便于用 \\n 写匹配模板"""
    return path.read_text(encoding="utf-8", newline="").replace("\r\n", "\n")


def write(path, text):
    """写回，并保留目标文件原本的行尾风格"""
    nl = "\r\n" if "\r\n" in path.read_text(encoding="utf-8", newline="") else "\n"
    path.write_text(text.replace("\n", nl), encoding="utf-8", newline="")


def sub(text, old, new, label, count=1):
    n = text.count(old)
    assert n == count, f"{label}: 匹配 {n} 次（期望 {count}）\n--- 模板 ---\n{old}"
    assert old != new, f"{label}: 新旧相同，替换没有意义"
    return text.replace(old, new)


# ---------------------------------------------------------------- 整文件复制

WHOLE_FILES = ["VoidWarehouseText.java"]
WHOLE_FILES_26X = ["restock/PrinterRestockNetwork.java"]


def copy_whole(src_path, dst_path):
    text = read(src_path)
    write(dst_path, text)


# ---------------------------------------------------------------- 补丁：VoidWarehouseData

DATA_OLD = """                lore.add(VoidWarehouseText.line("右键：按数量取货", VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line("消耗：潜影壳 ×" + VoidWarehouseShulker.SHELL_COST
                        + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST, VoidWarehouseText.COST));
"""

DATA_NEW = """                lore.add(VoidWarehouseText.line("右键：按数量取货", VoidWarehouseText.ACTION_RGB));
                // 这里原先还有一行，写明装盒要额外消耗多少个潜影壳与箱子（红色）。玩家要求去掉：
                // 物品格是「拿东西」的地方，代价那行挤进来只会让人以为「取这一份要额外掏材料」。
                // 材料账本身没变，说明留在「潜影盒模式」开关那个图标的 tooltip 上（VoidWarehouseNav）。
"""


def patch_data(path):
    text = read(path)
    text = sub(text, DATA_OLD, DATA_NEW, f"{path}: 盒模式「消耗」行")
    # 注意：文件别处还有「消耗潜影壳 / 箱子」的 javadoc（讲扣减逻辑），所以只能断言
    # 「那段 lore 构造代码」没了，不能断言「消耗」二字消失。
    assert DATA_OLD.strip() not in text, f"{path}: 盒模式「消耗」行还在"
    # SHELL_COST 在别处（装盒扣材料的逻辑与 javadoc）仍然合法使用，只能断言这行 lore 的文案没了
    assert "消耗：潜影壳 ×" not in text, f"{path}: 还残留「消耗：潜影壳 ×」"
    write(path, text)


# ---------------------------------------------------------------- 补丁：PrinterRestockNetwork（1.21.11）

NET_OLD = """        String name = outcome.sample().getHoverName().getString();
        // 配色按玩家的口径：只有「取走了多少个」是这条消息里要跳出来的关键数字（亮绿），
        // 其余——含「仓库还剩多少」——一律灰色。剩下的量是背景信息，亮着反而抢走注意力。
        player.sendSystemMessage(VoidWarehouseText.info(
                VoidWarehouseText.body("取走 ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(outcome.takenCount())))
                        .append(VoidWarehouseText.body(" 个 " + name
                                + "，仓库剩余 " + VoidWarehouseText.count(outcome.remaining()) + " 个"))));
"""

NET_NEW = """        // 配色按玩家的口径：前缀亮金；正文灰；「取走了多少个」和「仓库还剩多少个」
        // 这两个数字是玩家真正要读的，都用亮绿跳出来。
        // 前缀是「虚空仓库」而不是默认的「[信息]」—— 这条是自动、高频发的消息，
        // 得让玩家一眼看出是谁在补货。
        //
        // 物品名必须保持「可翻译组件」原样发出去，绝不能在服务端 getString()：
        // 单人存档里客户端与服务端同进程、客户端启动时已把语言表注入静态单例，
        // 所以看起来正常；专用服务器没人注入，单例一直是 en_us，
        // 同一个物品会解析成「dirt」发给中文客户端。
        player.sendSystemMessage(VoidWarehouseText.info(VoidWarehouseText.RESTOCK_PREFIX,
                VoidWarehouseText.body("取走 ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(outcome.takenCount())))
                        .append(VoidWarehouseText.body(" 个 "))
                        .append(VoidWarehouseText.body(outcome.sample().getHoverName()))
                        .append(VoidWarehouseText.body("，仓库剩余 "))
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(outcome.remaining())))
                        .append(VoidWarehouseText.body(" 个"))));
"""


def patch_network(path):
    text = read(path)
    text = sub(text, NET_OLD, NET_NEW, f"{path}: notifyTaken")
    assert "getHoverName().getString()" not in text, f"{path}: 还残留 getHoverName().getString()"
    write(path, text)


# ---------------------------------------------------------------- 主流程


def main():
    src_dir = ROOT / SRC_TREE / REL
    for rel in WHOLE_FILES + WHOLE_FILES_26X:
        assert (src_dir / rel).exists(), f"26.3 缺少 {rel}"

    for target in TARGETS:
        dst_dir = ROOT / target / REL

        # ① 五版本整文件复制：VoidWarehouseText
        for rel in WHOLE_FILES:
            copy_whole(src_dir / rel, dst_dir / rel)
            assert read(src_dir / rel) == read(dst_dir / rel), f"{target}/{rel} 复制后不一致"
            print(f"{target}: 复制 {rel}")

        # ② 26.x 三版本再整文件复制 PrinterRestockNetwork（1.21.11 版本差异太多，走补丁）
        if target in MIDDLE:
            for rel in WHOLE_FILES_26X:
                copy_whole(src_dir / rel, dst_dir / rel)
                assert read(src_dir / rel) == read(dst_dir / rel), f"{target}/{rel} 复制后不一致"
                print(f"{target}: 复制 {rel}")
        else:
            patch_network(dst_dir / "restock/PrinterRestockNetwork.java")
            print(f"{target}: 打补丁 restock/PrinterRestockNetwork.java")

        # ③ 所有版本都打补丁：VoidWarehouseData
        patch_data(dst_dir / "VoidWarehouseData.java")
        print(f"{target}: 打补丁 VoidWarehouseData.java")

    print("全部同步完成")


if __name__ == "__main__":
    sys.exit(main())
