package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.math.BigInteger;

/**
 * 虚空仓库的容器界面（分页 + 每种物品一格）。
 *
 * <p>继续继承原版 {@link ChestMenu} 并复用 {@code MenuType.GENERIC_9x6}：槽位数量、玩家背包位置
 * 完全与原版大箱子一致，客户端可以是纯原版（方案 A 的关键）。</p>
 *
 * <h2>布局（尺寸没变，仍是 6 行 × 9 列）</h2>
 * <pre>
 *   第 1..5 行  →  本页仓库内容，45 格（下标 0..44），每格一种物品
 *   第 6   行   →  固定导航行，9 格（下标 45..53），以页码为轴左右镜像：
 *                  45 红玻璃片  46 红玻璃片  47 潜影盒模式  48 上一页◀  49 页码
 *                  50 下一页▶   51 数量排序   52 红玻璃片    53 红玻璃片
 *   再往下      →  玩家背包 3 行 + 快捷栏 1 行（原版位置，未改动）
 * </pre>
 *
 * <h2>点击语义（服务端权威）</h2>
 * <pre>
 *   仓库格 左键                     取出 1 个，直接放进玩家背包
 *   仓库格 右键（光标空）            取出一组（最多该物品的 maxStackSize），直接放进背包
 *   仓库格 右键（光标拿着同种物品）   把这些物品放入仓库（数量累加到同一格）
 *   仓库格 右键（光标拿着别的物品）   原版语义：什么都不做
 *   仓库格 双击                     同左键，仍只取出 1 个（连点误判成双击时不会掏走一整组）
 *   Shift 点击仓库格                取出一组进背包；背包装不下的部分原样退回仓库
 *   Shift 点击背包格                整组存入仓库（同类合并到同一格，数量累加）
 *   数字键（点着仓库格按 1-9）       把快捷栏那一格整组存入仓库
 *   导航行                          上一页 / 下一页 + 两个模式开关响应左键单击；红玻璃占位格点了什么都不做
 *   创造模式中键克隆                被拦截，绝不克隆
 *   丢弃（Q）                       光标为空时不动仓库；光标有东西时仍按原版丢弃光标上那一份
 * </pre>
 *
 * <h2>排列顺序（槽位 51 的开关，图标是罗盘）</h2>
 * <p>仓库的默认顺序<b>只取决于物品本身</b>，按<b>创造模式物品栏顺序</b>排
 * （见 {@link VoidWarehouseSort}）—— 与「谁先存进来」彻底无关，所以
 * <b>取空之后再存回来还是待在原来的位置</b>，不会掉到队尾。</p>
 * <p>开关打开后换成<b>数量从大到小</b>排列（{@link VoidWarehouseData#amountOrder()}）：
 * 数量相同的物品仍按创造栏顺序，所以不会互相抖动。<b>它只改显示</b>，
 * 仓库里存放的东西一个字都不动，关掉就立刻回到创造栏顺序。</p>
 *
 * <h2>潜影盒模式（槽位 47 的开关）</h2>
 * <p>开关打开后，<b>取出动作都改成「交出装满的潜影盒」</b>：</p>
 * <ul>
 *   <li><b>左键 / 双击 / Shift 点击仓库格</b> → 交出 1 个装满的潜影盒；</li>
 *   <li><b>右键仓库格</b> → 发起「按数量取货」：提示玩家在聊天栏输入数量，
 *       按数量拆成若干满盒 + 散货一次性给出（见 {@link VoidWarehouseWithdraw}）。</li>
 * </ul>
 *
 * <pre>
 *   取出 1 盒 = 扣「该物品」min(库存, 27 × 堆叠上限) 个
 *              + 扣 2 个潜影壳 + 扣 1 个箱子
 * </pre>
 *
 * <p>三条边界：</p>
 * <ul>
 *   <li><b>库存不足一盒</b> → 交出<b>装不满</b>的盒子（有多少装多少），不做部分拒绝；</li>
 *   <li><b>潜影壳 / 箱子不够</b> → <b>整次操作拒绝</b>，只发一条提示，仓库一点不动；</li>
 *   <li><b>背包塞不下那个盒子</b> → <b>先试放、再扣账</b>，放不下就整次放弃，
 *       绝不会出现「扣了料却拿不到盒子」。</li>
 * </ul>
 *
 * <p>两个开关都是<b>每个玩家各自</b>的：状态存在按玩家 UUID 持久化的
 * {@link VoidWarehouseViews.View} 里（页码也在同一份），所以
 * <b>关掉界面再打开、甚至重连回来，开关和页码都还是离开时的样子</b>，
 * 而别人开了什么开关永远不会影响我这页。</p>
 *
 * <p><b>取出永远不进光标</b>：本版所有「取出」都是直接写进玩家背包
 * （{@link #withdrawToInventory} / {@link #withdrawShulkerBox}），物品不会悬浮在鼠标上；
 * 背包放不下的部分原样退回仓库，既不丢也不会凭空产生。</p>
 *
 * <h2>防复制 / 防丢失的八道闸门</h2>
 * <ol>
 *   <li>{@link VoidWarehousePageContainer#canPlaceItem(int, ItemStack)} 恒为 false，
 *       原版任何「往槽位里放东西」的路径（拖拽 / Shift / 双击）都被挡在门外；</li>
 *   <li>{@link VoidWarehousePageContainer#setItem(int, ItemStack)} 忽略写入，
 *       原版就算绕过第一道也改不动仓库的账目；</li>
 *   <li>{@code NavSlot} 的 {@code mayPickup/mayPlace/allowModification} 全是 false，
 *       按钮图标既拿不走也放不进；</li>
 *   <li>「视图格 → 仓库格」只有 {@link VoidWarehousePageContainer#slotIndexOf(int)} 一处换算，
 *       取物品的每条路径都用它重新解析一次索引，所以数量排序也不会点错格；</li>
 *   <li>所有取出/放入都在本类里显式走 {@code data.take()} / {@code data.insert()}，
 *       并且只用 {@code copyWithCount} 造新实例，绝不把同一个 {@link ItemStack} 挂在两处。</li>
 *   <li>{@link #syncAllViewers(Player)} 一律走 {@code sendAllDataToRemote()}（全量重发 + 强行
 *       对齐服务端的 {@code RemoteSlot}），客户端自己预测出来的错误顺序会被<b>立刻覆盖</b>，
 *       不会出现「界面显示的顺序和仓库实际顺序不一致」；</li>
 *   <li>潜影盒取出的顺序是「先把盒子放进背包 → 放进去了才扣账」，绝不反过来。</li>
 *   <li>漏斗出料时「每格最后 1 个」永不被搬走（见 {@link VoidWarehouseExtractor}）：
 *       仓库的账可以归零，漏斗里的分类物一定还剩 1 个，下游容器吸不走。</li>
 * </ol>
 */
public class VoidWarehouseMenu extends ChestMenu {
    // 聊天提示统一走 VoidWarehouseText.info()：亮金色 [虚空仓库] 前缀 + 灰色正文，
    // 一眼能和普通聊天分开；正文里的关键数字用 VoidWarehouseText.key() 标亮绿。
    private static final Component MSG_FULL = VoidWarehouseText.info("虚空仓库已满，放不进去了");
    private static final Component MSG_RESCUED =
            VoidWarehouseText.info("背包已满，手上的物品已存入虚空仓库");
    private static final Component MSG_INV_FULL =
            VoidWarehouseText.info("背包已满，放不下的留在虚空仓库");
    private static final Component MSG_REJECTED =
            VoidWarehouseText.info("带额外数据的物品（耐久 / 附魔 / 改名）拒收");
    private static final Component MSG_BOX_REJECTED =
            VoidWarehouseText.info("盒内有带额外数据的物品，整盒拒收");
    private static final Component MSG_SHULKER_ON = switchMessage("潜影盒模式", "开");
    private static final Component MSG_SHULKER_OFF = switchMessage("潜影盒模式", "关");
    private static final Component MSG_NO_BOX_PARTS = VoidWarehouseText.info(
            VoidWarehouseText.body("潜影壳或箱子不足，无法装盒（每盒需 潜影壳 ×")
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.SHELL_COST)))
                    .append(VoidWarehouseText.body(" + 箱子 ×"))
                    .append(VoidWarehouseText.key(String.valueOf(VoidWarehouseShulker.CHEST_COST)))
                    .append(VoidWarehouseText.body("）")));
    private static final Component MSG_BOX_INV_FULL =
            VoidWarehouseText.info("背包放不下盒子，本次取消（仓库未扣减）");
    private static final Component MSG_SORT_ON = switchMessage("数量排序", "开");
    private static final Component MSG_SORT_OFF = switchMessage("数量排序", "关");

    /**
     * 「xxx：开 / 关」这种模式开关提示。
     *
     * <p>名称用正文灰、<b>状态用亮绿</b>：玩家点开关时真正要读的是「现在开还是关」，
     * 让这两个字自己跳出来，不用把整句读完再回头找结论。</p>
     */
    private static Component switchMessage(String name, String state) {
        return VoidWarehouseText.info(VoidWarehouseText.body(name + "：")
                .append(VoidWarehouseText.key(state)));
    }

    private final VoidWarehouseData data;
    private final VoidWarehousePageContainer pageContainer;
    private final ResourceKey<Level> openDimension;

    public VoidWarehouseMenu(int syncId, Inventory playerInventory, VoidWarehouseData data, Player opener) {
        // 注意：super(...) 必须是第一条语句，所以这里直接 new 一个页面容器传进去，
        // 构造完再通过 ChestMenu#getContainer() 取回来。
        // 页码 + 两个开关是「开界面这个玩家自己的」，所以按 opener 取他那份持久化的视图状态
        // （VoidWarehouseViews.viewFor 是静态调用，可以在 super(...) 的参数里用）。
        super(MenuType.GENERIC_9x6, syncId, playerInventory,
                new VoidWarehousePageContainer(data, VoidWarehouseViews.viewFor(opener)), 6);

        this.data = data;
        this.pageContainer = (VoidWarehousePageContainer) this.getContainer();
        this.openDimension = opener.level().dimension();

        this.installNavSlots();
    }

    /**
     * 把第 6 行的 9 个槽位替换成不可拾取、不可放置的 {@link NavSlot}。
     *
     * <p>{@code AbstractContainerMenu#slots} 是 {@code public final NonNullList<Slot>}，
     * 元素可替换。坐标 / 容器下标 / 行内下标全部沿用原版已经算好的值，界面观感完全不变。</p>
     */
    private void installNavSlots() {
        for (int i = 0; i < VoidWarehouseData.TOTAL_SLOTS; i++) {
            Slot original = this.slots.get(i);
            if (i < VoidWarehouseData.NAV_START) {
                // 本页内容格：可能是真物品，也可能是屏障占位符。占位（屏障）不可拾取、不可放置
                this.slots.set(i, new PlaceholderSlot(original.container, original.getContainerSlot(), original.x, original.y));
            } else {
                this.slots.set(i, new NavSlot(original.container, original.getContainerSlot(), original.x, original.y));
            }
        }
    }

    /** 导航行专用槽位：既不给出、也不接收物品 */
    static final class NavSlot extends Slot {
        NavSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean allowModification(Player player) {
            return false;
        }
    }

    /**
     * 仓库内容格专用槽位。
     *
     * <p>它背后的 {@link VoidWarehousePageContainer} 对「空格子」返回屏障占位图标，
     * 所以这里按「装着的东西是不是屏障」动态决定能不能被客户端预测拾取：</p>
     * <ul>
     *   <li>真物品 → {@code mayPickup} 为 true，客户端照常预测、服务端在 {@link #clicked} 里处理取出；</li>
     *   <li>屏障占位 → {@code mayPickup} 为 false，客户端<b>连预测都不做</b>，
     *       点上等于点了空气，纯占位符；</li>
     * </ul>
     * <p>{@code mayPlace} / {@code allowModification} 一律 false（仓库格本就不接收原版写入）。</p>
     */
    static final class PlaceholderSlot extends Slot {
        PlaceholderSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPickup(Player player) {
            return this.getItem().getItem() != Items.BARRIER;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean allowModification(Player player) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 对应 Yarn 的 {@code canUse}。返回 false 时原版会在下一次 tick 安全地关掉界面
     * （{@code ServerPlayer#tick} 里就是 {@code if (!containerMenu.stillValid(this)) closeContainer()}），
     * 而本类的 {@link #removed(Player)} 不会丢弃仓库内容，所以关掉不会丢东西。
     *
     * <p><b>这里刻意没有「离打开位置不能超过 8 格」这类距离限制</b>。
     * 曾经有（对齐原版箱子的 8 格），但那是把虚拟仓库当成了「世界里的一个方块容器」：
     * 玩家鞘翅滑翔时右键开界面，飞出 8 格后界面就被原版强行关掉（约两秒），
     * 疾跑 / 骑马 / 划船同理。而实际上：</p>
     * <ul>
     *   <li>触发物是<b>手里的物品</b> —— {@link VoidWarehouseTrigger#isVoidWarehouse} 只判
     *       「工作台 + 自定义名」，对空气右键也能开，压根没有「容器方块」可以走远；</li>
     *   <li>内容存在 {@link VoidWarehouseData}（按维度的 SavedData）里，不在任何方块上。</li>
     * </ul>
     * 所以位置在语义上就是不相关的量，拿来当闸门只会误伤。仍然保留的三道闸门是：
     * 规则开关、玩家存活、维度一致（跨维度时坐标没有可比性，且仓库数据本身就是按维度存的）。
     */
    @Override
    public boolean stillValid(Player player) {
        // 规则被关掉时，已经打开的界面也要安全关闭
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!player.isAlive()) {
            return false;
        }
        // 切维度校验：跨维度时坐标没有可比性，直接让界面失效
        return player.level().dimension().equals(this.openDimension);
    }

    /**
     * 对应 Yarn 的 {@code onClosedByPlayer}。
     *
     * <p><b>仓库本体在这里绝不被动到</b>：内容存在 {@link VoidWarehouseData} 里，从不挂在菜单上，
     * 所以玩家关闭界面 / 下线 / 切维度 / 死亡 / 被踢出时，仓库内容既不会掉落也不会被清空。</p>
     *
     * <p>唯一要处理的是<b>光标上那一份</b>：原版 {@code AbstractContainerMenu#removed} 会把它
     * 放回背包（背包满就丢地上），我们覆写掉了整个方法，如果不自己处理，那一份会凭空消失。
     * 这里的顺序是「先放回背包 → 再存入仓库 → 绝不丢地上」，所以既不会丢也不会凭空产生。</p>
     */
    @Override
    public void removed(Player player) {
        ItemStack carried = this.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        this.setCarried(ItemStack.EMPTY);

        ItemStack rest = carried.copy();
        this.moveItemStackTo(rest, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
        if (rest.isEmpty()) {
            return;
        }
        if (this.data.insert(rest)) {
            // 背包塞不下：仓库无上限，一定装得下，比丢到地上安全
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(MSG_RESCUED);
            }
            return;
        }
        // 带数据的物品进不了仓库：按原版的兜底「先塞背包、塞不下才丢地上」还给他
        player.getInventory().placeItemBackInInventory(rest);
        this.reportReject(rest, player);
    }

    // ------------------------------------------------------------------ 点击

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId < 0 || slotId >= VoidWarehouseData.TOTAL_SLOTS) {
            // 玩家背包格 / 点空白处 / 丢弃等非仓库槽位：原版行为
            super.clicked(slotId, button, input, player);
            return;
        }

        // 拖拽（QUICK_CRAFT）：交还原版以保持它的状态机一致。
        // 安全：内容格与导航行的 mayPlace 都是 false，原版不会把它们加进拖拽列表。
        if (input == ContainerInput.QUICK_CRAFT) {
            super.clicked(slotId, button, input, player);
            return;
        }

        // ---- 第 6 行：导航行 ----
        if (slotId >= VoidWarehouseData.NAV_START) {
            int rowIndex = slotId - VoidWarehouseData.NAV_START;
            if (input == ContainerInput.PICKUP && button == 0) {
                if (VoidWarehouseNav.isShulkerToggle(rowIndex)) {
                    this.toggleShulkerMode(player);
                } else if (VoidWarehouseNav.isSortToggle(rowIndex)) {
                    this.toggleAmountSort(player);
                } else if (VoidWarehouseNav.isPageButton(rowIndex)) {
                    this.flipPage(slotId);
                }
            }
            // 红色玻璃片占位格，以及其它任何输入：不做任何操作
            return;
        }

        // ---- 第 1..5 行：本页仓库内容 ----
        // 「这一格是谁」只解析这一次，后面渲染 / 取出 / 存入全都用这一个值 ——
        // 数量排序、以及「可兑条目会随库存出现 / 消失」，都会让两次解析给出不同结果。
        VoidWarehouseData.DisplaySlot target = this.slotAt(slotId);
        if (target.isEmpty()) {
            // 空格子（屏障占位符）：任何输入都无响应，纯占位，直接返回、不做任何重发
            return;
        }
        // 潜影盒模式下，所有「取出」动作都换成交出 1 个装满的潜影盒；
        // 「存入」的路径（数字键、Shift 点背包格）完全不受影响。
        boolean boxMode = this.pageContainer.shulkerMode();

        switch (input) {
            case PICKUP -> {
                if (button == 0) {
                    if (boxMode) {
                        this.withdrawShulkerBox(target, player);
                    } else {
                        this.withdrawFrom(target, 1, player);
                    }
                } else if (button == 1) {
                    if (boxMode) {
                        // 潜影盒模式下右键：发起「按数量取货」请求 —— 玩家在聊天栏输入数量，
                        // 一次性取出 N 个（拆成满盒 + 散货）。见 VoidWarehouseWithdraw。
                        //
                        // request 会<b>主动把这个界面关掉</b>（聊天栏被界面盖着就没法输入），
                        // 所以这里必须直接 return：菜单此刻已经不存在了，再往下走
                        // syncAllViewers 就是对着一个已关闭的菜单发包。
                        // 取完货由 VoidWarehouseWithdraw 自己把界面开回来（页码/开关都还在）。
                        // 「可兑」虚拟条目走的是同一条路 —— 区别只在 execute 里改用兑换扣账。
                        if (player instanceof ServerPlayer serverPlayer) {
                            VoidWarehouseWithdraw.request(serverPlayer, target.template());
                        }
                        return;
                    } else {
                        this.withdrawOrStore(target, player);
                    }
                } else {
                    return;
                }
                this.syncAllViewers(player);
            }
            case PICKUP_ALL -> {
                // 双击（PICKUP_ALL）在普通模式下<b>仍只取 1 个</b>，和左键一致：
                // 客户端对同一槽位 <250ms 的快速连点会判定成「双击」，如果这里取一组，
                // 玩家想连点取几个「1 个」时会莫名其妙被掏走一整组。想取一组用右键或 Shift。
                // 潜影盒模式交出一盒。
                if (boxMode) {
                    this.withdrawShulkerBox(target, player);
                } else {
                    this.withdrawFrom(target, 1, player);
                }
                this.syncAllViewers(player);
            }
            case QUICK_MOVE -> {
                // Shift 点击仓库格：潜影盒模式交出一盒，普通模式取一组
                if (boxMode) {
                    this.withdrawShulkerBox(target, player);
                } else {
                    this.quickMoveStack(player, slotId);
                }
                this.syncAllViewers(player);
            }
            case SWAP -> {
                // 数字键：把快捷栏那一格整组存入仓库（模式只改变「取出」，存入不动）
                this.storeHotbarStack(button, player);
                this.syncAllViewers(player);
            }
            case THROW -> {
                // 26.3 的 THROW 只处理光标上那一份，碰不到容器槽位（已核对字节码）。
                // 光标为空时直接忽略，杜绝「把展示用堆叠丢出去」的可能。
                if (!this.getCarried().isEmpty()) {
                    super.clicked(slotId, button, input, player);
                }
            }
            case CLONE -> {
                // 拒绝：创造模式中键克隆是唯一能凭空造物的路径
            }
            default -> {
                // 未来的新输入类型：一律不处理，宁可无操作也不冒险
            }
        }
    }

    /**
     * 翻页。页码是<b>每个玩家各自</b>的（存在 {@link VoidWarehouseViews} 里，关界面也保留），
     * 所以只重发自己这一个菜单。
     *
     * <p>客户端是纯原版的 {@code ChestMenu}，它会把「点到箭头」预测成一次拾取，
     * 于是光标上会闪一下箭头图标。这里用 {@link #syncAllViewers}（全量重发）而不是差异包，
     * 客户端拿到的就是 54 格的绝对真相，预测连同光标状态一起被摁回来 ——
     * 所以只是极短的一帧回弹，不会留下错误状态。</p>
     */
    private void flipPage(int slotId) {
        int rowIndex = slotId - VoidWarehouseData.NAV_START;
        int pageCount = this.data.pageCount();
        int current = this.pageContainer.page();

        // 只剩「上一页 / 下一页」两种翻页（首页 / 末页按钮已去掉，见 VoidWarehouseNav）
        int target = switch (rowIndex) {
            case VoidWarehouseNav.SLOT_PREV -> current - 1;
            case VoidWarehouseNav.SLOT_NEXT -> current + 1;
            default -> current;
        };
        target = Math.max(0, Math.min(target, pageCount - 1));
        if (target == current) {
            return;
        }
        this.pageContainer.setPage(target);
        // 只有本菜单的槽位内容变了（页码是本玩家私有的）
        this.sendAllDataToRemote();
    }

    /**
     * 切换潜影盒模式（槽位 47 的开关）。
     *
     * <p>状态存在<b>按玩家持久化的</b> {@link VoidWarehouseViews.View} 里：
     * 别人开了开关不会影响我，而我关掉界面再打开、甚至重连回来，开关还是我离开时的样子。</p>
     */
    private void toggleShulkerMode(Player player) {
        boolean enabled = !this.pageContainer.shulkerMode();
        this.pageContainer.setShulkerMode(enabled);
        this.sendAllDataToRemote();
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(enabled ? MSG_SHULKER_ON : MSG_SHULKER_OFF);
        }
    }

    /**
     * 切换「数量排序」（槽位 51 的罗盘开关）。
     *
     * <p>状态同样是<b>按玩家持久化</b>的，所以「关掉界面再打开」不会把开关复位。</p>
     *
     * <p>切换后必须整页重发，因为本页 45 格<b>整体换人</b>了 ——
     * 不只是刷新图标，而是每一格指向的仓库格号都变了。</p>
     *
     * <p>页码不需要夹取：排序只改变「第 n 格放谁」，不改变 {@code entryCount()}，
     * 所以 {@code pageCount()} 与切换前一模一样。</p>
     */
    private void toggleAmountSort(Player player) {
        boolean enabled = !this.pageContainer.amountSort();
        this.pageContainer.setAmountSort(enabled);
        this.sendAllDataToRemote();
        this.message(player, enabled ? MSG_SORT_ON : MSG_SORT_OFF);
    }

    /**
     * 潜影盒模式下的取出：交出<b>1 个</b>潜影盒。
     *
     * <p>顺序是「先算量 → 先查料 → 把盒子放进背包 → 放成功了才扣账」，全程
     * <b>all-or-nothing</b>：任何一步不满足就整次放弃，仓库不会被扣掉一个物品。
     * 这样绝不会出现「扣了 2 个潜影壳却因为背包满而没拿到盒子」这种账目错乱。</p>
     *
     * <p><b>盒子里装多少个</b>：真实格与「可兑」虚拟条目都按<b>一盒容量</b>封顶
     * （27 组），也就是说「尽量装满」。料 / 库存不够装满时给出一个装了实际数量的盒子，
     * 两种格子的口径完全一致 —— 虚拟条目<b>不会</b>退化成「只给一组」。</p>
     *
     * <p><b>为什么不能先「试放」再真放</b>：{@code moveItemStackTo} 不是演练，
     * 它真的会往槽位里写。拿一份副本去试放，等于把盒子放进背包一次、再放一次 ——
     * 直接复制出一个盒子（这个坑是被自检抓出来的）。所以这里的做法是
     * <b>直接放真的那一份</b>，{@code box} 是「放完之后还剩多少」。
     * 潜影盒的 {@code maxStackSize} 恒为 1，放进背包这件事天然只有「整个放进去」和
     * 「一个都没放进去」两种结果，所以「放完不为空」就等于「一点都没放进去」。</p>
     */
    private void withdrawShulkerBox(VoidWarehouseData.DisplaySlot target, Player player) {
        if (target.isEmpty()) {
            return;
        }
        ItemStack template = target.template();
        long capacity = VoidWarehouseShulker.capacity(template);

        // ① 这一格现在能拿出多少个 + （可兑条目）这一笔账怎么扣。
        //    真实格看库存、可兑条目看「现在能兑出多少」，两者都按<b>一盒容量</b>封顶。
        //
        //    ⚠️ 可兑条目这里的上限必须是「容量」而不是 offer.obtainable() 直接取 min：
        //    obtainable 是按「一盒容量」规划出来的（见 VoidWarehouseData#exchangeOffers），
        //    这里重新按容量规划一次，兼作「库存从缓存算出来到现在有没有变」的新鲜度校验 ——
        //    规划是只读的，所以放在最前面也不会动到任何账。
        long available;
        VoidWarehouseExchange.Plan exchange = null;
        if (target.entry() != null) {
            available = target.entry().amount().min(BigInteger.valueOf(capacity)).longValue();
        } else {
            exchange = this.data.planExchangeMax(target.template(), BigInteger.valueOf(capacity));
            available = exchange == null
                    ? 0L
                    : exchange.amount().min(BigInteger.valueOf(capacity)).longValue();
        }
        if (available <= 0) {
            return;
        }

        // ② 盒子材料够不够？—— 优先消耗仓库里现成的空潜影盒，不够的那些才动用潜影壳 + 箱子。
        //    必须与「聊天按数量取货」用同一份算法（VoidWarehouseData.planBoxMaterials），
        //    否则玩家会遇到「库里有空盒，却在界面里被要求补潜影壳」的分裂行为。
        VoidWarehouseData.BoxMaterials plan = this.data.planBoxMaterials(1);
        if (!this.data.hasBoxMaterials(plan)) {
            this.message(player, MSG_NO_BOX_PARTS);
            return;
        }

        // ③ 盒子里的东西必须在<b>把盒子放进背包之前</b>就规划好（就是上面的 exchange）。
        //    反过来做的话，万一规划失败，盒子已经进了玩家背包、仓库却一个字节都没扣 ——
        //    那就是凭空造物。这一条是这条路唯一的凭空造物入口，所以单独盯住。
        //    盒子装的就是 available 个，而 exchange 正是「凑够 available 个」的那份账，
        //    两者同源，不会出现「盒子里有 64 个、账上只扣了 2 个」这种错位。

        // ④ 真的把盒子放进背包。放不下（背包满）时 box 会原封不动剩在手上 ——
        //    此时仓库还一个字节都没动，直接放弃即可。
        ItemStack box = VoidWarehouseShulker.pack(template, (int) available);
        this.moveItemStackTo(box, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
        if (!box.isEmpty()) {
            this.message(player, MSG_BOX_INV_FULL);
            return;
        }

        // ⑤ 盒子已经进背包了，这才扣账。三步都不能失败（① ② ③ 已验过），但顺序有讲究：
        //    取出物品必须排在最前 —— 扣空盒 / 壳 / 箱会移除别的格子、让 entries 前移，
        //    若先扣材料再按 realIndex 取物，那个下标就可能已经指向别的物品了。
        if (exchange != null) {
            this.data.applyExchange(exchange);
        } else {
            this.data.take(target.realIndex(), (int) available);
        }
        this.data.consumeBoxMaterials(plan);
    }

    /**
     * 右键：光标空 → 取一组进背包；光标上拿着同一种物品 → 存进仓库；拿着别的物品 → 什么都不做。
     *
     * <p>「存入」走 {@link VoidWarehouseData#insert(ItemStack)}：同一种物品只会占一格，
     * 数量直接累加，所以在第 5 页放东西也会合并到第 1 页那一格上。
     * <b>「可兑」虚拟条目不支持存入</b>：它背后根本没有一格，把东西「存进去」
     * 没有任何现实含义（真要存就直接存那件物品本身，物品列表里自然会多出它的一格）。</p>
     */
    private void withdrawOrStore(VoidWarehouseData.DisplaySlot target, Player player) {
        if (target.isEmpty()) {
            return;
        }
        ItemStack carried = this.getCarried();
        if (!carried.isEmpty() && target.entry() != null
                && ItemStack.isSameItemSameComponents(carried, target.template())) {
            // 光标上就是同一种物品：直接存进仓库（数量累加到同一格）
            this.insert(carried, player);
            return;
        }
        this.withdrawFrom(target, target.entry() != null ? target.entry().maxStack() : 0, player);
    }

    /**
     * 取出到<b>玩家背包</b>（不经过光标，物品不会悬浮在鼠标上）。
     *
     * <p>走 {@link VoidWarehouseData#withdraw}：成品有货就直接扣成品，
     * 成品一件都没有且规则允许时改用 9 合 1 兑换。<b>背包塞不下的部分原样退回仓库</b>，
     * 绝不丢、也不会凭空产生 —— 兑换那条路的退回是「整笔账反向执行 + 按实际用量重做」，
     * 精确到个位数，不是靠猜该退回哪个物品。</p>
     *
     * @param wanted 想要几个（{@code <= 0} 时按「该物品一组」算）
     */
    private void withdrawFrom(VoidWarehouseData.DisplaySlot target, int wanted, Player player) {
        if (target.isEmpty() || player == null) {
            return;
        }
        ItemStack template = target.template();
        int ask = wanted > 0 ? wanted : Math.max(1, template.getMaxStackSize());
        VoidWarehouseData.Withdrawal withdrawal = this.data.withdraw(template, BigInteger.valueOf(ask));
        if (withdrawal.isEmpty()) {
            return;
        }
        ItemStack moved = withdrawal.stack();
        this.moveItemStackTo(moved, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
        if (moved.isEmpty()) {
            return;
        }
        // 背包塞不下：把没放下的那部分还回仓库（走 returnUnplaced，不受筛选规则影响，绝不丢东西）
        withdrawal.returnUnplaced(withdrawal.taken() - moved.getCount());
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(MSG_INV_FULL);
        }
    }

    /**
     * 把 {@code stack} 尽可能塞进仓库。{@code stack} 会被就地清空，
     * 调用方（例如玩家的背包槽位）随后看到的就是剩余数量。
     *
     * <p>被筛选规则拒收时堆叠原样不动，并给玩家一条明确的提示 ——
     * 否则玩家只会看到「东西没进仓库」而不知道为什么。</p>
     */
    private void insert(ItemStack stack, Player player) {
        if (stack.isEmpty()) {
            return;
        }
        if (this.data.insert(stack)) {
            return;
        }
        this.reportReject(stack, player);
    }

    // ------------------------------------------------------------------ Shift 点击

    /**
     * 重写原版的 {@link ChestMenu#quickMoveStack}。
     *
     * <p>两个方向都自己算：</p>
     * <ul>
     *   <li>仓库 → 背包：取出一组；背包塞不下的部分<b>原样退回仓库</b>，绝不丢；</li>
     *   <li>背包 → 仓库：整组存入（同类合并到同一格），不经过原版的 moveItemStackTo，
     *       因为原版不认识「一种物品一格、数量无上限」这套规则。</li>
     * </ul>
     * 原版会把目标范围算成 0..53（含导航行），这也是必须重写的原因之一：不改的话
     * Shift 点击可能把物品塞进按钮格里。
     *
     * <h2>为什么两个方向都返回 {@link ItemStack#EMPTY}</h2>
     * <p>原版 {@code doClick} 的 {@code QUICK_MOVE} 分支是个循环：
     * <b>只要返回值不为空、且和那一格现在装的是同一种物品，就会再调一次</b>。
     * 我们如果把「刚搬走的那些」返回去，Shift 点击仓库格就会一直被循环调用，
     * 直到把那一整格（可能上万个）全掏进背包 —— 而文档承诺的是「取出一组」。
     * 返回空栈等于告诉原版「没有更多可搬的了」，于是<b>一次点击 = 正好一组</b>，
     * 普通模式与潜影盒模式行为一致、与排序模式无关。</p>
     *
     * <p>两个方向最后都做一次 {@link #syncAllViewers(Player)}：背包 → 仓库这条路是被原版
     * {@code doClick} 间接调用的，我们没法在 {@code clicked} 里统一收口；
     * 而它恰恰是最需要「全量重发」的一条路（客户端对 Shift 点击的预测与我们的真实语义
     * 完全不是一回事，见 {@link #syncAllViewers(Player)} 的说明）。</p>
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        // 导航行不可移动（NavSlot 本身也会挡住，这里是二重保险）
        if (index >= VoidWarehouseData.NAV_START && index < VoidWarehouseData.TOTAL_SLOTS) {
            return ItemStack.EMPTY;
        }

        if (index < VoidWarehouseData.NAV_START) {
            VoidWarehouseData.DisplaySlot target = this.slotAt(index);
            if (target.isEmpty()) {
                return ItemStack.EMPTY;
            }
            // 潜影盒模式：Shift 点击同样是「交出 1 个满潜影盒」
            if (this.pageContainer.shulkerMode()) {
                this.withdrawShulkerBox(target, player);
                this.syncAllViewers(player);
                return ItemStack.EMPTY;
            }
            // ---- 仓库 → 玩家背包：取出一组 ----
            // 这一格是谁只解析这一次（数量排序 + 可兑条目都让视图↔数据是动态映射）
            this.withdrawFrom(target, 0, player);
            this.syncAllViewers(player);
            return ItemStack.EMPTY;
        }

        // ---- 玩家背包 → 仓库：整组存入 ----
        ItemStack slotItem = slot.getItem();
        if (!this.data.insert(slotItem)) {
            // 带数据的物品 / 盒内带数据的潜影盒被拒收：槽位原样留在背包里，并说明原因
            this.reportReject(slotItem, player);
            return ItemStack.EMPTY;
        }
        // 收下时 insert 已把 slotItem 就地清空
        slot.setByPlayer(ItemStack.EMPTY);
        this.syncAllViewers(player);
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ 工具

    /** 给玩家发一条聊天栏提示（非服务端玩家 / 自检里手工造的玩家直接忽略） */
    private void message(Player player, Component text) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(text);
        }
    }

    /**
     * 说清楚「为什么没进仓库」。
     *
     * <p>三条拒绝理由长相完全不同，提示也分开：</p>
     * <ul>
     *   <li>潜影盒 → 是<b>盒子里</b>混了带数据的物品（盒子本身不是问题）；</li>
     *   <li>普通物品而且带数据组件 → 是它自己不合格；</li>
     *   <li>其余 → 落到「仓库已满」这条理论兜底（数量无上限，正常到不了）上。</li>
     * </ul>
     */
    private void reportReject(ItemStack stack, Player player) {
        Component text;
        if (VoidWarehouseShulker.isShulkerBox(stack)) {
            text = MSG_BOX_REJECTED;
        } else if (!VoidWarehouseData.isStorable(stack)) {
            text = MSG_REJECTED;
        } else {
            text = MSG_FULL;
        }
        this.message(player, text);
    }

    /**
     * 数字键交换：把快捷栏第 {@code hotbarIndex} 格整组存入仓库。
     * 快捷栏那一格是空的就什么都不做；被筛选规则拒收时原样留在快捷栏里。
     */
    private void storeHotbarStack(int hotbarIndex, Player player) {
        Slot hotbar = this.hotbarSlot(hotbarIndex);
        if (hotbar == null || !hotbar.hasItem()) {
            return;
        }
        ItemStack moving = hotbar.getItem().copy();
        if (!this.data.insert(moving)) {
            this.reportReject(moving, player);
            return;
        }
        // 收下时 insert 已把 moving 就地清空，写回去等价于清空那一格
        hotbar.set(moving);
        hotbar.setChanged();
    }

    /** 按容器内下标找玩家快捷栏槽位（不依赖槽位排列顺序） */
    private Slot hotbarSlot(int hotbarIndex) {
        for (int i = VoidWarehouseData.TOTAL_SLOTS; i < this.slots.size(); i++) {
            Slot candidate = this.slots.get(i);
            if (candidate.container instanceof Inventory && candidate.getContainerSlot() == hotbarIndex) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 本页第 {@code slotId} 格背后到底是什么：真实库存格、9 合 1「可兑」虚拟条目、或空。
     *
     * <p>唯一的一处「这一格是谁」的换算（{@link VoidWarehousePageContainer#slotAt(int)}）。
     * 菜单里所有取物品的路径都<b>先调它一次、把结果存进局部变量</b>，
     * 之后渲染、扣账、判定都用同一个值 ——
     * 避免「解析两次、中间顺序变了导致点到 A 却取走 B」，
     * 也避免「渲染时是可兑条目、点击时却按真实格处理」。</p>
     */
    private VoidWarehouseData.DisplaySlot slotAt(int slotId) {
        return this.pageContainer.slotAt(slotId);
    }

    /**
     * 把这次改动立刻推给所有正在看同一个仓库的玩家。
     *
     * <h2>为什么是「全量重发」而不是「发差异」</h2>
     * <p>26.3 的菜单同步换成了<b>预测 + 校验</b>机制：服务端每个槽位维护一个
     * {@code RemoteSlot}（{@code 服务端以为客户端手里是什么}），只有它和当前内容
     * <b>不一致</b>时才发差异包（{@code AbstractContainerMenu#broadcastChanges}）。
     * 而客户端在点下去的那一刻会<b>自己先跑一遍原版逻辑做预测</b>，然后把改过的槽位
     * 哈希上报（{@code HashedStack}）。</p>
     *
     * <p>问题在于：本仓库的客户端是个<b>纯原版大箱子</b>，它的预测（把物品塞进某个格子）
     * 和我们的真实语义（数量累加到「一种物品一格」上、整个页面按数量重排）完全不是一回事。
     * 一旦预测的结果和服务端某几个槽位的值「碰巧一致」，差异包就不会发，
     * 客户端就<b>永久停在自己的错误顺序上，直到关掉界面重开</b> —— 这正是
     * 「数量排序开着，新存了东西但排列没跟着变」这个 bug 的真正来源。</p>
     *
     * <p>{@link AbstractContainerMenu#sendAllDataToRemote()} 正好治这个病：它把
     * <b>全部 54 格重新取一遍当前内容</b>（{@code Slot#getItem()} → 我们的容器 →
     * 永远是当前正确顺序），用 {@code RemoteSlot#force(...)} 把服务端的「以为」直接
     * 摁成事实，再发一个全量内容包。客户端拿到的就是绝对真相，任何预测残留都被覆盖掉。
     * 代价只是一次点击多几十个物品的网络量，完全可以忽略。</p>
     */
    private void syncAllViewers(Player actor) {
        if (!(actor instanceof ServerPlayer serverPlayer)) {
            this.sendAllDataToRemote();
            return;
        }
        MinecraftServer server = serverPlayer.level().getServer();
        if (server == null) {
            this.sendAllDataToRemote();
            return;
        }
        boolean synced = false;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.containerMenu instanceof VoidWarehouseMenu menu && menu.data == this.data) {
                menu.sendAllDataToRemote();
                synced = true;
            }
        }
        if (!synced) {
            this.sendAllDataToRemote();
        }
    }
}
