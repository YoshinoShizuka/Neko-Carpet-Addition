package neko.voidwarehouse;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【临时探针 2】把「一次界面规划」的成本拆开：是<b>探针次数</b>多，还是<b>单次探针</b>贵。
 *
 * <p>{@code planMax} = 「从上界往下逐个试，第一个成功的即答案」，所以成本 = 探针次数 × 单次规划。
 * 这两个数要分开看：探针次数由<b>上界紧不紧</b>决定，单次规划由 {@code drain} 内部决定。</p>
 */
public final class ProbePlanMax {

    private ProbePlanMax() {
    }

    public static void main(String[] args) throws Exception {
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.Table.of(VoidWarehouseExchange.EDGES);
        for (long each : new long[]{1_000_000L, 10_000_000L, 100_000_000L}) {
            System.out.println("=== 每件 " + each + " ===");
            long worstProbeMs = 0;
            String worstProbe = "-";
            int worstProbes = 0;
            String mostProbes = "-";
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
                BigInteger upper = cap.min(VoidWarehouseExchange.reachableBound(table, target, st));

                // 先量「单次规划」的代价（直接在上界处试一次）
                long t0 = System.nanoTime();
                VoidWarehouseExchange.Plan first = VoidWarehouseExchange.plan(table, target, upper, st);
                long firstMs = (System.nanoTime() - t0) / 1_000_000L;
                if (firstMs > worstProbeMs) {
                    worstProbeMs = firstMs;
                    worstProbe = target;
                }

                // 再数「要试几次」
                BigInteger probe = upper;
                int probes = 0;
                while (probe.signum() > 0 && probes < 200_000) {
                    probes++;
                    if (VoidWarehouseExchange.plan(table, target, probe, st) != null) {
                        break;
                    }
                    probe = probe.subtract(BigInteger.ONE);
                }
                if (probes > worstProbes) {
                    worstProbes = probes;
                    mostProbes = target;
                }
                if (firstMs >= 20 || probes > 4) {
                    System.out.println(String.format("  %-16s 上界=%-12s 首探/成功=%-5s 首探 %4d ms  总探针 %d",
                            target, upper, first != null ? "中" : "失败", firstMs, probes));
                }
            }
            System.out.println(String.format("  >> 单次规划最贵：%s %d ms；探针次数最多：%s %d 次",
                    worstProbe, worstProbeMs, mostProbes, worstProbes));
            System.out.flush();
        }
    }

    private static VoidWarehouseExchange.Stock stock(Map<String, Long> snapshot) {
        Map<String, BigInteger> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : snapshot.entrySet()) {
            converted.put(e.getKey(), BigInteger.valueOf(e.getValue()));
        }
        return VoidWarehouseExchange.Stock.of(converted);
    }
}
