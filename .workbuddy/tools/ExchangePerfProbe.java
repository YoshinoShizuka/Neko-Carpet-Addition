package neko.voidwarehouse;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【临时探针】定位「打开虚空仓库 GUI 卡死」到底是哪个物品、什么样的库存把它拖死的。
 *
 * <p>复刻 {@code VoidWarehouseData.exchangeOffers()} 的内层循环 —— 注意它给
 * {@code planMax} 传的是 {@code cap} 本身（上界），<b>不是</b> {@code min(cap, 64)}：</p>
 * <pre>
 * for (item : table.items()) {
 *     if (仓库里有这件物品) continue;          // 有货不列「可兑」
 *     cap = maxObtainable(item, stock);        // 上界，可能很大
 *     if (cap &lt;= 0) continue;
 *     planMax(item, cap);                      // 从上界往下逐个试
 *     takeAmount(item, plan.amount(), 64);     // 右键「取一组」
 *     takeAmount(item, plan.amount(), 1728);   // 左键「取一盒」
 * }
 * </pre>
 *
 * <h2>两个判据</h2>
 * <ol>
 *   <li><b>不许卡死</b>：单个物品超过 {@link #BOMB_MS} 记为 BOMB；整轮扫完要打印总耗时。</li>
 *   <li><b>不许误判</b>：「上界 &gt; 0 却规划不出任何数量」的物品记为 DROP —— 这些格
 *       在界面上会凭空消失。真值确实兑不出来的（例如骨粉库存 ≡1 mod 3 时任何骨块都凑不出）
 *       也会落在这里，所以 DROP 只报警、不判死，要人工看一眼。</li>
 * </ol>
 */
public final class ExchangePerfProbe {

    /** 单个物品单次规划超过这个毫秒数就算「一次点击卡住服务器」 */
    private static final long BOMB_MS = 5_000;

    private ExchangePerfProbe() {
    }

    public static void main(String[] args) throws Exception {
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.Table.of(VoidWarehouseExchange.EDGES);
        System.out.println("表：" + table.edges().size() + " 条边 / " + table.items().size() + " 个物品");
        System.out.println("判据：单个物品「规划一次可兑上限」超过 " + BOMB_MS + " ms 即为地雷\n");
        System.out.flush();

        sweep(table, 1_000_000L, "每件 100 万");
        sweep(table, 10_000_000L, "每件 1000 万");
        sweep(table, 100_000_000L, "每件 1 亿");
        sweep(table, 100_000L, "每件 10 万");
        sweep(table, 10_000L, "每件 1 万");
        sweep(table, 64L, "每件 64（日常小仓库）");

        System.out.println("\n=== 诊断：病态库存（每件 1M，目标为 0）下几个数的关系 ===");
        for (String target : new String[]{"bone_meal", "copper_ingot", "copper_nugget",
                "bone_block", "potent_sulfur", "sulfur", "raw_iron", "gold_ingot", "iron_nugget"}) {
            Map<String, Long> snap = new LinkedHashMap<>();
            for (String item : table.items()) {
                snap.put(item, item.equals(target) ? 0L : 1_000_000L);
            }
            VoidWarehouseExchange.Stock st = stock(snap);
            BigInteger loose = VoidWarehouseExchange.maxObtainable(table, target, st);
            BigInteger tight = VoidWarehouseExchange.reachableBound(table, target, st);
            long t0 = System.nanoTime();
            VoidWarehouseExchange.Plan plan = VoidWarehouseExchange.planMax(table, target, tight, st);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            BigInteger amount = plan == null ? BigInteger.valueOf(-1) : plan.amount();
            System.out.println(String.format("  %-14s 旧上界=%-12s 紧上界=%-12s 真值=%-12s 相差=%-10s %d ms",
                    target, loose, tight, amount, tight.subtract(amount), ms));
        }
        System.out.flush();
    }

    /** 跑一整轮「打开界面要做的全部规划」，量总耗时与误判数 */
    private static void sweep(VoidWarehouseExchange.Table table, long each, String label) throws Exception {
        long start = System.nanoTime();
        int considered = 0;
        int planned = 0;
        int dropped = 0;
        long worstMs = 0;
        String worst = "-";
        for (String target : table.items()) {
            Map<String, Long> snap = new LinkedHashMap<>();
            for (String item : table.items()) {
                snap.put(item, item.equals(target) ? 0L : each);
            }
            VoidWarehouseExchange.Stock st = stock(snap);
            BigInteger cap = VoidWarehouseExchange.maxObtainable(table, target, st);
            if (cap.signum() <= 0) {
                continue;
            }
            considered++;
            long t0 = System.nanoTime();
            VoidWarehouseExchange.Plan plan = VoidWarehouseExchange.planMax(table, target, cap, st);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (ms > worstMs) {
                worstMs = ms;
                worst = target;
            }
            if (plan == null) {
                dropped++;
                System.out.println("  DROP  " + target + "  cap=" + cap + "  " + ms + " ms");
            } else {
                planned++;
            }
        }
        long total = (System.nanoTime() - start) / 1_000_000L;
        System.out.println(String.format("[%s] 整轮 %d ms（%d 个可兑候选 / 成功 %d / DROP %d），最慢 %s %d ms",
                label, total, considered, planned, dropped, worst, worstMs));
        System.out.flush();
    }

    private static VoidWarehouseExchange.Stock stock(Map<String, Long> snapshot) {
        Map<String, BigInteger> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            converted.put(e.getKey(), BigInteger.valueOf(e.getValue()));
        }
        return VoidWarehouseExchange.Stock.of(converted);
    }
}
