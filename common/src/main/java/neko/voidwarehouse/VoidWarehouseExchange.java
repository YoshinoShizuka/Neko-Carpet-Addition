package neko.voidwarehouse;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「9 合 1」兑换引擎 —— 纯逻辑，<b>不引用任何 Minecraft 类型</b>，五个版本共用同一份。
 *
 * <h2>它解决什么问题</h2>
 * <p>虚空仓库里只有 9 个铁粒、没有铁锭时，玩家点「铁锭」这一格应该能拿到 1 个铁锭
 * （仓库里那 9 个铁粒被扣掉），等价于「替玩家把合成步骤做掉」。反过来也成立：
 * 仓库里只有 1 个铁块时，取 1 个铁锭就是「把铁块拆了，1 个给玩家、8 个退回仓库」。</p>
 *
 * <h2>为什么这件事不能靠「等价总量」直接算</h2>
 * <p>26.3 全部 29 条 9 合 1 边里，只有 20 条是双向的。另外 8 条<b>只能向上合成</b>
 * （原版没有对应的拆分配方，例如 {@code 9 冰 → 1 浮冰}，但浮冰拆不回冰），
 * 还有 1 条<b>只能向下拆分</b>（{@code 1 涂蜡铜块 → 9 铜锭}，但 9 个铜锭合不出涂蜡铜块）。
 * 所以这是一个<b>有向图</b>，「把库存折成一个总数」的做法会把不存在的配方也算进去，
 * 那才是真正会凭空造物的地方。</p>
 *
 * <h2>算法：按方向递归「取数」，分支失败整支丢弃</h2>
 * <p>核心是 {@link #drain}：<b>让仓库的账目正好少掉 {@code need} 个 {@code item}</b>。
 * 三条路按优先级依次尝试，<b>每次都在状态副本上试，失败就整支丢掉</b>
 * （这点必须做：否则「先扣了几个下级、再发现凑不够」会把仓库的账吃掉一块）：</p>
 * <ol>
 *   <li><b>直接扣库存</b> —— <b>只要成品本身有货就永远走这条路</b>，
 *       所以「有货时绝不做任何兑换」是结构上保证的，不是靠判断；</li>
 *   <li><b>用下级合成</b>：需要 {@code 9 × need} 个上级原料，递归取；</li>
 *   <li><b>拆上级</b>：拆「用得完的整块」—— {@code floor(need / 9)} 个更大的物品，
 *       上级不够就逐块往下退；差的零头交给前两条路补。
 *       只有 {@code need < 9} 时才多拆一块、把用不完的<b>作为成品退回仓库</b>
 *       （这就是玩家要的「拆零找零」）。</li>
 * </ol>
 * <p>递归里用 {@link State#visiting} 破环：{@code 铁锭 → 需要铁粒 → 只能拆铁锭} 这种
 * 自我指涉的分支必须直接判失败，否则会无限递归。</p>
 *
 * <h2>守恒性（自检里逐条断言）</h2>
 * <p>每次 {@link #drain} 成功时，都有<b>不变式</b>：
 * <code>Σ(每条 delta 的数量 × 9^(该物品层级 − 目标物品层级)) == −need</code>。
 * 换句话说，仓库少掉的价值<b>恰好等于玩家拿走的价值</b>，一步不多一步不少 ——
 * 这是「不可能刷物」的形式化保证，也是本类唯一的正确性判据。</p>
 *
 * <h2>和五个版本的关系</h2>
 * <p>{@link #EDGES} 是<b>26.x 的超集</b>（在 26.3 的全量配方上采集）。
 * 旧版本里没有的物品 / 配方，由各版本在 {@code ServerLifecycleEvents.SERVER_STARTED}
 * 之后用真实的 {@code RecipeManager} 复核一遍，把不成立的边剔掉，再把剩下的交给
 * {@link #setActive}。所以本类本身不需要知道版本差异。</p>
 */
public final class VoidWarehouseExchange {

    private VoidWarehouseExchange() {
    }

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger NINE = BigInteger.valueOf(9);

    /**
     * 递归深度上限。
     *
     * <p>本表里最长的一条链是「冰 → 浮冰 → 蓝冰」（深 3），加一次「铁粒 → 铁锭 → 铁块」
     * 的拆零也才 4 层。取 8 有一倍余量，同时保证任何意外的环都会在有限步内被判失败
     * （环本身另有 {@link State#visiting} 兜着，这只是第二道闸门）。</p>
     */
    private static final int MAX_DEPTH = 8;

    // ------------------------------------------------------------------ 边

    /**
     * 一条 9 合 1 边：{@code 9 × small ⇄ 1 × big}。
     *
     * <p>两个方向各自独立：{@code combineRecipe} 非空 = 原版有「9 个 small → 1 个 big」的配方；
     * {@code splitRecipe} 非空 = 原版有「1 个 big → 9 个 small」的配方。<b>两个都可以为 null
     * 的边不存在</b>（构造器直接拒绝），但允许只有其中一个方向成立。</p>
     *
     * <p>配方 id 只用于启动自检的报错信息与文档追溯，运行期算法完全不看它。</p>
     */
    public record Edge(String small, String big, String combineRecipe, String splitRecipe) {
        public Edge {
            if (small == null || big == null || small.isEmpty() || big.isEmpty() || small.equals(big)) {
                throw new IllegalArgumentException(
                        "9合1 边的两端必须是两个不同的非空物品 id，收到 " + small + " / " + big);
            }
            if (combineRecipe == null && splitRecipe == null) {
                throw new IllegalArgumentException(
                        "9合1 边 " + small + " / " + big + " 至少要有 combine / split 其中一个方向");
            }
        }

        /** 能不能「9 个 small 合出 1 个 big」 */
        public boolean canCombine() {
            return this.combineRecipe != null;
        }

        /** 能不能「把 1 个 big 拆成 9 个 small」 */
        public boolean canSplit() {
            return this.splitRecipe != null;
        }
    }

    /**
     * 全部「9 合 1」边（29 条 / 53 个物品），按 (small, big) 字典序。
     *
     * <p>采集口径（对 Minecraft 26.3 的原版配方全集做过一次全量扫描，2042 个配方文件）：</p>
     * <ul>
     *   <li>{@code combineRecipe} 非空 = 存在配方「9 个 {@code small} → 1 个 {@code big}」
     *       （{@code crafting_shaped} 的 3×3 同符号，或 {@code crafting_shapeless} 的 9 项同物品）；</li>
     *   <li>{@code splitRecipe} 非空 = 存在配方「1 个 {@code big} → 9 个 {@code small}」。</li>
     * </ul>
     *
     * <p>26.3 的实测分布：<b>双向 20 条、只能向上合成 8 条、只能向下拆分 1 条</b>。
     * 只能向上合成的 8 条是：竹→竹块、唱片碎片→唱片 5、冰→浮冰、西瓜片→西瓜、
     * 地狱疣→地狱疣块、浮冰→蓝冰、海晶碎片→海晶砖、硫磺→浓缩硫；
     * 只能向下拆分的 1 条是：涂蜡铜块→9 铜锭（<b>反向不成立，绝不能逆用</b>）。</p>
     *
     * <p><b>其中「冰 → 浮冰 → 蓝冰」是一条三级链</b>（81 个冰 = 1 个蓝冰），
     * 递归取料天然支持：{@code need(蓝冰) → 9 × need(浮冰) → 81 × need(冰)}。</p>
     */
    public static final List<Edge> EDGES = List.of(
            // ---- 双向（既能 9 合 1，也能 1 拆 9） ----
            // 9 x bone_meal -> 1 x bone_block
            new Edge("bone_meal", "bone_block", "bone_block", "bone_meal_from_bone_block"),
            // 9 x coal -> 1 x coal_block
            new Edge("coal", "coal_block", "coal_block", "coal"),
            // 9 x copper_ingot -> 1 x copper_block
            new Edge("copper_ingot", "copper_block", "copper_block", "copper_ingot"),
            // 9 x copper_nugget -> 1 x copper_ingot
            new Edge("copper_nugget", "copper_ingot", "copper_ingot_from_nuggets", "copper_nugget"),
            // 9 x diamond -> 1 x diamond_block
            new Edge("diamond", "diamond_block", "diamond_block", "diamond"),
            // 9 x dried_kelp -> 1 x dried_kelp_block
            new Edge("dried_kelp", "dried_kelp_block", "dried_kelp_block", "dried_kelp"),
            // 9 x emerald -> 1 x emerald_block
            new Edge("emerald", "emerald_block", "emerald_block", "emerald"),
            // 9 x gold_ingot -> 1 x gold_block
            new Edge("gold_ingot", "gold_block", "gold_block", "gold_ingot_from_gold_block"),
            // 9 x gold_nugget -> 1 x gold_ingot
            new Edge("gold_nugget", "gold_ingot", "gold_ingot_from_nuggets", "gold_nugget"),
            // 9 x iron_ingot -> 1 x iron_block
            new Edge("iron_ingot", "iron_block", "iron_block", "iron_ingot_from_iron_block"),
            // 9 x iron_nugget -> 1 x iron_ingot
            new Edge("iron_nugget", "iron_ingot", "iron_ingot_from_nuggets", "iron_nugget"),
            // 9 x lapis_lazuli -> 1 x lapis_block
            new Edge("lapis_lazuli", "lapis_block", "lapis_block", "lapis_lazuli"),
            // 9 x netherite_ingot -> 1 x netherite_block
            new Edge("netherite_ingot", "netherite_block", "netherite_block", "netherite_ingot_from_netherite_block"),
            // 9 x raw_copper -> 1 x raw_copper_block
            new Edge("raw_copper", "raw_copper_block", "raw_copper_block", "raw_copper"),
            // 9 x raw_gold -> 1 x raw_gold_block
            new Edge("raw_gold", "raw_gold_block", "raw_gold_block", "raw_gold"),
            // 9 x raw_iron -> 1 x raw_iron_block
            new Edge("raw_iron", "raw_iron_block", "raw_iron_block", "raw_iron"),
            // 9 x redstone -> 1 x redstone_block
            new Edge("redstone", "redstone_block", "redstone_block", "redstone"),
            // 9 x resin_clump -> 1 x resin_block
            new Edge("resin_clump", "resin_block", "resin_block", "resin_clump"),
            // 9 x slime_ball -> 1 x slime_block
            new Edge("slime_ball", "slime_block", "slime_block", "slime_ball"),
            // 9 x wheat -> 1 x hay_block
            new Edge("wheat", "hay_block", "hay_block", "wheat"),

            // ---- 只能向上合成（1 拆 9 在原版里不存在） ----
            // 9 x bamboo -> 1 x bamboo_block
            new Edge("bamboo", "bamboo_block", "bamboo_block", null),
            // 9 x disc_fragment_5 -> 1 x music_disc_5
            new Edge("disc_fragment_5", "music_disc_5", "music_disc_5", null),
            // 9 x ice -> 1 x packed_ice
            new Edge("ice", "packed_ice", "packed_ice", null),
            // 9 x melon_slice -> 1 x melon
            new Edge("melon_slice", "melon", "melon", null),
            // 9 x nether_wart -> 1 x nether_wart_block
            new Edge("nether_wart", "nether_wart_block", "nether_wart_block", null),
            // 9 x packed_ice -> 1 x blue_ice
            new Edge("packed_ice", "blue_ice", "blue_ice", null),
            // 9 x prismarine_shard -> 1 x prismarine_bricks
            new Edge("prismarine_shard", "prismarine_bricks", "prismarine_bricks", null),
            // 9 x sulfur -> 1 x potent_sulfur
            new Edge("sulfur", "potent_sulfur", "potent_sulfur", null),

            // ---- 只能向下拆分（9 合 1 在原版里不存在） ----
            // 9 x copper_ingot -> 1 x waxed_copper_block
            new Edge("copper_ingot", "waxed_copper_block", null, "copper_ingot_from_waxed_copper_block"));

    // ------------------------------------------------------------------ 运行期生效的那一张图

    /**
     * 当前生效的表。
     *
     * <p>初值是 {@link #EDGES} 全表（这样即使没人调 {@link #setActive}，逻辑也是自洽的）；
     * 各版本在服务器起来之后用真实的 {@code RecipeManager} 复核一遍，把旧的 {@link Table}
     * 换上去。换的是<b>整张表</b>而不是往旧表里删边，所以不存在「改到一半」的中间态。</p>
     */
    private static volatile Table active = Table.of(EDGES);

    /** 把复核过的那张表设为生效表（只在启动时调一次） */
    public static void setActive(Table table) {
        if (table == null) {
            throw new IllegalArgumentException("生效表不能置空");
        }
        active = table;
    }

    /** 当前生效的表 */
    public static Table active() {
        return active;
    }

    // ------------------------------------------------------------------ 库存口径

    /**
     * 库存查询口：按<b>物品 id</b>（如 {@code "iron_nugget"}，不带命名空间）查数量。
     *
     * <p>调用方要保证「只数纯净物品」—— 带数据组件的那一格不算，
     * 否则附魔书、药水会被当成普通物品拿去合成，等于丢掉数据。</p>
     */
    @FunctionalInterface
    public interface Stock {
        BigInteger amountOf(String itemId);

        /** 用一份快照当库存：避免每次递归都去查一次真实仓库 */
        static Stock of(Map<String, BigInteger> snapshot) {
            return itemId -> snapshot.getOrDefault(itemId, ZERO);
        }
    }

    // ------------------------------------------------------------------ 计划

    /** 一笔增减：{@code change > 0} = 回到仓库，{@code change < 0} = 从仓库扣走 */
    public record Delta(String itemId, BigInteger change) {
    }

    /**
     * 一次兑换的完整计划。
     *
     * <p><b>先规划、后提交</b>：{@link #plan} 只读库存、不改任何状态，
     * 拿到的 {@link Plan} 是一串「谁加多少、谁减多少」。
     * 数据层据此一次性改账（{@code VoidWarehouseData#applyExchange}），
     * 所以「背包放不下要退回」这种回滚只需要把 {@link #negate()} 再执行一遍，
     * <b>精确到个位数</b>，不需要再走一遍规划。</p>
     */
    public record Plan(String itemId, BigInteger amount, List<Delta> deltas) {
        public Plan {
            deltas = List.copyOf(deltas);
        }

        /** {itemId → 增减量}（写盘 / 执行用） */
        public Map<String, BigInteger> asMap() {
            Map<String, BigInteger> map = new LinkedHashMap<>();
            for (Delta delta : this.deltas) {
                map.merge(delta.itemId(), delta.change(), BigInteger::add);
            }
            return map;
        }

        /** 反向计划：原样执行一遍就等于什么都没发生（回滚用） */
        public Plan negate() {
            List<Delta> reversed = new ArrayList<>(this.deltas.size());
            for (Delta delta : this.deltas) {
                reversed.add(new Delta(delta.itemId(), delta.change().negate()));
            }
            return new Plan(this.itemId, this.amount.negate(), reversed);
        }

        public boolean isEmpty() {
            return this.deltas.isEmpty();
        }
    }

    /**
     * 规划「从仓库拿到 {@code want} 个 {@code itemId}」，不改任何状态。
     *
     * @param want 想要多少个（{@code <= 0} 返回空计划）
     * @return 可行的计划；<b>凑不出来时返回 {@code null}</b>（调用方据此拒绝整次操作）
     */
    public static Plan plan(String itemId, BigInteger want, Stock stock) {
        return plan(active(), itemId, want, stock);
    }

    /** 同 {@link #plan(String, BigInteger, Stock)}，但用指定的表 */
    public static Plan plan(Table table, String itemId, BigInteger want, Stock stock) {
        if (want == null || want.signum() <= 0) {
            return new Plan(itemId, ZERO, List.of());
        }
        if (!table.items().contains(itemId)) {
            return null;
        }
        State state = new State();
        for (String id : table.items()) {
            state.stock.put(id, orZero(stock.amountOf(id)));
        }
        if (!drain(table, itemId, want, state, 0)) {
            return null;
        }
        List<Delta> deltas = new ArrayList<>(state.delta.size());
        for (Map.Entry<String, BigInteger> e : state.delta.entrySet()) {
            if (e.getValue().signum() != 0) {
                deltas.add(new Delta(e.getKey(), e.getValue()));
            }
        }
        return new Plan(itemId, want, deltas);
    }

    public static Plan planMax(String itemId, BigInteger limit, Stock stock) {
        return planMax(active(), itemId, limit, stock);
    }

    /**
     * 规划「最多能拿到多少个」，上限 {@code limit}。
     *
     * <h2>为什么这里<b>不能</b>二分</h2>
     * <p>早先的实现是二分，前提是「能拿 5 个就一定能拿 4 个」。这个前提在本规则里
     * <b>根本不成立</b>：一次取货要么拆整块上级、要么拿下级补零头，可行性是<b>跳档</b>的。
     * 仓库里只有 9 个铜块时，1~9 个都给得出来（9 是拆整块，1~8 是拆一块找零），
     * 而 10~17 一个都给不出，18 又给得出 —— 中间是一串洞。
     * 二分一旦落进洞里就会在洞边收敛，实测对「上限 64」给出 <b>9</b>：
     * 这就是玩家报的「铜锭显示可兑后，右键第一下给 8/9 个、第二下才给一组」。
     *
     * <h2>现在怎么做</h2>
     * <p>先由价值守恒算出「再怎么折腾也超不过去」的上界（{@link #maxObtainable}），
     * 与 {@code limit} 取小，然后<b>从上往下逐个试</b>；第一个规划得出来的就是答案，
     * 所以返回值必定是「真做得到的最大量」，不依赖任何单调性假设。</p>
     *
     * <p>代价可接受，因为这个上界很紧（仓库里有多少料，它就只给出对应那么多），
     * 而「9 的倍数」这一档永远可行，实际往下退的步数是个位数；
     * {@code limit} 本身也只是「堆叠上限 64」或「一盒容量」这个量级。</p>
     *
     * @return 可行的计划（数量可能小于 {@code limit}）；一个都拿不到时返回 {@code null}
     */
    public static Plan planMax(Table table, String itemId, BigInteger limit, Stock stock) {
        if (limit == null || limit.signum() <= 0 || !table.items().contains(itemId)) {
            return null;
        }
        BigInteger upper = limit.min(maxObtainable(table, itemId, stock));
        for (BigInteger probe = upper; probe.signum() > 0; probe = probe.subtract(ONE)) {
            Plan plan = plan(table, itemId, probe, stock);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 可兑数量的上界

    /**
     * 「现在最多能兑出多少个 {@code itemId}」的<b>安全上界</b>，只由<b>本族现有库存</b>推出来。
     *
     * <h2>为什么需要它</h2>
     * <p>「可兑数量」这个数<b>不能拍一个固定值</b>：早先写过「一组（堆叠上限）」，
     * 后来又写成「一盒容量 1728」，两次都被玩家指出不合理 ——
     * 仓库里只有 81 个铁锭时，铁块那格显示 1728 是<b>凭空的一个数</b>，跟仓库里有什么毫无关系。</p>
     * <p>正确做法是由库存自身推上界：<b>每一步兑换都守恒价值</b>（9 个下级 = 1 个上级），
     * 所以价值只会减少、不会增加，拿「本族库存的总价值」除以「一个目标物品的价值」，
     * 就是再怎么折腾也超不过去的数量。</p>
     *
     * <h2>为什么是「上界」而不是精确值</h2>
     * <p>价值守恒只说明上界<b>不会被突破</b>，不说明<b>够得着</b>。两种情况会让上界偏大：</p>
     * <ul>
     *   <li><b>被单向边卡住</b>：9 个浮冰在价值上等于 81 个冰，但浮冰拆不回冰，
     *       所以「只有 9 个浮冰」时取冰的上界是 81、真值是 0；</li>
     *   <li><b>剩下不够再换的零头</b>：整除法本来就是向下取整，零头算不进任何一整件。</li>
     * </ul>
     * <p>所以这个数有两个用途：给界面显示「这一格现在能兑多少个」，以及给
     * {@link #planMax} 当<b>往下试探的起点</b> —— 真正的答案由 {@code planMax} 收敛到
     * 「确实执行得出来」的最大值。上界偏大是安全的（最多多跑几次注定失败的规划），
     * 偏小才是 bug —— 而价值守恒保证了它不会偏小。</p>
     *
     * <h2>拿来当限制还有个副作用是好事</h2>
     * <p>本族一点料都没有时上界是 0，调用方可以<b>直接跳过</b>，不用跑一次注定失败的规划 ——
     * 界面每次刷可兑条目要问 53 个物品，这一下省掉的是绝大多数。</p>
     *
     * @return 上界（本族无料时为 {@code 0}）；{@code itemId} 不在表里也返回 {@code 0}
     */
    public static BigInteger maxObtainable(Table table, String itemId, Stock stock) {
        if (table == null || itemId == null || !table.contains(itemId)) {
            return ZERO;
        }
        Set<String> family = table.familyOf(itemId);
        if (family.isEmpty()) {
            return ZERO;
        }
        // 族内最低层级当基准，把所有库存折算成「最小单位的价值」
        int floor = Integer.MAX_VALUE;
        for (String id : family) {
            floor = Math.min(floor, table.levelOf(id));
        }
        BigInteger value = ZERO;
        for (String id : family) {
            BigInteger have = orZero(stock.amountOf(id));
            if (have.signum() > 0) {
                value = value.add(have.multiply(NINE.pow(table.levelOf(id) - floor)));
            }
        }
        return value.divide(NINE.pow(table.levelOf(itemId) - floor));
    }

    /**
     * 「<b>不拆上级</b>的话最多能补出多少个 {@code item}」的<b>安全上界</b>。
     *
     * <p>用途只有一个：给 {@link #drain} 第 ③ 条路的「往下少拆一块」循环一个停下来的理由 ——
     * 少拆一块，零头就多 9 个；零头一旦超过这个上界就再也补不上，剩下的块数不必再试，
     * 否则「9 个铜块取 64」这种明摆着做不通的请求会白试 7 次。</p>
     *
     * <p>口径：只数<b>层级不高于 {@code item}</b> 的族内库存。这是有依据的，不是随手切的阈值：
     * 拆零只会沿「层级 + 1」的方向发生，所以「不拆上级」这一路上能碰到的东西，
     * 要么在目标及其以下，要么是<b>同层</b>（例如目标是铜块时，涂蜡铜块能拆出铜锭、
     * 而它自己的层级和铜块一样），再往上一层就必须拆上级了 —— 那正是这条路不许做的事。</p>
     *
     * <p>⚠️ <b>改 {@link #EDGES} 时要重核这一条</b>：过滤条件成立的前提是
     * 「不存在『目标同层且能拆出下级、自身又能被再上层拆出来』的物品」。
     * 本表实测满足（涂蜡铜块是最接近的一个，但没有任何物品能拆出它）；
     * 真加了这样的边，这个上界就会偏小、也就可能把一个可行的零头补法掐掉 ——
     * 那种情况必须把这个过滤放宽（哪怕牺牲一点循环次数）。</p>
     *
     * <p>和 {@link #maxObtainable} 一样是<b>上界而不是精确值</b>（不同分支的料未必凑得到一起），
     * 但方向相反：这里<b>偏大安全</b> —— 最多多试几次注定失败的规划，绝不把一条可行的路掐掉。</p>
     */
    private static BigInteger coverBound(Table table, String itemId, State state) {
        Set<String> family = table.familyOf(itemId);
        if (family.isEmpty()) {
            return ZERO;
        }
        int floor = Integer.MAX_VALUE;
        for (String id : family) {
            floor = Math.min(floor, table.levelOf(id));
        }
        int level = table.levelOf(itemId);
        BigInteger value = ZERO;
        for (String id : family) {
            if (table.levelOf(id) > level) {
                continue;
            }
            BigInteger have = orZero(state.stock.get(id));
            if (have.signum() > 0) {
                value = value.add(have.multiply(NINE.pow(table.levelOf(id) - floor)));
            }
        }
        return value.divide(NINE.pow(level - floor));
    }

    // ------------------------------------------------------------------ 递归取数

    /**
     * 让仓库的账目<b>正好少掉 {@code need} 个 {@code item}</b>。
     *
     * <p>三条路按「目标本身 → 下级合成 → 上级拆零」的优先级依次试；
     * ②③ 都在状态副本上试，失败整支丢弃（{@link State#copy()}）。</p>
     *
     * <p><b>第 ③ 条路只拆「用得完的整块」</b>：拆的上级个数从 {@code floor(need / 9)}
     * <b>逐块往下试</b>（上级不够就少拆一块、把更多零头留给下级），差的零头交给
     * {@link #drainWithoutSplitting} 用下级补。这样做是为了让
     * <b>一次取货不改变仓库的构成形态</b> —— 细节与踩坑见该分支里的注释。
     * 唯一的例外是 {@code need < 9}（左键「取 1 个」），那时退回「拆 1 块 + 找零」。</p>
     *
     * @return 是否成功；失败时 {@code state} <b>一个字节都没动</b>
     */
    private static boolean drain(Table table, String item, BigInteger need, State state, int depth) {
        if (need.signum() <= 0) {
            return true;
        }
        if (depth > MAX_DEPTH || state.visiting.contains(item)) {
            // 深到离谱 / 撞上环（铁锭 → 铁粒 → 只能拆铁锭）：这条路不成立
            return false;
        }
        state.visiting.add(item);
        try {
            // ① 直接扣库存 —— 成品有货时永远走这条，绝不触发任何兑换
            BigInteger have = orZero(state.stock.get(item));
            if (have.signum() > 0) {
                BigInteger taken = have.min(need);
                state.stock.put(item, have.subtract(taken));
                bump(state.delta, item, taken.negate());
                need = need.subtract(taken);
                if (need.signum() <= 0) {
                    return true;
                }
            }

            // ② 用下级合成：9 个 Y 换 1 个 item（合成出来的直接给玩家，不进仓库账）
            for (String lower : table.makersOf(item)) {
                State branch = state.copy();
                if (drain(table, lower, need.multiply(NINE), branch, depth + 1)) {
                    state.adopt(branch);
                    return true;
                }
            }

            // ③ 拆上级：<b>只按「整块」拆</b>，而且<b>从能拆的最多块数往下逐块试</b>。
            //
            // 这里曾经用 ceilDiv：要取 64 个铜锭、仓库里只有铜块（一块 = 9 个）时，
            // 它会拆 ceil(64/9) = 8 块 → 得 72 个 → 给 64 → <b>8 个零头退回仓库</b>。
            // 玩家看到的后果：一次「取出」把仓库里的铜块拆掉一整批，仓库里还凭空多出 8 个铜锭，
            // 下一次取货量就变成 8 —— 取出动作不该改变仓库的<b>构成形态</b>。
            //
            // 现在的规则：块数从 floor(need / 9) 起、<b>逐块往下退</b>，只拆用得完的整块；
            // 剩下不足 9 的那一点点交给「目标库存 + 下级合成」去补
            // （仓库 9 个铜块 → 右键「取一组」给 63 = 拆 7 块，仓库剩 2 块，零头为 0）。
            // 补不上就让这一支失败，由 {@link #planMax} 从真上界往下收敛。
            //
            // 为什么块数要<b>往下枚举</b>，而不是只试 floor(need / 9) 这一个：
            // 上级不一定够。仓库里 1 个铜块 + 100 个铜粒，要 20 个铜锭 ——
            // floor(20/9) = 2 块（只有 1 块，这一支判失败），可正确答案是
            // 「拆 1 块（9 个）+ 铜粒合 11 个」，零头仍然为 0。
            // 少拆一块、把更多零头交给下级，是一条完全合法的路，必须被枚举到。
            //
            // 唯一例外是 need < 9（左键「取 1 个」）：floor 是 0，一块都取不出来，
            // 那时退回「拆 1 块 + 找零」—— 这正是玩家要的「拆上级找零」，必须保留。
            BigInteger maxUnits = need.divide(NINE);
            BigInteger cover = coverBound(table, item, state);
            for (String higher : table.splittersOf(item)) {
                for (BigInteger units = maxUnits.signum() <= 0 ? ONE : maxUnits;
                        units.signum() > 0;
                        units = units.subtract(ONE)) {
                    BigInteger rest = need.subtract(units.multiply(NINE));
                    if (rest.signum() > 0 && rest.compareTo(cover) > 0) {
                        // 少拆一块，零头就多 9 个，只会更补不上 —— 这条支路整体作废
                        break;
                    }
                    State branch = state.copy();
                    if (!drain(table, higher, units, branch, depth + 1)) {
                        continue;
                    }
                    if (rest.signum() < 0) {
                        // need < 9：拆多了，把用不完的作为「找零」退回仓库（玩家明确要的能力）
                        branch.stock.merge(item, rest.negate(), BigInteger::add);
                        bump(branch.delta, item, rest.negate());
                        state.adopt(branch);
                        return true;
                    }
                    // 差的这一点点只许用下级补，绝不再拆一个上级（那就是「留零头」的老毛病）
                    if (rest.signum() == 0 || drainWithoutSplitting(table, item, rest, branch, depth)) {
                        state.adopt(branch);
                        return true;
                    }
                }
            }

            return false;
        } finally {
            state.visiting.remove(item);
        }
    }

    /**
     * 只走「目标库存 + 下级合成」两条路，<b>绝不拆上级</b>。
     *
     * <p>用途单一：{@link #drain} 的第 ③ 条路按「整块」拆完之后，还差不足 9 的一点点
     * （例如 <b>7 个铜块 + 90 个铜粒</b> 要取 64 个：7 块 = 63，还差 1 个）。
     * 这 1 个必须用铜粒补 —— <b>不能靠再拆一块铜块</b>，那正是「为了凑满一组而留下 8 个零头」
     * 的老毛病（见 {@link #drain} 第 ③ 条路的注释）。补不上就老实返回 {@code false}，
     * 让 {@link #planMax} 从真上界往下收敛出一个做得到的量。</p>
     *
     * <p>{@code item} 此刻已经在 {@link State#visiting} 里（调用点就在 {@code drain(item)} 内部），
     * 所以下级递归里若想回头再拆到 {@code item} 会被自动挡住 —— 正是我们要的「不许再拆上级」。</p>
     */
    private static boolean drainWithoutSplitting(Table table, String item, BigInteger need,
                                                 State state, int depth) {
        if (need.signum() <= 0) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        // ① 目标库存
        BigInteger have = orZero(state.stock.get(item));
        if (have.signum() > 0) {
            BigInteger taken = have.min(need);
            state.stock.put(item, have.subtract(taken));
            bump(state.delta, item, taken.negate());
            need = need.subtract(taken);
            if (need.signum() <= 0) {
                return true;
            }
        }
        // ② 下级合成
        for (String lower : table.makersOf(item)) {
            State branch = state.copy();
            if (drain(table, lower, need.multiply(NINE), branch, depth + 1)) {
                state.adopt(branch);
                return true;
            }
        }
        return false;
    }

    /** 递归的工作状态：库存快照 + 累计增减 + 当前递归栈（破环用） */
    private static final class State {
        final Map<String, BigInteger> stock = new HashMap<>();
        final Map<String, BigInteger> delta = new LinkedHashMap<>();
        final Set<String> visiting = new HashSet<>();

        State copy() {
            State copy = new State();
            copy.stock.putAll(this.stock);
            copy.delta.putAll(this.delta);
            copy.visiting.addAll(this.visiting);
            return copy;
        }

        /** 把分支的结果接回主线（{@code visiting} 分支结束时已自行还原，不必也不该覆盖） */
        void adopt(State branch) {
            this.stock.clear();
            this.stock.putAll(branch.stock);
            this.delta.clear();
            this.delta.putAll(branch.delta);
        }
    }

    private static void bump(Map<String, BigInteger> delta, String itemId, BigInteger change) {
        delta.merge(itemId, change, BigInteger::add);
    }

    private static BigInteger orZero(BigInteger value) {
        return value == null ? ZERO : value;
    }

    // ------------------------------------------------------------------ 图

    /**
     * 复核过的一张「9 合 1」图。
     *
     * <p>除了原始的边，还会算出<b>层级</b>（{@code level}）：把一条边看成「两端层级差 1」，
     * 于是每个物品的价值就是 {@code 9^level}（同一张连通图内比才有意义）。
     * 层级是<b>校验过的</b>：如果绕着某个环回到同一件物品却算出不同的层级，
     * 说明表里有矛盾（比如某条边的方向和其它边打架），那条边会被丢掉并在
     * {@link #dropped()} 里留痕 —— 自检会把「丢了几条」断言成 0。</p>
     */
    public static final class Table {
        private final List<Edge> edges;
        private final Set<String> items;
        private final Map<String, List<String>> makers;
        private final Map<String, List<String>> splitters;
        private final Map<String, Integer> levels;
        private final Map<String, Set<String>> families;
        private final List<Edge> dropped;

        private Table(List<Edge> edges, Set<String> items, Map<String, List<String>> makers,
                      Map<String, List<String>> splitters, Map<String, Integer> levels,
                      Map<String, Set<String>> families, List<Edge> dropped) {
            this.edges = List.copyOf(edges);
            this.items = Set.copyOf(items);
            this.makers = makers;
            this.splitters = splitters;
            this.levels = levels;
            this.families = families;
            this.dropped = List.copyOf(dropped);
        }

        /** 无向传播用的一条邻接：从 {@code node} 走到 {@code peer}，层级要加 {@code delta}（+1 / -1） */
        private record Link(String peer, int delta) {
        }

        /**
         * 从一组边建表。
         *
         * <p>层级用 BFS 传播：把每条边看成「两端层级差 1」，
         * 从每个连通块里挑一个节点当 0，沿边推下去。若某条边推出来的层级和已有值打架，
         * 说明表里自相矛盾，这条边<b>直接丢掉</b>（不参与建图），并在 {@link #dropped()} 留痕。</p>
         *
         * <p>正常情况（本表已实测过）丢掉 0 条 —— 因为所有边的比例都恰好是 9，
         * 任何环走一圈差值的和必然是 0。丢边只是「万一以后有人手改表改错了」的兜底：
         * <b>宁可少一条兑换路径，也绝不允许一条自相矛盾的边被执行</b>。</p>
         */
        public static Table of(Iterable<Edge> source) {
            Map<String, List<Link>> adjacency = new HashMap<>();
            for (Edge edge : source) {
                adjacency.computeIfAbsent(edge.small(), key -> new ArrayList<>(2))
                        .add(new Link(edge.big(), 1));
                adjacency.computeIfAbsent(edge.big(), key -> new ArrayList<>(2))
                        .add(new Link(edge.small(), -1));
            }

            Map<String, Integer> levels = new HashMap<>();
            Map<String, Set<String>> families = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            // 根节点的选取顺序用字典序固定下来，保证同一张表每次建出来的层级完全一致
            for (String root : new TreeSet<>(adjacency.keySet())) {
                if (levels.containsKey(root)) {
                    continue;
                }
                levels.put(root, 0);
                // 顺手把这一族（连通分量）的成员收集起来 —— 层级是「族内相对值」，
                // 而「现在最多能兑出多少个」这类问题必须先知道<b>哪些库存跟目标是一族的</b>
                // （别的族的料一件都用不上），见 maxObtainable。
                Set<String> members = new TreeSet<>();
                members.add(root);
                queue.add(root);
                while (!queue.isEmpty()) {
                    String node = queue.poll();
                    int base = levels.get(node);
                    for (Link link : adjacency.get(node)) {
                        members.add(link.peer());
                        if (!levels.containsKey(link.peer())) {
                            levels.put(link.peer(), base + link.delta());
                            queue.add(link.peer());
                        }
                    }
                }
                Set<String> frozen = Set.copyOf(members);
                for (String member : members) {
                    families.put(member, frozen);
                }
            }

            List<Edge> kept = new ArrayList<>();
            List<Edge> dropped = new ArrayList<>();
            for (Edge edge : source) {
                int expected = levels.getOrDefault(edge.small(), 0) + 1;
                if (levels.getOrDefault(edge.big(), expected) == expected) {
                    kept.add(edge);
                } else {
                    dropped.add(edge);
                }
            }

            Map<String, List<String>> makers = new HashMap<>();
            Map<String, List<String>> splitters = new HashMap<>();
            Set<String> items = new LinkedHashSet<>();
            for (Edge edge : kept) {
                items.add(edge.small());
                items.add(edge.big());
                if (edge.canCombine()) {
                    makers.computeIfAbsent(edge.big(), key -> new ArrayList<>(2)).add(edge.small());
                }
                if (edge.canSplit()) {
                    splitters.computeIfAbsent(edge.small(), key -> new ArrayList<>(2)).add(edge.big());
                }
            }
            // 上级原料按「层级从高到低」（离目标最近的最先用）；拆零来源按「层级从低到高」
            // （拆最小的那个够用就行）。都补一层字典序当第二关键字，保证结果可复现。
            for (List<String> list : makers.values()) {
                list.sort((a, b) -> {
                    int byLevel = Integer.compare(levels.getOrDefault(b, 0), levels.getOrDefault(a, 0));
                    return byLevel != 0 ? byLevel : a.compareTo(b);
                });
            }
            for (List<String> list : splitters.values()) {
                list.sort((a, b) -> {
                    int byLevel = Integer.compare(levels.getOrDefault(a, 0), levels.getOrDefault(b, 0));
                    return byLevel != 0 ? byLevel : a.compareTo(b);
                });
            }
            return new Table(kept, items, makers, splitters, levels, families, dropped);
        }

        /** 表里的全部物品 id（已排序，方便快照与展示） */
        public Set<String> items() {
            return new TreeSet<>(this.items);
        }

        /** 生效的边 */
        public List<Edge> edges() {
            return this.edges;
        }

        /** 因自相矛盾被丢掉的边（正常永远是空） */
        public List<Edge> dropped() {
            return this.dropped;
        }

        /** 层级（同一张连通图内才有比较意义） */
        public int levelOf(String itemId) {
            return this.levels.getOrDefault(itemId, 0);
        }

        /**
         * 与 {@code itemId} <b>同族</b>（同一个连通分量）的全部物品 id；不在表里返回空集。
         *
         * <p>「族」是价值换算的作用域：只有族内的库存才可能被兑换用到，
         * 别的族的料一件都用不上（族与族之间没有边）。见 {@link #maxObtainable}。</p>
         */
        public Set<String> familyOf(String itemId) {
            return this.families.getOrDefault(itemId, Set.of());
        }

        /** 能「9 个合一」成 {@code itemId} 的下级原料（按「离目标最近」优先） */
        public List<String> makersOf(String itemId) {
            return this.makers.getOrDefault(itemId, List.of());
        }

        /** 拆开能给出 9 个 {@code itemId} 的上级物品（按「层级最低」优先） */
        public List<String> splittersOf(String itemId) {
            return this.splitters.getOrDefault(itemId, List.of());
        }

        /** 这个物品在不在表里（不在表里的物品永远不能兑换） */
        public boolean contains(String itemId) {
            return this.items.contains(itemId);
        }

        /** 表里的物品总数（不重复） */
        public int size() {
            return this.items.size();
        }
    }
}
