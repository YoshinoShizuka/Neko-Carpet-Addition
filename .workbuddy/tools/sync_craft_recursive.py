#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「可递归扣料」这一轮的三处改动同步到其余四个版本。

判据铁律（本项目付过代价）：
  old 在就改 / old 没了且 new 在就跳 / 都没有就报错
结尾逐字节比对：每个目标文件改动后必须包含 NEW 且不再包含 OLD。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]  # 仓库根
VERSIONS = ["modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

# ------------------------------------------------------------------ 改动 1：RecipeBook.stockFor
BOOK_OLD = '''    /**
     * 一条配方要用到的原料，现在仓库里各有多少（只数<b>纯净</b>物品，口径同 {@code amountOf}）。
     *
     * <p>只为这条配方涉及的候选 id 建快照，<b>不是</b>整仓快照：一条配方通常 1~9 项，
     * 而仓库可能有几百格。这样 {@link VoidWarehouseRecipes#plan} 拿到的 map 小、
     * 复制它的成本可以忽略（它在内部会复制两三份）。</p>
     *
     * <p>带数据组件的同一物品<b>不算</b>：配方要的是「能拿去合成的东西」，
     * 附魔书 / 改名物品 / 药水被当成原料扣掉等于把玩家的数据扔了 —— 与 9 合 1 同一口径。</p>
     */
    public static Map<String, BigInteger> stockFor(VoidWarehouseData data,
                                                   VoidWarehouseRecipes.Recipe recipe) {
        Map<String, BigInteger> stock = new HashMap<>();
        if (data == null || recipe == null) {
            return stock;
        }
        for (VoidWarehouseRecipes.Part part : recipe.parts()) {
            for (String candidate : part.candidates()) {
                if (stock.containsKey(candidate)) {
                    continue;
                }
                Item item = itemOf(candidate);
                stock.put(candidate, item == null ? BigInteger.ZERO : data.amountOf(item));
            }
        }
        return stock;
    }
}'''

BOOK_NEW = '''    /**
     * 一条配方<b>整条闭包</b>的库存快照：配方要用到的每一样东西（含往下钻几层才用得上的原料），
     * 现在仓库里各有多少。只数<b>纯净</b>物品，口径同 {@code amountOf}。
     *
     * <p>为什么是闭包而不是「这条配方的原料」：1.0.15 报过一个 bug ——
     * 学过一次「深色橡木原木 → 深色橡木木板」和一次活塞之后，木板用完了、原木还有一堆，
     * 取活塞却说做不出来。根因就是快照里只有木板、没有原木：模拟器看到「木板 0、原木 0」，
     * 自然判「没料」。把闭包一起取出来，模拟器才有机会顺着「原木 → 木板」往下钻。
     * 闭包本身由 {@link VoidWarehouseRecipes#stockClosure} 走，深度与条目数都有硬上限。</p>
     *
     * <p>只收闭包、<b>不是整仓快照</b>：一条配方通常 1~9 项、往下最多 4 层，
     * 闭包也就几十种物品，而仓库可能有几百格。这样模拟器拿到的 map 小、
     * 每个分支复制一份的成本可以忽略。</p>
     *
     * <p>带数据组件的同一物品<b>不算</b>：配方要的是「能拿去合成的东西」，
     * 附魔书 / 改名物品 / 药水被当成原料扣掉等于把玩家的数据扔了 —— 与 9 合 1 同一口径。</p>
     */
    public static Map<String, BigInteger> stockFor(VoidWarehouseData data,
                                                   VoidWarehouseRecipes.Recipe recipe,
                                                   Map<String, List<VoidWarehouseRecipes.Recipe>> byOutput) {
        if (data == null || recipe == null) {
            return new HashMap<>();
        }
        return VoidWarehouseRecipes.stockClosure(recipe, byOutput, data::amountOfId);
    }
}'''

# ------------------------------------------------------------------ 改动 2：planCraft 的三笔账
PLAN_OLD = '''        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe);'''
PLAN_NEW = '''        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);'''

CAP_OLD = '''                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock);'''
CAP_NEW = '''                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);'''

GET_OLD = '''            VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock);
            if (plan == null) {
                continue;
            }
            BigInteger produced = plan.crafts().multiply(BigInteger.valueOf(recipe.outputCount()));
            // 真正给到玩家手上的新做出来的数量 = 做出来的 − 退回仓库的余量
            BigInteger gained = produced.subtract(plan.surplus());
            Map<String, BigInteger> deltas = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, BigInteger> entry : plan.consumed().entrySet()) {
                deltas.merge(entry.getKey(), entry.getValue().negate(), BigInteger::add);
            }
            if (plan.surplus().signum() > 0) {
                deltas.merge(itemId, plan.surplus(), BigInteger::add);
            }
            deltas.merge(itemId, fromStock.negate(), BigInteger::add);
            return this.toExchangePlan(itemId, fromStock.add(gained), deltas);'''
GET_NEW = '''            VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock, this.recipesByOutput);
            if (plan == null) {
                continue;
            }
            // 玩家真正拿到手上的「新做出来的」数量。
            // 为什么不再写 `produced − surplus`：往下钻之后 surplus 是一个 map（每一层都可能做出零头），
            // 拿它跟单个数字比就错了。而按定义，做 crafts 次、给 wantOut 个，玩家拿到的就是 wantOut 个
            // —— 多做的部分全部记在 surplus 里回仓库。这两个写法必须等价，所以自检里有一条
            // 「账平」断言盯着它（做出来的 − 入账的 == 玩家拿到的）。
            BigInteger gained = wantOut;
            Map<String, BigInteger> deltas = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, BigInteger> entry : plan.consumed().entrySet()) {
                deltas.merge(entry.getKey(), entry.getValue().negate(), BigInteger::add);
            }
            // 余量全部入账（含根产物做多的零头，以及往下钻时中间产物多出来的部分）
            for (Map.Entry<String, BigInteger> entry : plan.surplus().entrySet()) {
                if (entry.getValue().signum() > 0) {
                    deltas.merge(entry.getKey(), entry.getValue(), BigInteger::add);
                }
            }
            deltas.merge(itemId, fromStock.negate(), BigInteger::add);
            return this.toExchangePlan(itemId, fromStock.add(gained), deltas);'''

# ------------------------------------------------------------------ 改动 3：collectCraftOffers
OFFER_HEAD_OLD = '''     * <h2>为什么这里不用「探针」那一套</h2>
     * <p>9 合 1 的 {@code planMax} 要从上界往下逐个试，因为「能拿 5 个不一定能拿 4 个」（拆整块会跳档）。
     * 配方扣料<b>没有这个问题</b>：能做 {@code n} 次就一定能做 {@code 1..n} 次
     * （原料按倍数扣，没有任何取整陷阱），所以上限直接由 {@link VoidWarehouseRecipes#maxCraftable}
     * 一次算出来，<b>零次试探</b>。这就是这条路径在界面上几乎不花钱的原因。</p>
     *
     * <p>也因此这里<b>不会</b>出现 1.0.13 那种「打开界面卡死」的形态 ——
     * 那个事故的根因是「上界虚高 × 逐个试探」，而这里两个乘数之一（试探次数）恒等于 0。</p>'''
OFFER_HEAD_NEW = '''     * <h2>为什么这里不用 9 合 1 的「探针」那一套</h2>
     * <p>9 合 1 的 {@code planMax} 要从上界往下逐个试，因为「能拿 5 个不一定能拿 4 个」（拆整块会跳档）。
     * 配方扣料<b>没有这个跳档</b>：原料按倍数扣，能做 {@code n} 次就一定能做 {@code 1..n} 次
     * （{@code ceil} 是单调的，往下钻的层数也只会变少）。所以这里只需要一次上界 + 最多几次验证，
     * 而不是把「能拿 64 个」这个上界一路试到 63、62……</p>
     *
     * <p>也因此这里<b>不会</b>出现 1.0.13 那种「打开界面卡死」的形态 ——
     * 那个事故的根因是「上界虚高 × 逐个试探几百次」，而这里的试探上限是
     * {@code MAX_VERIFY = 64} 且正常数据里第一次就成。</p>'''

OFFER_CAP_OLD = '''                Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe);
                BigInteger out = VoidWarehouseRecipes.maxCraftable(recipe, stock);'''
OFFER_CAP_NEW = '''                Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);
                BigInteger out = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);'''

OFFER_PLAN_OLD = '''            VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(best, maxOut, bestStock);'''
OFFER_PLAN_NEW = '''            VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(best, maxOut, bestStock, this.recipesByOutput);'''

PAIRS = [
    ("stockFor 签名", BOOK_OLD, BOOK_NEW),
    ("planCraft 取快照", PLAN_OLD, PLAN_NEW),
    ("planCraft 上限", CAP_OLD, CAP_NEW),
    ("planCraft 三笔账", GET_OLD, GET_NEW),
    ("collectCraftOffers 注释", OFFER_HEAD_OLD, OFFER_HEAD_NEW),
    ("collectCraftOffers 取料", OFFER_CAP_OLD, OFFER_CAP_NEW),
    ("collectCraftOffers 规划", OFFER_PLAN_OLD, OFFER_PLAN_NEW),
]


def patch(path: pathlib.Path, label: str, old: str, new: str) -> str:
    raw = path.read_bytes()
    text = raw.decode("utf-8")
    crlf = "\r\n" in text
    work = text.replace("\r\n", "\n") if crlf else text
    if old in work:
        if new in work:
            raise SystemExit(f"[{label}] {path}: old 与 new 同时在，脚本重复跑过，停下来人工看")
        work = work.replace(old, new, 1)
        out = work.replace("\n", "\r\n") if crlf else work
        path.write_bytes(out.encode("utf-8"))
        return "patched"
    if new in work:
        return "skip"
    raise SystemExit(f"[{label}] {path}: 既没有 old 也没有 new，格式不符预期")


def main() -> int:
    report = []
    for ver in VERSIONS:
        base = ROOT / ver / "src/main/java/neko/voidwarehouse"
        for name, old, new in PAIRS:
            target = base / ("VoidWarehouseRecipeBook.java" if "stockFor" in name else "VoidWarehouseData.java")
            state = patch(target, f"{ver} {name}", old, new)
            report.append(f"{ver:20s} {name:20s} {state}")
    # 逐字节复核：改完之后每个文件必须含 new、不含 old
    print("\n".join(report))
    problems = []
    for ver in VERSIONS:
        base = ROOT / ver / "src/main/java/neko/voidwarehouse"
        for name, old, new in PAIRS:
            target = base / ("VoidWarehouseRecipeBook.java" if "stockFor" in name else "VoidWarehouseData.java")
            # 复核时必须先把 CRLF 归一成 LF：26.3 是 LF，其余四个版本是 CRLF（core.autocrlf=true），
            # 直接拿原文跟 new 比会把「只是换行不同」判成失败 —— 这是本项目踩过的假阳性。
            text = target.read_bytes().decode("utf-8").replace("\r\n", "\n")
            if new not in text or old in text:
                problems.append(f"{ver} {name}")
    if problems:
        print("!! 复核失败: " + ", ".join(problems))
        return 1
    print(f"OK 四个版本全部同步，逐字节复核通过（{len(PAIRS)} 处 × {len(VERSIONS)} 版本）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
