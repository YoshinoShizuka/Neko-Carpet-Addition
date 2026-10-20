package neko.voidwarehouse;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.SURVIVAL;

/**
 * 虚空仓库的 Carpet 规则声明。
 *
 * <p>字段名 {@code voidWarehouse} 就是规则名，对应 {@code /carpet voidWarehouse true|false}，
 * 翻译键为 {@code carpet.rule.voidWarehouse.name} / {@code .desc}。</p>
 *
 * <p><b>默认关</b>：本模组的全部规则都默认 {@code false}，由服主显式开启，
 * 避免装上就悄悄改变原版行为。op-only 限制由其它模组负责，本扩展不做额外权限判定。</p>
 */
public class VoidWarehouseSettings {
    /** 自定义分类名，与 AutoMending 共用同一个分类，会出现在 /carpet 的分类列表里 */
    public static final String NEKO = "neko";

    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean voidWarehouse = false;

    /**
     * 「9 合 1 兑换」：缺成品时用下级物品顶替。
     *
     * <p>打开后，从虚空仓库取一件物品时如果<b>成品本身没货</b>，可以拿它的 9 合 1 下级来换 ——
     * 比如仓库里只有 9 个铁粒、没有铁锭，取铁锭就直接扣掉那 9 个铁粒，
     * 等价于替玩家把合成步骤做掉。反过来也成立（「拆上级找零」）：
     * 只有 1 个铁块时取 1 个铁锭，就是拆开铁块、1 个给玩家、8 个退回仓库。</p>
     *
     * <p><b>成品有货时永远走原路，绝不做任何兑换</b> —— 这不是靠判断，而是算法结构上的保证：
     * 规划的第一步就是「直接扣成品库存」，只有扣不够才往下走。</p>
     *
     * <p>范围只限「原版真有这条配方」的边：26.3 一共 47 条（20 条双向 + 25 条只能向上合成 +
     * 2 条只能向下拆分），涉及 86 个物品。列表与方向写死在
     * {@link VoidWarehouseExchange#EDGES}，启动时用真实的配方表复核一遍，
     * 旧版本里不存在的边会被剔掉。</p>
     *
     * <p><b>默认关</b>：与 {@link #voidWarehouse} 一样，这条规则会改变材料的实际消耗口径，
     * 属于服主需要主动知情的授权，不由模组替服主默认打开。
     * 它<b>依赖</b> {@link #voidWarehouse}：主开关关了，仓库界面本身就打不开，
     * 这里一并视为关闭。</p>
     */
    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean voidWarehouseExchange = false;
}
