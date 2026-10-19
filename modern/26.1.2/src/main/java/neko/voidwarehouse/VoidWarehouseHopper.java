package neko.voidwarehouse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 漏斗 → 虚空仓库 的搬运。
 *
 * <h2>无速率上限</h2>
 * <p>漏斗口（{@code HopperBlock.FACING} 指向的那一面）对着仓库终端时，漏斗格子里
 * 的物品会被<b>一次性全部</b>搬进仓库：既不遵循原版「每 8 gt 才搬 1 个」的节流，
 * 也不受一组 64 个的限制 —— 漏斗里有多少就搬多少，<b>1 tick 搬空</b>。</p>
 *
 * <h2>整条链都无速率上限（本轮修复）</h2>
 * <p>光有上面那一条不够 —— 瓶颈在<b>进货</b>那一端：原版 {@code suckInItems} 不管是从
 * 上方容器取料（{@code tryTakeInItemFromSlot}）还是收掉落物（{@code addItem}），
 * 都是「找到第一个能动的就 {@code return true}」，<b>一次只处理一个</b>；
 * 再叠上 {@code tryMoveItems} 结尾那个无条件的 {@code setCooldown(8)}，
 * 实际速率是「每 8 gt 一个」= 2.5 个/秒。物品在水里一秒能跑好几格，
 * 绝大多数<b>根本来不及进漏斗就被冲过去了</b> —— 这就是「东西从入库漏斗上方经过却没入库」。</p>
 * <p>现在的做法是<b>每 tick 一次全量清扫</b>（见 {@link #intake}）：既认同范围 ItemEntity 全收，
 * 也把上方容器能给的整格搬来，收完当场 {@link #drain} 进仓库。
 * 冷却则在 {@code pushItemsTick} 收尾处清零 —— 这一手与 Carpet-TIS 的
 * {@code hopperCountersUnlimitedSpeed} 是同一个思路（它在一轮里循环到无事可做、
 * 收尾 {@code setCooldown(0)}），区别只是我们不依赖 {@code tryMoveItems} 有没有被走到：
 * {@code pushItemsTick} 的 HEAD <b>每 tick 必然执行</b>，冷却对它没有约束力。</p>
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
 * 盒子变成 2 潜影壳 + 1 箱子，染色盒再多吐 1 个对应染料）；空盒子则整盒入库，不拆。</p>
 *
 * <h2>上游那一段也是无上限的</h2>
 * <p>「上方容器 → 漏斗」「掉落物 → 漏斗」这两段由 {@link #suckUnlimited} /
 * {@link #suckEntitiesUnlimited} 自己接管，同样不受 8 gt 节流管；
 * 漏斗 5 格塞满时当场 {@link #drain} 腾位再继续，所以一 tick 的吞吐量
 * 只取决于上游有多少货，不再被漏斗容量卡住。</p>
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
 *   <li><b>而且只进得了它自己那一格</b>（{@link VoidWarehouseClassifier#isOwnSlot}）：
 *       不许开垦空格子，否则原版 {@code addItem} 的逐格试放会把同一种分类物
 *       摊到 5 个格子上，「哪格是哪种」就不是玩家摆出来的样子了；</li>
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

    /**
     * 「上方容器某一格没被一次吸完」时，回头再啃的次数上限。
     *
     * <p>漏斗 5 格塞满 = 320 个，而上方容器一格最多 64 个，所以<b>腾一次位必定够</b>，
     * 正常只会用到 1 次。留 4 次是给「潜影盒拆出来的内容物」之类超出常规堆叠的情况兜底；
     * 真正的终止条件是 {@code drain} 搬不动任何东西（仓库拒收），那时立刻退出。</p>
     */
    private static final int MAX_RETRY_PER_SLOT = 4;

    private VoidWarehouseHopper() {
    }

    /**
     * 这个漏斗现在是不是一条「入库漏斗」：规则开着，而且口朝向的那个方块是工作台
     * （{@link VoidWarehouseTerminals#isWorkbench}，不要求登记为终端）。
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
        return VoidWarehouseTerminals.isWorkbench(level, pos.relative(facing));
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
        VoidWarehouseHopperFilter filter = (VoidWarehouseHopperFilter) hopper;
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || !VoidWarehouseData.isInsertable(stack)) {
                // 空格：原样留在漏斗里（带 NBT 的物品现在也放行，由玩家过滤）
                continue;
            }
            // 余量只留给「玩家在这一格指定过的分类物」。
            //
            // 早先这里是无差别地「每个非空格子留 KEEP 个」，于是任何混进漏斗的东西
            // 都会被永久留 1 个 —— 而且它一旦留下就成了漏斗里的常驻物品，
            // 下一轮反推白名单时又会被认成新的分类物，自己给自己续命。
            // 玩家看到的「橡木 石头 空 空 空」就是这么来的：石头从未被指定过，
            // 只是恰好在玩家动手那一刻躺在漏斗里，就被留了下来、还被认作了分类物。
            // 现在没被指定的格子一律搬空 —— 混进来的杂物会被自动清掉，不会占位。
            int keep = keepClassifiers && VoidWarehouseClassifier.isOwnSlot(filter, stack, i)
                    ? VoidWarehouseClassifier.KEEP
                    : 0;
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
     * 入库漏斗每 tick 要做的全部事：<b>先清空 → 尽量吸 → 吸完立刻入库</b>。
     *
     * <p>三步都必须在<b>同一个 tick</b>里完成，否则物品会在漏斗里多躺一 tick 才入库；
     * 而原版的 8 gt 节流会让它躺得更久。入口是 {@code HopperBlockEntity#pushItemsTick}
     * 的 HEAD —— 那是漏斗每 tick 的必经之处，<b>不受搬运冷却约束</b>
     * （原来的 {@code suckInItems} 注入受制于 {@code tryMoveItems} 开头的
     * {@code isOnCooldown()} 检查，实际只能 8 gt 跑一次）。</p>
     *
     * <h2>两条进货路径二选一（与原版一致）</h2>
     * <ul>
     *   <li>上方<b>是容器</b>：整格搬（{@link #suckUnlimited}）—— 与原版同一分支。</li>
     *   <li>上方<b>不是容器</b>（空气 / 水流 / 掉落物打在上面的方块）：
     *       收掉落物（{@link #suckEntitiesUnlimited}）。</li>
     * </ul>
     * <p>这里刻意保持「二选一」而不是两样都做：否则一个头顶放着箱子的入库漏斗会顺带把
     * 旁边地面上同一高度的掉落物也吸走（吸力盒横向比漏斗宽一格），那是<b>玩家没预期的行为</b>。
     * 原版怎么选演员表，我们就怎么选。</p>
     *
     * <h2>白做的两步不做</h2>
     * <p>实测（见 {@code docs/入库漏斗性能基准.md}）空转时一个漏斗每 tick 约 170ns，
     * 其中「上方是不是容器」那次查询占了近一半 —— 而原写法会查<b>两遍</b>
     * （{@link #intake} 判断走哪条分支一遍、{@link #suckUnlimited} 进门又查一遍）。
     * 这里改成查一次、结果直接传下去。同理，<b>一条都没收上来时第二次
     * {@link #drain} 无事可做</b>，直接跳过 —— 绝大多数入库漏斗绝大多数时间都是空的，
     * 省掉的正是这部分。</p>
     *
     * @param keepClassifiers 见 {@link #drain}
     */
    public static void intake(ServerLevel level, HopperBlockEntity hopper, boolean keepClassifiers) {
        // ① 先把上一 tick 残留的（含 vanilla 亲手吸进来的）搬空，让下面能吃到 5 格全空
        drain(level, hopper, keepClassifiers);
        // ② 进货：上方是容器 → 整格搬；否则 → 收掉落物（与原版同样的二选一）
        //    两条路都在「漏斗吃不下」的那一刻当场入库腾位（见各自的方法注释），
        //    所以一 tick 里能搬多少只取决于上游有多少货，不再被漏斗 5 格的容量卡住。
        Container above = HopperBlockEntity.getContainerAt(level, hopper.getBlockPos().above());
        boolean got = above != null
                ? suckUnlimited(level, above, hopper, keepClassifiers)
                : suckEntitiesUnlimited(level, hopper, keepClassifiers) > 0;
        // ③ 本 tick 刚收进来的当场入库，不让它在漏斗里多待一刻。
        //    一条都没收到时这一步无事可做 —— 跳过它，空转的漏斗就不用白跑第二趟。
        if (got) {
            drain(level, hopper, keepClassifiers);
        }
    }

    /**
     * 「上方经过的掉落物 → 漏斗」的全量吸取：把原版吸力范围内<b>所有</b>掉落物一次收进漏斗。
     *
     * <h2>为什么原版会吸不完</h2>
     * <p>原版 {@code suckInItems} 的掉落物分支是这样写的（已逐行核对字节码）：</p>
     * <pre>{@code
     * for (ItemEntity item : getItemsAtAndAbove(level, hopper)) {
     *     if (addItem(hopper, item)) return true;   // ← 收到一个就收工
     * }
     * return false;
     * }</pre>
     * <p><b>一次只收 1 个实体</b>，再由 {@code tryMoveItems} 结尾的 {@code setCooldown(8)}
     * 变成「8 gt 收 1 个」。水流传物的速度是每秒好几格，绝大多数根本来不及进漏斗就被冲过去了。</p>
     *
     * <h2>这里改成「遍历全部」</h2>
     * <p>用<b>原版自己的</b> {@link HopperBlockEntity#getItemsAtAndAbove} 取范围
     * （同一个吸力盒，不擅自放大也不缩小），再逐个走<b>原版自己的</b>
     * {@link HopperBlockEntity#addItem} 塞进漏斗 ——
     * 于是合并、堆叠上限、以及 {@code canPlaceItemInContainer} 上的分类物白名单
     * <b>全部照旧生效</b>：不是分类物的掉落物会原样留在地上，不会绕过滤波器。</p>
     *
     * <h2>绝不丢东西</h2>
     * <p>{@code addItem(Container, ItemEntity)} 对单个实体是原子的：<b>能全收走才
     * {@code discard()}</b>；只收得下一部分就把剩下那截 {@code setItem} 回实体身上。
     * 既不会让 64 个圆石硬塞成 65 个，也不会出现「实体没了但物品没进漏斗」。</p>
     *
     * <h2>边吸边入库</h2>
     * <p>漏斗只有 5 格，塞满之后原版 {@code addItem} 就一分都吃不下了。
     * 所以每当一个实体<b>没被整个吞掉</b>（漏斗满了 / 只剩半个格子的余量），
     * 就当场 {@link #drain} 一次把位置腾出来，接着收下一个 ——
     * 于是一 tick 的吞吐量不再被「漏斗容量 320」卡住。
     * 只在真吃不下时才 {@code drain}，绝大多数时候（漏斗有空位）这一步完全不跑。</p>
     *
     * @param keepClassifiers 见 {@link #drain}
     * @return 收走了几个<b>实体</b>（按实体个数算，不是物品个数）。只用于诊断 / 自检。
     */
    public static int suckEntitiesUnlimited(ServerLevel level, HopperBlockEntity hopper, boolean keepClassifiers) {
        List<ItemEntity> nearby = HopperBlockEntity.getItemsAtAndAbove(level, hopper);
        if (nearby.isEmpty()) {
            return 0;
        }
        int taken = 0;
        for (ItemEntity entity : nearby) {
            // getItemsAtAndAbove 已经按 ENTITY_STILL_ALIVE 过滤过一层；这里再挡住
            // 「同一 tick 里被旁边的入库漏斗先收走」的情况 —— 那种实体已被 discard，
            // 但还留在这次查询的快照里。少了这一句，计数会虚高（东西早就进库了）。
            if (entity.isRemoved() || entity.getItem().isEmpty()) {
                continue;
            }
            if (absorbEntity(hopper, entity)) {
                taken++;
            }
            if (!entity.isRemoved()) {
                // 没整个吞掉 = 漏斗吃不下（或只吃下半口）→ 入库腾位，下一个实体接着来
                drain(level, hopper, keepClassifiers);
            }
        }
        return taken;
    }

    /**
     * 把一个掉落物实体收进漏斗；实体没能全进去时，剩下的继续留在它身上。
     *
     * <h2>为什么要自己切一刀（实测发现的原版坑）</h2>
     * <p>实体带的物品<b>可以超过一堆</b>（多个 »64 的物品在地面上合并时止于一堆，
     * 但别的方式造出来的堆叠没这个限制）。而原版 {@code tryMoveInItem} 往<b>空格子</b>里放东西时写的是：</p>
     * <pre>{@code
     * destination.setItem(slot, stack);   // 整摞塞进去
     * stack = ItemStack.EMPTY;            // 然后声称「全搬完了」
     * }</pre>
     * <p>{@code HopperBlockEntity#setItem} 里有一句 {@code limitSize}，会把数量截到漏斗的上限
     * （64）—— <b>多出来的部分就这样被静默吞掉了</b>，实体那边却已经 {@code discard()}。
     * 实测：一个带 200 个圆石的实体落到空格子的入库漏斗上，仓库只进了 64，136 个凭空消失。</p>
     *
     * <p>所以这里先把「超过一堆」的那部分<b>按堆切片</b>喂给漏斗：每片都 ≤ 物品自身的堆叠上限，
     * 正好落在 {@code limitSize} 不会动手的范围里。常见的 ≤1 堆情况完全走原版路径，一个字节都不改。</p>
     *
     * <h2>绝不丢东西</h2>
     * <p>喂不进去的部分 {@code addItem} 会作为剩菜还回来，这里把它<b>原样放回实体身上</b>
     * （实体不删除），下一个 tick 再来 —— 宁可慢一 tick，也不让任何一个物品消失。</p>
     *
     * @return 是否有进展（至少进去了一部分）
     */
    private static boolean absorbEntity(HopperBlockEntity hopper, ItemEntity entity) {
        ItemStack payload = entity.getItem();
        if (payload.isEmpty()) {
            return false;
        }
        int before = payload.getCount();
        // 与「上方容器」那一走路同一份 {@link #absorbStack}：两份逻辑必须永远一致
        ItemStack leftover = absorbStack(null, hopper, payload);
        if (leftover.getCount() == before) {
            // 漏斗一个都吃不下 —— 实体原地留着，下一个 tick 再来
            return false;
        }
        // absorbStack 已经把实体身上那份 ItemStack 就地 shrink 成「还剩多少」，
        // leftover 与它就是同一个对象；这里显式写回，让 ItemEntity 自己的记账同步。
        if (!leftover.isEmpty()) {
            entity.setItem(leftover);
            return true;
        }
        // 全进去了：按原版 addItem(Container, ItemEntity) 的收尾方式处理
        entity.setItem(ItemStack.EMPTY);
        entity.discard();
        return true;
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
     * <p>塞不进漏斗的那部分 <b>原样放回上方容器的原格</b> —— 一丝不苟地还回去，
     * 从来没有「取出来却没地方放」这种事发生。</p>
     *
     * <h2>边吸边入库</h2>
     * <p>与掉落物那条路同一个道理：漏斗只有 5 格，塞满就吃不下了。
     * 所以每当一格货物<b>没被整个吞掉</b>，就当场 {@link #drain} 一次腾位，
     * 下一格接着吸。这条在<b>分类物模式</b>下尤其要紧：分类物只进得了自己那一格，
     * 而那一格还永久占着 {@link VoidWarehouseClassifier#KEEP} 个当种子，
     * 单次最多只能吃 {@code 64 - KEEP} 个 —— 不边吸边腾，速率会掉回 63 个/tick。</p>
     * <p>只在真吃不下时才 {@code drain}：上方容器前几格通常一口气填满 5 格、
     * 一次都不用腾，空转与常规场景的开销和改之前一样。</p>
     *
     * @param keepClassifiers 见 {@link #drain}
     * @return 是否真的吸进来了东西（至少有一个物品进了漏斗）
     */
    public static boolean suckUnlimited(ServerLevel level, Container source, HopperBlockEntity hopper,
                                        boolean keepClassifiers) {
        if (source == null) {
            return false;
        }
        boolean moved = false;
        int size = source.getContainerSize();
        int slot = 0;
        while (slot < size) {
            int retries = 0;
            while (true) {
                ItemStack stack = source.getItem(slot);
                if (stack.isEmpty()) {
                    break;
                }
                // 原版判定：这个格子的东西能不能被「取」出来（WorldlyContainer 侧面过滤等）
                if (!canTakeFrom(source, hopper, stack, slot)) {
                    break;
                }
                int available = stack.getCount();
                // 整格取走（不绕过原版，因为上面已经用 canTakeFrom 判定过）
                ItemStack taken = source.removeItem(slot, available);
                if (taken.isEmpty()) {
                    break;
                }
                // 塞进入库漏斗：<b>参数顺序</b>必须与原版一致 —— addItem(source, destination, ...)。
                // 这里曾经把 source / destination 写反过：东西被「取出来又塞回自己」，漏斗一个都没收到，
                // 实测上方容器的速率退化成 1 个/tick（而掉落物是每 tick 全收，相差 320 倍），
                // 分类物白名单也因为「关卡认错了目标容器」而整条失效。
                ItemStack leftover = absorbStack(source, hopper, taken);
                int accepted = available - leftover.getCount();
                if (accepted > 0) {
                    moved = true;
                }
                if (leftover.isEmpty()) {
                    break;
                }
                // 漏斗吃不下（或只吃下半口）的部分：并回上方容器的原格
                // （原格此时要么空、要么同种 —— removeItem 过后不可能出现第三种情况）
                mergeBack(source, slot, leftover);
                // 当场入库腾位，然后<b>回头再啃同一格</b> ——
                // 只腾位不回头的话，撞上「漏斗满了」的那一格货物会留到下一 tick，
                // 一 tick 的吞吐就永远卡在「5 格容量」这个数上。
                // 次数上限是硬保险：drain 没搬动任何东西（仓库拒收）时立刻退出，不存在转不出来的情况。
                if (drain(level, hopper, keepClassifiers) <= 0 || retries >= MAX_RETRY_PER_SLOT) {
                    break;
                }
                retries++;
            }
            slot++;
        }
        if (moved) {
            source.setChanged();
            hopper.setChanged();
        }
        return moved;
    }

    /**
     * 把一份物品塞进漏斗，返回<b>没塞进去的部分</b>。
     *
     * <h2>为什么要自己切一刀（实测发现的原版坑）</h2>
     * <p>原版 {@code tryMoveInItem} 往<b>空格子</b>里放东西时写的是：</p>
     * <pre>{@code
     * destination.setItem(slot, stack);   // 整摞塞进去
     * stack = ItemStack.EMPTY;            // 然后声称「全搬完了」
     * }</pre>
     * <p>而 {@code HopperBlockEntity#setItem} 里有一句 {@code limitSize}，会把数量截到堆叠上限
     * —— <b>多出来的部分就这样被静默吞掉了</b>。掉落物路径实测过：一个带 200 个圆石的实体
     * 落到空格子的入库漏斗上，仓库只进了 64，136 个凭空消失。</p>
     *
     * <p>所以这里先把「超过一堆」的那部分<b>按堆切片</b>喂给漏斗：每片都 ≤ 物品自身的堆叠上限，
     * 正好落在 {@code limitSize} 不会动手的范围里。常见的 ≤1 堆情况完全走原版路径，一个字节都不改。</p>
     *
     * @param source 物品来自哪个容器（可以是 {@code null}，例如掉落物路径）
     * @param destination 目标漏斗
     * @param payload 待塞的物品；<b>会被就地消耗</b>（吃进去多少就少多少）
     * @return 没被吃下去的剩余（可能为空）
     */
    private static ItemStack absorbStack(Container source, HopperBlockEntity destination, ItemStack payload) {
        if (payload.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int stackLimit = Math.max(1, payload.getMaxStackSize());
        // 只切「超过一堆」的那部分：常规情况根本进不了这个循环
        while (payload.getCount() > stackLimit) {
            ItemStack slice = payload.copyWithCount(stackLimit);
            ItemStack leftover = HopperBlockEntity.addItem(source, destination, slice, null);
            int accepted = stackLimit - leftover.getCount();
            if (accepted <= 0) {
                // 漏斗已经吃不下任何东西了 —— 剩下的原样退回
                return payload;
            }
            payload.shrink(accepted);
        }
        // 剩下的不超过一堆，交给原版一次性收尾（数量已经在 limitSize 不会吃亏的范围里了）
        return HopperBlockEntity.addItem(source, destination, payload, null);
    }

    /** 把 leftover 并回容器的某一格：能合并就合并，不能（该格已空）就直接写入。 */
    private static void mergeBack(Container container, int slot, ItemStack leftover) {
        ItemStack rest = container.getItem(slot);
        if (rest.isEmpty()) {
            container.setItem(slot, leftover);
        } else if (ItemStack.isSameItemSameComponents(rest, leftover)) {
            rest.grow(leftover.getCount());
        } else {
            // 理论到不了这里（removeItem 后原格只剩同种或空），防御性兜底
            container.setItem(slot, leftover);
        }
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
