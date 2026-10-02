# -*- coding: utf-8 -*-
"""把「聊天提示配色 + 去粗去斜 + 取出 N 个」改动从 26.3 同步到其余四个版本。

改动清单：
  1. VoidWarehouseText.java        —— 新增聊天三色（前缀亮金 / 正文灰 / 关键数字亮绿）、
                                      body() / key() / info(MutableComponent)，
                                      并给所有造文本的方法显式 withItalic(false)。
                                      五版本内容一致 ⇒ 整文件复制。
  2. VoidWarehouseWithdraw.java    —— MSG_TOO_LARGE 与取货总结改分段着色。五版本一致 ⇒ 整文件复制。
  3. restock/PrinterRestockNetwork —— 中键取货提示改分段着色。
                                      26.x 三版本一致 ⇒ 整文件复制；
                                      1.21.11 有真差异（playC2S/playS2C）⇒ 只打补丁。
  4. VoidWarehouseData.java        —— 「右键：取出 1 组（64 个）」改为「右键：取出 64 个」。打补丁。
  5. VoidWarehouseMenu.java        —— MSG_NO_BOX_PARTS 的两个数字改亮绿。
                                      26.2 / 26.1.1 / 26.1.2 额外修一处跨版本分叉：
                                      这三个版本的消息是自己 Component.literal 的（没有 [信息] 前缀），
                                      且内联了一份 infoMessage()（金色 + 加粗），文案也和 26.3 不同。
                                      统一改成 26.3 那套 VoidWarehouseText.info(...)，并删掉 infoMessage。

两个铁律：
  · 整文件复制要**保留目标文件原本的行尾**（Python 默认会把 \n 翻译成 \r\n，
    上一轮就是这样把四个目标的 Text.java 从 CRLF 变成了 LF，制造了一整文件的假 diff）；
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
    """精确替换，断言命中次数"""
    n = text.count(old)
    assert n == count, f"{label}: 匹配 {n} 次（期望 {count}）\n--- 模板 ---\n{old}"
    assert old != new, f"{label}: 新旧相同，替换没有意义"
    return text.replace(old, new)


# ---------------------------------------------------------------- 整文件复制

WHOLE_FILES = ["VoidWarehouseText.java", "VoidWarehouseWithdraw.java"]
WHOLE_FILES_26X = ["restock/PrinterRestockNetwork.java"]

# ---------------------------------------------------------------- 补丁模板

# --- VoidWarehouseData：右键取出文案 ---
DATA_OLD = '''                lore.add(VoidWarehouseText.line("左键：取出 1 个", VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line(
                        "右键：取出 1 组（" + max + " 个）", VoidWarehouseText.ACTION_RGB));'''
DATA_NEW = '''                lore.add(VoidWarehouseText.line("左键：取出 1 个", VoidWarehouseText.ACTION_RGB));
                // 直接写数字而不是「1 组（64 个）」：玩家真正要读的是「右键给我多少个」，
                // 括号里那个数字和「组」说的是同一件事，绕一道反而更慢。
                lore.add(VoidWarehouseText.line(
                        "右键：取出 " + max + " 个", VoidWarehouseText.ACTION_RGB));'''

DATA_DOC_OLD = "（「数量：N / 左键：取出 1 个 / 右键：取出一组」）"
DATA_DOC_NEW = "（「数量：N / 左键：取出 1 个 / 右键：取出 64 个」）"
DATA_DOC2_OLD = "「左键取出 1 个 / 右键取出一组」"
DATA_DOC2_NEW = "「左键取出 1 个 / 右键取出一整组」"

# --- VoidWarehouseMenu：材料提示的两个数字改亮绿（五版本通用） ---
PARTS_OLD = '''    private static final Component MSG_NO_BOX_PARTS = VoidWarehouseText.info(
            "潜影壳或箱子不足，无法装盒（每盒需 潜影壳 ×" + VoidWarehouseShulker.SHELL_COST
                    + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST + "）");'''
PARTS_NEW = '''    private static final Component MSG_NO_BOX_PARTS = VoidWarehouseText.info(
            VoidWarehouseText.body("潜影壳或箱子不足，无法装盒（每盒需 潜影壳 ×")
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.SHELL_COST)))
                    .append(VoidWarehouseText.body(" + 箱子 ×"))
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.CHEST_COST)))
                    .append(VoidWarehouseText.body("）")));'''

COMMENT_OLD = "    // 聊天提示统一走 VoidWarehouseText.info()：带金色 [信息] 前缀，一眼能和普通聊天分开"
COMMENT_NEW = ("    // 聊天提示统一走 VoidWarehouseText.info()：亮金色 [信息] 前缀 + 灰色正文，\n"
               "    // 一眼能和普通聊天分开；正文里的关键数字用 VoidWarehouseText.key() 标亮绿。")

# --- 26.2 / 26.1.1 / 26.1.2 专属：整个消息块换掉（含删掉两个已无用的常量） ---
MID_BLOCK_OLD = '''    /** 装一个潜影盒需要的潜影壳 / 箱子数量（{@link BigInteger} 形式，方便和仓库数量直接比） */
    private static final BigInteger SHELL_NEED = BigInteger.valueOf(VoidWarehouseShulker.SHELL_COST);
    private static final BigInteger CHEST_NEED = BigInteger.valueOf(VoidWarehouseShulker.CHEST_COST);

    private static final Component MSG_FULL = Component.literal("虚空仓库已满，无法放入");
    private static final Component MSG_RESCUED =
            Component.literal("背包已满，手上拿着的物品已存入虚空仓库");
    private static final Component MSG_INV_FULL =
            Component.literal("背包已满，放不下的物品留在了虚空仓库");
    private static final Component MSG_REJECTED =
            Component.literal("该物品带有额外数据（耐久 / 附魔 / 自定义名等），虚空仓库拒收");
    private static final Component MSG_BOX_REJECTED =
            Component.literal("潜影盒里含有带额外数据的物品（耐久 / 附魔 / 自定义名等），整盒拒收");
    private static final Component MSG_SHULKER_ON = infoMessage("潜影盒模式开");
    private static final Component MSG_SHULKER_OFF = infoMessage("潜影盒模式关");
    private static final Component MSG_NO_BOX_PARTS =
            Component.literal("仓库里的潜影壳或箱子不足，无法装盒（每盒需要 潜影壳 ×"
                    + VoidWarehouseShulker.SHELL_COST + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST + "）");
    private static final Component MSG_BOX_INV_FULL =
            Component.literal("背包放不下潜影盒，本次取出已取消（仓库未扣减任何物品）");
    private static final Component MSG_SORT_ON = infoMessage("数量排序开");
    private static final Component MSG_SORT_OFF = infoMessage("数量排序关");'''

MID_BLOCK_NEW = '''    // 聊天提示统一走 VoidWarehouseText.info()：亮金色 [信息] 前缀 + 灰色正文，
    // 一眼能和普通聊天分开；正文里的关键数字用 VoidWarehouseText.key() 标亮绿。
    private static final Component MSG_FULL = VoidWarehouseText.info("虚空仓库已满，放不进去了");
    private static final Component MSG_RESCUED =
            VoidWarehouseText.info("背包已满，手上的物品已存入虚空仓库");
    private static final Component MSG_INV_FULL =
            VoidWarehouseText.info("背包已满，放不下的留在虚空仓库");
    private static final Component MSG_REJECTED =
            VoidWarehouseText.info("带额外数据的物品（耐久 / 附魔 / 改名）拒收");
    private static final Component MSG_BOX_REJECTED =
            VoidWarehouseText.info("盒内有带额外数据的物品，整盒拒收");
    private static final Component MSG_SHULKER_ON = VoidWarehouseText.info("潜影盒模式开");
    private static final Component MSG_SHULKER_OFF = VoidWarehouseText.info("潜影盒模式关");
    private static final Component MSG_NO_BOX_PARTS = VoidWarehouseText.info(
            VoidWarehouseText.body("潜影壳或箱子不足，无法装盒（每盒需 潜影壳 ×")
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.SHELL_COST)))
                    .append(VoidWarehouseText.body(" + 箱子 ×"))
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.CHEST_COST)))
                    .append(VoidWarehouseText.body("）")));
    private static final Component MSG_BOX_INV_FULL =
            VoidWarehouseText.info("背包放不下盒子，本次取消（仓库未扣减）");
    private static final Component MSG_SORT_ON = VoidWarehouseText.info("数量排序开");
    private static final Component MSG_SORT_OFF = VoidWarehouseText.info("数量排序关");'''

INFO_MSG_OLD = '''    /**
     * 把提示包装成「金色 + [信息] 前缀」的聊天栏消息。
     *
     * <p>只用于两个模式开关（数量排序 / 潜影盒模式）的切换提示，让玩家一眼看出这是一条
     * 「信息类」提醒而非错误。前缀 {@code [信息]} 用加粗金色、正文用金色。</p>
     */
    private static Component infoMessage(String body) {
        return Component.literal("[信息] ")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(ChatFormatting.BOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.GOLD));
    }

'''
INFO_MSG_NEW = ''

CHAT_FMT_IMPORT = "import net.minecraft.ChatFormatting;\n"

# --- 1.21.11 专属：只换 notifyTaken 的正文构造 ---
PRINTER_OLD = '''        String body = "取走 " + VoidWarehouseText.count(outcome.takenCount()) + " 个 " + name
                + "，仓库剩余 " + VoidWarehouseText.count(outcome.remaining()) + " 个";
        player.sendSystemMessage(VoidWarehouseText.info(body));'''
PRINTER_NEW = '''        // 配色按玩家的口径：只有「取走了多少个」是这条消息里要跳出来的关键数字（亮绿），
        // 其余——含「仓库还剩多少」——一律灰色。剩下的量是背景信息，亮着反而抢走注意力。
        player.sendSystemMessage(VoidWarehouseText.info(
                VoidWarehouseText.body("取走 ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(outcome.takenCount())))
                        .append(VoidWarehouseText.body(" 个 " + name
                                + "，仓库剩余 " + VoidWarehouseText.count(outcome.remaining()) + " 个"))));'''


# ---------------------------------------------------------------- 执行


def main():
    src_dir = ROOT / SRC_TREE / REL

    # 前置校验：确认 26.3 的改动都在
    src_text = read(src_dir / "VoidWarehouseText.java")
    for token in ("INFO_PREFIX_RGB", "INFO_BODY_RGB", "INFO_KEY_RGB", "withItalic(false)"):
        assert token in src_text, f"26.3/VoidWarehouseText.java 缺少 {token}，确认源文件已改"
    assert "右键：取出 \" + max" in read(src_dir / "VoidWarehouseData.java"), "26.3/Data 文案未改"
    assert "VoidWarehouseText.key(" in read(src_dir / "VoidWarehouseMenu.java"), "26.3/Menu 未改"

    print("== 整文件复制（保留目标行尾）==")
    for t in TARGETS:
        for name in WHOLE_FILES:
            s = read(src_dir / name)
            dst = ROOT / t / REL / name
            before = read(dst)
            if before == s:
                print(f"  = {t}/{name} 已一致，跳过")
                continue
            write(dst, s)
            assert read(dst) == s, f"{t}/{name} 写入后比对不一致"
            print(f"  > {t}/{name} 整文件复制")

    for t in MIDDLE:
        for name in WHOLE_FILES_26X:
            s = read(src_dir / name)
            dst = ROOT / t / REL / name
            if read(dst) == s:
                print(f"  = {t}/{name} 已一致，跳过")
                continue
            write(dst, s)
            assert read(dst) == s
            print(f"  > {t}/{name} 整文件复制")

    print("\n== 1.21.11 / PrinterRestockNetwork 打补丁 ==")
    p = ROOT / "versions/1.21.11" / REL / "restock/PrinterRestockNetwork.java"
    text = read(p)
    if PRINTER_OLD in text:
        write(p, sub(text, PRINTER_OLD, PRINTER_NEW, "1.21.11/PrinterRestockNetwork"))
        print("  > 1.21.11/PrinterRestockNetwork notifyTaken 已改")
    else:
        print("  = 1.21.11/PrinterRestockNetwork 已改过，跳过")

    print("\n== VoidWarehouseData 打补丁 ==")
    for t in TARGETS:
        p = ROOT / t / REL / "VoidWarehouseData.java"
        text = read(p)
        if DATA_NEW in text:
            print(f"  = {t}/VoidWarehouseData 已改过，跳过")
            continue
        text = sub(text, DATA_OLD, DATA_NEW, f"{t}/Data: 右键文案")
        text = sub(text, DATA_DOC_OLD, DATA_DOC_NEW, f"{t}/Data: 类注释")
        text = sub(text, DATA_DOC2_OLD, DATA_DOC2_NEW, f"{t}/Data: displayInBoxMode 注释")
        write(p, text)
        print(f"  > {t}/VoidWarehouseData 已改")

    print("\n== VoidWarehouseMenu 打补丁 ==")
    for t in TARGETS:
        p = ROOT / t / REL / "VoidWarehouseMenu.java"
        text = read(p)
        if t in MIDDLE:
            # 这三个版本是分叉出去的：整块消息 + 内联 infoMessage 一起换掉
            if MID_BLOCK_OLD in text:
                text = sub(text, MID_BLOCK_OLD, MID_BLOCK_NEW, f"{t}/Menu: 消息块")
            if INFO_MSG_OLD in text:
                text = sub(text, INFO_MSG_OLD, INFO_MSG_NEW, f"{t}/Menu: 删 infoMessage")
            if "ChatFormatting" in text and "ChatFormatting." not in text.replace(
                    CHAT_FMT_IMPORT, ""):
                text = sub(text, CHAT_FMT_IMPORT, "", f"{t}/Menu: 删 ChatFormatting import")
        else:
            if PARTS_OLD in text:
                text = sub(text, PARTS_OLD, PARTS_NEW, f"{t}/Menu: 材料提示数字")
            if COMMENT_OLD in text:
                text = sub(text, COMMENT_OLD, COMMENT_NEW, f"{t}/Menu: 注释")
        new_text = text
        old_text = read(p)
        if new_text == old_text:
            print(f"  = {t}/VoidWarehouseMenu 无变化")
            continue
        write(p, new_text)
        print(f"  > {t}/VoidWarehouseMenu 已改")

    print("\n同步完成。下一步：逐版本构建 "
          "TARGET_MC_VERSIONS=<ver> ./gradlew :<proj>:build dist sourcesJar")


if __name__ == "__main__":
    sys.exit(main())
