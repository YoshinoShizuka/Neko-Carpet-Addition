#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「材料账预留」同步到另外四个版本。

用法：
    python sync_reserve.py

三份改动：
  1. `VoidWarehouseWithdraw.java` —— 五份本来就逐字节相同 ⇒ 二进制复制；
  2. `VoidWarehouseData.java` —— 新增 materialStock / hasBoxMaterials(3 参) /
     maxBoxesUpTo(2 参) / canMakeBoxes，并给 1 参版加一句「只当上界用」的告警；
  3. `VoidWarehouseMenu.java` —— 撤掉「壳/箱直接不装盒」的判据，换成「先收一点、留够材料」。

🔴 全部逐行定点插入 / 替换，新增行用目标文件自己的换行（26.2 / 26.1.x / 1.21.11 是 CRCRLF）。
"""
import io
import os
import shutil
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

BASE = "src/main/java/neko/voidwarehouse/"
SRC = "modern/26.3/" + BASE
VERS = [
    ("modern/26.2/" + BASE, "modern/26.2/"),
    ("modern/26.1.2/" + BASE, "modern/26.1.2/"),
    ("modern/26.1.1/" + BASE, "modern/26.1.1/"),
    ("versions/1.21.11/" + BASE, "versions/1.21.11/"),
]

METHODS = [
    "    /**",
    "     * 材料账上还剩多少：<b>把「马上就要离库的那批货」先刨掉</b>。",
    "     *",
    "     * <p>只对潜影壳 / 箱子有意义 —— 它们既是货、又是装盒的材料。取货口是",
    "     * 「先验材料 → 取货 → 再扣材料」，所以同一格里的东西不能既当货、又被当成材料余额：",
    "     * 否则「整盒容量一次取走」会让材料恰好归零，外盒等于白送（见",
    "     * {@link VoidWarehouseShulker#isBoxPart} 里的那笔账）。</p>",
    "     *",
    "     * <p>目标物与 {@code item} 不同时原样返回，所以对普通物品这条路径与不带预留完全一致。</p>",
    "     */",
    "    private BigInteger materialStock(Item item, ItemStack leaving, BigInteger leavingCount) {",
    "        BigInteger have = this.amountOf(item);",
    "        if (leaving == null || leaving.isEmpty() || !leaving.is(item)",
    "                || leavingCount == null || leavingCount.signum() <= 0) {",
    "            return have;",
    "        }",
    "        return have.subtract(leavingCount).max(BigInteger.ZERO);",
    "    }",
    "",
    "    /**",
    "     * 这些材料够不够 —— <b>把「盒子里要装的那批货」预留掉之后再算</b>。",
    "     *",
    "     * <p>与 {@link #hasBoxMaterials(BoxMaterials)} 的差别只在：目标物本身就是潜影壳 / 箱子时，",
    "     * 材料不能拿「即将离库的那批」来付。其余情况两者完全等价",
    "     * （{@code leaving} 与材料无关时一个数都不减）。</p>",
    "     */",
    "    public boolean hasBoxMaterials(BoxMaterials plan, ItemStack leaving, BigInteger leavingCount) {",
    "        if (plan == null) {",
    "            return false;",
    "        }",
    "        if (plan.shells() > 0 && this.materialStock(Items.SHULKER_SHELL, leaving, leavingCount)",
    "                .compareTo(BigInteger.valueOf(plan.shells())) < 0) {",
    "            return false;",
    "        }",
    "        return plan.chests() <= 0",
    "                || this.materialStock(Items.CHEST, leaving, leavingCount)",
    "                        .compareTo(BigInteger.valueOf(plan.chests())) >= 0;",
    "    }",
    "",
    "    /**",
    "     * 现有材料最多能凑出几个盒子，上限 {@code limit}，<b>并且把「盒子里要装的那批货」预留掉</b>。",
    "     *",
    "     * <p>为什么不能直接用 {@link #maxBoxesUpTo(long)}：目标物本身就是潜影壳 / 箱子时，",
    "     * 装进盒子的那批货与材料从同一格里出。{@code min(壳/2, 箱/1)} 会把「马上要装走的壳」",
    "     * 也算成可用材料，于是算出来的盒数在扣账那一步付不出材料钱（外盒白送）。</p>",
    "     *",
    "     * <p>这里用二分：{@code canMakeBoxes(n)} 的可行性随 {@code n} 单调不增",
    "     * （货越多、要现做的盒子也越多，两头都在吃同一批库存），所以上界取",
    "     * {@link #maxBoxesUpTo(long)} 之后二分不会漏解。</p>",
    "     */",
    "    public long maxBoxesUpTo(long limit, ItemStack sample) {",
    "        if (limit <= 0) {",
    "            return 0L;",
    "        }",
    "        long lo = 0L;",
    "        long hi = this.maxBoxesUpTo(limit);",
    "        while (lo < hi) {",
    "            long mid = (lo + hi + 1) >>> 1;",
    "            if (this.canMakeBoxes(mid, sample)) {",
    "                lo = mid;",
    "            } else {",
    "                hi = mid - 1;",
    "            }",
    "        }",
    "        return lo;",
    "    }",
    "",
    "    /** {@link #maxBoxesUpTo(long, ItemStack)} 的判据：{@code boxes} 盒连同货一起，材料够不够 */",
    "    private boolean canMakeBoxes(long boxes, ItemStack sample) {",
    "        if (boxes <= 0) {",
    "            return true;",
    "        }",
    "        if (sample == null || sample.isEmpty()) {",
    "            return false;",
    "        }",
    "        long capacity = VoidWarehouseShulker.capacity(sample);",
    "        if (capacity <= 0 || boxes > Long.MAX_VALUE / capacity) {",
    "            return false;",
    "        }",
    "        BigInteger goods = BigInteger.valueOf(boxes * capacity);",
    "        long empty = this.emptyBoxCount().min(BigInteger.valueOf(boxes)).longValue();",
    "        long craft = boxes - empty;",
    "        return this.materialStock(Items.SHULKER_SHELL, sample, goods)",
    "                        .compareTo(BigInteger.valueOf(craft * VoidWarehouseShulker.SHELL_COST)) >= 0",
    "                && this.materialStock(Items.CHEST, sample, goods)",
    "                        .compareTo(BigInteger.valueOf(craft * VoidWarehouseShulker.CHEST_COST)) >= 0;",
    "    }",
]

WARN_LINE = "     * <p>⚠️ 这个重载<b>不做预留</b>，只当上界用。真要拿去决定「给几盒」请用"
WARN_LINE2 = "     * {@link #maxBoxesUpTo(long, ItemStack)}。</p>"

MENU_OLD_COND = ("        if (!VoidWarehouseShulker.isBoxable(template)"
                 " || VoidWarehouseShulker.isBoxPart(template)) {")
MENU_NEW_COND = "        if (!VoidWarehouseShulker.isBoxable(template)) {"

MENU_PLAN_OLD = [
    "        VoidWarehouseData.BoxMaterials plan = this.data.planBoxMaterials(1);",
    "        if (!this.data.hasBoxMaterials(plan)) {",
    "            this.message(player, this.msgNoBoxParts());",
    "            return;",
    "        }",
]
MENU_PLAN_NEW = [
    "        VoidWarehouseData.BoxMaterials plan = this.data.planBoxMaterials(1);",
    "        // 🔴 目标物本身就是盒子材料（潜影壳 / 箱子）：这一盒的成本只能从那批货<b>之外</b>出。",
    "        //    所以先把要交出去的量收一点、留够 plan 里的壳 / 箱 —— 否则取走整整一盒容量之后",
    "        //    仓库里那 2 壳 + 1 箱恰好归零，consumeBoxMaterials 只留一条 WARN，",
    "        //    等于「1 个箱子换 1 个潜影盒」（见 VoidWarehouseShulker#isBoxPart）。",
    "        //    例：仓库 1728 壳 + 1 箱 → 交出的那盒装 <b>1726</b> 个，另扣 2 壳 + 1 箱，账是平的。",
    "        if (VoidWarehouseShulker.isBoxPart(template)) {",
    "            long reserve = (template.is(Items.SHULKER_SHELL) ? plan.shells() : 0)",
    "                    + (template.is(Items.CHEST) ? plan.chests() : 0);",
    "            long have = this.data.amountExact(template)",
    "                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();",
    "            available = Math.min(available, Math.max(0L, have - reserve));",
    "            if (available <= 0) {",
    "                this.message(player, this.msgNoBoxParts());",
    "                return;",
    "            }",
    "        }",
    "        if (!this.data.hasBoxMaterials(plan, template, BigInteger.valueOf(available))) {",
    "            this.message(player, this.msgNoBoxParts());",
    "            return;",
    "        }",
]


def split_keepends(raw):
    out, i, n = [], 0, len(raw)
    while i < n:
        j = raw.find(b"\n", i)
        if j < 0:
            out.append(raw[i:])
            break
        out.append(raw[i:j + 1])
        i = j + 1
    return out


def eol_of(line):
    if line.endswith(b"\r\r\n"):
        return b"\r\r\n"
    if line.endswith(b"\r\n"):
        return b"\r\n"
    return b"\n"


def text_of(line):
    return line.decode("utf-8", "replace")


def patch_data(path):
    raw = open(path, "rb").read()
    if b"materialStock" in raw:
        print("  = 已有 materialStock，跳过 " + path)
        return True
    lines = split_keepends(raw)
    k = -1
    for i, line in enumerate(lines):
        if "public long maxBoxesUpTo(long limit) {" in text_of(line):
            k = i
            break
    if k < 0:
        print("  ✗ 找不到 maxBoxesUpTo(long) " + path)
        return False
    s = k - 1
    while s >= 0 and text_of(lines[s]).strip() != "/**":
        s -= 1
    if s < 0:
        print("  ✗ 找不到 javadoc 起点 " + path)
        return False
    e = k - 1
    if text_of(lines[e]).strip() != "*/":
        print("  ✗ javadoc 结尾不是 */（k=%d）%s" % (k, path))
        return False
    eol = eol_of(lines[s]).decode("ascii")
    # 1) 给 1 参版补一句告警（插在 javadoc 的 */ 之前）
    lines[e:e] = [(WARN_LINE + eol).encode("utf-8"),
                  (WARN_LINE2 + eol).encode("utf-8")]
    # 2) 新方法插在 javadoc 之前（含一个空行结尾）
    lines[s:s] = [(m + eol).encode("utf-8") for m in METHODS] + [eol.encode("utf-8")]
    open(path, "wb").write(b"".join(lines))
    print("  ✓ Data 已插入预留方法 " + path)
    return True


def patch_menu(path):
    raw = open(path, "rb").read()
    lines = split_keepends(raw)
    cond = plan = -1
    for i, line in enumerate(lines):
        t = text_of(line)
        if MENU_OLD_COND in t and cond < 0:
            cond = i
        if "BoxMaterials plan = this.data.planBoxMaterials(1);" in t and plan < 0:
            plan = i
    if cond < 0:
        print("  ✗ 找不到菜单判据（可能已改过）" + path)
        return False
    if plan < 0:
        print("  ✗ 找不到 planBoxMaterials(1) " + path)
        return False
    eol = eol_of(lines[cond]).decode("ascii")
    lines[cond] = (MENU_NEW_COND + eol).encode("utf-8")
    body = "".join(text_of(l) for l in lines[plan:plan + 5])
    if "hasBoxMaterials(plan)" not in body:
        print("  ✗ plan 之后 5 行不是预期的旧写法 " + path)
        return False
    lines[plan:plan + 5] = [(m + eol).encode("utf-8") for m in MENU_PLAN_NEW]
    open(path, "wb").write(b"".join(lines))
    print("  ✓ Menu 已改为预留 " + path)
    return True


def main():
    ok = True
    src_withdraw = os.path.join(ROOT, SRC + "VoidWarehouseWithdraw.java")
    print("[1] Withdraw（五份逐字节相同 ⇒ 二进制复制）")
    for base, root in VERS:
        shutil.copyfile(src_withdraw, os.path.join(ROOT, root + BASE + "VoidWarehouseWithdraw.java"))
        print("  ✓ " + root)
    print("[2] Data")
    for base, root in VERS:
        ok = patch_data(os.path.join(ROOT, root + BASE + "VoidWarehouseData.java")) and ok
    print("[3] Menu")
    for base, root in VERS:
        ok = patch_menu(os.path.join(ROOT, root + BASE + "VoidWarehouseMenu.java")) and ok
    if not ok:
        print("失败：有文件没打上补丁")
        return 1
    print("全部完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
