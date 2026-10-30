package neko.voidwarehouse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「玩家合成过的配方」的<b>纯逻辑</b>模型与<b>一层扣料</b>算法。
 *
 * <p>本类不引用任何 {@code net.minecraft} 类型，所以放在 {@code common} 里只写一份，
 * 五个 MC 版本共用。各版本的 {@code VoidWarehouseRecipeBook} 负责把真实的
 * {@code RecipeHolder} 解析成这里的 {@link Recipe}（那是唯一需要碰 MC 类型的地方）。</p>
 *
 * <h2>与「9 合 1 兑换」的分工（重要）</h2>
 * <p>{@link VoidWarehouseExchange} 回答的是「库存能<b>换算</b>出多少」，它需要价值体系、
 * 三个上界和 {@code planMax} 的逐个试探。本类回答的是一个简单得多的问题：
 * <b>按这条配方，仓库里的原料现在够做几次</b>。</p>
 *
 * <p>答案是<b>一次算术</b>：合并共享需求 → 取上界 → 摆一遍验证。没有价值体系、没有递归、
 * 没有状态副本，也没有「从上往下探几百万次」那种东西（唯一的循环上限是
 * {@link #MAX_VERIFY}，正常数据里第一次就成功）。这是刻意的：9 合 1 那套复杂度存在的唯一理由是
 * 「成品缺货时要把库存换算成成品」，而配方扣料是「原料直接扣」，不存在换算，也就不需要那套机器。</p>
 *
 * <h2>只支持「一层」</h2>
 * <p>配方里的原料必须<b>仓库里现成</b>，不会再去问「这个原料能不能由别的原料做出来」。
 * 想做两层就得引入递归、状态副本与分支爆炸 —— 那是 1.0.14 事故的同源结构，本类不碰。
 * 需要多级换算的场景仍然交给 {@link VoidWarehouseExchange}（它已经做好了）。</p>
 */
public final class VoidWarehouseRecipes {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    private VoidWarehouseRecipes() {
    }

    /**
     * 配方里的一项原料：<b>任意一种</b>候选物品各 {@code count} 个。
     *
     * <p>为什么是候选集合而不是单个物品：原版配方的原料写着标签（{@code #minecraft:planks}），
     * 12 种木板都算数。记成集合之后，「玩家用橡木木板合成过」就等于学会了「任意木板」，
     * 这正是玩家预期的语义；也避免了「同一条配方因为木材种类不同被记成 12 条」。</p>
     *
     * <p>顺序有意义：优先按候选顺序找，找不到再用下一个。列表顺序由解析方决定
     * （{@code Ingredient#items()} 给出的顺序），本类不重排 —— 于是「同一份存档、
     * 同一份配方，算出来的账永远一样」。</p>
     */
    public record Part(List<String> candidates, int count) {
        public Part {
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalArgumentException("配方原料至少要有一种候选物品");
            }
            if (count < 1) {
                throw new IllegalArgumentException("配方原料的数量至少是 1，收到 " + count);
            }
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * 一条已学会的配方。
     *
     * @param recipeId    这条配方的标识。<b>由 {@link #encode(Recipe)} 生成的文本就是它</b> ——
     *                    从合成格记录下来的配方没有「原版配方 id」可言（玩家用 2 个橡木
     *                    + 2 个云杉木板拼出来的东西，原版里没有这条 id），所以标识直接用
     *                    展开后的内容本身，天然去重、天然跨版本稳定。算法不看这个字段。
     *                    传 {@code null} 或空串时由构造函数按内容生成
     * @param output      产物物品 id（{@code minecraft:} 前缀已去掉，与 9 合 1 表的口径一致）
     * @param outputCount 一次合成产出几个。木板是 4、活塞是 1 —— 这个值不能省，
     *                    否则「取 1 个木板」会算成扣 1 份原料却给 4 个
     * @param parts       原料清单，不可为空
     */
    public record Recipe(String recipeId, String output, int outputCount, List<Part> parts) {
        public Recipe {
            if (output == null || output.isEmpty()) {
                throw new IllegalArgumentException("配方产物 id 不能为空");
            }
            if (outputCount < 1) {
                throw new IllegalArgumentException("配方 " + output + " 的产出数量至少是 1");
            }
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("配方 " + output + " 至少要有一样原料");
            }
            parts = List.copyOf(parts);
            if (recipeId == null || recipeId.isEmpty()) {
                // 标识 = 内容本身。这样「同一条配方学两次」在集合里天然只有一条，
                // 不需要另外维护一份去重键，也不会出现「两个键指向同一条配方」。
                recipeId = format(output, outputCount, parts);
            }
        }
    }

    /**
     * 一次成功的扣料计划。
     *
     * @param crafts   做几次合成
     * @param consumed 实际要扣的物品（{@code itemId → 数量}，值恒为正）。只含真正被扣的候选，
     *                 没被选中的候选不在里面
     * @param surplus  合成产出多于玩家所要的那部分（{@code crafts × outputCount - want}），
     *                 要作为余量<b>入账</b>回仓库。拆上级找零是同一条语义。
     */
    public record Plan(BigInteger crafts, Map<String, BigInteger> consumed, BigInteger surplus) {
        public Plan {
            consumed = Map.copyOf(consumed);
        }
    }

    /**
     * 规划「取 {@code want} 个产物」。凑不出来返回 {@code null}（调用方据此整次拒绝，仓库一个字节都不动）。
     *
     * <h2>算法：一层、算上界、摆一遍</h2>
     * <ol>
     *   <li>先算要做几次：{@code crafts = ceil(want / outputCount)}（产出 4 个木板的配方，取 1 个也要做 1 次）；</li>
     *   <li>问 {@link #greedy}「现在最多能做几次」（合并共享需求 → 取上界 → 摆一遍验证）；</li>
     *   <li>要的次数超过它，就整支失败。</li>
     * </ol>
     *
     * <p><b>不是整数规划，也不需要是</b>：真正「最多能做几个」才需要最优分配（两个原料项共享
     * 同一批候选时谁先拿会影响结果）。本类的目标是「能不能做出来」，而可行解只要一条就够 ——
     * 而且 {@link #greedy} 摆的是<b>同一份候选选择</b>，所以「最多能做 N 次」与
     * 「做 1~N 次都能成功」是同一份计算，不会出现「报得多、做不出来」。</p>
     *
     * <p>⚠️ 多候选项之间仍是「先到先得」，<b>顺序会影响结果</b>。这是有意接受的：顺序由配方固定的
     * 解析顺序决定，同一份存档算出来的账永远一样，不会出现「同一件事两次算出两种扣法」。
     * 单候选项（配方本记下来的全是这种）不受顺序影响。</p>
     */
    public static Plan plan(Recipe recipe, BigInteger want, Map<String, BigInteger> stock) {
        if (recipe == null || want == null || want.signum() <= 0 || stock == null) {
            return null;
        }
        BigInteger out = BigInteger.valueOf(recipe.outputCount());
        BigInteger crafts = want.add(out).subtract(ONE).divide(out);

        Greedy greedy = greedy(recipe, stock);
        if (greedy == null || crafts.compareTo(greedy.crafts()) > 0) {
            return null;
        }

        // 按最终 crafts 重算一遍账，<b>但沿用贪心那一次挑出来的候选</b>。
        // 为什么不能重新挑：重挑会按「新的剩余」再选一次最好的，和界面显示的那份账
        // （{@link #maxCraftable}，同一份贪心）就可能不是同一个候选，于是出现
        // 「tooltip 说扣橡木木板、实际扣了云杉木板」。沿用同一个选择，两处的账永远一致。
        //
        // 而且沿用必定成功（不会出现「重算时才发现不够」）：贪心在第 i 项做决定时，
        // 前面各项已按 ≥ 最终 crafts 的次数扣过，所以那一刻的剩余 ≤ 现在的剩余；
        // 而最终 crafts ≤ 每一项当时的上限，故 take = crafts × count ≤ 当时的剩余。
        Map<String, BigInteger> fresh = new HashMap<>(stock);
        Map<String, BigInteger> consumed = new LinkedHashMap<>();
        List<Part> parts = recipe.parts();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            String chosen = greedy.chosen().get(i);
            BigInteger take = crafts.multiply(BigInteger.valueOf(part.count()));
            BigInteger left = fresh.getOrDefault(chosen, ZERO).subtract(take);
            if (left.signum() < 0) {
                // 上一条注释已经证明到这里走不到。真走到了也绝不硬扣：整支拒绝，
                // 仓库一个字节都不动（宁可玩家取不到，也不许账对不上）。
                return null;
            }
            fresh.put(chosen, left);
            consumed.merge(chosen, take, BigInteger::add);
        }
        return new Plan(crafts, consumed, crafts.multiply(out).subtract(want));
    }

    /**
     * 「现在最多能做几个（成品个数）」。
     *
     * <p>数值来自 {@link #greedy}，与 {@link #plan} 用的是<b>同一次贪心</b>，
     * 所以「界面说能合成 N 个」与「真点下去能拿到 N 个」是同一份计算，不存在
     * 「显示得比真值大 → 点了失败」。这是 9 合 1 在 1.0.7 栽过的形态
     * （{@code min(堆叠上限, 可兑数量)} 那种自己算的算式），这里从结构上避开。</p>
     *
     * <p>返回值是 {@code outputCount} 的整数倍 —— 它表示「能做出来的成品总数」，
     * 而不是次数。调用方要「正好取 N 个」时交给 {@link #plan}，那边会自己向上取整。</p>
     */
    public static BigInteger maxCraftable(Recipe recipe, Map<String, BigInteger> stock) {
        if (recipe == null || stock == null) {
            return ZERO;
        }
        Greedy greedy = greedy(recipe, stock);
        return greedy == null
                ? ZERO
                : greedy.crafts().multiply(BigInteger.valueOf(recipe.outputCount()));
    }

    /**
     * 贪心跑一次的结果：能做几次，以及<b>每一项原料各挑了哪个候选</b>。
     *
     * <p>{@code chosen} 和 {@code recipe.parts()} 一一对应（同下标同长度），
     * 这是 {@link #plan} 能「沿用同一个选择」的前提。</p>
     */
    private record Greedy(BigInteger crafts, List<String> chosen) {
    }

    /**
     * 验证阶段最多往下试几次。
     *
     * <p><b>正常数据里恒为 1 次</b>：配方本记下来的每一项原料都只有<b>一个</b>候选
     * （就是玩家当时真用的那件），这时上界是精确的，第一次验证就成功。
     * 只有「一项原料有多个候选、且多项抢同一批库存」才会往下退 —— 那个形态目前没有生产者，
     * 这里留着界只是不让它变成无界循环（这是本项目付过代价的规矩：任何「从上往下试」都必须有硬预算）。</p>
     */
    private static final int MAX_VERIFY = 64;

    /**
     * 算出「最多能做几次」，并记下每一项各用哪个候选。
     *
     * <h2>为什么不是「逐项挑候选、边挑边扣」那么简单</h2>
     * <p>最直觉的写法是按原料项顺序挑「剩余最多的候选」、用它的上限压小 {@code crafts}、
     * 立刻扣掉。这个写法对「各项原料互不相干」是对的，但<b>两项都只认同一种物品时会算错</b>：
     * 3 个 A、配方要两项各 1 个 A，正确是能做 1 次，它却让第一项把 3 个 A 全占了，
     * 第二项一看没料，直接判「做不出来」。方向虽然安全（少给不少扣），但「明明能做却说不能做」
     * 对玩家是实打实的错。</p>
     *
     * <h2>现在的做法：合并需求 → 取上界 → 从上往下验证</h2>
     * <ol>
     *   <li><b>合并需求</b>：把「只有一个候选」的原料项按候选求和 —— 两项都只认 A、各要 1 个，
     *       就是「A 要 2 份」。这一步让共享库存不再被数两遍；</li>
     *   <li><b>取上界</b>：每个候选算 {@code 库存 / 合并后的需求}，全部取最小。
     *       多候选的原料项取「各候选里最大的那个」（上界，不是估计值）；</li>
     *   <li><b>从上往下验证</b>：把这个上界真的摆一遍（{@link #tryAssign}），摆得下就是答案；
     *       摆不下退 1 再试，最多 {@link #MAX_VERIFY} 次。</li>
     * </ol>
     *
     * <p>为什么不能直接用上界当答案：多候选项的上界是「各候选独立算」的，
     * 两个都认 {@code [A,B]} 的项在一个只有 1 个 A、1 个 B 的仓库里会各自算出 1，
     * 而上界取小还是 1 —— 但真摆的时候三项抢两件东西，一件都摆不下。
     * 所以必须验证，不能只算。</p>
     *
     * <p>任一项一个候选都顶不了 1 次、或验证预算用完仍摆不下时返回 {@code null}。</p>
     */
    private static Greedy greedy(Recipe recipe, Map<String, BigInteger> stock) {
        List<Part> parts = recipe.parts();
        Map<String, Integer> forced = forcedDemand(parts);

        BigInteger cap = null;
        // 单候选项：合并后算，共享库存不会被数两遍
        for (Map.Entry<String, Integer> entry : forced.entrySet()) {
            cap = lower(cap, stock.getOrDefault(entry.getKey(), ZERO)
                    .divide(BigInteger.valueOf(entry.getValue())));
        }
        // 多候选项：各候选里最大的（上界），留着交给验证阶段
        for (Part part : parts) {
            if (part.candidates().size() <= 1) {
                continue;
            }
            BigInteger best = ZERO;
            for (String candidate : part.candidates()) {
                BigInteger each = stock.getOrDefault(candidate, ZERO)
                        .divide(BigInteger.valueOf(part.count()));
                if (each.compareTo(best) > 0) {
                    best = each;
                }
            }
            cap = lower(cap, best);
        }
        if (cap == null || cap.signum() <= 0) {
            return null;
        }
        BigInteger crafts = cap;
        for (int probe = 0; probe < MAX_VERIFY && crafts.signum() > 0; probe++, crafts = crafts.subtract(ONE)) {
            List<String> chosen = tryAssign(parts, forced, crafts, stock);
            if (chosen != null) {
                return new Greedy(crafts, chosen);
            }
        }
        return null;
    }

    /** 「只有一个候选」的原料项按候选合并后的总需求（候选 → 每次合成要几个） */
    private static Map<String, Integer> forcedDemand(List<Part> parts) {
        Map<String, Integer> forced = new HashMap<>();
        for (Part part : parts) {
            if (part.candidates().size() == 1) {
                forced.merge(part.candidates().get(0), part.count(), Integer::sum);
            }
        }
        return forced;
    }

    /**
     * 把「做 {@code crafts} 次」真摆一遍：单候选项按合并需求一次扣完，多候选项依次挑剩余最多的。
     *
     * <p>摆得下就返回每一项实际用的候选（顺序与 {@code parts} 一一对应），摆不下返回 {@code null}。
     * 整个过程只读传入的快照，不改任何外部状态。</p>
     */
    private static List<String> tryAssign(List<Part> parts, Map<String, Integer> forced,
                                          BigInteger crafts, Map<String, BigInteger> stock) {
        Map<String, BigInteger> remaining = new HashMap<>(stock);
        for (Map.Entry<String, Integer> entry : forced.entrySet()) {
            BigInteger take = crafts.multiply(BigInteger.valueOf(entry.getValue()));
            BigInteger left = remaining.getOrDefault(entry.getKey(), ZERO).subtract(take);
            if (left.signum() < 0) {
                return null;
            }
            remaining.put(entry.getKey(), left);
        }
        List<String> chosen = new ArrayList<>(parts.size());
        for (Part part : parts) {
            if (part.candidates().size() == 1) {
                // 已经在上面按合并需求统一扣过了，这里只登记选择
                chosen.add(part.candidates().get(0));
                continue;
            }
            String best = pickCandidate(part, remaining);
            if (best == null) {
                return null;
            }
            BigInteger left = remaining.get(best).subtract(crafts.multiply(BigInteger.valueOf(part.count())));
            if (left.signum() < 0) {
                return null;
            }
            remaining.put(best, left);
            chosen.add(best);
        }
        return chosen;
    }

    private static BigInteger lower(BigInteger current, BigInteger candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    /** 在剩余库存里挑「能顶最多次」的候选；一个都顶不了就返回 {@code null} */
    private static String pickCandidate(Part part, Map<String, BigInteger> remaining) {
        String best = null;
        BigInteger bestCap = ZERO;
        for (String cand : part.candidates()) {
            BigInteger cap = remaining.getOrDefault(cand, ZERO).divide(BigInteger.valueOf(part.count()));
            if (cap.signum() > 0 && cap.compareTo(bestCap) > 0) {
                bestCap = cap;
                best = cand;
            }
        }
        return best;
    }

    /**
     * 把一组配方按产物分组（产物 id → 该产物的所有配方）。
     *
     * <p>同一产物有多条配方是常态（同一件物品可能有「橡木木板版」和「云杉木板版」两条，
     * 因为它们确实是玩家各做过一次的两次不同合成）。取用时按列表顺序试第一个能做的，
     * 列表顺序由调用方保证稳定。</p>
     */
    public static Map<String, List<Recipe>> byOutput(Iterable<Recipe> recipes) {
        Map<String, List<Recipe>> map = new LinkedHashMap<>();
        for (Recipe recipe : recipes) {
            map.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
        }
        return map;
    }

    // ------------------------------------------------------------------ 存档编解码

    /**
     * 一条配方编码成一串文本：{@code 产物id*产出数;原料id×数量,原料id×数量}。
     *
     * <p>示例：{@code piston*1;oak_planks×3,cobblestone×4,iron_ingot×1,redstone×1}</p>
     *
     * <p><b>为什么存展开后的文本而不是原版配方 id</b>：这条配方是从「玩家实际摆进合成格的
     * 物品」记下来的（见 {@code CraftResultMixin}），原版可能根本没有一条 id 与之一一对应
     * （比如玩家用 2 个橡木木板 + 2 个云杉木板拼出的东西）。存展开文本还有两个好处：
     * 不依赖 {@code RecipeManager}（{@code /reload}、换数据包、换版本都不会失效），
     * 且存档里能直接读出「他当时到底用了什么」。</p>
     *
     * <p>解析失败一律返回 {@code null}，由调用方跳过 —— 一条坏数据不该让整个仓库读不出来。</p>
     */
    public static String encode(Recipe recipe) {
        return format(recipe.output(), recipe.outputCount(), recipe.parts());
    }

    /** 编码的唯一实现：{@link Recipe} 的构造函数与 {@link #encode(Recipe)} 共用，保证两边逐字节一致 */
    private static String format(String output, int outputCount, List<Part> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append(output).append('*').append(outputCount).append(';');
        boolean first = true;
        for (Part part : parts) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            // 候选之间用 '|' 分隔；从合成格记录的配方只有一个候选，但模型支持多个
            boolean firstCand = true;
            for (String cand : part.candidates()) {
                if (!firstCand) {
                    sb.append('|');
                }
                firstCand = false;
                sb.append(cand);
            }
            sb.append('×').append(part.count());
        }
        return sb.toString();
    }

    /** 反解 {@link #encode(Recipe)}；格式不对返回 {@code null}（坏数据静默跳过，不抛） */
    public static Recipe decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            int semi = encoded.indexOf(';');
            if (semi <= 0 || semi == encoded.length() - 1) {
                return null;
            }
            String head = encoded.substring(0, semi);
            int star = head.lastIndexOf('*');
            if (star <= 0) {
                return null;
            }
            String output = head.substring(0, star);
            int outputCount = Integer.parseInt(head.substring(star + 1));

            List<Part> parts = new ArrayList<>();
            for (String chunk : encoded.substring(semi + 1).split(",")) {
                int times = chunk.lastIndexOf('×');
                if (times <= 0) {
                    return null;
                }
                String cands = chunk.substring(0, times);
                int count = Integer.parseInt(chunk.substring(times + 1));
                List<String> candidates = new ArrayList<>();
                for (String cand : cands.split("\\|")) {
                    if (!cand.isEmpty()) {
                        candidates.add(cand);
                    }
                }
                if (candidates.isEmpty()) {
                    return null;
                }
                parts.add(new Part(candidates, count));
            }
            // 标识传 null：让构造函数按规范化后的内容重新生成，
            // 这样「手工改过格式的存档文本」与「我们自己写出去的」会折叠成同一条。
            return new Recipe(null, output, outputCount, parts);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 把一批编码解成配方，坏的跳过。返回的顺序与输入一致（调用方依赖这个顺序做稳定选择） */
    public static List<Recipe> decodeAll(Iterable<String> encoded) {
        List<Recipe> out = new ArrayList<>();
        for (String s : encoded) {
            Recipe recipe = decode(s);
            if (recipe != null) {
                out.add(recipe);
            }
        }
        return out;
    }
}
