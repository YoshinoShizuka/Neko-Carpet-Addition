package neko.voidwarehouse;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

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
 * <h2>容器下标 → 仓库格号</h2>
 * <ul>
 *   <li>0..44：本页内容，映射到仓库格号 {@code 页码 × 45 + 下标}；
 *       开了「数量排序」时，还要再经一层 {@link VoidWarehouseData#amountOrder()}
 *       的<b>视图映射</b>（视图第 n 位 → 仓库第几格），所以映射结果可能是 {@code -1}（该位置没有东西）；</li>
 *   <li>45..53：导航行，由 {@link VoidWarehouseNav} 实时生成，<b>不可写、不入存档</b>。</li>
 * </ul>
 * {@link #getContainerSize()} 恒为 54，这样原版客户端才会套用大箱子界面（GUI 尺寸不变）。
 *
 * <p>{@link #slotIndexOf(int)} 是本类里唯一一处「视图 → 数据」的换算，
 * 菜单里所有取物品的路径都经过它，所以数量排序不会出现「点到 A 却取出 B」。</p>
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
                Component.literal("空位（占位符）").withStyle(ChatFormatting.GRAY));
        placeholder.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("此处还没有存放物品").withStyle(ChatFormatting.GRAY),
                Component.literal("物品入库后这里会显示真实物品").withStyle(ChatFormatting.DARK_GRAY))));
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
     * 本页第 {@code slot} 格对应仓库里的<b>第几格</b>（会随页码与「数量排序」变化）。
     *
     * <p>默认排序时就是 {@code 页码 × 45 + slot}；开了数量排序则还要翻译一层
     * {@link VoidWarehouseData#amountOrder() 数量降序视图}。该位置没有东西时返回 {@code -1} ——
     * {@link VoidWarehouseData#entryAt(int)} 与 {@link VoidWarehouseData#take(int, int)} 都认这个值
     * （直接当「空格」处理），所以调用方不需要额外判空。</p>
     */
    int slotIndexOf(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.PAGE_SIZE) {
            return -1;
        }
        int view = this.page() * VoidWarehouseData.PAGE_SIZE + slot;
        if (!this.view.amountSort()) {
            return view;
        }
        int[] order = this.data.amountOrder();
        return view < order.length ? order[view] : -1;
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
                    this.view.shulkerMode(),
                    this.view.amountSort());
        }
        int index = this.slotIndexOf(slot);
        if (this.view.shulkerMode()) {
            return shulkerDisplay(index);
        }
        ItemStack shown = this.data.displayAt(index);
        // 空格子用屏障方块作临时占位图标：纯展示、不可交互，物品入库后由本方法换成真物品
        return shown.isEmpty() ? PLACEHOLDER : shown;
    }

    /** 潜影盒模式下，在展示堆叠后面追加几行「这么点下去会怎样」的说明 */
    private ItemStack shulkerDisplay(int index) {
        ItemStack shown = this.data.displayAt(index);
        if (shown.isEmpty()) {
            // 空位同样用屏障占位（潜影盒模式只是给「有物品」的格子加说明，空位仍是占位符）
            return PLACEHOLDER;
        }
        // displayAt 返回的是 data 里缓存的那一份，动它会把缓存写坏 —— 先复制再改
        // （copyWithCount/copy 的组件是 copy-on-write，改副本不会污染真品）
        ItemStack copy = shown.copy();
        ItemLore lore = copy.get(DataComponents.LORE);
        ItemLore base = lore == null ? ItemLore.EMPTY : lore;
        copy.set(DataComponents.LORE, base
                .withLineAdded(Component.literal(
                                "左键 / Shift / 双击 = 交出 1 盒（" + count(this.capacityOf(index)) + " 个）")
                        .withStyle(ChatFormatting.AQUA))
                .withLineAdded(Component.literal(
                                "右键 = 按数量取货（在聊天栏输入数量，拆成满盒 + 散货）")
                        .withStyle(ChatFormatting.AQUA))
                .withLineAdded(Component.literal(
                                "将消耗：潜影壳 ×" + VoidWarehouseShulker.SHELL_COST
                                        + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST + "（优先用空盒）")
                        .withStyle(ChatFormatting.AQUA)));
        return copy;
    }

    /** 本页第 {@code slot} 格那种物品「一盒能装多少个」 */
    int capacityOf(int index) {
        VoidWarehouseData.Entry entry = this.data.entryAt(index);
        return entry == null ? 0 : VoidWarehouseShulker.capacity(entry.template());
    }

    /** 千分位展示（一盒几百上千个，不写分隔符不容易一眼读出来） */
    private static String count(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 取出若干（真正扣减仓库数量）；返回的是一份崭新的堆叠，与仓库里的样板无关 */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= VoidWarehouseData.NAV_START || amount <= 0) {
            return ItemStack.EMPTY;
        }
        int index = this.slotIndexOf(slot);
        VoidWarehouseData.Entry entry = this.data.entryAt(index);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        // 必须在 take 之前记住样板：整格取空时那一格会被移除
        ItemStack template = entry.template();
        int actual = this.data.take(index, amount);
        return actual <= 0 ? ItemStack.EMPTY : template.copyWithCount(actual);
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
        int index = this.slotIndexOf(slot);
        VoidWarehouseData.Entry entry = this.data.entryAt(index);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemStack template = entry.template();
        int actual = this.data.take(index, entry.maxStack());
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

    /** 距离 / 存活校验统一放在 {@link VoidWarehouseMenu#stillValid(Player)}，这里没有「有效范围」概念 */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.data.clearContent();
    }
}
