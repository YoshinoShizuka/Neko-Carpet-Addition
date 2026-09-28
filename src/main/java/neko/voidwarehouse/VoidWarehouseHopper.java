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
 * 漏斗 → 虚空仓库 的搬运。
 *
 * <h2>无速率上限</h2>
 * <p>漏斗口（{@code HopperBlock.FACING} 指向的那一面）对着仓库终端时，漏斗格子里
 * 的物品会被<b>一次性全部</b>搬进仓库：既不遵循原版「每 8 gt 才搬 1 个」的节流，
 * 也不受一组 64 个的限制 —— 漏斗里有多少就搬多少，<b>1 tick 搬空</b>。</p>
 *
 * <h2>哪几个面算数</h2>
 * <p>漏斗的朝向只有「下 / 东南西北」五种（原版 {@code FACING_HOPPER} 没有「上」），
 * 所以要往工作台里送东西，漏斗只能架在<b>上面</b>或<b>四个侧面</b>上 ——
 * 恰好就是需求里的「六个面除了底部」。底部那一面原版根本不存在朝上的漏斗，
 * 因此这一条是天然成立的，代码里不需要额外排除。</p>
 *
 * <h2>筛选一致</h2>
 * <p>任何非空物品（含带数据组件的受损工具 / 附魔 / 改名物品）都会按「同物品 + 同数据组件」
 * 入库（都走 {@link VoidWarehouseData#isInsertable}），仓库本身不再筛选 ——
 * 要不要放行某个物品，由玩家自己在漏斗里用分类物白名单过滤。<b>潜影盒是例外</b>：
 * 漏斗里的非空潜影盒会被同样的逻辑<b>拆开</b>入库（盒内物品按真实数量存，
 * 盒子变成 2 潜影壳 + 1 箱子）；空盒子则整盒入库，不拆。</p>
 *
 * <h2>只搬漏斗自己</h2>
 * <p>这里只动「漏斗格子里的物品」。漏斗上方容器 → 漏斗那一段仍走原版逻辑（原版速率），
 * 所以整条链的速度由漏斗的抽取速率决定，虚空仓库这一端不再是瓶颈。</p>
 *
 * <h2>不丢东西</h2>
 * <p>搬运走的是仓库自己的 {@code insert}：仓库收下多少、漏斗就少多少，两者记的是同一笔账；
 * 搬不进去（被拒收）的物品一动不动地留在漏斗里，不会凭空消失。</p>
 *
 * <h2>分类物白名单（本轮新增，与出料端复用同一套规则）</h2>
 * <p><b>玩家手动往漏斗里放东西，就是在声明分类物</b>
 * （痕迹记在 {@link VoidWarehouseHopperFilter} 那个开关上）。一旦声明过：</p>
 * <ul>
 *   <li><b>只有与分类物相同的物品才进得来</b>：机器搬运（上方容器吸料、邻居漏斗推料、
 *       掉落物被吸入）都要先过 {@link VoidWarehouseClassifier#matchesAny} 这一关，
 *       不匹配的物品<b>原封不动留在上游</b>（不是丢在漏斗里堆着）；</li>
 *   <li><b>只有它们才存得进仓库</b>：{@code drain} 只搬「与分类物相同」的东西
 *       —— 其实漏斗里也只剩得下这些东西了；</li>
 *   <li><b>每格留 {@link VoidWarehouseClassifier#KEEP} 个当分类物</b>
 *       （{@code keepClassifiers} 参数，与出料端的「余量」是同一个常量）：
 *       分类物不会因为「搬空」而消失，仓库抽空、服务器重启之后白名单照样在；</li>
 *   <li><b>把漏斗里的东西全部拿走 → 自动回到原规则</b>（开关跟着关掉）：
 *       此时漏斗里没有任何分类物，<b>1 tick 搬空</b>的原始行为原样回来。</li>
 * </ul>
 * <p>没有声明过分类物的漏斗（也就是绝大多数自动化产线）<b>一个字节都不受影响</b>：
 * 开关默认关着，过滤与余量都不生效。</p>
 *
 * <h2>反方向</h2>
 * <p>把工作台终端压在漏斗<b>正上方</b>时方向反过来 —— 那是
 * {@link VoidWarehouseExtractor} 负责的「出料」（仓库按漏斗 5 格里的分类物发货）。
 * 两者靠「漏斗口朝哪」区分；一条漏斗两个条件都满足时<b>以入库为准</b>。</p>
 */
public final class VoidWarehouseHopper {
    private VoidWarehouseHopper() {
    }

    /**
     * 这个漏斗现在是不是一条「入库漏斗」：规则开着，而且口朝向的那个方块是仓库终端。
     *
     * <p>调用方（{@code HopperBlockEntityMixin} 的 tick 钩子、以及
     * {@code canPlaceItemInContainer} 上的分类物过滤）共用同一个判定，
     * 避免「tick 时算一次、过滤时又算一次」算出两种答案。</p>
     */
    public static boolean isIntake(ServerLevel level, BlockPos pos, BlockState state) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!state.is(Blocks.HOPPER)) {
            // 理论上不会发生（只从漏斗自己的 tick / 搬运里调用），防御性判断
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        return VoidWarehouseTerminals.isTerminal(level, pos.relative(facing));
    }

    /**
     * 把漏斗里所有能存的物品都搬进虚空仓库。
     *
     * @param keepClassifiers 是否保留分类物余量（{@code true} = 这个漏斗被玩家声明过分类物）。
     *                        为 {@code true} 时每个非空格子<b>至少留
     *                        {@link VoidWarehouseClassifier#KEEP} 个</b>不外流 ——
     *                        这就是「余量」，与出料端的手法是同一个。
     * @return 搬走的<b>格子个数</b>（潜影盒按「1 格」计，它拆出来的内容物不计入）。
     *         只用于自检 / 诊断，正式逻辑不依赖这个数。
     */
    public static int drain(ServerLevel level, HopperBlockEntity hopper, boolean keepClassifiers) {
        VoidWarehouseData data = VoidWarehouse.data(level.getServer());
        int moved = 0;
        boolean changed = false;
        int keep = keepClassifiers ? VoidWarehouseClassifier.KEEP : 0;
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || !VoidWarehouseData.isInsertable(stack)) {
                // 空格：原样留在漏斗里（带 NBT 的物品现在也放行，由玩家过滤）
                continue;
            }
            int count = stack.getCount();
            if (count <= keep) {
                // 只剩分类物的余量：留下那一份，它同时也继续当着分类物
                continue;
            }
            // keep == 0 时直接搬整格：insert 收下时会把 stack 就地清空，正好复用；
            // keep > 0 时搬的是副本，真品留在漏斗里当分类物（绝不把同一个 ItemStack 挂在两处）
            ItemStack cargo = keep == 0 ? stack : stack.copyWithCount(count - keep);
            if (data.insert(cargo)) {
                // 收下时 insert 已把 cargo 就地清空，这里把格子归位并记账
                hopper.setItem(i, keep == 0 ? ItemStack.EMPTY : stack.copyWithCount(keep));
                // 记的是「搬空了几个漏斗格」，不是搬走了几个物品 —— 一格 64 个和一格 1 个都算 1
                moved++;
                changed = true;
            }
        }
        if (changed) {
            hopper.setChanged();
        }
        return moved;
    }

    /**
     * 「上方容器 → 漏斗」的无上限吸取：把漏斗正上方那个容器的物品<b>一次吸满</b>漏斗。
     *
     * <p>这是对原版 {@code HopperBlockEntity#tryTakeInItemFromSlot} 的「整格版」：
     * 原版每 tick 只 {@code removeItem(slot, 1)}（拿 1 个），再靠 {@code tryMoveItems}
     * 里 {@code setCooldown(8)} 的节流实现「8 gt 吸 1 个」。这里改成<b>整格搬</b>，
     * 并且不设冷却（由调用方 {@code suckInItems} 的接管负责把冷却清零），
     * 于是每个 tick 都能把上方容器能给的尽量吸进漏斗 —— <b>无速率上限</b>。</p>
     *
     * <h2>为什么仍是「原版规则」</h2>
     * <ul>
     *   <li>「能不能从上方容器取」仍然走原版判定（{@link #canTakeFrom}，等价于
     *       原版 {@code canTakeItemFromContainer}）：熔炉之类 {@link WorldlyContainer}
     *        的侧面过滤照旧生效，不会因为我们的模组就「能从炉顶吸原料」；</li>
     *   <li>「能不能放进漏斗」仍走原版 {@link HopperBlockEntity#addItem} 的全部限制
     *       （堆叠上限、格子合并），放不下的部分<b>原样放回</b>上方容器；</li>
     *   <li>只对「上方是容器」的情况生效：上方是掉落物 / 完整方块时，调用方根本不走这里，
     *       还是原版 {@code suckInItems} 的吸掉落物分支。</li>
     * </ul>
     *
     * <p><b>绝不丢东西</b>：{@code addItem} 返回「没放进去的剩余」，这里把它并回上方容器的
     * 原格；只有当 {@code leftover} 为空（全部放进去了）才真正把上方格清掉。</p>
     *
     * @param level 服务端世界（调用方已断言）
     * @param hopper 入库漏斗（口朝向仓库终端、且上方是容器）
     * @return 是否真的吸进来了东西（调用方据此决定要不要让原版进入 8 gt 冷却 —— 我们恒为 false，
     *         因为无上限模式下不想要原版的节流）
     */
    public static boolean suckUnlimited(ServerLevel level, HopperBlockEntity hopper) {
        Container source = HopperBlockEntity.getContainerAt(level, hopper.getBlockPos().above());
        if (source == null) {
            return false;
        }
        boolean moved = false;
        int size = source.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = source.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // 原版判定：这个格子的东西能不能被「取」出来（WorldlyContainer 侧面过滤等）
            if (!canTakeFrom(source, hopper, stack, slot)) {
                continue;
            }
            int count = stack.getCount();
            // 整格拿走（不绕过原版，因为上面已经用 canTakeFrom 判定过）
            ItemStack taken = source.removeItem(slot, count);
            if (taken.isEmpty()) {
                continue;
            }
            // 放进漏斗：走原版 addItem 的全部合并 / 上限 / 放料判定，direction 传 null
            // 与「吸料」这一路原版语义一致（跳过 WorldlyContainer 的放料侧面过滤）
            ItemStack leftover = HopperBlockEntity.addItem(hopper, source, taken, null);
            if (leftover.isEmpty()) {
                moved = true;
            } else {
                // 漏斗放不下的部分：并回上方容器的原格（原格此时要么空、要么同种）
                ItemStack rest = source.getItem(slot);
                if (rest.isEmpty()) {
                    source.setItem(slot, leftover);
                } else if (ItemStack.isSameItemSameComponents(rest, leftover)) {
                    rest.grow(leftover.getCount());
                } else {
                    // 理论到不了这里（removeItem 后原格只剩同种或空），防御性兜底
                    source.setItem(slot, leftover);
                }
                moved = true;
            }
            source.setChanged();
        }
        if (moved) {
            hopper.setChanged();
        }
        return moved;
    }

    /**
     * 复刻原版 {@code HopperBlockEntity#canTakeItemFromContainer}：
     * 这个格子的物品能不能被取走（用于「上方容器 → 漏斗」的吸料）。
     *
     * <p>原版实现就两句话：{@code source.canTakeItem(dest, slot, stack)} 且
     * （若 source 是 {@link WorldlyContainer}）{@code canTakeItemThroughFace(slot, stack, face)}。
     * 因为原版方法是 {@code private static} 调不到，这里照着写一份等价物。</p>
     */
    private static boolean canTakeFrom(Container source, Container dest, ItemStack stack, int slot) {
        if (!source.canTakeItem(dest, slot, stack)) {
            return false;
        }
        if (source instanceof WorldlyContainer worldly) {
            return worldly.canTakeItemThroughFace(slot, stack, Direction.DOWN);
        }
        return true;
    }
}
