package neko.voidwarehouse;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

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
     * 这个漏斗里现在有没有分类物（任意一个非空格子）。
     *
     * <p>入库端的用法：一件都没有 = 「漏斗里不存在分类物」= 回到原规则（见
     * {@link VoidWarehouseHopper#drain}）。</p>
     */
    public static boolean hasAny(HopperBlockEntity hopper) {
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            if (!hopper.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 漏斗里有没有与 {@code stack} <b>相同</b>的分类物（物品 + 数据组件都要一致）。
     *
     * <p>入库端在「机器往漏斗里塞东西」的唯一关卡
     * （{@code HopperBlockEntity#canPlaceItemInContainer}）问这一句：
     * 问不过去的物品<b>根本进不来</b> —— 于是漏斗里永远不会攒下不认识的东西，
     * 白名单也就不会自己长大。</p>
     */
    public static boolean matchesAny(HopperBlockEntity hopper, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack resident = hopper.getItem(i);
            if (!resident.isEmpty() && ItemStack.isSameItemSameComponents(resident, stack)) {
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
     * <p>分类物<b>只许合并进已经放着同种分类物的格子，不许开垦新格子</b>。
     * 于是「哪格是哪种」永远等于玩家摆出来的样子，白名单不会自己长大，
     * 玩家想加第二种分类物时那个格子也还空着。</p>
     *
     * <p>注意这一条在语义上<b>已经蕴含</b>了 {@link #matchesAny}：格子里的东西与
     * {@code stack} 同种，那漏斗里当然存在这个分类物。两道检查都保留，
     * 是为了让「是不是白名单物品」与「该进哪一格」这两件事在代码里各自说清楚。</p>
     *
     * @param slot 机器正打算往哪一格塞（原版 {@code tryMoveInItem} 给的槽位号）
     */
    public static boolean isOwnSlot(HopperBlockEntity hopper, ItemStack stack, int slot) {
        if (stack.isEmpty() || slot < 0 || slot >= hopper.getContainerSize()) {
            return false;
        }
        ItemStack resident = hopper.getItem(slot);
        return !resident.isEmpty() && ItemStack.isSameItemSameComponents(resident, stack);
    }
}
