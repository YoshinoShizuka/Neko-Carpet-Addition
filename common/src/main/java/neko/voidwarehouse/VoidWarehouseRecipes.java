package neko.voidwarehouse;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 「玩家合成过的配方」的<b>纯逻辑</b>模型与<b>可递归的扣料</b>算法。
 *
 * <p>本类不引用任何 {@code net.minecraft} 类型，所以放在 {@code common} 里只写一份，
 * 五个 MC 版本共用。各版本的 {@code VoidWarehouseRecipeBook} 负责把真实的
 * {@code RecipeHolder} 解析成这里的 {@link Recipe}（那是唯一需要碰 MC 类型的地方）。</p>
 *
 * <h2>与「9 合 1 兑换」的分工（重要）</h2>
 * <p>{@link VoidWarehouseExchange} 回答的是「库存能<b>换算</b>出多少」，它需要价值体系、
 * 三个上界和 {@code planMax} 的逐个试探。本类回答的是一个简单得多的问题：
 * <b>按这些配方，仓库里的东西现在够做几个</b>。</p>
 *
 * <p>本类没有价值体系、没有状态副本矩阵、也没有「从上往下探几百万次」那种东西。
 * 唯一的循环上限是 {@link #MAX_VERIFY}（验证退让次数）与 {@link #MAX_NODES}（展开预算），
 * 两者都是硬预算。</p>
 *
 * <h2>可以往下钻几层（1.0.16 起的语义）</h2>
 * <p>取「活塞」时需要 3 个深色橡木木板。如果仓库里木板是 <b>0</b> 个、但躺着深色橡木原木，
 * 那么「木板」这一项会<b>顺着玩家学过的「原木 → 木板」配方继续往下钻</b>，
 * 扣原木、把多出来的木板入账回仓库。这就是玩家直觉里的行为：合成台里他会先做木板再做活塞，
 * 仓库替他做掉这一步，天经地义。</p>
 *
 * <p>往下钻有四个硬约束，缺一不可（每一条都是「不这样做就会算出做不出来的账、或者刷物」）：</p>
 * <ol>
 *   <li><b>深度上限 {@link #MAX_DEPTH}</b>：「铁锭 ← 铁块 ← …」这种链在模组包里可以很长，
 *       不设界就是无限递归；</li>
 *   <li><b>路径去环</b>：某一项正在被展开时，它的祖先不许再出现在子树里
 *       （否则「A 由 A 做出来」会自己吃自己，那是 9 合 1 里「撞环返 0」的同源问题）；</li>
 *   <li><b>展开预算 {@link #MAX_NODES}</b>：一条配方 9 项、每项又有 3 条配方、再往下 9 项……
 *       节点数是乘积，必须有一把尺子；</li>
 *   <li><b>先吃库存再合成</b>：家里有 2 个木板、需要 3 个，就扣 2 个木板 + 做 1 次木板
 *       （出 4 个，用掉 1 个，剩 3 个入账）—— <b>不是</b>「一缺货就整项去做」，
 *       那会把家里现成的木板白白放过、反而多扣原木。</li>
 * </ol>
 *
 * <h2>算法的形状：算上界 → 摆一遍 → 摆不下就退一步</h2>
 * <p>「能做几次」靠两步得出：先用<b>宽松上界</b>估一个次数（{@link #available}，宁可估大），
 * 再用 {@link #simulate} <b>真的把整套扣料摆一遍</b>（含往下钻的每一层、每一项选哪个候选）。
 * 摆不下就退 1 次再摆，最多退 {@link #MAX_VERIFY} 次。摆得下的那一次，账目就是最终账目 ——
 * 于是「界面上说能做 N 个」与「真点下去能拿到 N 个」是<b>同一次模拟</b>的结果，
 * 不存在「显示得比真值大」。这是 9 合 1 在 1.0.7 栽过的形态，这里从结构上避开。</p>
 */
public final class VoidWarehouseRecipes {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;

    /**
     * 往下钻几层。
     *
     * <p>4 层足够覆盖原版最常见的嵌套：<b>原木 → 木板 → 木棍/台阶 → 成品</b>、
     * <b>矿粒 → 锭 → 块 → 成品</b>。再深的链（模组包里的多级中间产物）会停在
     * 「这一项找不到料」，结果是<b>保守失败</b>（取不到），不会算错账。</p>
     *
     * <p>为什么不干脆放开：每加一层，展开节点数的上界就乘一次「每项候选数 × 每项配方数」。
     * 一条 9 项、每项 3 条配方的配方，4 层就是 (9×3)^4 ≈ 6.5 万，已经贴着单次规划的预算了。</p>
     */
    public static final int MAX_DEPTH = 4;

    /**
     * 一次规划最多展开多少个「节点」（一次 {@code supply} 的往下钻算一个节点）。
     *
     * <p>这是本项目付过代价的规矩：<b>任何「从上往下试」都必须有硬预算</b>。
     * 预算用完就当作「这一项没料」—— 保守失败，绝不返回一个没验证过的账。</p>
     */
    private static final int MAX_NODES = 512;

    /** 验证阶段最多往下退几次（见 {@link #maxCraftable}）；正常数据里第一次就摆得下 */
    private static final int MAX_VERIFY = 64;

    /** {@link #stockClosure} 最多收集多少种物品（防止病态配方把闭包撑爆） */
    private static final int MAX_CLOSURE = 256;

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
     * @param crafts   做几次合成（根配方做几次）
     * @param consumed 实际要扣的物品（{@code itemId → 数量}，值恒为正）。<b>只含叶子物品</b> ——
     *                 凡是被「先扣掉、再生出来」的中间产物都净额抵消掉了，
     *                 没被选中的候选也不在里面
     * @param surplus  做多了要<b>入账回仓库</b>的东西（{@code itemId → 数量}）。
     *                 含两类：根产物做多了的零头（{@code crafts × outputCount − want}），
     *                 以及往下钻时中间产物多出来的那部分（1 原木做 4 木板、只用 3 个 → 剩 1 个木板）
     */
    public record Plan(BigInteger crafts, Map<String, BigInteger> consumed,
                       Map<String, BigInteger> surplus) {
        public Plan {
            consumed = Map.copyOf(consumed);
            surplus = Map.copyOf(surplus);
        }
    }

    // ================================================================== 对外：规划 / 上限 / 库存闭包

    /**
     * 规划「取 {@code want} 个产物」。凑不出来返回 {@code null}（调用方据此整次拒绝，仓库一个字节都不动）。
     *
     * <p>做法：{@code crafts = ceil(want / outputCount)}（产出 4 个木板的配方，取 1 个也要做 1 次），
     * 然后<b>真的摆一遍</b>（{@link #simulate}，含往下钻的每一层）。
     * 摆得下就返回那一遍算出来的账；摆不下返回 {@code null}。</p>
     *
     * <p>⚠️ 这里做的是一次<b>精确模拟</b>，不是估计。所以返回的
     * {@code consumed} 里不会出现「猜的」数字 —— 它恰好等于「这一份计划执行时会扣掉的东西」，
     * 而 {@code surplus} 恰好等于「会还回来的东西」。界面上列的成本、真实执行的扣料，
     * 与这里返回的是同一次计算。</p>
     *
     * @param stock    库存快照，<b>必须包含整条闭包</b>（见 {@link #stockClosure}）：
     *                 中间产物也在里面，否则会被当成「家里没有」而去多做一层
     * @param byOutput 产物 id → 该产物的所有已学配方（{@link #byOutput}）。空 map = 不往下钻
     */
    public static Plan plan(Recipe recipe, BigInteger want, Map<String, BigInteger> stock,
                            Map<String, List<Recipe>> byOutput) {
        if (recipe == null || want == null || want.signum() <= 0 || stock == null || byOutput == null) {
            return null;
        }
        BigInteger out = BigInteger.valueOf(recipe.outputCount());
        BigInteger crafts = want.add(out).subtract(ONE).divide(out);
        if (crafts.signum() <= 0) {
            return null;
        }
        Model model = new Model(stock);
        if (!simulate(recipe, crafts, model, byOutput, 0, new LinkedHashSet<>(), new int[]{MAX_NODES})) {
            return null;
        }
        BigInteger extra = crafts.multiply(out).subtract(want);
        if (extra.signum() > 0) {
            model.surplus.merge(recipe.output(), extra, BigInteger::add);
        }
        return new Plan(crafts, model.consumed, model.surplus);
    }

    /**
     * 「现在最多能做几个（成品个数）」。
     *
     * <p>两步：先用 {@link #available} 这套宽松上界估一个次数，再用 {@link #simulate}
     * 摆一遍；摆不下就退 1 次再摆，最多退 {@link #MAX_VERIFY} 次。摆得下的那一次就是答案 ——
     * 与 {@link #plan} 用的是<b>同一个模拟器</b>，所以「界面说能合成 N 个」与
     * 「真点下去能拿到 N 个」不会打架。</p>
     *
     * <p>为什么上界要「宽松」而不是「精确」：往下钻之后，「某项够不够」取决于这一项
     * 又去做了几次别的合成，精确上界本身就要靠搜索求。既然验证阶段已经把整棵树摆过一遍，
     * 上界的作用就只是<b>把搜索起点压低</b>，估大一点无所谓（退几次就到真值），估小才是灾难
     * （会少报）。所以 {@link #available} 一律取「家里有的 + 全部拿去做能做出的」，
     * 明知共享原料时会重复计数 —— 那份误差由验证兜住。</p>
     *
     * <p>返回值是 {@code outputCount} 的整数倍 —— 它表示「能做出来的成品总数」，
     * 而不是次数。调用方要「正好取 N 个」时交给 {@link #plan}，那边会自己向上取整。</p>
     */
    public static BigInteger maxCraftable(Recipe recipe, Map<String, BigInteger> stock,
                                          Map<String, List<Recipe>> byOutput) {
        if (recipe == null || stock == null || byOutput == null) {
            return ZERO;
        }
        Map<String, BigInteger> cache = new HashMap<>();
        int[] budget = new int[]{MAX_NODES};
        BigInteger cap = boundCap(recipe.parts(),
                id -> availOf(id, stock, byOutput, cache, new LinkedHashSet<>(), 0, budget));
        if (cap == null) {
            // 合法配方至少有一项、每项至少一个候选，所以这里走不到；留着是为了「不返回 null 出去」
            // —— 调用方会直接 cap.signum()，一个 null 就是崩服。
            return ZERO;
        }
        BigInteger out = BigInteger.valueOf(recipe.outputCount());
        for (int probe = 0; probe < MAX_VERIFY && cap.signum() > 0; probe++, cap = cap.subtract(ONE)) {
            Model model = new Model(stock);
            if (simulate(recipe, cap, model, byOutput, 0, new LinkedHashSet<>(), new int[]{MAX_NODES})) {
                return cap.multiply(out);
            }
        }
        return ZERO;
    }

    /**
     * 把一条配方<b>整条闭包</b>的库存快照取出来（配方要用到的每一样东西现在有多少）。
     *
     * <p>为什么要闭包而不是「这条配方的原料」：往下钻之后，判断「够不够」要用到
     * <b>更深一层的原料</b>（活塞 → 木板 → 原木）。少了原木那一项，
     * 模拟会当成「家里 0 个原木」而直接判做不出来 —— 正是 1.0.15 报的那个 bug 的形态。</p>
     *
     * <p>只收闭包、<b>不是整仓快照</b>：一条配方通常 1~9 项、往下最多 4 层，
     * 闭包也就几十种物品，而仓库可能有几百格。小 map 的复制成本可以忽略
     * （模拟过程里每个分支都要复制一份）。</p>
     *
     * <p>不认识的 id（模组卸载后的残留配方）会拿到 {@code ZERO}，不会抛。</p>
     *
     * @param amount 物品 id → 仓库里有多少个<b>纯净</b>物品
     */
    public static Map<String, BigInteger> stockClosure(Recipe root,
                                                       Map<String, List<Recipe>> byOutput,
                                                       Function<String, BigInteger> amount) {
        Map<String, BigInteger> stock = new LinkedHashMap<>();
        if (root == null || byOutput == null || amount == null) {
            return stock;
        }
        collectStock(root, byOutput, amount, stock, new LinkedHashSet<>(), 0);
        return stock;
    }

    private static void collectStock(Recipe recipe, Map<String, List<Recipe>> byOutput,
                                     Function<String, BigInteger> amount,
                                     Map<String, BigInteger> stock, Set<String> path, int depth) {
        for (Part part : recipe.parts()) {
            for (String candidate : part.candidates()) {
                if (stock.size() >= MAX_CLOSURE) {
                    return;
                }
                if (stock.containsKey(candidate)) {
                    continue;
                }
                BigInteger got = amount.apply(candidate);
                stock.put(candidate, got == null ? ZERO : got);
                if (depth >= MAX_DEPTH || path.contains(candidate)) {
                    continue;
                }
                List<Recipe> subs = byOutput.get(candidate);
                if (subs == null || subs.isEmpty()) {
                    continue;
                }
                path.add(candidate);
                try {
                    for (Recipe sub : subs) {
                        collectStock(sub, byOutput, amount, stock, path, depth + 1);
                    }
                } finally {
                    path.remove(candidate);
                }
            }
        }
    }

    // ================================================================== 模拟器

    /**
     * 模拟过程中的三本账。
     *
     * <p>{@code pool} 是「还能用多少」的<b>可变池</b>：既有仓库里现成的，也有刚刚往下钻做出来的。
     * 分支走错时要能退回去，所以每个分支都拿 {@link #copy()} 而不是改原件；
     * 走对了再用 {@link #adopt} 把结果搬回来。</p>
     *
     * <p>为什么不用「先记操作日志、失败再回放逆操作」：逆操作的写法容易漏，
     * 而漏一处就是账目对不上（这是本项目最不能接受的一类 bug）。复制小 map 笨但不会错。</p>
     */
    private static final class Model {
        final Map<String, BigInteger> pool;
        final Map<String, BigInteger> consumed = new LinkedHashMap<>();
        final Map<String, BigInteger> surplus = new LinkedHashMap<>();

        Model(Map<String, BigInteger> stock) {
            this.pool = new HashMap<>(stock);
        }

        Model copy() {
            Model copy = new Model(this.pool);
            copy.consumed.putAll(this.consumed);
            copy.surplus.putAll(this.surplus);
            return copy;
        }

        void adopt(Model other) {
            this.pool.clear();
            this.pool.putAll(other.pool);
            this.consumed.clear();
            this.consumed.putAll(other.consumed);
            this.surplus.clear();
            this.surplus.putAll(other.surplus);
        }
    }

    /**
     * 把「做 {@code crafts} 次 {@code recipe}」真摆一遍，成功时把账写进 {@code model}。
     *
     * <p>逐项处理原料；一项有多候选时按候选顺序试，<b>每个候选都在独立的副本上试</b>，
     * 失败就换下一个（换了之后前一候选的扣料不会残留）。</p>
     */
    private static boolean simulate(Recipe recipe, BigInteger crafts, Model model,
                                    Map<String, List<Recipe>> byOutput,
                                    int depth, Set<String> path, int[] budget) {
        for (Part part : recipe.parts()) {
            BigInteger amount = crafts.multiply(BigInteger.valueOf(part.count()));
            boolean done = false;
            for (String candidate : part.candidates()) {
                Model trial = model.copy();
                if (supply(candidate, amount, trial, byOutput, depth, path, budget)) {
                    model.adopt(trial);
                    done = true;
                    break;
                }
            }
            if (!done) {
                return false;
            }
        }
        return true;
    }

    /**
     * 凑出 {@code amount} 个 {@code item}：<b>先吃库存，不够的再往下钻</b>。
     *
     * <p>顺序不能反。家里有 2 个木板、这一项要 3 个，正确做法是「扣 2 个木板 + 做 1 次木板」；
     * 若改成「一缺货就整项去做」，会做出 4 个木板、用掉 3 个、剩 3 个入账 ——
     * 净效果虽然也是「多了 1 个木板」，但白扣了一次原木，玩家看到的就是「明明有木板还扣我原木」。</p>
     *
     * <p>⚠️ 本方法会改 {@code model}，<b>失败时不会自动还原</b> —— 调用方必须传副本
     * （{@link #simulate} 的候选循环与下面子配方循环都是这么做的）。</p>
     */
    private static boolean supply(String item, BigInteger amount, Model model,
                                  Map<String, List<Recipe>> byOutput,
                                  int depth, Set<String> path, int[] budget) {
        BigInteger have = model.pool.getOrDefault(item, ZERO);
        BigInteger use = have.min(amount);
        if (use.signum() > 0) {
            model.pool.put(item, have.subtract(use));
            model.consumed.merge(item, use, BigInteger::add);
        }
        BigInteger rest = amount.subtract(use);
        if (rest.signum() <= 0) {
            return true;
        }
        // 剩下的从库存里拿不到 —— 只能往下钻。四条硬约束依次过一遍。
        if (depth >= MAX_DEPTH || path.contains(item)) {
            return false;
        }
        List<Recipe> subs = byOutput.get(item);
        if (subs == null || subs.isEmpty()) {
            return false;
        }
        if (--budget[0] < 0) {
            return false;
        }
        path.add(item);
        try {
            for (Recipe sub : subs) {
                BigInteger out = BigInteger.valueOf(sub.outputCount());
                // 要 rest 个，一次出 out 个 ⇒ 做 ceil(rest / out) 次。
                // 多出来的 subCrafts × out − rest 个必须<b>入账回仓库</b>（不是丢掉，也不是白送）。
                BigInteger subCrafts = rest.add(out).subtract(ONE).divide(out);
                Model trial = model.copy();
                if (simulate(sub, subCrafts, trial, byOutput, depth + 1, path, budget)) {
                    BigInteger extra = subCrafts.multiply(out).subtract(rest);
                    if (extra.signum() > 0) {
                        trial.surplus.merge(item, extra, BigInteger::add);
                    }
                    model.adopt(trial);
                    return true;
                }
            }
            return false;
        } finally {
            path.remove(item);
        }
    }

    // ================================================================== 上界

    /**
     * 宽松上界：{@code 家里有的 + 全部拿去做能做出的}。
     *
     * <p>明知共享原料时会重复计数（两处都要原木时，原木在两处各算一遍），
     * 所以这个数是<b>上界而不是答案</b> —— 它的唯一用途是把 {@link #maxCraftable} 的搜索起点压低。
     * 估大的代价是多退几次，估小才是灾难（会少报「能做几个」）。</p>
     *
     * <p>环（A 由 B 做、B 又由 A 做）在 {@code path} 里被截断，环上的物品只按库存算 ——
     * 这是保守方向，与 9 合 1 的「撞环返 0」同一条思路。</p>
     *
     * <p>深度靠 {@code depth + 1} 往下传，<b>不是</b>「同一个 depth 反复调」——
     * 后者只有 {@code path} 与预算在拦，深度上限形同虚设（这是第一版写错的地方）。</p>
     */
    private static BigInteger availOf(String item, Map<String, BigInteger> stock,
                                      Map<String, List<Recipe>> byOutput,
                                      Map<String, BigInteger> cache, Set<String> path,
                                      int depth, int[] budget) {
        BigInteger cached = cache.get(item);
        if (cached != null) {
            return cached;
        }
        BigInteger base = stock.getOrDefault(item, ZERO);
        // 这三条出口返回的是「别往下看了」的保守值，<b>不能进缓存</b>：
        // 同一件物品在别的路径上（不在环里、深度还够）可能算出更大的数。
        if (depth >= MAX_DEPTH || path.contains(item) || --budget[0] < 0) {
            return base;
        }
        List<Recipe> subs = byOutput.get(item);
        if (subs == null || subs.isEmpty()) {
            cache.put(item, base);
            return base;
        }
        BigInteger bestMade = ZERO;
        path.add(item);
        try {
            for (Recipe sub : subs) {
                BigInteger cap = boundCap(sub.parts(),
                        id -> availOf(id, stock, byOutput, cache, path, depth + 1, budget));
                if (cap == null) {
                    continue;
                }
                bestMade = bestMade.max(cap.multiply(BigInteger.valueOf(sub.outputCount())));
            }
        } finally {
            path.remove(item);
        }
        BigInteger total = base.add(bestMade);
        cache.put(item, total);
        return total;
    }

    /**
     * 按一条配方的原料清单算「最多能做几次」的宽松上界；一项都顶不了就返回 {@code null}。
     *
     * <h2>为什么要按候选合并，而不是逐项各自取 min</h2>
     * <p>配方两项都只认 A、各要 1 个，仓库有 3 个 A：逐项算会得出「第一项能做 3 次」，
     * 再取 min 还是 3 —— 而真值是 1（两项一共要 2 个 A）。这个错法是 1.0.15 之前的形态，
     * 表现是「报得比真值大、点下去失败」。所以单候选项先按物品<b>合并需求</b>再相除，
     * 共享的那份库存就不会被数两遍。</p>
     *
     * <p>多候选项不能合并（它们本来就是「任选其一」），取「各候选里最大的那个」作为上界 ——
     * 这是<b>上界</b>而不是估计值，误差交给验证阶段。</p>
     */
    private static BigInteger boundCap(List<Part> parts, Function<String, BigInteger> avail) {
        Map<String, Integer> forced = forcedDemand(parts);
        BigInteger cap = null;
        for (Map.Entry<String, Integer> entry : forced.entrySet()) {
            cap = lower(cap, avail.apply(entry.getKey())
                    .divide(BigInteger.valueOf(entry.getValue())));
        }
        for (Part part : parts) {
            if (part.candidates().size() <= 1) {
                continue;
            }
            BigInteger best = ZERO;
            for (String candidate : part.candidates()) {
                BigInteger each = avail.apply(candidate)
                        .divide(BigInteger.valueOf(part.count()));
                if (each.compareTo(best) > 0) {
                    best = each;
                }
            }
            cap = lower(cap, best);
        }
        return cap;
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

    private static BigInteger lower(BigInteger current, BigInteger candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    // ================================================================== 分组

    /**
     * 把一组配方按产物分组（产物 id → 该产物的所有配方）。
     *
     * <p>同一产物有多条配方是常态（同一件物品可能有「橡木木板版」和「云杉木板版」两条，
     * 因为它们确实是玩家各做过一次的两次不同合成）。取用时按列表顺序试第一个能做的，
     * 列表顺序由调用方保证稳定。</p>
     *
     * <p>这张表同时也是「能不能往下钻」的唯一依据：<b>没学过的配方不会凭空出现</b>，
     * 所以仓库永远不会替玩家做一件他从没做过的东西。</p>
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
