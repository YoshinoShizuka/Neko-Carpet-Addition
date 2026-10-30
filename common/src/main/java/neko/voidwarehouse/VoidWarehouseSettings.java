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
     * <p><b>能往下钻几层</b>：从 1.0.16 起，原料不够时会顺着<b>别的已学配方</b>继续往下钻
     * （最多 4 层）。比如取活塞要 3 个木板、木板是 0 个但仓库里有原木，就扣原木做木板
     * —— 这正是玩家在合成台上会做的事，仓库替他把这一步做掉。
     * 钻的层数、展开的节点数都有硬上限，撞环一律保守失败（宁可取不到，也不许算出做不出来的账）。</p>
     *
     * <p>它<b>不会</b>凭空出现玩家没学过的东西：往下钻只在<b>配方本里已有的配方</b>之间发生。
     * 也就是说「仓库能替你做什么」完全由「你在合成台上真做过什么」决定。</p>
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

    /**
     * 「方块掉落物直接进虚空仓库」：玩家挖掉方块的那些掉落物，不再掉在地上，直接入库。
     *
     * <p>灵感来自 <a href="https://github.com/fcsailboat/Carpet-Org-Addition">Carpet-Org-Addition</a>
     * 的 {@code blockDropsDirectlyEnterInventory}，但去处从「玩家背包」改成了「虚空仓库」。
     * 规则名<b>故意不同</b>：Carpet 的 {@code SettingsManager#addCarpetRule} 遇重名会直接抛异常，
     * 沿用原名意味着「两个模组同装 = 服务端起不来」。</p>
     *
     * <h2>收哪些</h2>
     * <p>只认「<b>玩家挖掉一个方块</b>时产生的那批物品」—— 判据是这些掉落物是不是在
     * {@code ServerPlayerGameMode#destroyBlock} 这次调用里被创建出来的。
     * 所以苦力怕炸掉的一地、怪物死亡掉的东西、玩家自己扔出去的东西<b>都不受影响</b>，
     * 照原样留在世界里；经验球也不是物品，不参与。</p>
     *
     * <h2>收不进去怎么办</h2>
     * <p>入库不是「吞掉」：仓库读不出来（比如存档闸门拦下）、规则刚好被关掉、
     * 物品为空，这几种情况下<b>一律照原样掉在地上</b> —— 失败时绝不静默消失。
     * 潜影盒走的是与漏斗入库完全相同的那条路（<b>拆盒</b>：盒内物品逐件入库、盒子折成
     * 潜影壳 + 箱子、染色盒再还一份染料；空盒整盒入库），因为「进仓库」在别处就是这个语义，
     * 不值得为一条规则发明第二套。</p>
     *
     * <p><b>默认关</b>：它会改变「挖方块」这件事的产出归属（东西不再落在地上给旁边的人捡），
     * 属于服主需要主动知情的授权。依赖 {@link #voidWarehouse}：主开关关了就没有仓库可进。</p>
     */
    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean voidWarehouseBlockDrops = false;
}
