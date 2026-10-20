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
 * 「N 合 1」兑换引擎 —— 纯逻辑，<b>不引用任何 Minecraft 类型</b>，五个版本共用同一份。
 *
 * <p><b>「N」是每条边自己的比例</b>，不是常数 9：绝大多数原版小配方是 9 合 1，
 * 但 2×2 的配方是 <b>4 合 1</b>（{@code 4 个海晶碎片 → 1 个海晶石}）。
 * 引擎里所有乘除都走边上的 {@link Edge#count()}，任何地方都不写死 9。</p>
 *
 * <p><b>还有第二类关系：多原料配方</b>（{@link MultiRecipe}）—— 一种原料凑不出来的时候，
 * 原版还有「几种不同原料 → 1 个成品」的配方，例如
 * {@code 5 海晶碎片 + 4 海晶棱镜 → 1 海晶灯}。这类<b>不能</b>拆成若干个单边再叠加
 * （拆出来的中间物在原版里根本不存在），所以它是一等公民：有自己的价值公式
 * （{@code value(成品) = Σ 原料价值}）和自己的取料分支（每种原料一样都不能少）。</p>
 *
 * <h2>它解决什么问题</h2>
 * <p>虚空仓库里只有 9 个铁粒、没有铁锭时，玩家点「铁锭」这一格应该能拿到 1 个铁锭
 * （仓库里那 9 个铁粒被扣掉），等价于「替玩家把合成步骤做掉」。反过来也成立：
 * 仓库里只有 1 个铁块时，取 1 个铁锭就是「把铁块拆了，1 个给玩家、8 个退回仓库」。</p>
 *
 * <h2>为什么这件事不能靠「等价总量」直接算</h2>
 * <p>26.3 全部 30 条边里，只有 20 条是双向的。另外 9 条<b>只能向上合成</b>
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
 *   <li><b>用下级合成</b>：需要 {@code count × need} 个上级原料，递归取；</li>
 *   <li><b>拆上级</b>：拆「用得完的整块」—— {@code floor(need / count)} 个更大的物品，
 *       上级不够就逐块往下退；差的零头交给前两条路补。
 *       只有 {@code need < count} 时才多拆一块、把用不完的<b>作为成品退回仓库</b>
 *       （这就是玩家要的「拆零找零」）。</li>
 * </ol>
 * <p>递归里用 {@link State#visiting} 破环：{@code 铁锭 → 需要铁粒 → 只能拆铁锭} 这种
 * 自我指涉的分支必须直接判失败，否则会无限递归。</p>
 *
 * <h2>守恒性（自检里逐条断言）</h2>
 * <p>每次 {@link #drain} 成功时，都有<b>不变式</b>：
 * <code>Σ(每条 delta 的数量 × 该物品的价值) == −need × 目标物品的价值</code>，
 * 其中价值由边上的比例算出来（见 {@link Table#valueOf}：{@code count 个下级 = 1 个上级}）。
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
     * 一条「N 合 1」边：{@code count × small ⇄ 1 × big}。
     *
     * <p><b>{@code count} 不是常数 9</b>：绝大多数原版「小配方」确实是 9 合 1，
     * 但 2×2 的配方是 <b>4 合 1</b>（例如 <b>4 个海晶碎片 → 1 个海晶石</b>）。
     * 早期实现把 9 写死在整个引擎里（层级、守恒式、拆零），加 4:1 时全部要跟着泛化 ——
     * 现在比例是<b>每条边自己的属性</b>，引擎只按边上的数算。</p>
     *
     * <p>两个方向各自独立：{@code combineRecipe} 非空 = 原版有「{@code count} 个 small → 1 个 big」
     * 的配方；{@code splitRecipe} 非空 = 原版有「1 个 big → {@code count} 个 small」的配方。
     * <b>两个都可以为 null 的边不存在</b>（构造器直接拒绝），但允许只有其中一个方向成立。</p>
     *
     * <p>配方 id 只用于启动复核的报错信息与文档追溯，运行期算法完全不看它。</p>
     *
     * <p>⚠️ <b>没有「不写比例就是 9:1」的便捷构造器，这是故意的</b>：那种构造器一旦存在，
     * 任何一处重建边时漏传 {@code count} 都会静默变成 9 合 1 —— 启动复核里就真的漏过一次
     * （4 个碎片换 1 个海晶石被悄悄改回 9 个），界面上只表现为「可兑数量少了一半」，
     * 没有任何报错。比例必须一条一条写出来。</p>
     */
    public record Edge(String small, String big, int count, String combineRecipe, String splitRecipe) {
        public Edge {
            if (small == null || big == null || small.isEmpty() || big.isEmpty() || small.equals(big)) {
                throw new IllegalArgumentException(
                        "兑换边的两端必须是两个不同的非空物品 id，收到 " + small + " / " + big);
            }
            if (combineRecipe == null && splitRecipe == null) {
                throw new IllegalArgumentException(
                        "兑换边 " + small + " / " + big + " 至少要有 combine / split 其中一个方向");
            }
            if (count < 2) {
                // 1:1 不是「合成」，那是同一个物品；比例必须在 2 以上
                throw new IllegalArgumentException(
                        "兑换边 " + small + " / " + big + " 的比例至少是 2:1，收到 " + count);
            }
        }

        /** 能不能「{@code count} 个 small 合出 1 个 big」 */
        public boolean canCombine() {
            return this.combineRecipe != null;
        }

        /** 能不能「把 1 个 big 拆成 {@code count} 个 small」 */
        public boolean canSplit() {
            return this.splitRecipe != null;
        }
    }

    /**
     * 全部兑换边（<b>31 条 / 55 个物品</b>），先按方向分组、组内按 (small, big) 字典序。
     * 另有 <b>1 条多原料配方</b>（海晶灯，2 种原料），见 {@link #MULTI_RECIPES} ——
     * 算上它表里一共 57 个物品。
     *
     * <p>采集口径（对 Minecraft 26.3 的原版配方全集做过一次全量扫描，2042 个配方文件）：</p>
     * <ul>
     *   <li>{@code combineRecipe} 非空 = 存在配方「{@code count} 个 {@code small} → 1 个 {@code big}」
     *       （{@code crafting_shaped} 的 2×2 / 3×3 同符号，或 {@code crafting_shapeless} 的同物品 N 项）；</li>
     *   <li>{@code splitRecipe} 非空 = 存在配方「1 个 {@code big} → {@code count} 个 {@code small}」。</li>
     * </ul>
     *
     * <p>26.3 的实测分布：<b>双向 20 条、只能向上合成 9 条、只能向下拆分 2 条</b>。
     * 只能向上合成的 9 条是：竹→竹块、唱片碎片→唱片 5、冰→浮冰、西瓜片→西瓜、
     * 地狱疣→地狱疣块、浮冰→蓝冰、海晶碎片→海晶砖、硫磺→浓缩硫，
     * 以及 <b>4 个海晶碎片→海晶石</b>（2×2 配方）；
     * 只能向下拆分的 2 条是：<b>骨头→3 骨粉</b>（<b>反向不成立</b>，原版没有骨粉合成骨头）、
     * 涂蜡铜块→9 铜锭（<b>反向不成立，绝不能逆用</b>）。</p>
     *
     * <p>比例<b>不是常数 9</b>：全表 29 条 9:1、1 条 <b>4:1</b>（4 海晶碎片→海晶石）、
     * 1 条 <b>3:1</b>（3 骨粉←1 骨头）。</p>
     *
     * <p><b>其中「冰 → 浮冰 → 蓝冰」是一条三级链</b>（81 个冰 = 1 个蓝冰），
     * 递归取料天然支持：{@code need(蓝冰) → 9 × need(浮冰) → 81 × need(冰)}。</p>
     *
     * <p><b>骨头这一族的形状值得单独说</b>：骨粉有两个上级 —— 骨头（3:1，<b>只能拆</b>）
     * 和骨块（9:1，双向），所以它是一棵「一个下级、两个上级」的树，不是一条链。
     * 这与海晶族的形状相同（碎片 ← 海晶石 4:1 / 海晶砖 9:1），价值标定天然支持：
     * 骨粉 = 1、骨头 = 3、骨块 = 9，两条边都自洽（{@code 3 × 1 = 3}、{@code 9 × 1 = 9}）。
     * 于是还可以跨两级取料：<b>3 个骨头 = 9 骨粉 = 1 骨块</b>。</p>
     *
     * <p><b>为什么不收「海晶灯」</b>：它是 <b>5 海晶碎片 + 4 海晶棱镜</b>——
     * 9 个原料但<b>不是同一种</b>，属于「多原料配方」。本表的每条边只描述
     * 「一种原料 N 个 → 1 个成品」，多原料要另一套规划与上界算法（2026-10-18 玩家拍板先不做）。</p>
     */
    public static final List<Edge> EDGES = List.of(
            // ---- 双向（既能 9 合 1，也能 1 拆 9） ----
            // 9 x bone_meal -> 1 x bone_block
            new Edge("bone_meal", "bone_block", 9, "bone_block", "bone_meal_from_bone_block"),
            // 9 x coal -> 1 x coal_block
            new Edge("coal", "coal_block", 9, "coal_block", "coal"),
            // 9 x copper_ingot -> 1 x copper_block
            new Edge("copper_ingot", "copper_block", 9, "copper_block", "copper_ingot"),
            // 9 x copper_nugget -> 1 x copper_ingot
            new Edge("copper_nugget", "copper_ingot", 9, "copper_ingot_from_nuggets", "copper_nugget"),
            // 9 x diamond -> 1 x diamond_block
            new Edge("diamond", "diamond_block", 9, "diamond_block", "diamond"),
            // 9 x dried_kelp -> 1 x dried_kelp_block
            new Edge("dried_kelp", "dried_kelp_block", 9, "dried_kelp_block", "dried_kelp"),
            // 9 x emerald -> 1 x emerald_block
            new Edge("emerald", "emerald_block", 9, "emerald_block", "emerald"),
            // 9 x gold_ingot -> 1 x gold_block
            new Edge("gold_ingot", "gold_block", 9, "gold_block", "gold_ingot_from_gold_block"),
            // 9 x gold_nugget -> 1 x gold_ingot
            new Edge("gold_nugget", "gold_ingot", 9, "gold_ingot_from_nuggets", "gold_nugget"),
            // 9 x iron_ingot -> 1 x iron_block
            new Edge("iron_ingot", "iron_block", 9, "iron_block", "iron_ingot_from_iron_block"),
            // 9 x iron_nugget -> 1 x iron_ingot
            new Edge("iron_nugget", "iron_ingot", 9, "iron_ingot_from_nuggets", "iron_nugget"),
            // 9 x lapis_lazuli -> 1 x lapis_block
            new Edge("lapis_lazuli", "lapis_block", 9, "lapis_block", "lapis_lazuli"),
            // 9 x netherite_ingot -> 1 x netherite_block
            new Edge("netherite_ingot", "netherite_block", 9, "netherite_block", "netherite_ingot_from_netherite_block"),
            // 9 x raw_copper -> 1 x raw_copper_block
            new Edge("raw_copper", "raw_copper_block", 9, "raw_copper_block", "raw_copper"),
            // 9 x raw_gold -> 1 x raw_gold_block
            new Edge("raw_gold", "raw_gold_block", 9, "raw_gold_block", "raw_gold"),
            // 9 x raw_iron -> 1 x raw_iron_block
            new Edge("raw_iron", "raw_iron_block", 9, "raw_iron_block", "raw_iron"),
            // 9 x redstone -> 1 x redstone_block
            new Edge("redstone", "redstone_block", 9, "redstone_block", "redstone"),
            // 9 x resin_clump -> 1 x resin_block
            new Edge("resin_clump", "resin_block", 9, "resin_block", "resin_clump"),
            // 9 x slime_ball -> 1 x slime_block
            new Edge("slime_ball", "slime_block", 9, "slime_block", "slime_ball"),
            // 9 x wheat -> 1 x hay_block
            new Edge("wheat", "hay_block", 9, "hay_block", "wheat"),

            // ---- 只能向上合成（1 拆 9 在原版里不存在） ----
            // 9 x bamboo -> 1 x bamboo_block
            new Edge("bamboo", "bamboo_block", 9, "bamboo_block", null),
            // 9 x disc_fragment_5 -> 1 x music_disc_5
            new Edge("disc_fragment_5", "music_disc_5", 9, "music_disc_5", null),
            // 9 x ice -> 1 x packed_ice
            new Edge("ice", "packed_ice", 9, "packed_ice", null),
            // 9 x melon_slice -> 1 x melon
            new Edge("melon_slice", "melon", 9, "melon", null),
            // 9 x nether_wart -> 1 x nether_wart_block
            new Edge("nether_wart", "nether_wart_block", 9, "nether_wart_block", null),
            // 9 x packed_ice -> 1 x blue_ice
            new Edge("packed_ice", "blue_ice", 9, "blue_ice", null),
            // 9 x prismarine_shard -> 1 x prismarine_bricks
            new Edge("prismarine_shard", "prismarine_bricks", 9, "prismarine_bricks", null),
            // 9 x sulfur -> 1 x potent_sulfur
            new Edge("sulfur", "potent_sulfur", 9, "potent_sulfur", null),
            // 4 x prismarine_shard -> 1 x prismarine（原版 2x2，全表唯一的 4:1；
            // 原版没有「1 个海晶石 → 4 个碎片」的配方，所以只能向上合成，不能拆）
            new Edge("prismarine_shard", "prismarine", 4, "prismarine", null),

            // ---- 只能向下拆分（9 合 1 在原版里不存在） ----
            // 3 x bone_meal <- 1 x bone（1 个骨头 → 3 个骨粉）
            //
            // ⚠️ 原版<b>没有</b>「3 骨粉 → 1 骨头」这条配方，所以 combineRecipe 必须留 null。
            // 一旦写成非 null，骨头就会变成「可兑」条目，骨粉就能凭空换出骨头 ——
            // 而界面上完全看不出来（只会看到骨头那格显示「可兑 N」）。
            // 这条边的比例是 3 而不是 9，也是全表唯一一条 3:1；引擎里没有写死任何比例。
            new Edge("bone_meal", "bone", 3, null, "bone_meal"),
            // 9 x copper_ingot -> 1 x waxed_copper_block
            new Edge("copper_ingot", "waxed_copper_block", 9, null, "copper_ingot_from_waxed_copper_block"));

    // ------------------------------------------------------------------ 多原料配方

    /**
     * 一个多原料配方里的一项：{@code count} 个 {@code itemId}。
     *
     * <p>和 {@link Edge} 的区别是：一条边只描述「一种原料 N 个 → 1 个成品」，
     * 而多原料配方可以有几种不同的原料（例如海晶灯要 5 个碎片 + 4 个晶体）。
     * 这类配方<b>不能</b>折成若干个单边再叠加 —— 折出来的中间物在原版里并不存在，
     * 那样算等于凭空造物。</p>
     */
    public record Part(String itemId, int count) {
        public Part {
            if (itemId == null || itemId.isEmpty()) {
                throw new IllegalArgumentException("配方原料的物品 id 不能为空");
            }
            if (count < 1) {
                throw new IllegalArgumentException("配方原料 " + itemId + " 的数量至少是 1，收到 " + count);
            }
        }
    }

    /**
     * 一个多原料配方：{@code Σ part.count 个 part.item → 1 个 output}。
     *
     * <p><b>只有合成方向</b>：原版对这类配方没有「拆回去」的配方（海晶灯拆不回碎片和晶体），
     * 所以这里没有 {@code splitRecipe} 这个字段 —— 少一个字段就少一处「以为能拆」的误解。</p>
     *
     * <p>配方 id 同样只用于启动复核与文档追溯，运行期算法不看它。</p>
     */
    public record MultiRecipe(String output, List<Part> inputs, String recipeId) {
        public MultiRecipe {
            if (output == null || output.isEmpty()) {
                throw new IllegalArgumentException("配方成品 id 不能为空");
            }
            if (inputs == null || inputs.size() < 2) {
                throw new IllegalArgumentException(
                        "多原料配方 " + output + " 至少要两种原料；只有一种请用 Edge");
            }
            if (recipeId == null || recipeId.isEmpty()) {
                throw new IllegalArgumentException("多原料配方 " + output + " 必须有配方 id");
            }
            Set<String> seen = new HashSet<>();
            for (Part part : inputs) {
                if (part.itemId().equals(output)) {
                    throw new IllegalArgumentException(
                            "多原料配方 " + output + " 的原料里不能有它自己");
                }
                if (!seen.add(part.itemId())) {
                    throw new IllegalArgumentException(
                            "多原料配方 " + output + " 的同一种原料出现了两次：" + part.itemId());
                }
            }
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * 全部多原料配方（目前<b>只有 1 条</b>：海晶灯）。
     *
     * <p>为什么单独立一张表而不是硬编码进 {@link #drain}：硬编码等于给这件物品开后门，
     * 而「它凭什么特殊」写不进数据里 —— 下一条多原料配方又得再改一遍算法。
     * 现在引擎只认「一组原料 → 1 个成品」这个形状，表里加一条就能用。</p>
     *
     * <p><b>为什么收海晶灯</b>：它是玩家点名要的，而且原料都是<b>不可合成</b>的原始材料
     * （海晶碎片、海晶棱镜），不存在「用别的物品折算出来」的空间。
     * 表里其它多原料配方（例如 8 碎片 + 1 黑色染料 → 暗海晶石）都带染料或带工具，
     * 换算成价值毫无意义，所以仍然不收。</p>
     */
    public static final List<MultiRecipe> MULTI_RECIPES = List.of(
            // 5 x prismarine_shard + 4 x prismarine_crystals -> 1 x sea_lantern
            // 原版 3×3 的 SCS/CCC/SCS；没有拆解配方，所以只能合成
            new MultiRecipe("sea_lantern",
                    List.of(new Part("prismarine_shard", 5), new Part("prismarine_crystals", 4)),
                    "sea_lantern"));

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
     * <p>正确做法是由库存自身推上界：<b>每一步兑换都守恒价值</b>（{@code count} 个下级 = 1 个上级），
     * 所以价值只会减少、不会增加，拿「本族库存的总价值」除以「一个目标物品的价值」，
     * 就是再怎么折腾也超不过去的数量。</p>
     *
     * <h2>为什么是「上界」而不是精确值</h2>
     * <p>价值守恒只说明上界<b>不会被突破</b>，不说明<b>够得着</b>。剩下这一种情况会让上界偏大：
     * <b>不够再换的零头</b>（整除法向下取整，零头算不进任何一整件）。</p>
     * <p>所以这个数有两个用途：给界面显示「这一格现在能兑多少个」，以及给
     * {@link #planMax} 当<b>往下试探的起点</b> —— 真正的答案由 {@code planMax} 收敛到
     * 「确实执行得出来」的最大值。上界偏大是安全的（最多多跑几次注定失败的规划），
     * 偏小才是 bug。</p>
     *
     * <h2>⚠️ 上界必须紧，否则会把 TPS 吃掉（1.0.11 玩家实测）</h2>
     * <p>早先的写法把<b>整个族</b>的库存都算进来，于是上界经常离谱地大，而 {@code planMax}
     * 是「从上界往下逐个试」—— 白试的次数就等于「上界 − 真值」。玩家仓库里有点规模之后，
     * 一次重算要几百毫秒，界面上又每次改动都重算，TPS 直接塌。</p>
     * <p>现在只数<b>真能变成目标</b>的那部分库存（见 {@link Table#suppliersOf}）：</p>
     * <ul>
     *   <li>单向边反方向的东西不算 —— 9 个浮冰拆不回冰，那 81 点价值对「取冰」毫无帮助；</li>
     *   <li>多原料配方的<b>每种原料各自是个硬顶</b> —— 没有海晶棱镜时，碎片再多也兑不出海晶灯，
     *       不必从上界一路试到 1 才发现。要 1 个成品就得凑齐 {@code count} 个该原料，
     *       所以「该原料自己的上界 ÷ count」是铁上界。</li>
     * </ul>
     * <p>这两条都只是<b>把上界往下压</b>，压的依据仍然只有「价值守恒 + 边有没有这个方向」，
     * 所以不会把一个本来拿得到的数量压掉。</p>
     *
     * <h2>拿来当限制还有个副作用是好事</h2>
     * <p>一点相关料都没有时上界是 0，调用方可以<b>直接跳过</b>，不用跑一次注定失败的规划 ——
     * 界面每次刷可兑条目要问 56 个物品，这一下省掉的是绝大多数。</p>
     *
     * @return 上界（没有任何能变成它的库存时为 {@code 0}）；{@code itemId} 不在表里也返回 {@code 0}
     */
    public static BigInteger maxObtainable(Table table, String itemId, Stock stock) {
        return maxObtainable(table, itemId, stock, 0);
    }

    /** 递归深度上限：多原料配方目前只有一层，留 4 层足够并且能防止将来手滑写出环 */
    private static final int MAX_RECIPE_DEPTH = 4;

    private static BigInteger maxObtainable(Table table, String itemId, Stock stock, int depth) {
        if (table == null || itemId == null || !table.contains(itemId)) {
            return ZERO;
        }
        Set<String> suppliers = table.suppliersOf(itemId);
        BigInteger unit = table.valueOf(itemId);
        if (suppliers.isEmpty() || unit.signum() <= 0) {
            return ZERO;
        }
        // 只有「真能变成它」的库存才算数（同族但方向上不可达的不算，见方法注释）。
        // 同一族里所有物品的价值都基于同一个尺度（Table 建表时已放大成整数），
        // 所以直接 Σ(库存 × 价值) 再除「一个目标的那个值」，不需要再折什么基准层。
        BigInteger value = ZERO;
        for (String id : suppliers) {
            BigInteger have = orZero(stock.amountOf(id));
            if (have.signum() > 0) {
                value = value.add(have.multiply(table.valueOf(id)));
            }
        }
        BigInteger bound = value.divide(unit);

        // 多原料配方：每种原料各自是硬顶。多条配方时取「最好的那条」（max），
        // 单条配方时就是「最缺的那种原料」（min）—— 把上界压到真实可及的量级。
        List<MultiRecipe> recipes = table.recipesOf(itemId);
        if (!recipes.isEmpty() && depth < MAX_RECIPE_DEPTH) {
            BigInteger best = ZERO;
            for (MultiRecipe recipe : recipes) {
                BigInteger tightest = null;
                for (Part part : recipe.inputs()) {
                    BigInteger partBound = maxObtainable(table, part.itemId(), stock, depth + 1)
                            .divide(countOf(part));
                    tightest = tightest == null ? partBound : tightest.min(partBound);
                }
                if (tightest != null && tightest.compareTo(best) > 0) {
                    best = tightest;
                }
            }
            bound = bound.min(best);
        }
        return bound;
    }

    /**
     * 「<b>不拆上级</b>的话最多能补出多少个 {@code item}」的<b>安全上界</b>。
     *
     * <p>用途只有一个：给 {@link #drain} 第 ③ 条路的「往下少拆一块」循环一个停下来的理由 ——
     * 少拆一块，零头就多 {@code count} 个；零头一旦超过这个上界就再也补不上，剩下的块数不必再试，
     * 否则「9 个铜块取 64」这种明摆着做不通的请求会白试 7 次。</p>
     *
     * <p>口径：只数<b>价值不高于 {@code item}</b> 的族内库存。这是有依据的，不是随手切的阈值：
     * 拆零只会沿「价值变小」的方向发生，所以「不拆上级」这一路上能碰到的东西，
     * 要么在目标及其以下，要么是<b>等价</b>（例如目标是铜块时，涂蜡铜块能拆出铜锭、
     * 而它和铜块一样值 9 个铜锭），再往上一档就必须拆上级了 —— 那正是这条路不许做的事。</p>
     *
     * <p>⚠️ <b>改 {@link #EDGES} 时要重核这一条</b>：过滤条件成立的前提是
     * 「不存在『目标等价且能拆出下级、自身又能被再上层拆出来』的物品」。
     * 本表实测满足（涂蜡铜块是最接近的一个，但没有任何物品能拆出它）；
     * 真加了这样的边，这个上界就会偏小、也就可能把一个可行的零头补法掐掉 ——
     * 那种情况必须把这个过滤放宽（哪怕牺牲一点循环次数）。</p>
     *
     * <p>和 {@link #maxObtainable} 一样是<b>上界而不是精确值</b>（不同分支的料未必凑得到一起），
     * 但方向相反：这里<b>偏大安全</b> —— 最多多试几次注定失败的规划，绝不把一条可行的路掐掉。</p>
     */
    private static BigInteger coverBound(Table table, String itemId, State state) {
        Set<String> family = table.familyOf(itemId);
        BigInteger unit = table.valueOf(itemId);
        if (family.isEmpty() || unit.signum() <= 0) {
            return ZERO;
        }
        BigInteger value = ZERO;
        for (String id : family) {
            if (table.valueOf(id).compareTo(unit) > 0) {
                // 比目标更「大」的物品：用它就得拆上级，这条路不许做的事，直接不计
                continue;
            }
            BigInteger have = orZero(state.stock.get(id));
            if (have.signum() > 0) {
                value = value.add(have.multiply(table.valueOf(id)));
            }
        }
        return value.divide(unit);
    }

    // ------------------------------------------------------------------ 递归取数

    /**
     * 让仓库的账目<b>正好少掉 {@code need} 个 {@code item}</b>。
     *
     * <p>三条路按「目标本身 → 下级合成 → 上级拆零」的优先级依次试；
     * ②③ 都在状态副本上试，失败整支丢弃（{@link State#copy()}）。</p>
     *
     * <p><b>第 ③ 条路只拆「用得完的整块」</b>：拆的上级个数从 {@code floor(need / count)}
     * <b>逐块往下试</b>（上级不够就少拆一块、把更多零头留给下级），差的零头交给
     * {@link #drainWithoutSplitting} 用下级补。这样做是为了让
     * <b>一次取货不改变仓库的构成形态</b> —— 细节与踩坑见该分支里的注释。
     * 唯一的例外是 {@code need < count}（左键「取 1 个」），那时退回「拆 1 块 + 找零」。</p>
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

            // ② 用下级合成：count 个 Y 换 1 个 item（合成出来的直接给玩家，不进仓库账）
            for (Edge maker : table.makersOf(item)) {
                State branch = state.copy();
                if (drain(table, maker.small(), need.multiply(countOf(maker)), branch, depth + 1)) {
                    state.adopt(branch);
                    return true;
                }
            }

            // ②b 多原料配方：<b>几种原料一样都不能少</b>（例如 5 海晶碎片 + 4 海晶棱镜 → 1 海晶灯）。
            // 这正是玩家对海晶灯的要求「只能使用原料兑换」：不能拿海晶石 / 海晶石砖去折算，
            // 哪怕它们在价值上刚好等价 —— 原版里根本没有「3 个海晶石 → 1 个海晶灯」这种配方。
            // 每种原料各自递归取，任何一项凑不出来就整支作废（分支副本保证仓库没被改）。
            for (MultiRecipe recipe : table.recipesOf(item)) {
                State branch = state.copy();
                boolean all = true;
                for (Part part : recipe.inputs()) {
                    if (!drain(table, part.itemId(), need.multiply(countOf(part)), branch, depth + 1)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
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
            // 现在的规则：块数从 floor(need / count) 起、<b>逐块往下退</b>，只拆用得完的整块；
            // 剩下不足 count 的那一点点交给「目标库存 + 下级合成」去补
            // （仓库 9 个铜块 → 右键「取一组」给 63 = 拆 7 块，仓库剩 2 块，零头为 0）。
            // 补不上就让这一支失败，由 {@link #planMax} 从真上界往下收敛。
            //
            // 为什么块数要<b>往下枚举</b>，而不是只试 floor(need / count) 这一个：
            // 上级不一定够。仓库里 1 个铜块 + 100 个铜粒，要 20 个铜锭 ——
            // floor(20/9) = 2 块（只有 1 块，这一支判失败），可正确答案是
            // 「拆 1 块（9 个）+ 铜粒合 11 个」，零头仍然为 0。
            // 少拆一块、把更多零头交给下级，是一条完全合法的路，必须被枚举到。
            //
            // 唯一例外是 need < count（左键「取 1 个」）：floor 是 0，一块都取不出来，
            // 那时退回「拆 1 块 + 找零」—— 这正是玩家要的「拆上级找零」，必须保留。
            BigInteger cover = coverBound(table, item, state);
            for (Edge splitter : table.splittersOf(item)) {
                BigInteger count = countOf(splitter);
                BigInteger maxUnits = need.divide(count);
                for (BigInteger units = maxUnits.signum() <= 0 ? ONE : maxUnits;
                        units.signum() > 0;
                        units = units.subtract(ONE)) {
                    BigInteger rest = need.subtract(units.multiply(count));
                    if (rest.signum() > 0 && rest.compareTo(cover) > 0) {
                        // 少拆一块，零头就多 count 个，只会更补不上 —— 这条支路整体作废
                        break;
                    }
                    State branch = state.copy();
                    if (!drain(table, splitter.big(), units, branch, depth + 1)) {
                        continue;
                    }
                    if (rest.signum() < 0) {
                        // need < count：拆多了，把用不完的作为「找零」退回仓库（玩家明确要的能力）
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
     * <p>用途单一：{@link #drain} 的第 ③ 条路按「整块」拆完之后，还差不足 {@code count} 的一点点
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
        for (Edge maker : table.makersOf(item)) {
            State branch = state.copy();
            if (drain(table, maker.small(), need.multiply(countOf(maker)), branch, depth + 1)) {
                state.adopt(branch);
                return true;
            }
        }
        // ②b 多原料配方（口径同 {@link #drain}：几种原料一样都不能少）
        for (MultiRecipe recipe : table.recipesOf(item)) {
            State branch = state.copy();
            boolean all = true;
            for (Part part : recipe.inputs()) {
                if (!drain(table, part.itemId(), need.multiply(countOf(part)), branch, depth + 1)) {
                    all = false;
                    break;
                }
            }
            if (all) {
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

    /** 边上的比例（{@code count}）转成 BigInteger，免得每处都写 {@code BigInteger.valueOf(…)} */
    private static BigInteger countOf(Edge edge) {
        return BigInteger.valueOf(edge.count());
    }

    /** 多原料配方里某一项的数量转成 BigInteger */
    private static BigInteger countOf(Part part) {
        return BigInteger.valueOf(part.count());
    }

    // ------------------------------------------------------------------ 图

    /**
     * 复核过的一张兑换图（单原料边 + 多原料配方）。
     *
     * <p>除了原始的表，还会算出每个物品的<b>价值</b>（{@link #valueOf}）：
     * 沿边传播「{@code count 个 small = 1 个 big}」，所以 {@code value(big) = count × value(small)}；
     * 多原料配方则是 {@code value(output) = Σ count × value(原料)}。
     * 价值只有<b>同一族内</b>才有比较意义（不同族之间没有关系，比值无意义）。</p>
     *
     * <p>价值是<b>校验过的</b>：如果绕着某个环回到同一件物品却算不出同一个价值，
     * 说明表里有矛盾（比如某条边的方向和其它边打架），那条边会被丢掉并在
     * {@link #dropped()} / {@link #droppedRecipes()} 里留痕 —— 自检会把「丢了几条」断言成 0。</p>
     *
     * <p><b>为什么不再是「层级」（{@code 9^level}）</b>：一旦表里同时有 9:1 与 4:1 两条边，
     * 「层级」就分不出价值了 —— 海晶石与海晶石砖同属「离碎片一条边」，但一个是 4 个碎片、
     * 一个是 9 个碎片。层级只能表达「差几跳」，价值才能表达「值多少」，而守恒式要的是后者。</p>
     */
    public static final class Table {
        private final List<Edge> edges;
        private final List<MultiRecipe> recipes;
        private final Set<String> items;
        /**
         * 「能变成它」的物品集合（含自己），按生产方向算的反向闭包。
         *
         * <p>用途只有一个：{@link #maxObtainable} 只能数<b>这部分</b>库存。
         * 少算就会把上界压到真值以下（少给东西），所以它必须覆盖所有<b>可能的</b>来路，
         * 哪怕那条来路此刻凑不齐 —— 覆盖多了只是上界偏大，覆盖少了才是 bug。</p>
         */
        private final Map<String, Set<String>> suppliers;
        private final Map<String, List<Edge>> makers;
        private final Map<String, List<Edge>> splitters;
        private final Map<String, List<MultiRecipe>> recipeMakers;
        private final Map<String, BigInteger> values;
        private final Map<String, Set<String>> families;
        private final List<Edge> dropped;
        private final List<MultiRecipe> droppedRecipes;

        private Table(List<Edge> edges, List<MultiRecipe> recipes, Set<String> items,
                      Map<String, Set<String>> suppliers, Map<String, List<Edge>> makers,
                      Map<String, List<Edge>> splitters,
                      Map<String, List<MultiRecipe>> recipeMakers, Map<String, BigInteger> values,
                      Map<String, Set<String>> families, List<Edge> dropped,
                      List<MultiRecipe> droppedRecipes) {
            this.edges = List.copyOf(edges);
            this.recipes = List.copyOf(recipes);
            this.items = Set.copyOf(items);
            this.suppliers = suppliers;
            this.makers = makers;
            this.splitters = splitters;
            this.recipeMakers = recipeMakers;
            this.values = values;
            this.families = families;
            this.dropped = List.copyOf(dropped);
            this.droppedRecipes = List.copyOf(droppedRecipes);
        }

        /**
         * 无向传播用的一条邻接：从 {@code node} 走到 {@code peer}。
         *
         * <p>{@code up == true} 表示这是「{@code count} 个 node → 1 个 peer」，价值要<b>乘</b> {@code count}；
         * {@code up == false} 表示「{@code count} 个 peer → 1 个 node」，价值要<b>除</b> {@code count}。</p>
         */
        private record Link(String peer, BigInteger count, boolean up) {
        }

        /**
         * 传播过程中的<b>分数</b>价值。
         *
         * <p>为什么要分数：BFS 的起点是族内字典序最小的物品，它可能是个<b>大件</b>
         * （例如 {@code blue_ice} 排在 {@code ice} 前面），往下走一步就成了 1/9、1/81。
         * 用整数硬算会立刻截断成 0。所以传播期用分数，等整族算完再把公共分母乘掉，
         * 得到的整数<b>同族同尺度</b>，比值的意义完全不变。</p>
         */
        private record Frac(BigInteger num, BigInteger den) {
            static final Frac ONE = new Frac(BigInteger.ONE, BigInteger.ONE);

            Frac times(BigInteger k) {
                return new Frac(this.num.multiply(k), this.den);
            }

            Frac over(BigInteger k) {
                return new Frac(this.num, this.den.multiply(k));
            }
        }

        /** 分数放大成整数用的公共分母（{@code lcm(a, b) = a / gcd(a, b) * b}） */
        private static BigInteger lcm(BigInteger a, BigInteger b) {
            return a.divide(a.gcd(b)).multiply(b);
        }

        /**
         * 从一组边建表（多原料配方默认取 {@link #MULTI_RECIPES}）。
         */
        public static Table of(Iterable<Edge> source) {
            return of(source, MULTI_RECIPES);
        }

        /**
         * 从一组边 + 一组多原料配方建表。
         *
         * <h2>价值怎么标定（两步，顺序不能反）</h2>
         * <ol>
         *   <li><b>先按单原料边各自归一</b>：把每条边看成「{@code value(big) = count × value(small)}」，
         *       从每个连通块里挑一个节点当 1，沿边推下去（走向 big 乘 count、走向 small 除 count，
         *       除法在传播期用 {@link Frac} 兜着），整块再乘上公共分母变成整数。
         *       这一步和上一版<b>一字不差</b>，所以老物品的价值一个都没变；</li>
         *   <li><b>再算多原料配方</b>：{@code value(output) = Σ count × value(原料)}。
         *       原料的价值来自第 1 步（或更早算出来的另一个多原料配方），所以这里要迭代到不动点。</li>
         * </ol>
         * <p>为什么不能一步到位：多原料配方是<b>加法</b>约束，单原料边是<b>乘法</b>约束，
         * 混在一个 BFS 里会互相污染。分开做还有一个好处 —— 每块单边的尺度只由它自己决定
         * （族内最小值 = 1），于是「一块原始原料算 1 个价值」这条口径天然成立：
         * 海晶碎片在海晶族里是 1，海晶棱镜自己一块也是 1，海晶灯 = 5 + 4 = 9。</p>
         *
         * <p>若某条边推出来的价值和已有值打架，说明表里自相矛盾，这条边<b>直接丢掉</b>
         * （不参与建图），并在 {@link #dropped()} 留痕；多原料配方同理进 {@link #droppedRecipes()}。
         * 正常情况（本表已实测过）两者都是 0 条 —— 丢东西只是「万一以后有人手改表改错了」的兜底：
         * <b>宁可少一条兑换路径，也绝不允许一条自相矛盾的关系被执行</b>。</p>
         */
        public static Table of(Iterable<Edge> source, Iterable<MultiRecipe> recipes) {
            List<Edge> all = new ArrayList<>();
            for (Edge edge : source) {
                all.add(edge);
            }
            all.sort((a, b) -> {
                int bySmall = a.small().compareTo(b.small());
                return bySmall != 0 ? bySmall : a.big().compareTo(b.big());
            });
            List<MultiRecipe> allRecipes = new ArrayList<>();
            for (MultiRecipe recipe : recipes) {
                allRecipes.add(recipe);
            }
            allRecipes.sort((a, b) -> a.recipeId().compareTo(b.recipeId()));

            // ---- 第 1 步：单原料边按连通块归一 ----
            Map<String, List<Link>> adjacency = new HashMap<>();
            for (Edge edge : all) {
                BigInteger count = countOf(edge);
                adjacency.computeIfAbsent(edge.small(), key -> new ArrayList<>(2))
                        .add(new Link(edge.big(), count, true));
                adjacency.computeIfAbsent(edge.big(), key -> new ArrayList<>(2))
                        .add(new Link(edge.small(), count, false));
            }

            Map<String, Frac> frac = new HashMap<>();
            Map<String, BigInteger> values = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            // 根节点的选取顺序用字典序固定下来，保证同一张表每次建出来的价值完全一致
            for (String root : new TreeSet<>(adjacency.keySet())) {
                if (frac.containsKey(root)) {
                    continue;
                }
                frac.put(root, Frac.ONE);
                Set<String> members = new TreeSet<>();
                members.add(root);
                queue.add(root);
                while (!queue.isEmpty()) {
                    String node = queue.poll();
                    Frac base = frac.get(node);
                    for (Link link : adjacency.get(node)) {
                        members.add(link.peer());
                        if (!frac.containsKey(link.peer())) {
                            frac.put(link.peer(), link.up() ? base.times(link.count()) : base.over(link.count()));
                            queue.add(link.peer());
                        }
                    }
                }
                // 整块乘上公共分母 → 全整数，且块内比值一个不变
                BigInteger scale = BigInteger.ONE;
                for (String member : members) {
                    scale = lcm(scale, frac.get(member).den());
                }
                for (String member : members) {
                    Frac f = frac.get(member);
                    values.put(member, f.num().multiply(scale.divide(f.den())));
                }
            }

            // ---- 第 2 步：多原料配方的价值 = 原料价值之和（迭代到不动点）----
            //
            // 先给「只出现在多原料配方里的原料」定个基准：它们没有任何单原料边
            // （海晶棱镜只能靠打怪 / 挖矿拿到，原版没有任何配方能做它），
            // 所以第 1 步压根没碰过它们。这类物品按<b>一块算 1 个价值</b> ——
            // 这和「海晶碎片在它自己那族里价值是 1」是同一条口径，
            // 于是海晶灯 = 5 × 1 + 4 × 1 = 9 个原始原料，正是配方本身的样子。
            Set<String> produced = new HashSet<>();
            for (Edge edge : all) {
                produced.add(edge.big());
            }
            for (MultiRecipe recipe : allRecipes) {
                produced.add(recipe.output());
            }
            for (MultiRecipe recipe : allRecipes) {
                for (Part part : recipe.inputs()) {
                    if (!values.containsKey(part.itemId()) && !produced.contains(part.itemId())) {
                        values.put(part.itemId(), BigInteger.ONE);
                    }
                }
            }
            for (boolean progress = true; progress; ) {
                progress = false;
                for (MultiRecipe recipe : allRecipes) {
                    if (values.containsKey(recipe.output())) {
                        continue;
                    }
                    BigInteger sum = ZERO;
                    boolean complete = true;
                    for (Part part : recipe.inputs()) {
                        BigInteger input = values.get(part.itemId());
                        if (input == null) {
                            complete = false;
                            break;
                        }
                        sum = sum.add(input.multiply(countOf(part)));
                    }
                    if (complete) {
                        values.put(recipe.output(), sum);
                        progress = true;
                    }
                }
            }

            // ---- 族：单原料边 + 多原料配方都算「连在一起」----
            // 族是价值换算的作用域：只有同族的库存才可能被兑换用到，见 maxObtainable。
            // 多原料配方必须把原料和成品并进同一族，否则「5 碎片 + 4 晶体」的库存会被算漏。
            Map<String, String> parent = new HashMap<>();
            for (String item : adjacency.keySet()) {
                parent.put(item, item);
            }
            for (Edge edge : all) {
                union(parent, edge.small(), edge.big());
            }
            for (MultiRecipe recipe : allRecipes) {
                parent.putIfAbsent(recipe.output(), recipe.output());
                for (Part part : recipe.inputs()) {
                    union(parent, recipe.output(), part.itemId());
                }
            }
            Map<String, Set<String>> families = new HashMap<>();
            Map<String, Set<String>> byRoot = new HashMap<>();
            for (String item : new TreeSet<>(parent.keySet())) {
                byRoot.computeIfAbsent(find(parent, item), key -> new TreeSet<>()).add(item);
            }
            for (Set<String> members : byRoot.values()) {
                Set<String> frozen = Set.copyOf(members);
                for (String member : members) {
                    families.put(member, frozen);
                }
            }

            // ---- 校验：矛盾的关系一律丢掉（宁可少一条路，也不允许它被执行）----
            List<Edge> kept = new ArrayList<>();
            List<Edge> dropped = new ArrayList<>();
            for (Edge edge : all) {
                BigInteger small = values.getOrDefault(edge.small(), ZERO);
                BigInteger expected = small.multiply(countOf(edge));
                if (values.getOrDefault(edge.big(), ZERO).equals(expected)) {
                    kept.add(edge);
                } else {
                    dropped.add(edge);
                }
            }
            List<MultiRecipe> keptRecipes = new ArrayList<>();
            List<MultiRecipe> droppedRecipes = new ArrayList<>();
            for (MultiRecipe recipe : allRecipes) {
                BigInteger sum = ZERO;
                for (Part part : recipe.inputs()) {
                    sum = sum.add(values.getOrDefault(part.itemId(), ZERO).multiply(countOf(part)));
                }
                // sum 为 0 说明有原料根本没算过价值（表里缺东西），这种配方绝不能留
                if (sum.signum() > 0 && values.getOrDefault(recipe.output(), ZERO).equals(sum)) {
                    keptRecipes.add(recipe);
                } else {
                    droppedRecipes.add(recipe);
                }
            }

            Map<String, List<Edge>> makers = new HashMap<>();
            Map<String, List<Edge>> splitters = new HashMap<>();
            Map<String, List<MultiRecipe>> recipeMakers = new HashMap<>();
            Set<String> items = new LinkedHashSet<>();
            for (Edge edge : kept) {
                items.add(edge.small());
                items.add(edge.big());
                if (edge.canCombine()) {
                    makers.computeIfAbsent(edge.big(), key -> new ArrayList<>(2)).add(edge);
                }
                if (edge.canSplit()) {
                    splitters.computeIfAbsent(edge.small(), key -> new ArrayList<>(2)).add(edge);
                }
            }
            for (MultiRecipe recipe : keptRecipes) {
                items.add(recipe.output());
                for (Part part : recipe.inputs()) {
                    items.add(part.itemId());
                }
                recipeMakers.computeIfAbsent(recipe.output(), key -> new ArrayList<>(2)).add(recipe);
            }

            // ---- 「谁能变成它」的反向闭包（上界只许数这部分库存，见 maxObtainable）----
            // 直接关系按<b>生产方向</b>算：原料 → 成品。
            //   合成边：small → big；拆解边：big → small（只有这条边允许拆时才算）；
            //   多原料配方：每种原料 → 成品（多原料要齐，但闭包只关心「能不能算进价值」，
            //   凑不齐的情况由上界偏大来兜，不影响安全性）。
            Map<String, Set<String>> directInputs = new HashMap<>();
            for (Edge edge : kept) {
                if (edge.canCombine()) {
                    directInputs.computeIfAbsent(edge.big(), key -> new LinkedHashSet<>(2))
                            .add(edge.small());
                }
                if (edge.canSplit()) {
                    directInputs.computeIfAbsent(edge.small(), key -> new LinkedHashSet<>(2))
                            .add(edge.big());
                }
            }
            for (MultiRecipe recipe : keptRecipes) {
                Set<String> inputs = directInputs.computeIfAbsent(recipe.output(),
                        key -> new LinkedHashSet<>(4));
                for (Part part : recipe.inputs()) {
                    inputs.add(part.itemId());
                }
            }
            Map<String, Set<String>> suppliers = new HashMap<>();
            for (String item : items) {
                Set<String> reachable = new LinkedHashSet<>();
                Deque<String> pending = new ArrayDeque<>();
                reachable.add(item);
                pending.add(item);
                while (!pending.isEmpty()) {
                    String cursor = pending.poll();
                    for (String input : directInputs.getOrDefault(cursor, Set.of())) {
                        if (reachable.add(input)) {
                            pending.add(input);
                        }
                    }
                }
                suppliers.put(item, Set.copyOf(reachable));
            }
            // 下级原料按「价值从高到低」（离目标最近的最先用）；拆零来源按「价值从低到高」
            // （拆最小的那个够用就行）。都补一层字典序当第二关键字，保证结果可复现。
            for (List<Edge> list : makers.values()) {
                list.sort((a, b) -> {
                    int byValue = values.getOrDefault(b.small(), ZERO)
                            .compareTo(values.getOrDefault(a.small(), ZERO));
                    return byValue != 0 ? byValue : a.small().compareTo(b.small());
                });
            }
            for (List<Edge> list : splitters.values()) {
                list.sort((a, b) -> {
                    int byValue = values.getOrDefault(a.big(), ZERO)
                            .compareTo(values.getOrDefault(b.big(), ZERO));
                    return byValue != 0 ? byValue : a.big().compareTo(b.big());
                });
            }
            return new Table(kept, keptRecipes, items, suppliers, makers, splitters, recipeMakers,
                    values, families, dropped, droppedRecipes);
        }

        /** 并查集：把两个物品并进同一族（用于「原料和成品算不算一伙的」） */
        private static String find(Map<String, String> parent, String item) {
            String root = item;
            while (!parent.get(root).equals(root)) {
                root = parent.get(root);
            }
            // 路径压缩：这一族的链最多两三跳，压缩一次就够
            String cursor = item;
            while (!parent.get(cursor).equals(root)) {
                String next = parent.get(cursor);
                parent.put(cursor, root);
                cursor = next;
            }
            return root;
        }

        private static void union(Map<String, String> parent, String a, String b) {
            parent.putIfAbsent(a, a);
            parent.putIfAbsent(b, b);
            String rootA = find(parent, a);
            String rootB = find(parent, b);
            if (!rootA.equals(rootB)) {
                parent.put(rootB, rootA);
            }
        }

        /** 表里的全部物品 id（已排序，方便快照与展示） */
        public Set<String> items() {
            return new TreeSet<>(this.items);
        }

        /** 生效的边 */
        public List<Edge> edges() {
            return this.edges;
        }

        /** 生效的多原料配方 */
        public List<MultiRecipe> recipes() {
            return this.recipes;
        }

        /** 因自相矛盾被丢掉的边（正常永远是空） */
        public List<Edge> dropped() {
            return this.dropped;
        }

        /** 因自相矛盾被丢掉的多原料配方（正常永远是空） */
        public List<MultiRecipe> droppedRecipes() {
            return this.droppedRecipes;
        }

        /** 价值（同一族内才有比较意义；不在表里返回 0） */
        public BigInteger valueOf(String itemId) {
            return this.values.getOrDefault(itemId, ZERO);
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

        /** 能合成出 {@code itemId} 的那些边（{@code edge.big() == itemId}，按「离目标最近」优先） */
        public List<Edge> makersOf(String itemId) {
            return this.makers.getOrDefault(itemId, List.of());
        }

        /** 拆开能给出 {@code itemId} 的那些边（{@code edge.small() == itemId}，按「价值最低」优先） */
        public List<Edge> splittersOf(String itemId) {
            return this.splitters.getOrDefault(itemId, List.of());
        }

        /** 能做出 {@code itemId} 的那些多原料配方（{@code recipe.output() == itemId}） */
        public List<MultiRecipe> recipesOf(String itemId) {
            return this.recipeMakers.getOrDefault(itemId, List.of());
        }

        /**
         * 能变成 {@code itemId} 的物品集合（含自己），按<b>生产方向</b>算的反向闭包。
         *
         * <p>例：</p>
         * <ul>
         *   <li>{@code prismarine_shard}（碎片）：只有它自己 —— 海晶石 / 海晶石砖都拆不回碎片
         *       （原版没有那条配方），所以它们那点价值对「取碎片」一点用都没有；</li>
         *   <li>{@code sea_lantern}（海晶灯）：自己 + 碎片 + 晶体（多原料配方把三种连在一起）。</li>
         * </ul>
         *
         * <p>用途：{@link #maxObtainable} 只数这部分库存。这是<b>把上界压紧</b>的关键 ——
         * 早先按「整个族」数，玩家仓库一大，上界就离谱地大，而 {@link #planMax} 是从上界
         * <b>往下逐个试</b>，白试次数等于「上界 − 真值」，一次界面重算要几百毫秒（1.0.11 实测）。</p>
         *
         * <p>⚠️ 覆盖范围只许多、不许少：多算一样东西只是上界偏大（安全），
         * 少算一样就会把能拿到的数量压掉（少给东西）。所以多原料配方里<b>每种原料都算进来</b>，
         * 哪怕它们此刻凑不齐。</p>
         */
        public Set<String> suppliersOf(String itemId) {
            return this.suppliers.getOrDefault(itemId, Set.of());
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
