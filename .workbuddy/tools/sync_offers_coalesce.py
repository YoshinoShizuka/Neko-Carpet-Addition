#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「可兑表重算按 tick 合并」的修复同步到五个版本。

判据（铁律）：
  · old 在  → 替换（且必须只命中 1 次）
  · old 没了但 new 在 → 跳过（已经改过）
  · 都没有  → 报错，绝不瞎写
收尾逐字节复核：new 必须全在、old 必须全没、换行风格必须与原文件一致。
"""

import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSIONS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]
BASE = "src/main/java/neko/voidwarehouse"

# ---------------------------------------------------------------- 补丁内容（LF 书写）

DATA_FIELDS_OLD = """    private List<ExchangeOffer> offers;

    /**
     * 统一展示序缓存"""

DATA_FIELDS_NEW = """    private List<ExchangeOffer> offers;

    /**
     * 库存变过、{@link #offers} 可能已经不准了。
     *
     * <p>与「直接把 {@link #offers} 置空」的区别，就是本字段存在的唯一理由：
     * 置空之后再读，<b>同一个 tick 内</b>就会被逼着重算一次；而一次重算约 1.6 ms
     * （40 条可兑时要跑 40 次规划）。20 名玩家在同一个 tick 里各入库一次、各刷新一次界面，
     * 就是 20 次重算 ≈ 30 ms ≈ 61% 的 tick 预算 —— 这是实测出来的峰值。
     * 所以这里只标「脏」，真正的重算按 tick 合并（见 {@link #exchangeOffers()}）。</p>
     */
    private boolean offersStale;

    /** {@link #offers} 是在哪个 tick 上算出来的（与 {@link #serverTick} 同口径） */
    private long offersTick = Long.MIN_VALUE;

    /**
     * 当前的服务端 tick，由 {@link VoidWarehouseExtension#onTick} 每 tick 写一次。
     *
     * <p><b>是 static 的，故意的</b>：一来不必为了记一个 tick 戳就去 {@code VoidWarehouse.data(server)}
     * （那个方法在读不到存档时会抛异常拒绝服务，绝不能因此打断整个服务端 tick）；
     * 二来存档数据实例被替换（重载世界）时，缓存和 tick 戳不会各说各话。</p>
     *
     * <p><b>用服务端自己的 tick，而不是自己加一</b>：万一哪天 onTick 不再被调用，
     * 自己加一的计数器会停在原地 —— 那 {@link #offers} 就<b>永远</b>不再重算（比慢更糟）。
     * 用真 tick 则最坏退化成「每 tick 重算一次」，行为仍然正确。</p>
     */
    private static long serverTick;

    /**
     * 统一展示序缓存"""

DATA_MARK_OLD = """        this.amountOrder = null;
        // 库存一动，「能兑出什么、能兑多少」就全变了 —— 两个缓存一起作废
        this.offers = null;
        this.unifiedOrder = null;
        this.setDirty();"""

DATA_MARK_NEW = """        this.amountOrder = null;
        // 库存一动，「能兑出什么、能兑多少」就全变了。
        //
        // offers 只标脏、**不置空**：置空会让「同一 tick 内的下一次读」立刻重算一次，
        // 而一次重算约 1.6 ms。20 个玩家在同一 tick 里各做一次动作、各刷新一次界面，
        // 就是 20 次重算 ≈ 30 ms（实测占掉 61% 的 tick 预算）。重算按 tick 合并，
        // 见 exchangeOffers()。
        //
        // unifiedOrder 必须**立刻**作废：它存的是 entries 的下标，取空一格就会让后面
        // 全部前移，留着旧数组轻则显示错格、重则数组越界。它重建只要一次 O(n) 归并
        // （几十微秒），不是瓶颈，这里不许偷懒。
        this.offersStale = true;
        this.unifiedOrder = null;
        this.setDirty();"""

DATA_REVALIDATE_OLD = """            this.exchangeCacheSignature = signature;
            this.offers = null;
            this.unifiedOrder = null;
            this.amountOrder = null;"""

DATA_REVALIDATE_NEW = """            this.exchangeCacheSignature = signature;
            this.offers = null;
            // 这里是「开关 / 配方本变了」，不是「库存变了」：必须真作废，不能像
            // markChanged() 那样只标脏 —— 否则关掉规则后界面上那些「可兑」幽灵格
            // 还要多挂一个 tick。
            this.offersStale = false;
            this.offersTick = Long.MIN_VALUE;
            this.unifiedOrder = null;
            this.amountOrder = null;"""

DATA_GETTER_OLD = """        this.revalidateExchangeCache();
        if (this.offers != null) {
            return this.offers;
        }"""

DATA_GETTER_NEW = """        this.revalidateExchangeCache();
        if (this.offers != null && (!this.offersStale || this.offersTick == serverTick)) {
            // 第二个条件就是「按 tick 合并」的全部内容：
            //   · 本 tick 已经算过一次 → 这一 tick 里再怎么改库存，返回的仍是这一份；
            //   · 界面只在 tick 边界渲染，所以同一 tick 内所有人看到的本来就是同一帧，
            //     多算的那 N−1 次纯属浪费（每次 1.6 ms）。
            // 下一个 tick 的第一次读会因为 offersTick != serverTick 而重算，最多旧一 tick。
            //
            // 这是**有意的取舍**：用「最多 1 tick 的陈旧」换「tick 时间有上界」。
            // 拿旧名单去点击是安全的 —— 取出路径一律重新规划（VoidWarehouseMenu#withdrawFrom
            // → data.withdraw() / #withdrawShulkerBox → planExchangeMax），凑不出来就是空操作，
            // 不会凭空造物；名单下标也有界检查（见 displaySlotAt），不会越界。
            return this.offers;
        }"""

DATA_STORE_OLD = """        this.offers = List.copyOf(built);
        return this.offers;
    }"""

DATA_STORE_NEW = """        this.offers = List.copyOf(built);
        this.offersStale = false;
        this.offersTick = serverTick;
        return this.offers;
    }

    /**
     * 由 {@link VoidWarehouseExtension#onTick} 每 tick 调用一次，记下当前的服务端 tick。
     *
     * <p>只服务于 {@link #exchangeOffers()} 的「按 tick 合并」：同一 tick 内不重复重算。
     * 必须是 static（原因见 {@link #serverTick}），所以调它<b>不需要</b>先拿到数据实例。</p>
     */
    public static void noteServerTick(int serverTick) {
        VoidWarehouseData.serverTick = serverTick;
    }"""

EXT_OLD = """    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {"""

EXT_NEW = """    public void onTick(MinecraftServer server) {
        // 「可兑表」的重算按 tick 合并，需要一个 tick 戳（见 VoidWarehouseData#serverTick）。
        // 写在这里、且在任何提前返回之前：规则关着时也要推进，否则重新打开时会拿到很旧的名单。
        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。
        VoidWarehouseData.noteServerTick(server.getTickCount());

        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {"""

PATCHES = {
    "VoidWarehouseData": [
        ("字段与 tick 戳", DATA_FIELDS_OLD, DATA_FIELDS_NEW),
        ("markChanged 只标脏", DATA_MARK_OLD, DATA_MARK_NEW),
        ("revalidate 真作废", DATA_REVALIDATE_OLD, DATA_REVALIDATE_NEW),
        ("exchangeOffers 按 tick 合并", DATA_GETTER_OLD, DATA_GETTER_NEW),
        ("写回时盖 tick 戳", DATA_STORE_OLD, DATA_STORE_NEW),
    ],
    "VoidWarehouseExtension": [
        ("onTick 里推进 tick", EXT_OLD, EXT_NEW),
    ],
}


def detect_eol(raw: bytes) -> str:
    crlf = raw.count(b"\r\n")
    lf = raw.count(b"\n") - crlf
    if crlf and lf:
        raise SystemExit(f"!! 混合换行（CRLF {crlf} / LF {lf}），拒绝自动改写")
    return "\r\n" if crlf else "\n"


def main() -> int:
    applied = skipped = 0
    for version in VERSIONS:
        for cls, patches in PATCHES.items():
            path = ROOT / version / BASE / f"{cls}.java"
            raw = path.read_bytes()
            eol = detect_eol(raw)
            text = raw.decode("utf-8").replace("\r\n", "\n")

            for label, old, new in patches:
                if old in text:
                    hits = text.count(old)
                    if hits != 1:
                        print(f"!! {version} {cls} 「{label}」命中 {hits} 次（要求 1）")
                        return 1
                    text = text.replace(old, new)
                    applied += 1
                elif new in text:
                    skipped += 1
                else:
                    print(f"!! {version} {cls} 「{label}」既没有 old 也没有 new")
                    return 1

            out = text.replace("\n", eol) if eol == "\r\n" else text
            path.write_bytes(out.encode("utf-8"))

    # ---- 收尾逐字节复核 ----
    problems = []
    for version in VERSIONS:
        for cls, patches in PATCHES.items():
            path = ROOT / version / BASE / f"{cls}.java"
            text = path.read_bytes().decode("utf-8").replace("\r\n", "\n")
            for label, old, new in patches:
                if new not in text:
                    problems.append(f"{version} {cls} 「{label}」new 缺失")
                if old in text:
                    problems.append(f"{version} {cls} 「{label}」old 仍在")
            # 语法门禁：花括号配平（防止补丁切坏方法体）
            if text.count("{") != text.count("}"):
                problems.append(f"{version} {cls} 花括号不配平 "
                                f"({text.count('{')} vs {text.count('}')})")

    print(f"applied={applied} skipped={skipped}")
    if problems:
        print("!! 复核失败：")
        for p in problems:
            print("   ×", p)
        return 1
    print("复核通过：new 全在 / old 全无 / 花括号配平")
    return 0


if __name__ == "__main__":
    sys.exit(main())
