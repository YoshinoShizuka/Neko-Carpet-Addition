package neko.voidwarehouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * 「某一页」的容器视图。
 *
 * <p>它<b>不是</b>全局共享的那一份：每个玩家可以停在不同的页码、也可以各自开关
 * 「潜影盒模式」与「数量排序」，所以每个 {@link VoidWarehouseMenu} 各持有一个本类实例。
 * 真正共享的是背后的 {@link VoidWarehouseData}。多人实时同步依然免费 ——
 * 不同菜单读的是同一份数据，原版 {@code ServerPlayer} 每 tick 的
 * {@code broadcastChanges()} 会各自把差异推给对应玩家。</p>
 *
 * <h2>状态存在哪：{@link VoidWarehouseViews.View}</h2>
 * <p>页码与两个开关<b>不是本类的字段</b>，而是委托给玩家自己的那份
 * {@link VoidWarehouseViews.View}。这一点很关键：本类实例是<b>跟着菜单一起创建、一起销毁</b>的，
 * 状态如果放在这里，玩家「开一下数量排序 → 关掉界面 → 再打开」就会发现开关被复位了
 * （这是一个真实修过的 bug）。放到按玩家 UUID 持久化的 {@code View} 上之后，
 * <b>关界面、重连、重启服务器都保留，而 A 的状态永远不影响 B</b>。</p>
 *
 * <p>页码仍然在这里做「按实际页数夹取」——存在 {@code View} 里的原始页码可能超出范围
 * （仓库被取空之后页数会变小），读的时候夹一下即可，不回写。</p>
 *
 * <h2>容器下标 → 展示序号 →（真实格 / 可兑条目）</h2>
 * <ul>
 *   <li>0..44：本页内容。先经 {@link #displayIndexOf(int)} 算出<b>展示序号</b>
 *       （默认排序时就是「页码 × 45 + 下标」；开了「数量排序」还要过一层
 *       {@link VoidWarehouseData#displayAmountOrder() 数量降序视图}），
 *       所以结果可能是 {@code -1}（该位置没有东西）；</li>
 *   <li>再把展示序号交给 {@link #slotAt(int)}，得到这一格到底是
 *       <b>真实库存格</b>、<b>9 合 1 可兑虚拟条目</b>还是<b>空</b>；</li>
 *   <li>45..53：导航行，由 {@link VoidWarehouseNav} 实时生成，<b>不可写、不入存档</b>。</li>
 * </ul>
 * {@link #getContainerSize()} 恒为 54，这样原版客户端才会套用大箱子界面（GUI 尺寸不变）。
 *
 * <p>{@link #slotAt(int)} 是本类里唯一一处「这一格是谁」的换算，
 * 菜单里所有取物品的路径都经过它，所以数量排序也不会点错格，
 * 更不会出现「渲染时是可兑条目、点击时却按真实格处理」这种错位。</p>
 *
 * <h2>为什么这里几乎全是「只读」的</h2>
 * <p>仓库的写操作只有一种正确姿势：{@link VoidWarehouseData#insert(ItemStack)}（数量累加）和
 * {@link VoidWarehouseData#take(int, int)}（数量扣减）。原版那些直接往槽位里写物品的路径
 * （Shift 点击、拖拽、双击取物）如果绕过这两条，就会把「一种物品一格、数量无上限」的账目写乱，
 * 甚至凭空生造物品。所以：</p>
 * <ul>
 *   <li>{@link #canPlaceItem(int, ItemStack)} 对<b>所有</b>格子都返回 false ——
 *       原版的行为都先问 {@code mayPlace}，返回 false 就不会有任何原版路径写进本容器；</li>
 *   <li>{@link #setItem(int, ItemStack)} 直接忽略写入，宁可什么都不做也不破坏账目；</li>
 *   <li>{@link #removeItem(int, int)} / {@link #removeItemNoUpdate(int)} 仍然按真实数量扣减，
 *       万一真有原版路径从仓库里取东西，也只会按规矩扣，不会丢也不会多。</li>
 * </ul>
 */
final class VoidWarehousePageContainer implements Container {
    /**
     * 仓库空格子的占位图标（屏障方块）。
     *
     * <p>只在<b>展示层</b>出现：{@link #getItem(int)} 对「本页该位置没有物品」的内容格返回它，
     * 客户端（纯原版大箱子）就会画一个屏障图标。它<b>永不被读 / 写 / 取</b>——
     * 所有取物路径都走 {@link VoidWarehouseData#entryAt(int)}（空位返回 {@code null}），
     * 所以点击屏障永远是空操作；一旦有物品入库，同一个槽位下一帧就换成真物品，屏障自然消失。</p>
     *
     * <p>屏障原版自带一个不可见的名字（带前导空格的「 Barrier」），这里用
     * {@code CUSTOM_NAME} / {@code LORE} 覆盖成可见的「空位（占位符）」提示，
     * 鼠标移上去能一眼看出这是占位、点击无意义。</p>
     */
    private static final ItemStack PLACEHOLDER;

    static {
        ItemStack placeholder = new ItemStack(Items.BARRIER);
        placeholder.set(DataComponents.CUSTOM_NAME,
                VoidWarehouseText.line("空位", VoidWarehouseText.OFF));
        placeholder.set(DataComponents.LORE, new ItemLore(List.of(
                VoidWarehouseText.line("物品入库后这里会显示真实物品", VoidWarehouseText.NOTE))));
        PLACEHOLDER = placeholder;
    }

    private final VoidWarehouseData data;

    /**
     * 这个玩家自己的视图状态（页码 + 两个开关）。
     *
     * <p>它<b>不跟着菜单走</b>：在 {@link VoidWarehouseViews} 里按玩家 UUID 持久化，
     * 所以关掉界面再打开（甚至重启服务器）都还是他离开时的样子。</p>
     */
    private final VoidWarehouseViews.View view;

    VoidWarehousePageContainer(VoidWarehouseData data, VoidWarehouseViews.View view) {
        this.data = data;
        this.view = view;
    }

    /**
     * 当前页码：把存着的页码按<b>实际页数</b>夹一下再返回。
     *
     * <p>仓库会被取空、页数会变小，所以不能直接相信存下来的值；
     * 这里只在读的时候夹，不回写，免得一次无意的读取就把玩家原本的页码改掉。</p>
     */
    int page() {
        return Math.max(0, Math.min(this.view.page(), this.data.pageCount() - 1));
    }

    void setPage(int page) {
        this.view.setPage(page);
    }

    boolean shulkerMode() {
        return this.view.shulkerMode();
    }

    void setShulkerMode(boolean enabled) {
        this.view.setShulkerMode(enabled);
    }

    boolean amountSort() {
        return this.view.amountSort();
    }

    void setAmountSort(boolean enabled) {
        this.view.setAmountSort(enabled);
    }

    /**
     * 本页第 {@code slot} 格对应<b>展示序里的第几格</b>（会随页码与「数量排序」变化）。
     *
     * <p>默认排序时就是 {@code 页码 × 45 + slot}；开了数量排序则还要翻译一层
     * {@link VoidWarehouseData#displayAmountOrder() 数量降序视图}。该位置没有东西时返回 {@code -1} ——
     * {@link VoidWarehouseData#displaySlotAt(int)} 认这个值（直接当「空格」处理），
     * 所以调用方不需要额外判空。</p>
     *
     * <h2>为什么返回的是「展示序号」而不是「仓库格号」</h2>
     * <p>加上 9 合 1 兑换之后，「这一页第 3 格是谁」有三种可能：<b>真实库存格</b>、
     * <b>可兑虚拟条目</b>、空。三者混在一条序列里（见 {@link VoidWarehouseData#unifiedOrder()}），
     * 所以这一层给出的必须是<b>那条序列的下标</b>，再由
     * {@link #slotAt(int)} 翻译成「真实格还是可兑条目」。
     * 如果这里仍旧返回 {@code entries} 的格号，虚拟条目就没有任何一格能指向它 ——
     * 那正是「明明说能兑、界面里却找不到」的来源。</p>
     */
    int displayIndexOf(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.PAGE_SIZE) {
            return -1;
        }
        int view = this.page() * VoidWarehouseData.PAGE_SIZE + slot;
        if (!this.view.amountSort()) {
            return view < this.data.displayCount() ? view : -1;
        }
        int[] order = this.data.displayAmountOrder();
        return view < order.length ? order[view] : -1;
    }

    /**
     * 第 {@code slot} 格背后到底是什么（真实库存格 / 可兑条目 / 空）。
     *
     * <p><b>界面层唯一一处「这一格是谁」的答案</b>：渲染它、点击它、Shift 点它，
     * 全都先问这里一次、把结果存进局部变量，绝不允许「先解析一次渲染、再解析一次扣账」——
     * 数量排序与「库存变了之后可兑条目会消失」都会让两次解析给出不同结果。</p>
     */
    VoidWarehouseData.DisplaySlot slotAt(int slot) {
        int index = this.displayIndexOf(slot);
        if (index < 0) {
            return VoidWarehouseData.DisplaySlot.empty();
        }
        return this.data.displaySlotAt(index);
    }

    @Override
    public int getContainerSize() {
        return VoidWarehouseData.TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.TOTAL_SLOTS) {
            return ItemStack.EMPTY;
        }
        if (slot >= VoidWarehouseData.NAV_START) {
            return VoidWarehouseNav.build(
                    slot - VoidWarehouseData.NAV_START,
                    this.page(),
                    this.data.pageCount(),
                    this.data.entryCount(),
                    this.data.totalAmount(),
                    this.data.exchangeOfferCount(),
                    this.view.shulkerMode(),
                    this.view.amountSort());
        }
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.isEmpty()) {
            // 空格子用屏障方块作临时占位图标：纯展示、不可交互，有东西之后由本方法换成真物品
            return PLACEHOLDER;
        }
        if (target.isExchange()) {
            // 「可兑」虚拟条目：仓库里没有这种物品，但能靠 9 合 1 兑换拿出来。
            // 两种模式各给一套说明，与真实格的显示口径保持一致。
            return VoidWarehouseData.buildExchangeDisplay(target.offer(), this.view.shulkerMode());
        }
        return this.view.shulkerMode() ? target.entry().displayInBoxMode() : target.entry().display();
    }

    /** 取出若干（真正扣减仓库数量）；返回的是一份崭新的堆叠，与仓库里的样板无关 */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= VoidWarehouseData.NAV_START || amount <= 0) {
            return ItemStack.EMPTY;
        }
        return this.takeDisplayed(slot, amount);
    }

    /**
     * 取出「一格」。因为一格可能装着远超一组的数量，这里只交出一组，
     * 剩下的留在仓库里 —— 绝不整格清空（那会把上万件物品一次性丢出去）。
     */
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.NAV_START) {
            return ItemStack.EMPTY;
        }
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return this.takeDisplayed(slot, target.entry() == null
                ? Math.max(1, target.offer().template().getMaxStackSize())
                : target.entry().maxStack());
    }

    /**
     * 真实格才按真实数量扣减；<b>可兑虚拟条目一律返回空</b>。
     *
     * <h2>为什么虚拟条目在这里什么都不做</h2>
     * <p>{@code removeItem} / {@code removeItemNoUpdate} 是原版<b>绕过菜单</b>直接往容器里取东西的
     * 两条小路（拖拽、Shift、双击取物的收尾都会走到）。真实格走它们没问题 ——
     * 扣的就是这一格里存的那件东西，口径唯一。</p>
     *
     * <p>而虚拟条目背后<b>根本没有一格</b>：它的「取出」是一笔可能跨好几种物品的兑换
     * （扣 9 个铁粒、还可能退回 8 个铁锭）。从这里放行等于把一笔复杂的账塞进一个
     * 「取出 n 个」的接口里，一旦半途失败就没有任何地方能补偿。
     * 所以虚拟条目的取出<b>只认 {@link VoidWarehouseMenu#clicked} 这一条路</b> ——
     * 那条路有完整的规划、提交、回滚三步。这里返回空，客户端那次预测会被
     * {@code sendAllDataToRemote()} 全量覆盖掉，玩家看到的只是这一格没反应（与空格子一致）。</p>
     */
    private ItemStack takeDisplayed(int slot, int amount) {
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.entry() == null) {
            return ItemStack.EMPTY;
        }
        int index = target.realIndex();
        // 必须在 take 之前记住样板：整格取空时那一格会被移除
        ItemStack template = target.entry().template();
        int actual = this.data.take(index, amount);
        return actual <= 0 ? ItemStack.EMPTY : template.copyWithCount(actual);
    }

    /**
     * <b>忽略所有写入</b>。入库只有一条合法路径：
     * {@link VoidWarehouseMenu} 收到点击后调用 {@link VoidWarehouseData#insert(ItemStack)}。
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        // intentionally empty
    }

    /**
     * 关键闸门：<b>一律 false</b>（内容格也一样）。
     *
     * <p>原版的 Shift 点击、拖拽分配、双击取物在动某个槽位之前都会先问
     * {@code slot.mayPlace(...)}，这里返回 false 就等于把「原版直接写容器」这条路彻底封死。
     * 入库由菜单调 {@code data.insert()} 完成，不依赖 {@code canPlaceItem}。</p>
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItem(Container container, int slot, ItemStack stack) {
        return slot >= 0 && slot < VoidWarehouseData.NAV_START;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void setChanged() {
        this.data.markChanged();
    }

    /** 生效 / 失效的判定统一放在 {@link VoidWarehouseMenu#stillValid(Player)}，这里没有「有效范围」概念 */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.data.clearContent();
    }
}
