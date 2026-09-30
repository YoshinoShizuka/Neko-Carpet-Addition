package neko.voidwarehouse;

import net.minecraft.world.item.ItemStack;

/**
 * 「分类物」—— 漏斗里玩家摆出来的那几件物品。
 *
 * <p>本模组<b>两端共用同一套分类物规则</b>，规则集中在这一个类里：</p>
 * <ul>
 *   <li><b>出料端</b>（终端压在漏斗正上方，{@link VoidWarehouseExtractor}）：
 *       漏斗 5 格里已有的那件物品就是一条「出料品类」，仓库按它往同一个格子里补料；</li>
 *   <li><b>入库端</b>（漏斗口朝向终端，{@link VoidWarehouseHopper}）：
 *       玩家手动填进漏斗的 5 格的物品就是一份「入库白名单」，只有与之相同的物品
 *       才进得来、才存得进仓库。</li>
 * </ul>
 *
 * <h2>三条共用的约定</h2>
 * <ol>
 *   <li><b>分类物就是「漏斗格子里那件物品」</b>：没有额外的手续、没有隐藏登记表，
 *       玩家把东西放进漏斗格子，那就是分类物；拿出去了，分类就没了；</li>
 *   <li><b>余量 {@link #KEEP} 个</b>：每格物品的最后 {@code KEEP} 个永远不外流、不入库，
 *       于是「仓库被抽空 / 漏斗被搬空」时分类物一定还在，玩家不用重新摆一遍 ——
 *       出料端靠 {@link VoidWarehouseExtractor#KEEP_AS_CLASSIFIER} 实现，入库端靠
 *       {@link VoidWarehouseHopper#drain} 的 {@code keepClassifiers} 参数实现；</li>
 *   <li><b>匹配 = 物品 + 数据组件</b>（{@link ItemStack#isSameItemSameComponents}）：
 *       改过名的钻石剑不等于普通钻石剑，与仓库「一格一种形态」的模型一致，
 *       也与出料端的 {@link VoidWarehouseData#takeExact} 完全一致。</li>
 * </ol>
 */
public final class VoidWarehouseClassifier {
    /** 漏斗固定 5 格（与 {@code HopperBlockEntityMixin#HOPPER_SLOTS} 一致）。 */
    private static final int SLOTS = 5;

    /**
     * 每格至少要留住的个数 —— 这就是「余量」。
     *
     * <p>出料端与入库端共用同一个常量，改一处两端同时生效。
     * 改成 0 就等于「用完拉倒、分类物一并送走」，那样分类会自己消失、玩家得重新摆，
     * 不是我们要的行为。</p>
     */
    public static final int KEEP = 1;

    private VoidWarehouseClassifier() {
    }

    /**
     * 玩家有没有为这个漏斗指定过<b>任何</b>分类物（清单里任意一格非空）。
     *
     * <p>入库端的用法：一件都没有 = 「不存在分类物」= 回到原规则（见
     * {@link VoidWarehouseHopper#drain}）。</p>
     *
     * <p><b>看的是玩家那份清单，不是漏斗当前的内容</b> —— 这条区别是
     * {@code 橡木 石头 空 空 空} 那次事故的全部教训：反推漏斗内容会把任何
     * 恰好躺在里面的货物一并认成分类物。</p>
     */
    public static boolean hasAnySeed(VoidWarehouseHopperFilter filter) {
        for (int i = 0; i < SLOTS; i++) {
            if (!filter.getClassifierSeed(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code stack} 是不是玩家指定的分类物之一（物品 + 数据组件都要一致）。
     *
     * <p>入库端在「机器往漏斗里塞东西」的唯一关卡
     * （{@code HopperBlockEntity#canPlaceItemInContainer}）问这一句：
     * 问不过去的物品<b>根本进不来</b> —— 于是漏斗里永远不会攒下不认识的东西，
     * 白名单也就不会自己长大。</p>
     */
    public static boolean matchesAny(VoidWarehouseHopperFilter filter, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (int i = 0; i < SLOTS; i++) {
            ItemStack seed = filter.getClassifierSeed(i);
            if (!seed.isEmpty() && ItemStack.isSameItemSameComponents(seed, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code slot} 这一格是不是「{@code stack} 这件分类物<b>自己的格子</b>」。
     *
     * <h2>为什么必须有这一条（实测事故）</h2>
     * <p>光有 {@link #matchesAny} 只回答了「这物品在不在白名单里」，没回答
     * 「它该进哪一格」。而原版 {@code HopperBlockEntity#addItem} 是<b>按 slot 0→N
     * 逐个试</b>的（字节码已核对：一个 {@code for} 循环里对每个 slot 调
     * {@code tryMoveInItem}，直到物品被分光），{@code tryMoveInItem} 对<b>空格子</b>
     * 又是直接 {@code setItem} 整摞写入。</p>
     * <p>于是：漏斗 slot0 摆着 1 个橡木原木时，来一堆橡木原木 → 先合并进 slot0（补到 64），
     * <b>剩下那 1 个顺手开垦了 slot1</b>；上方容器货一多，5 格就被逐个开垦成
     * {@code 1 1 1 1 1}。手动填充看不出来，是因为玩家只写一次、只碰一格。</p>
     *
     * <h2>规则</h2>
     * <p>分类物<b>只许合并进玩家为它指定的那一格，不许开垦新格子</b>。
     * 于是「哪格是哪种」永远等于玩家摆出来的样子，白名单不会自己长大，
     * 玩家想加第二种分类物时那个格子也还空着。</p>
     *
     * <p>判据是<b>玩家那份清单在这一格写的是什么</b>，与漏斗当前装着什么无关 ——
     * 后者会把残留在漏斗里的货物也认成「这格的分类物」。</p>
     *
     * @param slot 机器正打算往哪一格塞（原版 {@code tryMoveInItem} 给的槽位号）
     */
    public static boolean isOwnSlot(VoidWarehouseHopperFilter filter, ItemStack stack, int slot) {
        if (stack.isEmpty() || slot < 0 || slot >= SLOTS) {
            return false;
        }
        ItemStack seed = filter.getClassifierSeed(slot);
        return !seed.isEmpty() && ItemStack.isSameItemSameComponents(seed, stack);
    }

    /**
     * <b>旧存档治愈（根因修复）</b>：种子清单上线之前写的旧存档，只存了一个 filter 开关、
     * 没有逐格种子，分类物是靠「反推漏斗当前内容」得到的。那种存档的漏斗里可能混着杂物
     * （事故现场 {@code 橡木 石头 空 空 空}），如果原样认领，石头会被永久当成分类物 ——
     * 玩家只摆了橡木，看到的却是 {@code 橡木 石头 空 空 空}，而且<b>以后每次加载都复现</b>
     * （污染被写进了永久的逐格种子）。
     *
     * <p>所以旧存档<b>一律不信任</b>：清掉所有种子、关掉过滤，让玩家重新手动指定。
     * 分类物只能由玩家的手来声明，这是硬规则，不能靠反推漏斗内容猜。</p>
     *
     * @param schemaOk 存档是否带新格式 schema 标记。带标记的（本轮之后写的）才信任其种子；
     *                 不带标记的一律按旧存档处理（清掉、关掉）。
     */
    public static void healLegacy(VoidWarehouseHopperFilter filter, boolean schemaOk) {
        if (schemaOk) {
            return;
        }
        for (int i = 0; i < 5; i++) {
            filter.setClassifierSeed(i, ItemStack.EMPTY);
        }
        filter.setClassifierFilter(false);
    }

}
