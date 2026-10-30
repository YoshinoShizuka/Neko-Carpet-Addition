package neko.voidwarehouse;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把「玩家手上真实的合成格」翻译成 {@link VoidWarehouseRecipes.Recipe}，并负责物品 id 的双向映射。
 *
 * <h2>为什么从合成格取原料，而不是去解析 {@code RecipeManager}</h2>
 * <p>「玩家合成过什么」这件事的权威来源就是<b>他刚才摆进格子里的那些东西</b>。
 * 去 {@code RecipeManager} 里反查那条配方、再展开它的 {@code Ingredient}，能拿到的信息
 * 反而更少、更不确定：</p>
 * <ul>
 *   <li>配方的原料写着<b>标签</b>（{@code #minecraft:planks}），展开成 12 种木板后
 *       「要扣哪一种」是没有答案的 —— 而合成格里有答案（他就用了橡木）；</li>
 *   <li>{@code Ingredient} / {@code RecipeHolder} / {@code CraftingInput} 这一套在
 *       五个目标版本里改过名、改过签名（1.21.x 与 26.x 差别不小），逐版本对一遍
 *       属于「为了拿一个信息，引入五个版本各一份的失配风险」；</li>
 *   <li>合成格里摆的是<b>已经发生过的事实</b>，不需要任何解释，也不受数据包重载影响。</li>
 * </ul>
 * <p>代价是：原料被「具体化」了 —— 用橡木木板合成过的活塞，之后也只认橡木木板
 * （不会认云杉木板）。这是有意接受的：玩家真做过的那一次就是这样，
 * 而「认所有木板」需要标签支持，那是另一种语义，要单独设计（见 {@code Part} 的注释，
 * 数据结构已经支持多候选，只是本版本不往里填）。</p>
 *
 * <h2>一格算一个（关键）</h2>
 * <p>合成格的每一格只消耗 <b>1 个</b>物品，<b>与该格的堆叠数量无关</b>：
 * 格里放着一组 64 个橡木木板，合成一次也只吃掉 1 个。
 * 所以这里的计数是「<b>有几格放着这种物品</b>」，<b>不是</b>「这些格里一共多少个」。
 * 这一点写错就会变成「合成一次活塞扣 64 个木板」，而且只会在玩家格里放着整组时才犯。</p>
 */
public final class VoidWarehouseRecipeBook {

    private VoidWarehouseRecipeBook() {
    }

    /**
     * 配方本最多记多少条。
     *
     * <p>编码后的文本按内容去重，所以条数的天然上限就是「这台服务器上真被合成过的不同配方数」。
     * 原版大约 1500 条，加上模组也就几千 —— 512 是一个偏保守的界：
     * 正常情况下永远撞不到，撞到了说明配方本在被人拿奇怪的方式灌，
     * 那就停止记录（而不是让存档无限长大）。</p>
     */
    public static final int MAX_RECIPES = 512;

    // ------------------------------------------------------------------ 物品 id 映射

    /**
     * {@link Item} → <b>带命名空间的</b>物品 id（{@code "minecraft:iron_ingot"}）；解析不出来返回 {@code null}。
     *
     * <p>与 9 合 1 的 {@link VoidWarehouseExchangeBootstrap#idOf(Item)} <b>不是同一套口径</b>，
     * 这一点很关键：那边只认 {@code minecraft:} 命名空间、只留 path，因为兑换表描述的全是原版配方，
     * 把 {@code mymod:iron_ingot} 当成铁锭拿去兑换是错的。而配方本记的是「玩家实际合成过的东西」，
     * 模组物品一样算数 —— 玩家用模组材料合成的东西，凭什么不许从仓库取？
     * 所以这里保留完整命名空间，两套 id 各自独立、互不干扰。</p>
     */
    public static String idOf(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    /** 带命名空间的物品 id → {@link Item}；这个版本里没有这个物品时返回 {@code null}（不拿 AIR 糊弄） */
    public static Item itemOf(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item found = BuiltInRegistries.ITEM.getValue(id);
        return found == null || found == Items.AIR ? null : found;
    }

    // ------------------------------------------------------------------ 从合成格解析

    /**
     * 问配方系统「这一格摆法一次能做出什么」。
     *
     * <h2>为什么不用 {@code onTake} 收到的那个堆叠</h2>
     * <p>直觉上「取合成产物」时回调会拿到那件产物，拿它的 {@code getCount()} 当「一次产出几个」
     * 就行。但核对 26.3 的字节码之后发现<b>那个参数在两条路径上含义完全不同</b>：</p>
     * <ul>
     *   <li><b>单击取出</b>：传的是 {@code slot.getItem()}，正好是一次合成的产物；</li>
     *   <li><b>Shift 快速移动</b>：{@code CraftingMenu#quickMoveStack} 先把产物<b>搬进背包、
     *       把槽位里的堆叠就地减掉</b>，再把<b>搬剩的余量</b>传进来 —— 全搬完时传的是
     *       {@code ItemStack.EMPTY}，只搬进去一部分时传的是零头。</li>
     * </ul>
     * <p>也就是说这个参数既可能是 4，也可能是 2（背包快满），还可能是空气。
     * 拿它当产出数会直接算错账，而这个字段是<b>乘法因子</b>：产出数记大了，
     * 「取 64 个木板」会算成只做 1 次合成 —— 那是凭空造物。</p>
     *
     * <h2>正确来源</h2>
     * <p>直接问 {@code RecipeManager}：拿合成格的内容去匹配，把匹配到的配方
     * {@code assemble}（26.x）出来。这是<b>唯一权威</b>的口径 —— 原版自己在每次
     * 合成格变化时做的就是这一次查询（{@code CraftingMenu#slotsChanged}），
     * 所以这里不多花一分钱，也不依赖任何一个「恰好传进来」的参数。</p>
     *
     * <p>{@link CustomRecipe}（原版叫 special recipe：染色、烟火、地图缩放、成书复制……）
     * <b>一律不学</b>：它们的产物带数据组件，本身就不可复制 ——
     * 「学会用皮革胸甲 + 染料换一件红皮甲」之后，仓库给的是一张白板皮甲，
     * 那是坑玩家。这类配方要么产物是 {@code EMPTY}，要么学会了也没用，直接跳过最省事。</p>
     *
     * @return 一次合成的产物；匹配不到 / 是 special recipe / 产物为空时返回
     *         {@link ItemStack#EMPTY}
     */
    public static ItemStack resultOf(MinecraftServer server, Level level, CraftingInput input) {
        if (server == null || level == null || input == null || input.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Optional<RecipeHolder<CraftingRecipe>> found =
                server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (found.isEmpty()) {
            return ItemStack.EMPTY;
        }
        CraftingRecipe recipe = found.get().value();
        if (recipe instanceof CustomRecipe) {
            return ItemStack.EMPTY;
        }
        try {
            ItemStack result = recipe.assemble(input, level.registryAccess());
            return result == null || result.isEmpty() || result.getCount() <= 0
                    ? ItemStack.EMPTY
                    : result;
        } catch (RuntimeException ex) {
            // 配方实现（尤其是模组的）抛异常时绝不能把整次合成搞崩 —— 这里只是「不记这条」。
            return ItemStack.EMPTY;
        }
    }

    /**
     * 把一次成功合成的<b>合成格内容 + 权威产物</b>翻译成一条配方；任何一项读不出来就返回 {@code null}。
     *
     * <p>调用时机是 {@code ResultSlot#onTake} 的<b>最开头</b> —— 那时合成格<b>还没被清空</b>
     * （清空发生在 {@code onTake} 内部，已核对 26.3 字节码）。晚一步就只能记到一张空网格。</p>
     *
     * <p>返回 {@code null} 的几种情形，都只是「这次不记」，不算错误：</p>
     * <ul>
     *   <li>合成格是空的（理论上到不了，防一手）；</li>
     *   <li>产物是空气 / 数量为 0（见 {@link #resultOf}）；</li>
     *   <li>格里有物品在注册表里查不到 id（模组已卸载的残留），记下来也会变成一条永远扣不动的幽灵配方。</li>
     * </ul>
     *
     * <p><b>不处理 {@code getRemainingItems}</b>：像蛋糕那样「牛奶桶用掉又还回空桶」的配方，
     * 这里按「扣掉 1 个桶」记账，不会退回空桶。这类配方极少，而且口径偏保守
     * （多扣不少扣），不会造成刷物；真要精确处理得逐版本对 {@code RecipeManager} 的内部结构，
     * 与本类开头列的三条理由相冲突。</p>
     *
     * @param grid   合成格里的全部堆叠（{@code CraftingContainer#getItems()}）
     * @param result 这一次合成的<b>权威产物</b>（{@link #resultOf} 给出，决定产物 id 与一次产出几个）
     */
    public static VoidWarehouseRecipes.Recipe fromGrid(List<ItemStack> grid, ItemStack result) {
        if (grid == null || result == null || result.isEmpty() || result.getCount() <= 0) {
            return null;
        }
        String outputId = idOf(result.getItem());
        if (outputId == null) {
            return null;
        }

        // 按「有几格放着这种物品」计数 —— 不是按这些格里的总数（见类注释「一格算一个」）。
        // LinkedHashMap 保序：配方里原料的先后顺序在同一个合成格里是稳定的，
        // 于是同一次合成永远编出同一串文本，天然去重。
        Map<String, Integer> cells = new LinkedHashMap<>();
        for (ItemStack stack : grid) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = idOf(stack.getItem());
            if (id == null) {
                // 有一格读不出来就整条放弃：少记一条配方，好过记下一条永远扣不动的
                return null;
            }
            cells.merge(id, 1, Integer::sum);
        }
        if (cells.isEmpty()) {
            return null;
        }

        List<VoidWarehouseRecipes.Part> parts = new ArrayList<>(cells.size());
        for (Map.Entry<String, Integer> entry : cells.entrySet()) {
            // 单候选：就是这个玩家刚才用的那个具体物品（多候选的语义见 Part 的注释）
            parts.add(new VoidWarehouseRecipes.Part(List.of(entry.getKey()), entry.getValue()));
        }
        try {
            // recipeId 传 null：由构造函数按内容生成，于是「同一条配方学两次」在集合里天然只有一条
            return new VoidWarehouseRecipes.Recipe(null, outputId, result.getCount(), parts);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
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
}
