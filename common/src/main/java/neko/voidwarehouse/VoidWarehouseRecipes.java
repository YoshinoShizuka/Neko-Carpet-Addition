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
 * 唯一的循环上限是 {@link #MAX_NODES}（展开预算）与 {@link #MAX_DEPTH}（往下钻的层数），
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
 * <h2>算法的形状：算上界 → 摆一遍 → 摆不下就二分</h2>
 * <p>「能做几次」靠两步得出：先用<b>宽松上界</b>估一个次数（{@link #boundCap} / {@link Bound}，
 * 宁可估大），再用 {@link #simulate} <b>真的把整套扣料摆一遍</b>（含往下钻的每一层、每一项选哪个候选）。
 * 上界本身摆得下就直接是答案（只跑一次模拟，实测 79% 的配方走这条）；摆不下就在
 * {@code [0, 上界 - 1]} 上<b>二分</b>最大的可行次数 —— 「做得越多越难」是单调的。
 * 摆得下的那一次，账目就是最终账目 ——
 * 于是「界面上说能做 N 个」与「真点下去能拿到 N 个」是<b>同一次模拟</b>的结果，
 * 不存在「显示得比真值大」。这是 9 合 1 在 1.0.7 栽过的形态，这里从结构上避开。</p>
 *
 * <h2>1.0.17：把「每次探针重新展开一遍树」换成「先编译树，再在树上灌数量」</h2>
 * <p>R2 把单次探针的成本从 ~35 µs 压到 ~4 µs（消灭了「每个分支复制一份账本」），
 * 但每探针仍在付四笔<b>与算法无关</b>的开销：把物品 id 当哈希键查 {@code stock} / {@code pool} /
 * {@code path} / {@code byOutput}（一次满本重建是十万级的字符串哈希）、每探针新建一个
 * {@code LinkedHashSet} 与一份 {@code Model}、每个上界节点 {@code new HashMap} 算合并需求、
 * 以及每条配方各构造一次闭包 {@code HashMap}。</p>
 *
 * <p>本版把这四笔一次性拿掉，做法与 AE2 的合成树同构：</p>
 * <ol>
 *   <li>{@link Tree} 在<b>配方本换版时编译一次</b>：每个物品一个槽位（下标），每个产物挂好它的
 *       全部候选配方，每条配方挂好它的原料槽位数组，并把「单候选原料合并后的需求」
 *       预先算在节点上（{@link RecipeNode#forcedSlots}）。之后所有查找都是<b>数组下标</b>，
 *       没有任何字符串哈希。</li>
 *   <li>库存来一份 {@code BigInteger[]}（下标对齐上面的槽位），由调用方一次填满、多个产物共享
 *       —— 而不是每条配方各构造一个 40 项的 {@code HashMap}。</li>
 *   <li>{@link Model} 改造成<b>可复位</b>的模拟状态（世代戳 {@code stamp} + 撤销日志），
 *       一次 {@code maxCraftable} 的十几次探针复用同一份数组，不再每次分配。</li>
 *   <li>环检测用 {@code boolean[] onPath}、上界缓存用 {@code BigInteger[]} + {@code boolean[]}，
 *       都是下标直达。</li>
 * </ol>
 *
 * <p><b>功能一个字都没变</b>：同一份配方本、同一份库存，{@code maxCraftable} 的返回值与
 * {@code plan} 的 {@code consumed} / {@code surplus} 与改造前<b>逐字节相同</b>
 * （对拍口径见 {@code docs/配方本满本并发测试报告.md} §12.3：每条配方取 {@code maxCraftable}
 * 与 5 个目标量的完整账单，7,416 行输出共一个 SHA-256）。旧的方法签名全部保留，内部转调新路径，
 * 供自检与离线工具继续使用。</p>
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

    /** {@link Tree#stockArray(RecipeNode, Function)} 最多收集多少种物品（防止病态配方把闭包撑爆） */
    private static final int MAX_CLOSURE = 256;

    /** 没有配方的物品（叶子原料）复用的空数组，省掉每个叶子的一个小对象 */
    private static final RecipeNode[] NO_RECIPES = new RecipeNode[0];
    private static final PartNode[] NO_PARTS = new PartNode[0];

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
     * @param output      产物物品 id，<b>全命名空间</b>（{@code "minecraft:iron_ingot"}）；
     *                    来源是 {@code VoidWarehouseRecipeBook#idOf}（即 {@code Identifier#toString}）。
     *                    注意与 9 合 1 表的 id 口径<b>不同</b>（那张表只留 path），两者唯一相遇的
     *                    地方是可兑表的去重，那里会显式翻译
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

    // ================================================================== 预编译的配方树

    /**
     * 一棵<b>按配方本编译出来的、不可变的</b>配方树。
     *
     * <h2>它解决什么</h2>
     * <p>没有它的时候，一次「能做几个」的每次探针都要：{@code byOutput.get(item)}（字符串哈希）
     * 找候选配方、遍历 {@code List<Part>} 与 {@code List<String>} 找候选原料、
     * {@code path.contains(item)}（字符串哈希）判环、{@code Map<String, BigInteger>}（字符串哈希）
     * 查库存与覆盖值、每个节点 {@code new HashMap} 算「单候选合并需求」。
     * 满本重建实测要走 51,645 个节点、其中 41,156 次往下钻 —— 也就是十万级的字符串哈希。</p>
     *
     * <p>编译之后这些全变成<b>数组下标</b>：一个物品在树里只有一个 {@code slot}，
     * 库存、覆盖值、环标记、上界缓存都按这个下标存。字符串只在两个边界出现：
     * 编译时（把 id 换成 slot）与算完后（把 slot 换回 id 填 {@code Plan}）。</p>
     *
     * <h2>为什么可以预编译</h2>
     * <p>树的<b>结构</b>只由配方本决定，与库存数量无关 —— 库存变了结构不会变。
     * 所以它跟着 {@code recipesByOutput} 一起在配方本换版时重建一次即可
     * （调用方 {@code VoidWarehouseData#rebuildRecipes}）。</p>
     *
     * <h2>成环怎么办</h2>
     * <p>先给「所有出现过的 id」分配槽位、把每个节点的候选配方数组算好，再建节点 ——
     * 于是 A → B → A 这种环在编译期自然闭合，不需要特殊处理。
     * 运行期的环由 {@link Model#onPath} 在模拟时截断（与旧实现的 {@code path} 同义）。</p>
     */
    public static final class Tree {

        /** 槽位 → 物品 id（编译期定序，同一份配方本永远相同） */
        private final String[] ids;
        /** 槽位 → 节点（叶子节点的 {@code recipes} 是空数组） */
        private final Node[] nodes;
        /** 物品 id → 槽位（只有编译期与外部按 id 取节点时用，热路径不走它） */
        private final Map<String, Integer> slots;
        /** 配方标识 → 配方节点（旧 API 收到一个 {@code Recipe} 时要靠它找回节点） */
        private final Map<String, RecipeNode> byRecipeId;

        private Tree(String[] ids, Node[] nodes, Map<String, Integer> slots,
                     Map<String, RecipeNode> byRecipeId) {
            this.ids = ids;
            this.nodes = nodes;
            this.slots = slots;
            this.byRecipeId = byRecipeId;
        }

        /** 空树：配方本为空时的唯一实例（调用方不必判 null） */
        private static final Tree EMPTY = new Tree(new String[0], new Node[0], Map.of(), Map.of());

        /**
         * 把 {@code byOutput} 编译成树。
         *
         * <p>槽位顺序：先按 {@code byOutput} 的键序（调用方给的是 {@code LinkedHashMap}，
         * 与配方本的学会顺序一致），再按「配方 → 原料 → 候选」的出现顺序追加。
         * 顺序本身不影响任何数值（所有查找都是按键取值），定序只是为了可复现。</p>
         *
         * <p>代价：满本 1,236 条配方编译一次约 1 万个操作、几百微秒，只在配方本换版时发生。</p>
         */
        public static Tree compile(Map<String, List<Recipe>> byOutput) {
            if (byOutput == null || byOutput.isEmpty()) {
                return EMPTY;
            }
            Map<String, Integer> slots = new LinkedHashMap<>();
            for (String output : byOutput.keySet()) {
                slots.put(output, slots.size());
            }
            for (List<Recipe> group : byOutput.values()) {
                for (Recipe recipe : group) {
                    for (Part part : recipe.parts()) {
                        for (String candidate : part.candidates()) {
                            if (!slots.containsKey(candidate)) {
                                slots.put(candidate, slots.size());
                            }
                        }
                    }
                }
            }
            String[] ids = slots.keySet().toArray(new String[0]);

            // 两阶段：先算出每个槽位的候选配方数组，再建节点 —— 这样环（A 的配方里有 B、
            // B 的配方里又有 A）在数组层面天然成立，不需要「边编译边递归」的一百种防环写法。
            RecipeNode[][] recipes = new RecipeNode[ids.length][];
            Map<String, RecipeNode> byRecipeId = new HashMap<>();
            for (Map.Entry<String, List<Recipe>> entry : byOutput.entrySet()) {
                Integer slot = slots.get(entry.getKey());
                if (slot == null) {
                    continue;
                }
                List<Recipe> group = entry.getValue();
                RecipeNode[] compiled = new RecipeNode[group.size()];
                for (int i = 0; i < compiled.length; i++) {
                    Recipe recipe = group.get(i);
                    RecipeNode node = compileRecipe(recipe, slot.intValue(), slots);
                    compiled[i] = node;
                    byRecipeId.put(recipe.recipeId(), node);
                }
                recipes[slot.intValue()] = compiled;
            }
            Node[] nodes = new Node[ids.length];
            for (int i = 0; i < ids.length; i++) {
                nodes[i] = new Node(i, ids[i], recipes[i] == null ? NO_RECIPES : recipes[i]);
            }
            return new Tree(ids, nodes, slots, byRecipeId);
        }

        private static RecipeNode compileRecipe(Recipe recipe, int outputSlot,
                                                Map<String, Integer> slots) {
            List<Part> parts = recipe.parts();
            PartNode[] compiled = new PartNode[parts.size()];
            // 单候选原料按物品合并需求：两项都只认 A、各要 1 个时，共享的那份库存
            // 不能被数两遍（1.0.15 的错法）。合并在编译期做完，运行期不再建 map。
            Map<Integer, Integer> forced = new LinkedHashMap<>();
            List<PartNode> multi = new ArrayList<>();
            for (int i = 0; i < compiled.length; i++) {
                Part part = parts.get(i);
                List<String> candidates = part.candidates();
                int[] cands = new int[candidates.size()];
                for (int j = 0; j < cands.length; j++) {
                    Integer slot = slots.get(candidates.get(j));
                    cands[j] = slot == null ? -1 : slot.intValue();
                }
                PartNode node = new PartNode(part.count(), cands);
                compiled[i] = node;
                if (cands.length == 1) {
                    forced.merge(Integer.valueOf(cands[0]), Integer.valueOf(part.count()), Integer::sum);
                } else {
                    multi.add(node);
                }
            }
            int[] forcedSlots = new int[forced.size()];
            int[] forcedCounts = new int[forced.size()];
            int k = 0;
            for (Map.Entry<Integer, Integer> entry : forced.entrySet()) {
                forcedSlots[k] = entry.getKey().intValue();
                forcedCounts[k] = entry.getValue().intValue();
                k++;
            }
            return new RecipeNode(recipe, outputSlot, recipe.outputCount(), compiled,
                    forcedSlots, forcedCounts, multi.toArray(NO_PARTS));
        }

        /** 树里有几个物品（= 数组口径的长度） */
        public int size() {
            return this.ids.length;
        }

        /** 某个物品的槽位；不在树里返回 {@code -1} */
        public int slotOf(String itemId) {
            Integer slot = this.slots.get(itemId);
            return slot == null ? -1 : slot.intValue();
        }

        /** 槽位 → 节点；越界返回 {@code null} */
        public Node node(int slot) {
            return slot >= 0 && slot < this.nodes.length ? this.nodes[slot] : null;
        }

        /** 物品 id → 节点；不在树里返回 {@code null} */
        public Node node(String itemId) {
            return this.node(this.slotOf(itemId));
        }

        /** 槽位 → 物品 id；越界返回 {@code null} */
        public String idOf(int slot) {
            return slot >= 0 && slot < this.ids.length ? this.ids[slot] : null;
        }

        /** 一条配方对应的节点（旧 API 用）；找不到返回 {@code null} */
        public RecipeNode recipeNodeOf(Recipe recipe) {
            return recipe == null ? null : this.byRecipeId.get(recipe.recipeId());
        }

        /**
         * 把一张「物品 id → 数量」的表铺成按槽位对齐的数组（缺键 = 0）。
         *
         * <p>只给<b>旧签名 API</b> 用（{@link #plan(Recipe, BigInteger, Map, Map)} 与
         * {@link #maxCraftable(Recipe, Map, Map)} 收到的是 {@code Map}）。生产路径不要用它 ——
         * 那等于把整棵树 1,223 个槽位都读一遍，真正的热路径应当传
         * {@link #stockArray(RecipeNode, Function)} 取到的闭包数组。</p>
         */
        public BigInteger[] arrayOf(Map<String, BigInteger> stock) {
            BigInteger[] out = new BigInteger[this.ids.length];
            if (stock == null) {
                return out;
            }
            for (int i = 0; i < this.ids.length; i++) {
                BigInteger got = stock.get(this.ids[i]);
                out[i] = got == null ? ZERO : got;
            }
            return out;
        }

        /**
         * 取「<b>这一条配方</b>」的闭包库存（其余槽位留 {@code null}，等价于 0）。
         *
         * <p>口径与旧 {@link #stockClosure(Recipe, Map, Function)} 逐条对齐：同样的遍历起点
         * （这条配方的原料，depth=0）、同样的 {@link #MAX_DEPTH} 层数、同样的路径去环、
         * 同样的 {@link #MAX_CLOSURE} 截断顺序 —— 闭包外的物品必须仍然读成 0，
         * 否则「模拟本来就该看到 0」的地方会突然看见真值。</p>
         *
         * <p>🔴 <b>不要「顺手」改成从产物节点出发</b>（一次取该产物所有配方的并集）。
         * 同名产物经常有多条配方（{@code blue_cushion} 就有两条），并集会先把某条分支的
         * 候选标成「已访问」，等另一条分支走到同一个中间产物时就不再往下钻 ——
         * 深度预算被先扫到的分支吃掉，闭包因此比逐条取更浅。实测后果是
         * {@code *_cushion} 这类四层链「仓库里明明有原料却算成做不出来」。
         * 判据：离线审计的 D 组（闭包 vs 全树）差异集合必须与 R3 <b>完全相同</b>。</p>
         *
         * <p>返回值里 {@code null} 表示「这个槽位不在闭包里」（等价于 0），
         * 模拟器读它就是 0 —— 与旧实现「map 里没有这个键」一致。</p>
         */
        public BigInteger[] stockArray(RecipeNode root, Function<String, BigInteger> amount) {
            BigInteger[] out = new BigInteger[this.ids.length];
            if (root == null) {
                return out;
            }
            boolean[] seen = new boolean[this.ids.length];
            boolean[] onPath = new boolean[this.ids.length];
            this.collectStock(root, amount, out, seen, onPath, 0, new int[]{MAX_CLOSURE});
            return out;
        }

        private void collectStock(RecipeNode recipe, Function<String, BigInteger> amount, BigInteger[] out,
                                  boolean[] seen, boolean[] onPath, int depth, int[] budget) {
            for (PartNode part : recipe.parts) {
                for (int candidate : part.candidates) {
                    if (budget[0] <= 0) {
                        return;
                    }
                    if (candidate < 0 || seen[candidate]) {
                        continue;
                    }
                    BigInteger got = amount.apply(this.ids[candidate]);
                    out[candidate] = got == null ? ZERO : got;
                    seen[candidate] = true;
                    budget[0]--;
                    if (depth >= MAX_DEPTH || onPath[candidate]) {
                        continue;
                    }
                    Node sub = this.nodes[candidate];
                    if (sub.recipes.length == 0) {
                        continue;
                    }
                    onPath[candidate] = true;
                    try {
                        for (RecipeNode subRecipe : sub.recipes) {
                            this.collectStock(subRecipe, amount, out, seen, onPath, depth + 1, budget);
                        }
                    } finally {
                        onPath[candidate] = false;
                    }
                }
            }
        }
    }

    /** 树上的一个物品：槽位 + id + 它的全部候选配方（空数组 = 叶子） */
    public static final class Node {
        public final int slot;
        public final String id;
        public final RecipeNode[] recipes;

        Node(int slot, String id, RecipeNode[] recipes) {
            this.slot = slot;
            this.id = id;
            this.recipes = recipes;
        }

        /** 这个物品没有任何已学配方（只能从库存里拿） */
        public boolean isLeaf() {
            return this.recipes.length == 0;
        }
    }

    /** 树上的一条配方：产物槽位 + 原料槽位数组 + 编译期算好的合并需求 */
    public static final class RecipeNode {
        public final Recipe recipe;
        public final int outputSlot;
        public final int outputCount;
        final PartNode[] parts;
        /** 单候选原料按物品合并后的需求（槽位 → 每次合成要几个），编译期定好 */
        final int[] forcedSlots;
        final int[] forcedCounts;
        /** 多候选原料（「任选其一」），上界要按候选各算一遍取最大 */
        final PartNode[] multiParts;

        RecipeNode(Recipe recipe, int outputSlot, int outputCount, PartNode[] parts,
                   int[] forcedSlots, int[] forcedCounts, PartNode[] multiParts) {
            this.recipe = recipe;
            this.outputSlot = outputSlot;
            this.outputCount = outputCount;
            this.parts = parts;
            this.forcedSlots = forcedSlots;
            this.forcedCounts = forcedCounts;
            this.multiParts = multiParts;
        }

        /** 这条配方产出什么（全命名空间 id） */
        public String output() {
            return this.recipe.output();
        }
    }

    /** 配方的一项原料：每次合成要 {@code count} 个，候选是槽位数组 */
    static final class PartNode {
        final int count;
        final int[] candidates;

        PartNode(int count, int[] candidates) {
            this.count = count;
            this.candidates = candidates;
        }
    }

    /**
     * 可复用的一套「模拟草稿纸」：覆盖层、世代戳、环标记、上界缓存。
     *
     * <h2>为什么需要它</h2>
     * <p>{@link Model} 与 {@link Bound} 每个都要三组「与树同长」的数组。满本 1,223 个槽位、
     * 746 个产物、866 条候选配方，如果每次都新分配，光是「分配 + 清零」就是二十多 MB ——
     * 实测约 2.4 ms，占新路径总耗时的 11%。这批数组的内容全都<b>由世代戳界定有效范围</b>，
     * 所以它们本来就可以跨调用复用：换一次 {@code epoch} 就等于把它们全部清空。</p>
     *
     * <h2>它凭什么保证「与新建一个一样」</h2>
     * <p>复用能成立的前提只有一条：<b>任何旧值都不会被新的一次调用读到</b>。这里靠两个
     * 单调递增的世代号保证 —— 覆盖层读的是 {@code stamp[slot] == modelEpoch}，
     * 上界缓存读的是 {@code cacheStamp[slot] == cacheEpoch}，每次 {@code reset()} / 每次新建
     * {@link Bound} 都把对应的世代号加一。旧世代的残留值<b>永远不满足等号</b>，
     * 与「数组刚 new 出来全是 0」在语义上完全等价。</p>
     *
     * <p>与 AE2 的 {@code CraftingSimulationState} 是同一个角色：一次计算带一份可复位的模拟态，
     * 而不是每次询问都重新铺一张表。</p>
     *
     * <p>只给<b>同一个线程</b>用（服务端主线程）。跨线程共享会读到别人的世代号 ——
     * {@code VoidWarehouseData} 里所有调用都在主线程，这一点由「仓库数据是 server 单例」保证。</p>
     */
    public static final class Scratch {
        private final int size;
        private final BigInteger[] pool;
        private final int[] stamp;
        private final boolean[] onPath;
        private final BigInteger[] cache;
        private final int[] cacheStamp;
        private final boolean[] boundPath;
        private int modelEpoch = 1;
        private int cacheEpoch = 1;

        public Scratch(Tree tree) {
            this.size = tree == null ? 0 : tree.size();
            this.pool = new BigInteger[this.size];
            this.stamp = new int[this.size];
            this.onPath = new boolean[this.size];
            this.cache = new BigInteger[this.size];
            this.cacheStamp = new int[this.size];
            this.boundPath = new boolean[this.size];
        }

        /** 这套草稿纸是按多长的树分配的（配方本换版后必须重建） */
        public int size() {
            return this.size;
        }

        /** 推进模拟世代：所有旧覆盖值同时失效（等价于把 {@code pool} 清空） */
        private void nextModelEpoch() {
            if (++this.modelEpoch == Integer.MIN_VALUE) {
                java.util.Arrays.fill(this.stamp, 0);
                this.modelEpoch = 1;
            }
        }

        /** 推进上界世代：所有旧缓存同时失效（等价于把 {@code cache} 清空） */
        private int nextCacheEpoch() {
            if (++this.cacheEpoch == Integer.MIN_VALUE) {
                java.util.Arrays.fill(this.cacheStamp, 0);
                this.cacheEpoch = 1;
            }
            return this.cacheEpoch;
        }
    }

    // ================================================================== 对外：规划 / 上限

    /**
     * 规划「取 {@code want} 个产物」。凑不出来返回 {@code null}（调用方据此整次拒绝，仓库一个字节都不动）。
     *
     * <p>这是<b>接收树与库存数组</b>的主路径，{@code VoidWarehouseData} 走的就是它。</p>
     *
     * @param stock 与 {@link Tree} 槽位对齐的库存数组，{@code null} 元素表示 0
     */
    public static Plan plan(Tree tree, RecipeNode recipe, BigInteger want, BigInteger[] stock) {
        return plan(tree, recipe, want, stock, null);
    }

    /**
     * 同 {@link #plan(Tree, RecipeNode, BigInteger, BigInteger[])}，但可以带一份
     * {@link Scratch} 复用模拟草稿纸（批量算多个产物时省掉每条的六组数组分配）。
     *
     * <p>{@code scratch} 传 {@code null} 时自己新建一份；{@code scratch.size()} 与树的长度
     * 对不上（配方本换版后忘了重建）时也自己新建 —— 宁可多分配，也不能越界。</p>
     */
    public static Plan plan(Tree tree, RecipeNode recipe, BigInteger want, BigInteger[] stock,
                            Scratch scratch) {
        if (tree == null || recipe == null || want == null || want.signum() <= 0 || stock == null) {
            return null;
        }
        int outCount = recipe.outputCount;
        BigInteger crafts = ceilDiv(want, outCount);
        if (crafts.signum() <= 0) {
            return null;
        }
        Model model = new Model(tree, stock, true, usable(tree, scratch));
        if (!simulate(tree, recipe, crafts, model, 0, new int[]{MAX_NODES})) {
            return null;
        }
        BigInteger extra = surplusOf(crafts, outCount, want);
        if (extra.signum() > 0) {
            model.addSurplus(recipe.outputSlot, extra);
        }
        return new Plan(crafts, model.consumedView(), model.surplusView());
    }

    /**
     * {@link #plan(Tree, RecipeNode, BigInteger, BigInteger[])} 的旧签名版本（临时编译树，慢）。
     *
     * <p>保留这个签名是为了自检与离线工具（它们手里只有 {@code Recipe} + {@code Map}）。
     * 它会<b>临时编译一棵树</b>，因此比主路径贵得多 —— 生产路径不要用它。</p>
     */
    public static Plan plan(Recipe recipe, BigInteger want, Map<String, BigInteger> stock,
                            Map<String, List<Recipe>> byOutput) {
        if (recipe == null || want == null || want.signum() <= 0 || stock == null || byOutput == null) {
            return null;
        }
        Tree tree = Tree.compile(byOutput);
        RecipeNode node = tree.recipeNodeOf(recipe);
        if (node == null) {
            // 旧实现的语义：root 配方不在 byOutput 里也能算（只是「往下钻」不生效）。
            // 把这条配方补进去再编译一次，保持逐字节相同的行为，而不是直接返回 null。
            Map<String, List<Recipe>> patched = new LinkedHashMap<>(byOutput);
            patched.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
            tree = Tree.compile(patched);
            node = tree.recipeNodeOf(recipe);
            if (node == null) {
                return null;
            }
        }
        return plan(tree, node, want, tree.arrayOf(stock));
    }

    /**
     * 「现在最多能做几个（成品个数）」，接收预编译树与库存数组。
     *
     * <p>两步：先用 {@link #boundCap} 估一个宽松上界，再用 {@link #simulate} 摆一遍。
     * 上界本身摆得下就直接是答案（只跑一次模拟）；摆不下就在 {@code [0, 上界 - 1]} 上
     * <b>二分</b>，找得到的最大的那个可行次数就是答案。与 {@link #plan} 用的是
     * <b>同一个模拟器</b>，所以「界面说能合成 N 个」与「真点下去能拿到 N 个」不会打架。</p>
     *
     * <p>为什么上界要「宽松」而不是「精确」：往下钻之后，「某项够不够」取决于这一项
     * 又去做了几次别的合成，精确上界本身就要靠搜索求。既然验证阶段已经把整棵树摆过一遍，
     * 上界的作用就只是<b>把搜索区间压窄</b>，估大一点无所谓（多几次二分而已），估小才是灾难
     * （会少报）。所以 {@link Bound} 一律取「家里有的 + 全部拿去做能做出的」，
     * 明知共享原料时会重复计数 —— 那份误差由二分兜住。</p>
     *
     * <h2>为什么是二分，不是「上界减 1 再试」</h2>
     * <p>宽松上界的<b>相对</b>误差其实很小（实测 6%~33%），但真值本身在万位，
     * 于是<b>绝对</b>误差也在千位：实测 P50 = 3,328 次、最大 114,081 次。
     * 线性退让于是两头都堵死 —— 上限设小了（曾经是 64）就<b>退不到</b>真值，
     * 配方被判成「做不出」而整格从界面消失；上限设大了就慢到不可用（按 P50 要慢 52 倍）。
     * 二分只要 {@code ⌈log₂(上界+1)⌉} 次探针，与虚高多少<b>无关</b>。
     * 这不是理论推演，是 1.0.17 之前真实发生过的缺陷，见
     * {@code docs/配方本满本并发测试报告.md}。</p>
     *
     * <p>「做得越多越难」这条单调性<b>原本就在依赖</b>：旧实现从上界往下扫，
     * 遇到第一个摆得下的就当成最大值 —— 那正是「可行集是区间」的假设。
     * 二分不引入任何新假设，只是把「线性扫描」换成「对数查找」。
     * 万一某个配方因为贪心分支选择而在某处非单调，二分只会<b>低估</b>（保守），
     * 绝不会高估 —— 高估就是「显示能取、点下去失败」，那才是不能接受的方向。</p>
     *
     * <p>返回值是 {@code outputCount} 的整数倍 —— 它表示「能做出来的成品总数」，
     * 而不是次数。调用方要「正好取 N 个」时交给 {@link #plan}，那边会自己向上取整。</p>
     */
    public static BigInteger maxCraftable(Tree tree, RecipeNode recipe, BigInteger[] stock) {
        return maxCraftable(tree, recipe, stock, null);
    }

    /**
     * 同 {@link #maxCraftable(Tree, RecipeNode, BigInteger[])}，但可以带一份 {@link Scratch}
     * 复用模拟草稿纸。批量算整本配方时<b>必须</b>传同一份 —— 满本 866 次调用能省掉
     * 二十多 MB 的临时数组（实测约占总耗时 11%）。
     */
    public static BigInteger maxCraftable(Tree tree, RecipeNode recipe, BigInteger[] stock,
                                          Scratch scratch) {
        if (tree == null || recipe == null || stock == null) {
            return ZERO;
        }
        Scratch shared = usable(tree, scratch);
        Bound bound = new Bound(tree, stock, shared);
        BigInteger cap = boundCap(recipe, bound, 0);
        if (cap == null) {
            // 合法配方至少有一项、每项至少一个候选，所以这里走不到；留着是为了「不返回 null 出去」
            // —— 调用方会直接 cap.signum()，一个 null 就是崩服。
            return ZERO;
        }
        BigInteger out = BigInteger.valueOf(recipe.outputCount);
        // 快路径：宽松上界本身就摆得下。它既是上界又可行，那它就是答案，没有更大的次数了。
        // 实测 79% 的配方走这条，只跑 1 次 simulate —— 这个快路径必须留着，
        // 否则每条配方都要多花 ⌈log₂⌉ 次探针，白贵一个数量级。
        //
        // 一次调用内所有探针复用同一个 Model（每次 reset 即可，数组不重新分配）；
        // 探针之间仍然互不影响 —— 这是「可复位」而不是「共享状态」，见 Model#reset。
        Model probe = new Model(tree, stock, false, shared);
        if (cap.signum() > 0 && feasible(tree, recipe, cap, probe)) {
            return cap.multiply(out);
        }
        // 慢路径：二分 [0, cap - 1]。lo 始终是「已知可行 + 1」，hi 始终是「已知不可行 - 1」，
        // 于是区间每轮都严格收缩，⌈log₂(cap+1)⌉ 轮后必停。
        BigInteger lo = ZERO;
        BigInteger hi = cap.subtract(ONE);
        BigInteger best = ZERO;
        while (lo.compareTo(hi) <= 0) {
            BigInteger mid = lo.add(hi).shiftRight(1);
            if (feasible(tree, recipe, mid, probe)) {
                best = mid;
                lo = mid.add(ONE);
            } else {
                hi = mid.subtract(ONE);
            }
        }
        return best.multiply(out);
    }

    /**
     * {@link #maxCraftable(Tree, RecipeNode, BigInteger[])} 的旧签名版本（临时编译树，慢）。
     *
     * <p>保留它是为了不破坏自检与离线工具（{@code CraftExchangeAudit}、{@code RecipeScaleBench}、
     * {@code Dump} 等）。生产路径请用带 {@link Tree} 的重载。</p>
     */
    public static BigInteger maxCraftable(Recipe recipe, Map<String, BigInteger> stock,
                                          Map<String, List<Recipe>> byOutput) {
        if (recipe == null || stock == null || byOutput == null) {
            return ZERO;
        }
        Tree tree = Tree.compile(byOutput);
        RecipeNode node = tree.recipeNodeOf(recipe);
        if (node == null) {
            Map<String, List<Recipe>> patched = new LinkedHashMap<>(byOutput);
            patched.computeIfAbsent(recipe.output(), k -> new ArrayList<>()).add(recipe);
            tree = Tree.compile(patched);
            node = tree.recipeNodeOf(recipe);
            if (node == null) {
                return ZERO;
            }
        }
        return maxCraftable(tree, node, tree.arrayOf(stock));
    }

    /**
     * 「按这份库存，做 {@code crafts} 次 {@code recipe} 到底摆不摆得下」。
     *
     * <p>单独抽出来是为了让 {@link #maxCraftable} 的二分读起来还是「一问一答」。
     * 每次探针都从<b>复位后</b>的 {@link Model} 与一份<b>崭新</b>的预算开始 ——
     * 探针之间必须互不影响，否则前一次挖深了会让后一次因为预算耗尽而假失败，
     * 二分的单调性前提当场作废。</p>
     *
     * <p>{@code crafts <= 0} 一律当作「摆不下」。做 0 次当然是成立的，但那不是
     * 「能做几个」的答案；返回 {@code false} 可以让二分的 {@code best} 稳稳停在
     * {@link #ZERO}，顺便省掉一次没意义的整树模拟。</p>
     *
     * <p>探针<b>关掉扣料账本</b>（{@link Model} 的 {@code trackBooks}）：这里只问摆不摆得下，
     * 账本没人读，开着等于每次探针白写一半的 map —— 而满本重建要跑 5,000 多次探针。</p>
     */
    private static boolean feasible(Tree tree, RecipeNode recipe, BigInteger crafts, Model probe) {
        if (crafts.signum() <= 0) {
            return false;
        }
        probe.reset();
        return simulate(tree, recipe, crafts, probe, 0, new int[]{MAX_NODES});
    }

    /**
     * 挑一份与这棵树匹配的草稿纸：调用方给的能用就用，否则自己新建。
     *
     * <p>「匹配」的判据是长度相等。配方本换版之后树会换、长度会变，这时旧的 {@link Scratch}
     * 一定不能再碰（数组短了一截就是越界）—— 宁可多分配一次，也不能让一条过期草稿纸
     * 把越界异常带进 tick。</p>
     */
    private static Scratch usable(Tree tree, Scratch scratch) {
        return scratch != null && scratch.size() == tree.size() ? scratch : new Scratch(tree);
    }

    /**
     * 把一条配方<b>整条闭包</b>的库存快照取出来（配方要用到的每一样东西现在有多少）。
     *
     * <p>保留这个 {@code Map} 版本是为了旧调用方与「对拍」；生产路径用
     * {@link Tree#stockArray(RecipeNode, Function)} —— 它返回按槽位对齐的数组，
     * 热路径上没有任何字符串哈希。两者的<b>槽位集合与数值必须逐键相同</b>
     * （离线审计的 H 组就是比这个）。</p>
     *
     * <p>为什么是闭包而不是「这条配方的原料」：1.0.15 报过一个 bug ——
     * 学过一次「深色橡木原木 → 深色橡木木板」和一次活塞之后，木板用完了、原木还有一堆，
     * 取活塞却说做不出来。根因就是快照里只有木板、没有原木：模拟器看到「木板 0、原木 0」，
     * 自然判「没料」。把闭包一起取出来，模拟器才有机会顺着「原木 → 木板」往下钻。</p>
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
     * <p>{@code pool} 是「还能用多少」的<b>覆盖层</b>：只有本世代改过的槽位才在里面，
     * 没改过的回落到库存数组。分支走错时要能退回去 —— 用的是
     * <b>标记 + 撤销日志</b>（{@link #mark()} / {@link #rollback(int)}），
     * 而不是「每个分支复制一份」。</p>
     *
     * <h2>1.0.17：可复位，而不是每探针新建</h2>
     * <p>R2 已经把「复制小 map」换成了「记旧值 + 回滚」，但每次探针仍要新建一个
     * {@code Model}（外加一个 {@code LinkedHashSet} 的 {@code path}）。满本重建要跑 5,393 次探针，
     * 这笔固定开销与「算了几条配方」无关，纯粹是白付。</p>
     *
     * <p>现在改成一个可复位的状态：覆盖层用 {@code slot} 下标的 {@code pool} 数组 +
     * 一个<b>世代戳</b> {@code stamp}（{@code stamp[slot] == epoch} 表示这个槽位本世代被改过），
     * {@link #reset()} 只把 {@code epoch} 加一、{@code undoSize} 归零 —— 数组不用清、
     * 一个对象都不分配。环标记同理（{@code boolean[] onPath}，进出成对置位）。
     * 「可复位」与「共享状态」的区别就在这里：复位之后没有任何残留，
     * 每次探针看到的东西与新建一个 {@code Model} 逐字节相同。</p>
     *
     * <h2>为什么用日志化撤销，而不是逆操作</h2>
     * <p>「改用逆操作」原本被否掉过，理由是「逆操作的写法容易漏，漏一处就是账目对不上」。
     * 这里避开那个风险的办法是：<b>不写逆操作，只记旧值</b> —— 每次改之前把
     * (哪本账, 哪个槽位, 旧值, 旧世代) 压进一条撤销日志，回滚时倒序贴回。
     * 它和逆操作的区别是机械可验证：只要「所有写操作都经过 {@link #setPool} /
     * {@link #addConsumed} / {@link #addSurplus}」这一条成立，回滚就必然精确 ——
     * 而这条由 {@code pool}、{@code stamp}、{@code consumed}、{@code surplus}
     * 四个字段都是 {@code private} 来<b>强制</b>：想绕过日志直接写，当场编译不过。</p>
     */
    private static final class Model {
        private static final int POOL = 0;
        private static final int CONSUMED = 1;
        private static final int SURPLUS = 2;

        private final Tree tree;
        /** 库存快照（只读，按槽位；{@code null} = 0） */
        private final BigInteger[] stock;
        /** 模拟草稿纸：覆盖值 / 世代戳 / 环标记都存在这里，跨调用复用 */
        private final Scratch scratch;

        /** 环标记（= {@code scratch.onPath}；进出成对，正常结束必然全 false） */
        private final boolean[] onPath;

        private final Map<String, BigInteger> consumed = new LinkedHashMap<>();
        private final Map<String, BigInteger> surplus = new LinkedHashMap<>();

        /**
         * 是否维护扣料账本（{@code consumed} / {@code surplus}）。
         *
         * <p>{@link #maxCraftable} 的探针只问「摆不摆得下」，账本既不参与判断也不被读取，
         * 所以探针一律关掉它 —— 每次探针能省掉约一半的写操作。{@link #plan} 要拿账本当结果，
         * 必须开着。这条开关不影响任何判断结果：账本只在 {@link #plan} 的返回值里被读。</p>
         */
        private final boolean trackBooks;

        // 撤销日志用平行数组，不用对象 —— 每次探针要压上万条，装箱会把 GC 拖进来。
        private int[] undoBook = new int[64];
        private int[] undoSlot = new int[64];
        private BigInteger[] undoOld = new BigInteger[64];
        private int[] undoStamp = new int[64];
        private int undoSize;

        Model(Tree tree, BigInteger[] stock, boolean trackBooks, Scratch scratch) {
            this.tree = tree;
            this.stock = stock;
            this.scratch = scratch;
            this.onPath = scratch.onPath;
            this.trackBooks = trackBooks;
            // 本对象一出生就占一个新世代：之前那批（可能与别人共享）的覆盖值全部作废。
            scratch.nextModelEpoch();
        }

        /**
         * 把状态复位成「刚构造出来的样子」，供下一次探针使用。
         *
         * <p>三件事：撤销日志清空（{@code undoSize = 0}，旧值引用置空免得拽着 BigInteger）、
         * 世代戳加一（于是所有旧覆盖值<b>同时失效</b>，等价于把覆盖层清空）、
         * 环标记理论上已被 {@code try/finally} 清干净，这里不再重复清（清了反而掩盖 bug）。</p>
         *
         * <p>世代戳溢出时只有一路退化：整表清零后从 1 重来。那只会在同一个
         * {@link Scratch} 上跑 20 亿次探针时发生，正确性不受影响。</p>
         */
        void reset() {
            for (int i = 0; i < this.undoSize; i++) {
                this.undoOld[i] = null;
            }
            this.undoSize = 0;
            this.scratch.nextModelEpoch();
        }

        /** 记下当前位置。回滚到这个位置 = 撤销这之后的所有写。 */
        int mark() {
            return this.undoSize;
        }

        /** 倒序把旧值贴回去。{@code old == null} 表示「当时这个槽位没有覆盖值」。 */
        void rollback(int mark) {
            for (int i = this.undoSize - 1; i >= mark; i--) {
                int book = this.undoBook[i];
                int slot = this.undoSlot[i];
                BigInteger old = this.undoOld[i];
                if (book == POOL) {
                    // 恢复「旧值 + 旧世代」：旧世代不是本世代时，这个覆盖值自动失效。
                    this.scratch.stamp[slot] = this.undoStamp[i];
                    if (old != null) {
                        this.scratch.pool[slot] = old;
                    }
                } else {
                    Map<String, BigInteger> target = book == CONSUMED ? this.consumed : this.surplus;
                    String id = this.tree.idOf(slot);
                    if (id == null) {
                        continue;
                    }
                    if (old == null) {
                        target.remove(id);
                    } else {
                        target.put(id, old);
                    }
                }
            }
            // 清引用：撤销日志数组会活到这次探针结束，不清就会拽着一堆 BigInteger 不放。
            for (int i = mark; i < this.undoSize; i++) {
                this.undoOld[i] = null;
            }
            this.undoSize = mark;
        }

        /** 「还能用多少」：本世代改过就看覆盖值，没改过回落到库存。 */
        BigInteger available(int slot) {
            BigInteger overridden = this.scratch.stamp[slot] == this.scratch.modelEpoch
                    ? this.scratch.pool[slot] : null;
            if (overridden != null) {
                return overridden;
            }
            BigInteger base = this.stock[slot];
            return base == null ? ZERO : base;
        }

        boolean onPath(int slot) {
            return this.onPath[slot];
        }

        void pushPath(int slot) {
            this.onPath[slot] = true;
        }

        void popPath(int slot) {
            this.onPath[slot] = false;
        }

        void setPool(int slot, BigInteger value) {
            int oldStamp = this.scratch.stamp[slot];
            BigInteger old = oldStamp == this.scratch.modelEpoch ? this.scratch.pool[slot] : null;
            this.journal(POOL, slot, old, oldStamp);
            this.scratch.stamp[slot] = this.scratch.modelEpoch;
            this.scratch.pool[slot] = value;
        }

        void addConsumed(int slot, BigInteger delta) {
            if (!this.trackBooks) {
                return;
            }
            String id = this.tree.idOf(slot);
            if (id == null) {
                return;
            }
            BigInteger old = this.consumed.get(id);
            this.journal(CONSUMED, slot, old, 0);
            this.consumed.put(id, old == null ? delta : old.add(delta));
        }

        void addSurplus(int slot, BigInteger delta) {
            if (!this.trackBooks) {
                return;
            }
            String id = this.tree.idOf(slot);
            if (id == null) {
                return;
            }
            BigInteger old = this.surplus.get(id);
            this.journal(SURPLUS, slot, old, 0);
            this.surplus.put(id, old == null ? delta : old.add(delta));
        }

        Map<String, BigInteger> consumedView() {
            return this.consumed;
        }

        Map<String, BigInteger> surplusView() {
            return this.surplus;
        }

        private void journal(int book, int slot, BigInteger old, int oldStamp) {
            if (this.undoSize == this.undoSlot.length) {
                int grown = this.undoSize * 2;
                this.undoBook = java.util.Arrays.copyOf(this.undoBook, grown);
                this.undoSlot = java.util.Arrays.copyOf(this.undoSlot, grown);
                this.undoOld = java.util.Arrays.copyOf(this.undoOld, grown);
                this.undoStamp = java.util.Arrays.copyOf(this.undoStamp, grown);
            }
            this.undoBook[this.undoSize] = book;
            this.undoSlot[this.undoSize] = slot;
            this.undoOld[this.undoSize] = old;
            this.undoStamp[this.undoSize] = oldStamp;
            this.undoSize++;
        }
    }

    /**
     * 把「做 {@code crafts} 次 {@code recipe}」真摆一遍，成功时把账写进 {@code model}。
     *
     * <p>逐项处理原料；一项有多候选时按候选顺序试，<b>每个候选试之前先打个 {@code mark}，
     * 失败就 {@code rollback} 回去</b> —— 换候选后前一候选的扣料不会残留。</p>
     */
    private static boolean simulate(Tree tree, RecipeNode recipe, BigInteger crafts, Model model,
                                    int depth, int[] budget) {
        for (PartNode part : recipe.parts) {
            BigInteger amount = times(crafts, part.count);
            boolean done = false;
            for (int candidate : part.candidates) {
                int mark = model.mark();
                if (supply(tree, candidate, amount, model, depth, budget)) {
                    done = true;
                    break;
                }
                model.rollback(mark);
            }
            if (!done) {
                return false;
            }
        }
        return true;
    }

    /**
     * 凑出 {@code amount} 个 {@code slot} 的物品：<b>先吃库存，不够的再往下钻</b>。
     *
     * <p>顺序不能反。家里有 2 个木板、这一项要 3 个，正确做法是「扣 2 个木板 + 做 1 次木板」；
     * 若改成「一缺货就整项去做」，会做出 4 个木板、用掉 3 个、剩 3 个入账 ——
     * 净效果虽然也是「多了 1 个木板」，但白扣了一次原木，玩家看到的就是「明明有木板还扣我原木」。</p>
     *
     * <p>⚠️ 本方法会改 {@code model}，<b>失败时不会自己还原</b> —— 还原由调用方按它自己记的
     * {@code mark} 做（{@link #simulate} 的候选循环与下面子配方循环都是这么做的）。
     * 自己还原反而会错：子配方循环里「已经扣掉的那部分库存」在换下一条子配方时<b>必须保留</b>，
     * 所以这里只能由调用方决定回滚到哪个位置。</p>
     */
    private static boolean supply(Tree tree, int slot, BigInteger amount, Model model,
                                  int depth, int[] budget) {
        if (slot < 0) {
            // 编译期没拿到槽位（原料 id 不在任何 key 里 —— 理论上不会发生）。
            // 保守失败，与旧实现「byOutput 里没有它」同义。
            return false;
        }
        BigInteger have = model.available(slot);
        BigInteger use = have.min(amount);
        if (use.signum() > 0) {
            model.setPool(slot, have.subtract(use));
            model.addConsumed(slot, use);
        }
        BigInteger rest = amount.subtract(use);
        if (rest.signum() <= 0) {
            return true;
        }
        // 剩下的从库存里拿不到 —— 只能往下钻。四条硬约束依次过一遍。
        if (depth >= MAX_DEPTH || model.onPath(slot)) {
            return false;
        }
        Node node = tree.node(slot);
        if (node == null || node.recipes.length == 0) {
            return false;
        }
        if (--budget[0] < 0) {
            return false;
        }
        model.pushPath(slot);
        try {
            for (RecipeNode sub : node.recipes) {
                int outCount = sub.outputCount;
                // 要 rest 个，一次出 out 个 ⇒ 做 ceil(rest / out) 次。
                // 多出来的 subCrafts × out − rest 个必须<b>入账回仓库</b>（不是丢掉，也不是白送）。
                BigInteger subCrafts = ceilDiv(rest, outCount);
                int mark = model.mark();
                if (simulate(tree, sub, subCrafts, model, depth + 1, budget)) {
                    BigInteger extra = surplusOf(subCrafts, outCount, rest);
                    if (extra.signum() > 0) {
                        model.addSurplus(slot, extra);
                    }
                    return true;
                }
                model.rollback(mark);
            }
            return false;
        } finally {
            model.popPath(slot);
        }
    }

    // ================================================================== 小整数快路径

    /**
     * 模拟器里最热的三行都是 {@code BigInteger} 的乘除法：每个节点算 {@code crafts × count}，
     * 每次往下钻算 {@code ceil(rest / out)} 与多出来的零头。{@code BigInteger} 每做一次
     * 都要分配中间对象，而这里的量级（几次合成、几十个原料）用 {@code long} 完全装得下。
     *
     * <p>这不是凭感觉的微优化：满本重建 5,393 次探针只走 51,645 个节点，
     * 平均一次探针 10 个节点 —— 单次探针的开销里既没有哈希表增长也没有 GC 压力，
     * 剩下的就是这些算术的对象分配。归因见 {@code docs/配方本满本并发测试报告.md} §12。</p>
     *
     * <p>{@code 40} 位是保守阈值：{@code 2^40 ≈ 1.1×10¹²}，再乘最多 64 也不会溢出 {@code long}。
     * 超过就退回 {@code BigInteger} 原路，<b>行为完全一致</b>（对拍验证过）。</p>
     */
    private static final int SMALL_BITS = 40;

    /** 1~64 的 {@code BigInteger} 常量：{@code BigInteger.valueOf} 只缓存 -16~16，其余每次都新建。 */
    private static final BigInteger[] SMALL_COUNT = new BigInteger[65];

    static {
        for (int i = 0; i < SMALL_COUNT.length; i++) {
            SMALL_COUNT[i] = BigInteger.valueOf(i);
        }
    }

    private static BigInteger small(int count) {
        return count >= 0 && count < SMALL_COUNT.length
                ? SMALL_COUNT[count] : BigInteger.valueOf(count);
    }

    /** {@code value × factor}。 */
    private static BigInteger times(BigInteger value, int factor) {
        if (value.bitLength() <= SMALL_BITS) {
            return BigInteger.valueOf(value.longValue() * factor);
        }
        return value.multiply(small(factor));
    }

    /** {@code ceil(rest / out)}，要求 {@code out > 0}。 */
    private static BigInteger ceilDiv(BigInteger rest, int out) {
        if (rest.bitLength() <= SMALL_BITS) {
            long r = rest.longValue();
            return BigInteger.valueOf((r + out - 1) / out);
        }
        BigInteger divisor = small(out);
        return rest.add(divisor).subtract(ONE).divide(divisor);
    }

    /** {@code crafts × out − rest}：做多了要入账的零头。 */
    private static BigInteger surplusOf(BigInteger crafts, int out, BigInteger rest) {
        if (crafts.bitLength() <= SMALL_BITS && rest.bitLength() <= SMALL_BITS) {
            return BigInteger.valueOf(crafts.longValue() * out - rest.longValue());
        }
        return crafts.multiply(small(out)).subtract(rest);
    }

    // ================================================================== 上界

    /**
     * 上界计算的状态：库存数组 + 缓存 + 环标记 + 预算。
     *
     * <p>与 {@link Model} 分开，是因为上界<b>只读库存</b>（不改覆盖层）而需要一份递归缓存；
     * 两件事混在一个类里会让「探针能不能看到上界留下的痕迹」变得不清楚。
     * 这里的 {@code cache} 语义与旧 {@code availOf} 的 {@code Map} 缓存逐条对齐：
     * 只有「正常算出结果」的分支才写缓存，深度/环/预算三个出口返回的保守值<b>不进缓存</b>
     * （同一件物品在别的路径上可能算出更大的数）。</p>
     */
    private static final class Bound {
        private final Tree tree;
        private final BigInteger[] stock;
        private final Scratch scratch;
        /** 本次上界的缓存世代：{@code scratch.cacheStamp[slot] == cacheEpoch} 表示缓存有效 */
        private final int cacheEpoch;
        private int budget = MAX_NODES;

        Bound(Tree tree, BigInteger[] stock, Scratch scratch) {
            this.tree = tree;
            this.stock = stock;
            this.scratch = scratch;
            // 一出生就换一个缓存世代：上一次上界留下的值全部作废（等价于清空缓存）。
            this.cacheEpoch = scratch.nextCacheEpoch();
        }

        BigInteger avail(int slot) {
            return this.avail(slot, 0);
        }

        BigInteger avail(int slot, int depth) {
            if (slot < 0) {
                return ZERO;
            }
            if (this.scratch.cacheStamp[slot] == this.cacheEpoch) {
                return this.scratch.cache[slot];
            }
            BigInteger base = this.stock[slot];
            if (base == null) {
                base = ZERO;
            }
            // 这三条出口返回的是「别往下看了」的保守值，<b>不能进缓存</b>：
            // 同一件物品在别的路径上（不在环里、深度还够）可能算出更大的数。
            if (depth >= MAX_DEPTH || this.scratch.boundPath[slot] || --this.budget < 0) {
                return base;
            }
            Node node = this.tree.node(slot);
            if (node == null || node.recipes.length == 0) {
                this.scratch.cache[slot] = base;
                this.scratch.cacheStamp[slot] = this.cacheEpoch;
                return base;
            }
            BigInteger bestMade = ZERO;
            this.scratch.boundPath[slot] = true;
            try {
                for (RecipeNode sub : node.recipes) {
                    BigInteger cap = boundCap(sub, this, depth + 1);
                    if (cap == null) {
                        continue;
                    }
                    bestMade = bestMade.max(cap.multiply(BigInteger.valueOf(sub.outputCount)));
                }
            } finally {
                this.scratch.boundPath[slot] = false;
            }
            BigInteger total = base.add(bestMade);
            this.scratch.cache[slot] = total;
            this.scratch.cacheStamp[slot] = this.cacheEpoch;
            return total;
        }
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
     * <p>合并的需求现在是<b>编译期算好的</b>（{@link RecipeNode#forcedSlots}），
     * 运行期不再每个节点分配一个 {@code HashMap} —— 满本重建有上万次
     * {@code availOf} 调用，那是上万个小短命对象。</p>
     *
     * <p>多候选项不能合并（它们本来就是「任选其一」），取「各候选里最大的那个」作为上界 ——
     * 这是<b>上界</b>而不是估计值，误差交给验证阶段。</p>
     */
    private static BigInteger boundCap(RecipeNode recipe, Bound bound, int depth) {
        BigInteger cap = null;
        for (int i = 0; i < recipe.forcedSlots.length; i++) {
            cap = lower(cap, bound.avail(recipe.forcedSlots[i], depth)
                    .divide(small(recipe.forcedCounts[i])));
        }
        for (PartNode part : recipe.multiParts) {
            BigInteger best = ZERO;
            for (int candidate : part.candidates) {
                BigInteger each = bound.avail(candidate, depth).divide(small(part.count));
                if (each.compareTo(best) > 0) {
                    best = each;
                }
            }
            cap = lower(cap, best);
        }
        return cap;
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
     *
     * <p>它现在还是 {@link Tree#compile} 的输入 —— 树只由它决定，与库存无关。</p>
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
