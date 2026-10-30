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

    /**
     * 「配方扣料」：仓库里没有成品时，用<b>玩家亲手合成过的那条配方</b>扣原料，直接把成品取出来。
     *
     * <p>打开后，玩家在合成台（含背包 2×2）里合成成功一次，这次合成用到的原料与产物就被
     * <b>记进配方本</b>。此后从虚空仓库取这件物品时，如果成品本身没货，就直接按记下的配方
     * 扣掉仓库里的原料，把成品给玩家 —— 等价于替玩家做掉这一步合成。</p>
     *
     * <h2>与 {@link #voidWarehouseExchange}（9 合 1）的分工</h2>
     * <p>9 合 1 只能做「原版那 47 条 9:1 / 4:1 的换算」（铁粒↔铁锭、铜块↔铜锭……），
     * 因为它的表是静态写死的、必须有价值体系才能防刷。而本规则面对的是<b>任意配方</b>
     * （活塞、漏斗、侦测器……），那些配方没有「价值守恒」可言，也无法预先列举 ——
     * 所以这里换一条完全不同、也简单得多的路：<b>照玩家真做过的那一次，原样扣料</b>。</p>
     *
     * <p><b>只做一层</b>：配方里的原料必须仓库里现成，不会再问「这个原料能不能由别的原料做出来」。
     * 想做两层得引入递归与状态副本，那正是需要上界与探针的那套机器（9 合 1 已经承担了）。
     * 本规则刻意不碰，所以它是 O(原料种类数) 的一次算术，没有搜索、没有预算、没有递归。</p>
     *
     * <p><b>配方本是全服共用的一份</b>，和仓库本身一样（虚空仓库就是全服一个）。
     * 也就是说「有人在这台服务器上合成过活塞」，全服的人就都能从仓库直接取活塞。
     * 这一条是刻意的：仓库是共用的，材料也是共用的，把配方按人头分开会让
     * 「仓库里明明有材料、却因为不是自己合成过而取不出来」变成一件没道理的事。</p>
     *
     * <p><b>默认关</b>：它和 9 合 1 一样会改变材料的实际消耗口径，属于服主需要主动知情的授权。
     * 依赖 {@link #voidWarehouse}：主开关关了，仓库界面本身就打不开。</p>
     */
    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean voidWarehouseCraft = false;
}
