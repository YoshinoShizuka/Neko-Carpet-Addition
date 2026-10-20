package neko.voidwarehouse;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把 {@link VoidWarehouseExchange} 里那张<b>纯字符串的静态表</b>绑到当前版本真实的物品与配方上。
 *
 * <h2>为什么需要这一层</h2>
 * <p>静态表是在 Minecraft 26.3 的全量配方上采集的（47 条单原料边 + 1 条多原料配方 / 88 个物品），
 * 是<b>26.x 的超集</b>。但本模组还编译到 1.21.x：那些版本里既可能没有某些物品
 * （硫磺、树脂是后来才加的），也可能没有某些配方。如果不复核就照单执行，
 * 结果就是「点一下说换了，实际什么也没发生」，甚至更糟 —— 凭空变出物品。</p>
 *
 * <p>所以启动时做两件事，<b>两件都是「按事实收紧」而不是「按版本号写死」</b>：</p>
 * <ol>
 *   <li><b>物品存在性</b>：{@link BuiltInRegistries#ITEM} 里查不到这个 id，这条边作废；</li>
 *   <li><b>配方存在性</b>：拿表里记着的<b>配方 id</b>去 {@code RecipeManager} 里查。
 *       两个方向各自独立判定 —— 某个版本只剩「向上合成」而丢了「向下拆分」时，
 *       只把那半个方向砍掉，另一条路照常可用。多原料配方（{@link VoidWarehouseExchange.MultiRecipe}）
 *       只有合成方向，物品与配方 id 都在才留下。</li>
 * </ol>
 *
 * <h2>为什么用「配方 id 是否存在」而不是「重新解析配方内容」</h2>
 * <p>重新扫一遍配方、再把「N 个相同物品 → 1 个产物」的模式推一遍，能交叉验证得更彻底，
 * 但代价是要把 {@code ShapedRecipe} / {@code ShapelessRecipe} / {@code Ingredient} 的
 * 内部结构逐版本对一遍 —— 那正是最容易随版本改名而失配的地方，为了一个启动检查引入这种风险不划算。</p>
 *
 * <p>而且「模式本身对不对」在<b>开发期已经被更严的方式验过了</b>：用脚本直接读
 * {@code RO26.3.jar} 里 2042 个配方 JSON 全量扫描，并且自检里还有一条
 * 「把表里每条边按它自己的比例还原成物品、交给真实配方系统去匹配」的断言。
 * 启动期这一层只需要拦住「这个版本没有这条配方」这一类差异，用 id 查表就够了。</p>
 *
 * <h2>何时执行</h2>
 * <p>配方是<b>跟着数据包重载</b>的，服务器构造完成时还不一定有。
 * 所以不挂在 {@code onGameStarted}（那时配方表可能是空的），而是第一次 tick 时
 * 惰性执行一次；数据包重载（{@code /reload}）之后作废重做。</p>
 */
public final class VoidWarehouseExchangeBootstrap {

    private VoidWarehouseExchangeBootstrap() {
    }

    /** 物品 id → Item 的缓存。{@code Items.AIR} 是「这个版本里没有这个物品」的记号。 */
    private static final Map<String, Item> RESOLVED = new HashMap<>();

    /** 表已经绑到哪个服务器上了；换服务器 / 重载后要重做 */
    private static volatile MinecraftServer boundServer;

    /** 最近一次绑定的结果（自检断言用） */
    private static volatile Summary summary = new Summary(0, 0, 0, 0, 0, 0, List.of());

    /**
     * 一次绑定的结果。
     *
     * @param declared    静态表里声明了多少条边（26.3 版本是 47）
     * @param kept        这个版本真正生效了多少条
     * @param dropped     整条作废了几条（物品不存在，或两个方向的配方都不存在）
     * @param downgraded  保留但砍掉了半个方向的条数
     * @param recipesDeclared 多原料配方声明了几条（26.3 是 1 条：海晶灯）
     * @param recipesKept     这个版本真正生效了几条多原料配方
     * @param notes       诊断信息（每条作废 / 降级一行），只有真丢了东西时才非空
     */
    public record Summary(int declared, int kept, int dropped, int downgraded,
                          int recipesDeclared, int recipesKept, List<String> notes) {
        public Summary {
            notes = List.copyOf(notes);
        }
    }

    // ------------------------------------------------------------------ 物品映射

    /**
     * 物品 id（如 {@code "iron_nugget"}，不带命名空间）→ 这个版本里的 {@link Item}。
     *
     * <p>这个版本里没有这个物品时返回 {@code null}（不会抛异常、也不会拿 AIR 糊弄）。</p>
     */
    public static Item itemOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        Item cached = RESOLVED.get(itemId);
        if (cached != null) {
            return cached == Items.AIR ? null : cached;
        }
        Item resolved = null;
        Identifier id = Identifier.withDefaultNamespace(itemId);
        if (BuiltInRegistries.ITEM.containsKey(id)) {
            Item found = BuiltInRegistries.ITEM.getValue(id);
            if (found != null && found != Items.AIR) {
                resolved = found;
            }
        }
        RESOLVED.put(itemId, resolved == null ? Items.AIR : resolved);
        return resolved;
    }

    /**
     * {@link Item} → 物品 id（不含命名空间）；<b>非原版命名空间的物品一律返回 {@code null}</b>。
     *
     * <p>为什么要卡命名空间：兑换表只描述原版那 47 条配方，别的模组自己加一个
     * {@code mymod:iron_ingot} 不该被当成铁锭拿去兑换 —— 那是两个完全不同的物品。</p>
     */
    public static String idOf(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null || !Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        return id.getPath();
    }

    // ------------------------------------------------------------------ 绑定

    /**
     * 每个 tick 调一次。<b>只有一个 volatile 引用比较</b>，绑好之后就是纯开销为零的空转。
     *
     * <p>惰性而不是「启动时一次」的原因见类注释：配方表跟着数据包重载，
     * 服务器构造完成的那一刻还不一定读好了。</p>
     */
    public static void tick(MinecraftServer server) {
        if (server == null || boundServer == server) {
            return;
        }
        bootstrap(server);
    }

    /** 数据包重载后作废，让下一次 tick 重新绑一遍 */
    public static void invalidate() {
        boundServer = null;
    }

    /**
     * 用当前服务器的真实物品表 + 配方表复核静态表，并把结果设为生效表。
     *
     * <p>幂等：同一个服务器重复调用只是重做一次同样的计算，结果完全一致。</p>
     */
    public static Summary bootstrap(MinecraftServer server) {
        Set<String> recipeIds = new HashSet<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (holder != null && holder.id() != null && holder.id().identifier() != null) {
                recipeIds.add(holder.id().identifier().toString());
            }
        }

        List<VoidWarehouseExchange.Edge> keptEdges = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int dropped = 0;
        int downgraded = 0;

        for (VoidWarehouseExchange.Edge edge : VoidWarehouseExchange.EDGES) {
            if (itemOf(edge.small()) == null || itemOf(edge.big()) == null) {
                dropped++;
                notes.add("作废（物品不存在）：" + edge.small() + " / " + edge.big());
                continue;
            }
            String combine = exists(recipeIds, edge.combineRecipe()) ? edge.combineRecipe() : null;
            String split = exists(recipeIds, edge.splitRecipe()) ? edge.splitRecipe() : null;
            if (combine == null && split == null) {
                dropped++;
                notes.add("作废（配方不存在）：" + edge.small() + " / " + edge.big());
                continue;
            }
            if (!Objects.equals(combine, edge.combineRecipe()) || !Objects.equals(split, edge.splitRecipe())) {
                downgraded++;
                notes.add("降级为单向：" + edge.small() + " / " + edge.big()
                        + (combine == null ? "（缺合成方向）" : "（缺拆分方向）"));
            }
            keptEdges.add(new VoidWarehouseExchange.Edge(
                    edge.small(), edge.big(), edge.count(), combine, split));
        }

        // 多原料配方同样按事实收紧：物品都在、配方 id 也在，才留下
        List<VoidWarehouseExchange.MultiRecipe> keptRecipes = new ArrayList<>();
        for (VoidWarehouseExchange.MultiRecipe recipe : VoidWarehouseExchange.MULTI_RECIPES) {
            boolean itemsOk = itemOf(recipe.output()) != null;
            for (VoidWarehouseExchange.Part part : recipe.inputs()) {
                itemsOk &= itemOf(part.itemId()) != null;
            }
            if (!itemsOk) {
                notes.add("作废多原料配方（物品不存在）：" + recipe.output());
                continue;
            }
            if (!exists(recipeIds, recipe.recipeId())) {
                notes.add("作废多原料配方（配方不存在）：" + recipe.output());
                continue;
            }
            keptRecipes.add(recipe);
        }

        VoidWarehouseExchange.Table table = VoidWarehouseExchange.Table.of(keptEdges, keptRecipes);
        VoidWarehouseExchange.setActive(table);

        if (!table.dropped().isEmpty() || !table.droppedRecipes().isEmpty()) {
            notes.add("价值自相矛盾被丢弃：" + table.dropped().size() + " 条边 / "
                    + table.droppedRecipes().size() + " 条多原料配方（这是表写错了，请检查）");
        }

        Summary built = new Summary(VoidWarehouseExchange.EDGES.size(), table.edges().size(),
                dropped, downgraded, VoidWarehouseExchange.MULTI_RECIPES.size(),
                table.recipes().size(), notes);
        boundServer = server;
        summary = built;
        log(built);
        return built;
    }

    /** 最近一次绑定的结果 */
    public static Summary summary() {
        return summary;
    }

    private static boolean exists(Set<String> recipeIds, String recipePath) {
        return recipePath != null && recipeIds.contains(Identifier.withDefaultNamespace(recipePath).toString());
    }

    /**
     * 把结果打到日志里。
     *
     * <p><b>全绿时不刷屏</b>：只在「丢东西了」的时候列出逐条详情 ——
     * 正常服主不需要知道内部有 47 条边，但一旦某条边因为版本差异没生效，
     * 他必须能从日志里看出是哪一条、为什么。</p>
     */
    private static void log(Summary summary) {
        StringBuilder line = new StringBuilder("[虚空仓库] 兑换表：");
        line.append("生效 ").append(summary.kept()).append(" 条 / 声明 ").append(summary.declared()).append(" 条");
        line.append("；多原料配方 ").append(summary.recipesKept())
                .append(" / ").append(summary.recipesDeclared()).append(" 条");
        if (summary.downgraded() > 0) {
            line.append("，降级 ").append(summary.downgraded()).append(" 条");
        }
        if (summary.dropped() > 0) {
            line.append("，作废 ").append(summary.dropped()).append(" 条");
        }
        System.out.println(line);
        for (String note : summary.notes()) {
            System.out.println("[虚空仓库]   · " + note);
        }
    }
}
