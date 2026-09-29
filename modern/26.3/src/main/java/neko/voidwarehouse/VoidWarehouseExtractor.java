package neko.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 虚空仓库 → 漏斗 的<b>出料</b>（与 {@link VoidWarehouseHopper} 的入库正好相反）。
 *
 * <h2>怎么摆</h2>
 * <pre>
 *   [工作台 / 仓库终端]   ← 终端压在漏斗<b>正上方</b>
 *        [漏斗]          ← 口朝下（或朝任意方向），下方接容器
 *      [箱子 / 熔炉…]
 * </pre>
 * <p>原版漏斗天生就会「从<b>上方</b>的容器里抽东西」，而工作台不是容器，
 * 所以原版在这里是空操作。本类补上的正是这一段：<b>把上方的仓库终端当成料源</b>，
 * 按漏斗自己的内容去仓库里取料。<br>
 * 反方向那条（漏斗口<b>朝向</b>终端 = 往仓库里灌）仍然走
 * {@link VoidWarehouseHopper}，两者靠「漏斗口朝哪」区分，不会打架：
 * 同一条漏斗两个条件都满足时<b>以入库为准</b>（见 {@link #isExtractor}）。</p>
 *
 * <h2>漏斗的 5 格 = 分类物</h2>
 * <p>漏斗里摆着什么，仓库就只出什么：每一格里已有的那件物品就是一条「出料品类」，
 * 仓库按这个品类把料补进<b>同一格</b>，一直补到该格满
 * （{@code min(该物品的堆叠上限, 容器上限)}，圆石就是 64）。</p>
 * <ul>
 *   <li><b>空格子什么都不出</b>：分类是玩家摆出来的，仓库不会替玩家挑品类，
 *       所以「我不摆分类物 = 这里不出料」，语义明确、没有意外；</li>
 *   <li><b>只出分类过的品类</b>：仓库里另外 1600 种物品一个都不会从这里流出去；</li>
 *   <li><b>匹配是「物品 + 数据组件」</b>（{@link VoidWarehouseData#takeExact}）：
 *       漏斗里那把改过名的钻石剑不会去匹配普通的钻石剑，与仓库「一格一种形态」的模型一致。</li>
 * </ul>
 * <p>补料<b>没有速率上限</b>：不踩原版「每 8 gt 搬 1 个」的节流，每个 tick 都会把格子补满，
 * 所以漏斗永远是个满仓的缓冲。往<b>下游容器</b>那一段仍是原版速率（每 8 gt 1 个）——
 * 虚空仓库这一端不再是瓶颈，瓶颈回到玩家自己搭的容器那一端。</p>
 *
 * <h2>分类物永不被抽走（本功能的硬约束）</h2>
 * <p>漏斗天生会把自己的东西往下游推，推干净了分类也就没了。所以在取出模式下
 * {@link #eject} 会<b>接管原版的出料动作</b>：每格物品的<b>最后 1 个</b>永远留在漏斗里。</p>
 * <ul>
 *   <li>仓库还有料时：格子被补满 → 下游按原版速率取用 → 格子始终 ≥ 2 → 正常出货；</li>
 *   <li>仓库的该物品<b>被抽空</b>时：格子停在那 1 个上（这就是「余量」），
 *       下游再也拿不走它 —— 于是<b>分类不会丢</b>，玩家也不用重新摆一遍。</li>
 * </ul>
 * <p>换句话说：<b>仓库的账可以归零，漏斗的分类物一定还剩 1 个</b>。</p>
 *
 * <h2>不丢东西 / 不多东西</h2>
 * <p>取料走的是仓库自己的 {@link VoidWarehouseData#takeExact}：仓库扣多少，漏斗就加多少，
 * 两者记的是同一笔账；下游容器放不下时，原样留在漏斗里（{@code addItem} 会退回剩余堆叠），
 * 既不会凭空消失、也不会因为「先加后扣」出现复制窗口。</p>
 *
 * <h2>红石语义不变</h2>
 * <p>被红石锁存（{@code ENABLED = false}）时，调用方根本不会进到这里：
 * 原版整个漏斗停摆，本功能也不会「无视红石往外倒料」。
 * 只有漏斗被原版激活时，它才会「从上方抽料」——这一条和原版漏斗的语义一致。</p>
 *
 * <h2>已知限制</h2>
 * <p>堆叠上限为 1 的物品（水桶、床、被拆过的工具…）当分类物时，格子本来就只能装 1 个，
 * 「补满」无从谈起，而最后 1 个又是分类物不能外流 —— 所以这类物品<b>出不了料</b>。
 * 需要它们时直接在界面上取，或改用能堆叠的物品当分类物。</p>
 */
public final class VoidWarehouseExtractor {
    /**
     * 每格至少要留住的个数。
     *
     * <p>这就是「余量」：不论仓库还有没有料，漏斗里每一种物品都至少剩这么多个，
     * 下游容器永远吸不走。改成 0 就等于「出完拉倒、分类物一并送走」，
     * 那样仓库一抽空分类就丢了，玩家得重新摆一遍 —— 不是我们要的行为。</p>
     *
     * <p>就是 {@link VoidWarehouseClassifier#KEEP}：<b>入库端的白名单也留同样多的余量</b>
     * （见 {@link VoidWarehouseHopper#drain} 的 {@code keepClassifiers}），
     * 两端共用同一个数，这里保留这个名字只是为了不改动已经写进文档的 API。</p>
     */
    public static final int KEEP_AS_CLASSIFIER = VoidWarehouseClassifier.KEEP;

    private VoidWarehouseExtractor() {
    }

    /**
     * 这个漏斗现在是不是一条「出料口」。
     *
     * <p>三个条件同时成立才是：</p>
     * <ol>
     *   <li>规则 {@code voidWarehouse} 开着；</li>
     *   <li><b>正上方</b>是工作台（{@link VoidWarehouseTerminals#isWorkbench}，不要求登记）；</li>
     *   <li>漏斗口<b>朝向</b>的方块<b>不是</b>工作台 —— 那条是入库。</li>
     * </ol>
     *
     * <p>第 3 条是优先级规则：一条漏斗同时「上方是工作台」且「口朝另一个工作台」时，
     * <b>入库优先</b>。这样本轮之前就搭好的机器行为一个字都不变，
     * 不会因为新功能突然开始往反方向倒料。</p>
     */
    public static boolean isExtractor(ServerLevel level, BlockPos pos, BlockState state) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!state.is(Blocks.HOPPER)) {
            // 理论上不会发生（只从漏斗自己的 tick / 出料里调用），防御性判断
            return false;
        }
        if (!VoidWarehouseTerminals.isWorkbench(level, pos.above())) {
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        return !VoidWarehouseTerminals.isWorkbench(level, pos.relative(facing));
    }

    /**
     * 按漏斗里的分类物，从仓库里<b>补料</b>。
     *
     * <p>每个 tick 调用一次（不踩原版冷却），把每个「有分类物的格子」补到上限。
     * 仓库里没有这个品类、或者格子本来就满了，都是无损的空操作。</p>
     *
     * @return 本次补进漏斗的<b>物品个数</b>（只用于自检 / 诊断，正式逻辑不依赖它）
     */
    public static int pull(ServerLevel level, HopperBlockEntity hopper) {
        VoidWarehouseData data = VoidWarehouse.data(level.getServer());
        int moved = 0;
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack sample = hopper.getItem(i);
            if (sample.isEmpty()) {
                // 空格子 = 没分类：仓库不替玩家挑品类
                continue;
            }
            int limit = Math.max(1, hopper.getMaxStackSize(sample));
            int room = limit - sample.getCount();
            if (room <= 0) {
                continue;
            }
            int taken = data.takeExact(sample, room);
            if (taken <= 0) {
                continue;
            }
            // 只加数量：分类物那件东西本身（含数据组件）原样不动，
            // 而且用 copyWithCount 造新实例，绝不把同一个 ItemStack 挂在两处（防复制）
            hopper.setItem(i, sample.copyWithCount(sample.getCount() + taken));
            moved += taken;
        }
        if (moved > 0) {
            hopper.setChanged();
        }
        return moved;
    }

    /**
     * 出料（接管原版 {@code ejectItems}）：把漏斗里的物品送进下游容器，
     * <b>但每格永远留下 {@link #KEEP_AS_CLASSIFIER} 个</b>。
     *
     * <p>原版的动作是「从头扫，谁非空就搬 1 个走」；这里的差别只有一处：
     * <b>数量 ≤ 1 的格子直接跳过</b>。所以</p>
     * <ul>
     *   <li>正常出货时：格子被 {@link #pull} 补满，跳过规则完全不生效，出料速率与原版一致
     *       （每次尝试搬 1 个、搬成功进 8 gt 冷却）；</li>
     *   <li>仓库抽空后：格子只剩分类物，跳过规则接管，下游容器<b>吸不走</b>它。</li>
     * </ul>
     *
     * <p>搬运用的是原版自己的 {@link HopperBlockEntity#addItem}，所以下游容器的
     * 各种限制（熔炉只收燃料、朝向过滤、堆叠上限…）全部照旧生效，一个都不绕过。</p>
     *
     * @return 是否真的搬走了 1 个（原版用这个返回值决定要不要进 8 gt 冷却）
     */
    public static boolean eject(ServerLevel level, BlockPos pos, HopperBlockEntity hopper) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof HopperBlock)) {
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        Container target = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
        if (target == null) {
            return false;
        }
        // 与原版一致的快筛：下游一个位置都放不下时直接返回，避免白跑 5 次 addItem
        Direction side = facing.getOpposite();
        if (isFullContainer(target, side)) {
            return false;
        }
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || stack.getCount() <= KEEP_AS_CLASSIFIER) {
                // 空格子没东西可搬；只剩分类物的格子**不许搬** —— 下游容器不能吸收分类物
                continue;
            }
            // 每次只搬 1 个，与原版速率一致
            ItemStack leftover = HopperBlockEntity.addItem(hopper, target, stack.copyWithCount(1), side);
            if (leftover.isEmpty()) {
                hopper.setItem(i, stack.copyWithCount(stack.getCount() - 1));
                target.setChanged();
                hopper.setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * 复刻原版 {@code HopperBlockEntity#isFullContainer}：这个容器现在一个位置都放不下吗。
     *
     * <p>原版这个方法在 {@code ejectItems} 开头做快筛，我们用自己的一套出料逻辑，
     * 所以也要有一份等价物，否则「下游塞满」的情况下每个 tick 都会白跑 5 次
     * {@code addItem}（每次都还要遍历目的容器的全部槽位）。</p>
     *
     * <p>{@link WorldlyContainer} 的侧面过滤（熔炉只从上面收原料、箱子四面都收…）
     * 走 {@code getSlotsForFace}，与原版同一套判断。</p>
     */
    private static boolean isFullContainer(Container container, Direction side) {
        if (container instanceof WorldlyContainer worldly) {
            int[] slots = worldly.getSlotsForFace(side);
            for (int slot : slots) {
                if (!isSlotFull(container, slot)) {
                    return false;
                }
            }
            return true;
        }
        int size = container.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            if (!isSlotFull(container, slot)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSlotFull(Container container, int slot) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
            return false;
        }
        return stack.getCount() >= Math.max(1, container.getMaxStackSize(stack));
    }
}
