#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「盒模式刷潜影盒」的修复同步到其余四个版本。

用法： python .workbuddy/tools/sync_voidwarehouse_boxdup.py

改的是这四件事（26.3 是规范版本，先在 26.3 改好、跑过真实服务端自检再跑本脚本）：

  A. VoidWarehouseShulker#isBoxable —— 明确「潜影盒不能再装进盒子」，
     并给 pack() 补上「调用前必须先问」的警告。
     · 26.2 与 26.3 内容一致 → 整文件复制
     · 26.1.2 / 26.1.1 / 1.21.11 只差染料 API 与 contentsOf 的一句 → 打补丁
  B. VoidWarehouseMenu#withdrawShulkerBox —— 目标格本身是潜影盒时改走散盒取出。
     · 26.2 / 26.1.2 / 26.1.1 内容一致；26.3 与 1.21.11 各自有差异 → 一律打补丁
  C. VoidWarehouseWithdraw#execute —— 同上，聊天按数量取货那条口。
     · 五版本内容完全一致 → 整文件复制
  D. VoidWarehouseData#consumeBoxMaterials —— 空盒没扣够时按配方折算成壳 + 箱补扣，
     不再「少扣当没事」（否则同类漏账会再发生一次）。
     · 一律打补丁（各版本这个方法的正文逐字节相同）

⚠️ 换行符：五个版本的源码全是 CRLF。本脚本读入时统一转 LF 再匹配，
写回时恢复 CRLF —— 否则字符串匹配会全部落空（踩过一次）。
"""

import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
SRC = "modern/26.3"
TARGETS = ["modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse")

# ---------------------------------------------------------------- A. Shulker

SHULKER_OLD = """    /**
     * 把 {@code count} 个 {@code template} 打包成一个潜影盒。
     *
     * <p>从第 0 格开始按「每格装满」往后铺，装不下的部分留在下一格（原版潜影盒
     * 本来就允许有空位），所以 {@code count} 小于一盒容量时得到的就是一个「没装满的盒子」。</p>
     *
     * <p>{@code template} 只当样板用（数量无所谓），内部一律走
     * {@code copyWithCount(n)} 造新堆叠，<b>绝不把同一个实例挂在两处</b>。</p>
     */
    public static ItemStack pack(ItemStack template, long count) {
"""

SHULKER_NEW = """    /**
     * 这种物品能不能「装进盒子」。
     *
     * <p><b>潜影盒不行</b> —— 原版是靠 {@code ShulkerBoxBlock#canFitInsideContainerItems}
     * 在放进去那一刻挡住的（{@code minecraft:shulker_boxes} 物品标签），而 {@link #pack}
     * 是直接 {@code set(CONTAINER, ...)} 造盒子，<b>绕过</b>了那道检查。于是「盒模式点
     * 潜影盒那一格」就会造出原版根本不允许存在的「盒子套盒子」。</p>
     *
     * <p>那个嵌套盒送回仓库时会被 {@code unpackShulkerBox} 拆成「里层盒子 + 2 壳 + 1 箱」，
     * 而外盒的材料账是拿<b>同一个空盒格</b>算的（{@code planBoxMaterials}）——
     * 取货那一步先把这一格取空，扣材料时便再也扣不到，外盒等于白送。
     * 一来一回，每循环一次就凭空多出一个盒子的价值（2026-11 真踩到）。
     * 所以「目标格本身是潜影盒」时<b>一律不装盒</b>，直接按散盒交出，见两个取货口。</p>
     */
    public static boolean isBoxable(ItemStack template) {
        return template != null && !template.isEmpty() && !isShulkerBox(template);
    }

    /**
     * 把 {@code count} 个 {@code template} 打包成一个潜影盒。
     *
     * <p>从第 0 格开始按「每格装满」往后铺，装不下的部分留在下一格（原版潜影盒
     * 本来就允许有空位），所以 {@code count} 小于一盒容量时得到的就是一个「没装满的盒子」。</p>
     *
     * <p>{@code template} 只当样板用（数量无所谓），内部一律走
     * {@code copyWithCount(n)} 造新堆叠，<b>绝不把同一个实例挂在两处</b>。</p>
     *
     * <p>⚠️ <b>调用前必须先问 {@link #isBoxable}</b>：这个方法不做类型检查，
     * 拿潜影盒当模板会造出原版不允许的嵌套盒（后果见 {@link #isBoxable}）。</p>
     */
    public static ItemStack pack(ItemStack template, long count) {
"""

# ---------------------------------------------------------------- B. Menu

MENU_OLD = """        ItemStack template = target.template();
        long capacity = VoidWarehouseShulker.capacity(template);
"""

MENU_NEW = """        ItemStack template = target.template();

        // ⓪ 这一格<b>本身就是潜影盒</b> → 不装盒，直接按散盒交出 1 个（与普通模式同一口径）。
        //
        //    为什么不能照常装：pack() 是直接 set(CONTAINER, ...) 造盒子，绕过原版
        //    「潜影盒不能装进潜影盒」的检查，于是会造出「装着 N 个潜影盒的潜影盒」——
        //    玩家把外层打开就等于凭空多出 1 个盒子。更糟的是外盒的材料账
        //    （planBoxMaterials 空盒优先）恰好也从这一格算，而下面的 take() 会先把这一格
        //    取空、把格子移除，consumeBoxMaterials 便再也扣不到，于是外盒一分钱不付。
        //    实测：仓库 5 个空盒 → 交出「1 个装着 5 个空盒的盒子」（实收 6 个）→ 送回仓库
        //    变成 5 个空盒 + 2 壳 + 1 箱，来回几轮壳和箱就无限增长（2026-11 事故）。
        //    盒子本来就是盒子，没有再装一道的理由。见 VoidWarehouseShulker#isBoxable。
        if (!VoidWarehouseShulker.isBoxable(template)) {
            this.withdrawFrom(target, 0, player);
            return;
        }

        long capacity = VoidWarehouseShulker.capacity(template);
"""

# ---------------------------------------------------------------- D. Data

DATA_OLD = """    /**
     * 按 {@link #planBoxMaterials} 的结果扣账。
     *
     * <p>返回实际扣掉的空盒数量（正常应等于 {@code plan.emptyBoxes()}）；
     * 少扣了说明并发操作把空盒抢走了，调用方不必在意 —— 那部分材料本来就不该花出去。</p>
     */
    public int consumeBoxMaterials(BoxMaterials plan) {
        int taken = plan.emptyBoxes() > 0 ? this.takeEmptyBoxes(plan.emptyBoxes()) : 0;
        if (plan.shells() > 0) {
            this.takeItem(Items.SHULKER_SHELL, plan.shells());
        }
        if (plan.chests() > 0) {
            this.takeItem(Items.CHEST, plan.chests());
        }
        return taken;
    }
"""

DATA_NEW = """    /**
     * 按 {@link #planBoxMaterials} 的结果扣账。
     *
     * <h2>为什么「少扣了」可以补壳箱，而不能当成没发生</h2>
     * <p>原先这里少扣几个空盒就默默少扣了，调用方也不看返回值，注释还写着「那部分材料
     * 本来就不该花出去」。这个说法只在「空盒被别人抢走」时成立，一旦差额来自
     * <b>同一笔操作的取货</b>（目标格本身就是空盒格时 {@code take()} 会先把这一格取空），
     * 就变成「外盒白送一个」—— 送回仓库拆出 2 壳 + 1 箱，来回几轮就能无限刷盒子
     * （2026-11 事故，根因已由 {@link VoidWarehouseShulker#isBoxable} 堵掉）。</p>
     *
     * <p>所以这里不再「少扣就算」，而是<b>把差额换算成原版配方补扣</b>
     * （每缺 1 个空盒补 2 潜影壳 + 1 箱子）：账永远是平的，差额只可能表现为
     * 「玩家多花了壳箱」而不是「凭空多出一个盒子」。真补不上时留一条 WARN ——
     * 那是异常路径，必须能在日志里看见。</p>
     *
     * @return 实际扣掉的空盒数量（正常等于 {@code plan.emptyBoxes()}）
     */
    public int consumeBoxMaterials(BoxMaterials plan) {
        int taken = plan.emptyBoxes() > 0 ? this.takeEmptyBoxes(plan.emptyBoxes()) : 0;
        int shortfall = plan.emptyBoxes() - taken;
        int shells = plan.shells();
        int chests = plan.chests();
        if (shortfall > 0) {
            // 空盒没扣够 → 按原版配方把差额换算成壳 + 箱，绝不让外盒变成免费的
            shells += shortfall * VoidWarehouseShulker.SHELL_COST;
            chests += shortfall * VoidWarehouseShulker.CHEST_COST;
            LOGGER.warn("装盒材料少了 {} 个空盒可扣，按原版配方改用 {} 潜影壳 + {} 箱子补扣（"
                            + "若这条日志反复出现，说明取货路径与空盒材料共用了同一格）",
                    shortfall, shortfall * VoidWarehouseShulker.SHELL_COST,
                    shortfall * VoidWarehouseShulker.CHEST_COST);
        }
        if (shells > 0 && !this.takeItem(Items.SHULKER_SHELL, shells)) {
            LOGGER.warn("潜影壳不足，{} 个未能扣掉（仓库账目会少扣这点材料）", shells);
        }
        if (chests > 0 && !this.takeItem(Items.CHEST, chests)) {
            LOGGER.warn("箱子不足，{} 个未能扣掉（仓库账目会少扣这点材料）", chests);
        }
        return taken;
    }
"""


def read_text(path):
    """读成纯 LF，并记下这个文件原本用的行尾序列。

    ⚠️ 这里必须把**所有** CR 都吃掉，不能只换 `\\r\\n`：26.2 / 26.1.x / 1.21.11 的
    `VoidWarehouseMenu` 与 `VoidWarehouseData` 里是 `\\r\\r\\n`（历史上某次同步脚本
    对已经带 CR 的文本又做了一遍 `\\n`→`\\r\\n`）。只换 `\\r\\n` 会剩一个裸 CR 在行尾，
    锚点全部匹配不上（本次真踩到）。

    ⚠️ 写回时必须**还原原样**：改成干净的 `\\r\\n` 会让 git 认为整个文件每一行都变了
    （`\\r\\r\\n` → `\\r\\n` 在 blob 里逐行都不同），23 行的修复被埋进 2579 行的 diff 噪声里。
    """
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    if "\r\r\n" in raw:
        ending = "\r\r\n"
    elif "\r\n" in raw:
        ending = "\r\n"
    else:
        ending = "\n"
    return raw.replace("\r", ""), ending


def write_text(path, text, ending):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", ending))


def path_of(version, name):
    return os.path.join(ROOT, version, PKG, name)


def patch(version, name, old, new, label, sentinel):
    p = path_of(version, name)
    text, ending = read_text(p)
    if sentinel in text and old not in text:
        print("   %-18s %-28s 已经是新版，跳过" % (version, label))
        return
    if old not in text:
        raise SystemExit("!! %s 的 %s 里找不到锚点（%s）" % (version, name, label))
    write_text(p, text.replace(old, new, 1), ending)
    print("   %-18s %-28s 已打补丁（原行尾 %s）" % (version, label, repr(ending)))


def copy_file(version, name):
    src = path_of(SRC, name)
    body, _ = read_text(src)
    target = path_of(version, name)
    _, ending = read_text(target)
    write_text(target, body, ending)
    print("   %-18s %-28s 已整文件复制" % (version, name))


def main():
    for version in TARGETS:
        print(version)
        # C：聊天按数量取货（五版本本来逐字节一致）
        copy_file(version, "VoidWarehouseWithdraw.java")
        # A：isBoxable（26.2 与 26.3 一致，其余打补丁）
        if version == "modern/26.2":
            copy_file(version, "VoidWarehouseShulker.java")
        else:
            patch(version, "VoidWarehouseShulker.java", SHULKER_OLD, SHULKER_NEW,
                  "A isBoxable", "public static boolean isBoxable(ItemStack template) {")
        # B：界面盒模式
        patch(version, "VoidWarehouseMenu.java", MENU_OLD, MENU_NEW,
              "B 盒模式早退", "// ⓪ 这一格<b>本身就是潜影盒</b>")
        # D：材料账补扣
        patch(version, "VoidWarehouseData.java", DATA_OLD, DATA_NEW,
              "D 材料账补扣", "int shortfall = plan.emptyBoxes() - taken;")

    # ---- 收口门禁：五个版本都必须真的改到位
    print("\n== 门禁 ==")
    bad = []
    for version in [SRC] + TARGETS:
        checks = {
            "isBoxable": "boolean isBoxable(ItemStack template)" in read_text(path_of(version, "VoidWarehouseShulker.java"))[0],
            "menu早期返回": "if (!VoidWarehouseShulker.isBoxable(template)) {" in read_text(path_of(version, "VoidWarehouseMenu.java"))[0],
            "withdraw盒目标": "boolean boxTarget = !VoidWarehouseShulker.isBoxable(sample);"
                              in read_text(path_of(version, "VoidWarehouseWithdraw.java"))[0],
            "材料补扣": "int shortfall = plan.emptyBoxes() - taken;"
                        in read_text(path_of(version, "VoidWarehouseData.java"))[0],
        }
        miss = [k for k, ok in checks.items() if not ok]
        print("   %-18s %s" % (version, "OK" if not miss else "缺 " + "、".join(miss)))
        if miss:
            bad.append(version)
    if bad:
        raise SystemExit("!! 以下版本没改全：%s" % ", ".join(bad))
    print("\n五版本全部到位。")


if __name__ == "__main__":
    main()
