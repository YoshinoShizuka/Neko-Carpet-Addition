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
}
