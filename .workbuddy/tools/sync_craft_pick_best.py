#!/usr/bin/env python3
"""修「可合成数量显示 64、右键实际只给 1」——planCraft 的「尽量给」分支改成挑做得最多的那条配方。

五版本各一份 VoidWarehouseData.java，函数体逐字节一致（改前已核 md5 全同）。

判据按项目老规矩写：
  · old 在 → 必须命中 1 次，就地替换
  · old 不在、new 在 → 已改过，跳过
  · 都没有 → 报错（说明这个文件的人肉改动跑偏了）
收尾：逐字节复核（new 在 / old 无 / 花括号配平 / 只多出预期的方法数），并按每个文件自己的换行风格写回。
"""
import sys

FILES = [
    "modern/26.3/src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseData.java",
]

OLD = '''        // 同一件物品可能有多条已学配方（用橡木木板做过一次、用云杉木板又做过一次）。
        // 按学会的顺序逐条试，第一条做得出来的就用它 —— 顺序稳定，账才稳定。
        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);
            // 已经划给玩家的 fromStock 个必须从配方可用的料里<b>先扣掉</b>。
            // 否则「产物自己就是原料」的配方（地图缩放：1 地图 + 8 纸 → 1 地图；地图复制：
            // 1 地图 + 1 空地图 → 2 地图；潜影盒染色同理）会把同一份库存数两遍 ——
            // 一份算给玩家、一份算进配方，那就是凭空造物。
            // 扣掉之后这类配方的结果是「做不出来」（保守失败），而不是「做出来了但账对不上」。
            if (fromStock.signum() > 0 && stock.containsKey(itemId)) {
                stock.put(itemId, stock.get(itemId).subtract(fromStock).max(BigInteger.ZERO));
            }
            BigInteger wantOut = need;
            if (allowPartial) {
                // 「尽量给」：做得少就少给，不整次失败（右键取一组要的就是这个语义）
                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);
                if (maxOut.signum() <= 0) {
                    continue;
                }
                wantOut = need.min(maxOut);
            }
            VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock, this.recipesByOutput);
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
            return this.toExchangePlan(itemId, fromStock.add(gained), deltas);
        }
        return null;
    }
'''

NEW = '''        // 同一件物品可能有多条已学配方（用橡木木板做过一次、用云杉木板又做过一次）。
        // 挑哪一条，「尽量给」与「要多少给多少」是两条不同的规则：
        //
        //   · 「尽量给」（右键取一组 / 取一盒）：先挑<b>做得最多</b>的那条。
        //     界面上的「可合成 N 个」是 collectCraftOffers 取的「各候选 maxCraftable 的最大值」，
        //     执行时若按别的规则挑，就会出现「界面写 64、右键实际只给 1」—— 那是 1.0.15 修过的
        //     一处真偏差。**两边的口径必须始终是同一个**，别再各写一套。
        //   · 精确请求（左键取 1 个 / 打印机按需取）：没有「最多」可言，按学会顺序逐条试，
        //     第一条能一次性满足的就是它 —— 顺序稳定，账才稳定。
        if (allowPartial) {
            VoidWarehouseRecipes.Recipe bestRecipe = null;
            Map<String, BigInteger> bestStock = null;
            BigInteger bestOut = BigInteger.ZERO;
            for (VoidWarehouseRecipes.Recipe recipe : candidates) {
                Map<String, BigInteger> stock = this.craftStock(recipe, itemId, fromStock);
                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);
                // 严格大于才换：持平取先学会的那条，与 collectCraftOffers 的 tie-break 保持一致
                if (maxOut.compareTo(bestOut) > 0) {
                    bestOut = maxOut;
                    bestRecipe = recipe;
                    bestStock = stock;
                }
            }
            if (bestRecipe == null) {
                return null;   // 哪一条都做不出来
            }
            return this.planCraftWith(bestRecipe, bestStock, itemId, fromStock, need.min(bestOut));
        }
        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = this.craftStock(recipe, itemId, fromStock);
            VoidWarehouseExchange.Plan plan = this.planCraftWith(recipe, stock, itemId, fromStock, need);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    /**
     * 某条配方可用的料：仓库闭包 + 把<b>已经划给玩家的</b> {@code fromStock} 个产物先扣掉。
     *
     * <p>不扣就会出问题：像「产物自己就是原料」的配方（地图缩放：1 地图 + 8 纸 → 1 地图；
     * 地图复制：1 地图 + 1 空地图 → 2 地图；潜影盒染色同理）会把同一份库存数两遍 ——
     * 一份算给玩家、一份算进配方，那就是凭空造物。扣掉之后这类配方的结果是「做不出来」
     * （保守失败），而不是「做出来了但账对不上」。</p>
     */
    private Map<String, BigInteger> craftStock(VoidWarehouseRecipes.Recipe recipe, String itemId,
                                               BigInteger fromStock) {
        Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);
        if (fromStock.signum() > 0 && stock.containsKey(itemId)) {
            stock.put(itemId, stock.get(itemId).subtract(fromStock).max(BigInteger.ZERO));
        }
        return stock;
    }

    /**
     * 用<b>指定的某一条</b>配方把 {@code wantOut} 个做出来，收成一份可执行计划。
     *
     * <p>规划失败返回 {@code null}（调用方决定「换下一条」还是「整次拒绝」）。</p>
     *
     * <p>玩家真正拿到手上的「新做出来的」数量就是 {@code wantOut}：为什么不再写
     * {@code produced − surplus} —— 往下钻之后 surplus 是一个 map（每一层都可能做出零头），
     * 拿它跟单个数字比就错了。而按定义，做 crafts 次、给 wantOut 个，玩家拿到的就是 wantOut 个，
     * 多做的部分全部记在 surplus 里回仓库。这两个写法必须等价，所以自检里有一条
     * 「账平」断言盯着它（做出来的 − 入账的 == 玩家拿到的）。</p>
     */
    private VoidWarehouseExchange.Plan planCraftWith(VoidWarehouseRecipes.Recipe recipe,
                                                     Map<String, BigInteger> stock, String itemId,
                                                     BigInteger fromStock, BigInteger wantOut) {
        VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock, this.recipesByOutput);
        if (plan == null) {
            return null;
        }
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
        return this.toExchangePlan(itemId, fromStock.add(wantOut), deltas);
    }
'''


def main():
    applied = skipped = 0
    for path in FILES:
        raw = open(path, "rb").read()
        crlf = b"\r\n" in raw
        text = raw.decode("utf-8")
        old = OLD.replace("\n", "\r\n") if crlf else OLD
        new = NEW.replace("\n", "\r\n") if crlf else NEW
        if old in text:
            if text.count(old) != 1:
                raise SystemExit(f"[FAIL] {path}: old 命中 {text.count(old)} 次（应为 1）")
            out = text.replace(old, new, 1)
            open(path, "wb").write(out.encode("utf-8"))
            applied += 1
            print(f"[ok]   {path}  ({'CRLF' if crlf else 'LF'})")
        elif new in text:
            skipped += 1
            print(f"[skip] {path}  已经是新版本")
        else:
            raise SystemExit(f"[FAIL] {path}: old / new 都找不到，人肉改动跑偏了")

    # 收尾逐字节复核
    for path in FILES:
        text = open(path, "rb").read().decode("utf-8")
        old = OLD.replace("\n", "\r\n") if "\r\n" in text else OLD
        new = NEW.replace("\n", "\r\n") if "\r\n" in text else NEW
        assert old not in text, f"{path}: old 残留在文件里"
        assert new in text, f"{path}: new 没写进去"
        assert text.count("private Map<String, BigInteger> craftStock(") == 1, f"{path}: craftStock 数量不对"
        assert text.count("private VoidWarehouseExchange.Plan planCraftWith(") == 1, f"{path}: planCraftWith 数量不对"
        assert text.count("private VoidWarehouseExchange.Plan planCraft(") == 1, f"{path}: planCraft 数量不对"
        assert text.count("{") == text.count("}"), f"{path}: 花括号不配平"
    print(f"\napplied={applied} skipped={skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
