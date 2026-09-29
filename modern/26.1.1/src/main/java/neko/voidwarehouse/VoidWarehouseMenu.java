package neko.voidwarehouse;

import net.minecraft.ChatFormatting;
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
import net.minecraft.world.phys.Vec3;

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
    /** 打开界面的位置允许的最大距离（单位：格），对齐原版箱子的 8 格 */
    public static final double MAX_DISTANCE = 8.0;
    private static final double MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

    /** 装一个潜影盒需要的潜影壳 / 箱子数量（{@link BigInteger} 形式，方便和仓库数量直接比） */
    private static final BigInteger SHELL_NEED = BigInteger.valueOf(VoidWarehouseShulker.SHELL_COST);
    private static final BigInteger CHEST_NEED = BigInteger.valueOf(VoidWarehouseShulker.CHEST_COST);

    private static final Component MSG_FULL = Component.literal("虚空仓库已满，无法放入");
    private static final Component MSG_RESCUED =
            Component.literal("背包已满，手上拿着的物品已存入虚空仓库");
    private static final Component MSG_INV_FULL =
            Component.literal("背包已满，放不下的物品留在了虚空仓库");
    private static final Component MSG_REJECTED =
            Component.literal("该物品带有额外数据（耐久 / 附魔 / 自定义名等），虚空仓库拒收");
    private static final Component MSG_BOX_REJECTED =
            Component.literal("潜影盒里含有带额外数据的物品（耐久 / 附魔 / 自定义名等），整盒拒收");
    private static final Component MSG_SHULKER_ON = infoMessage("潜影盒模式开");
    private static final Component MSG_SHULKER_OFF = infoMessage("潜影盒模式关");
    private static final Component MSG_NO_BOX_PARTS =
            Component.literal("仓库里的潜影壳或箱子不足，无法装盒（每盒需要 潜影壳 ×"
                    + VoidWarehouseShulker.SHELL_COST + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST + "）");
    private static final Component MSG_BOX_INV_FULL =
            Component.literal("背包放不下潜影盒，本次取出已取消（仓库未扣减任何物品）");
    private static final Component MSG_SORT_ON = infoMessage("数量排序开");
    private static final Component MSG_SORT_OFF = infoMessage("数量排序关");

    private final VoidWarehouseData data;
    private final VoidWarehousePageContainer pageContainer;
    private final Vec3 openPos;
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
        this.openPos = opener.position();
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
     * 对应 Yarn 的 {@code canUse}。返回 false 时原版会安全地关掉界面，
     * 而本类的 {@link #removed(Player)} 不会丢弃仓库内容，所以关掉不会丢东西。
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
        if (!player.level().dimension().equals(this.openDimension)) {
            return false;
        }
        return player.distanceToSqr(this.openPos) <= MAX_DISTANCE_SQ;
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
        // 空格子（屏障占位符）：任何输入都无响应，纯占位，直接返回、不做任何重发
        if (this.entryAt(slotId) == null) {
            return;
        }
        // 潜影盒模式下，所有「取出」动作都换成交出 1 个装满的潜影盒；
        // 「存入」的路径（数字键、Shift 点背包格）完全不受影响。
        boolean boxMode = this.pageContainer.shulkerMode();

        switch (input) {
            case PICKUP -> {
                if (button == 0) {
                    if (boxMode) {
                        this.withdrawShulkerBox(slotId, player);
                    } else {
                        this.withdrawToInventory(slotId, 1, player);
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
                        VoidWarehouseData.Entry entry = this.entryAt(slotId);
                        if (entry != null && player instanceof ServerPlayer serverPlayer) {
                            VoidWarehouseWithdraw.request(serverPlayer, entry.template());
                        }
                        return;
                    } else {
                        this.withdrawOrStore(slotId, player);
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
                    this.withdrawShulkerBox(slotId, player);
                } else {
                    this.withdrawToInventory(slotId, 1, player);
                }
                this.syncAllViewers(player);
            }
            case QUICK_MOVE -> {
                // Shift 点击仓库格：潜影盒模式交出一盒，普通模式取一组
                if (boxMode) {
                    this.withdrawShulkerBox(slotId, player);
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
     * <p><b>为什么不能先「试放」再真放</b>：{@code moveItemStackTo} 不是演练，
     * 它真的会往槽位里写。拿一份副本去试放，等于把盒子放进背包一次、再放一次 ——
     * 直接复制出一个盒子（这个坑是被自检抓出来的）。所以这里的做法是
     * <b>直接放真的那一份</b>，{@code box} 是「放完之后还剩多少」。
     * 潜影盒的 {@code maxStackSize} 恒为 1，放进背包这件事天然只有「整个放进去」和
     * 「一个都没放进去」两种结果，所以「放完不为空」就等于「一点都没放进去」。</p>
     */
    private void withdrawShulkerBox(int slotId, Player player) {
        // 索引只解析这一次，后面扣账用同一个值（数量排序下视图↔数据是动态映射）
        int index = this.slotIndexOf(slotId);
        VoidWarehouseData.Entry entry = this.data.entryAt(index);
        if (entry == null) {
            return;
        }
        ItemStack template = entry.template();
        long capacity = VoidWarehouseShulker.capacity(template);
        // 库存是 BigInteger（无上限），但一盒最多 1728 个，所以这里一定在 int 范围内
        int fill = entry.amount().min(BigInteger.valueOf(capacity)).intValue();
        if (fill <= 0) {
            return;
        }

        // ① 潜影壳 / 箱子够不够？不够就整次拒绝，只发提示
        if (this.data.amountOf(Items.SHULKER_SHELL).compareTo(SHELL_NEED) < 0
                || this.data.amountOf(Items.CHEST).compareTo(CHEST_NEED) < 0) {
            this.message(player, MSG_NO_BOX_PARTS);
            return;
        }

        // ② 真的把盒子放进背包。放不下（背包满）时 box 会原封不动剩在手上 ——
        //    此时仓库还一个字节都没动，直接放弃即可。
        ItemStack box = VoidWarehouseShulker.pack(template, fill);
        this.moveItemStackTo(box, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
        if (!box.isEmpty()) {
            this.message(player, MSG_BOX_INV_FULL);
            return;
        }

        // ③ 盒子已经进背包了，这才扣账：物品 + 潜影壳 + 箱子（两样都在 ① 里验过，不会失败）
        this.data.take(index, fill);
        this.data.takeItem(Items.SHULKER_SHELL, VoidWarehouseShulker.SHELL_COST);
        this.data.takeItem(Items.CHEST, VoidWarehouseShulker.CHEST_COST);
    }

    /**
     * 右键：光标空 → 取一组进背包；光标上拿着同一种物品 → 存进仓库；拿着别的物品 → 什么都不做。
     *
     * <p>「存入」走 {@link VoidWarehouseData#insert(ItemStack)}：同一种物品只会占一格，
     * 数量直接累加，所以在第 5 页放东西也会合并到第 1 页那一格上。</p>
     */
    private void withdrawOrStore(int slotId, Player player) {
        ItemStack carried = this.getCarried();
        if (!carried.isEmpty()) {
            VoidWarehouseData.Entry entry = this.entryAt(slotId);
            if (entry != null && ItemStack.isSameItemSameComponents(carried, entry.template())) {
                // 光标上就是同一种物品：直接存进仓库（数量累加到同一格）
                this.insert(carried, player);
                return;
            }
        }
        this.withdrawToInventory(slotId, this.batchSize(slotId), player);
    }

    /**
     * 取出到<b>玩家背包</b>（不经过光标，物品不会悬浮在鼠标上）。
     *
     * <p>先用 {@link VoidWarehouseData#take} 扣账，再造一份全新的堆叠
     * {@code copyWithCount(actual)} 塞进背包（{@code moveItemStackTo} 会优先并进已有的同类格，
     * 再找空格）。背包塞不下的部分<b>原样退回仓库</b>，绝不丢、也不会凭空产生。</p>
     */
    private void withdrawToInventory(int slotId, int wanted, Player player) {
        int index = this.slotIndexOf(slotId);
        VoidWarehouseData.Entry entry = this.data.entryAt(index);
        if (entry == null || wanted <= 0) {
            return;
        }
        ItemStack template = entry.template();
        int actual = this.data.take(index, wanted);
        if (actual <= 0) {
            return;
        }
        ItemStack moved = template.copyWithCount(actual);
        this.moveItemStackTo(moved, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
        if (!moved.isEmpty()) {
            // 背包塞不下：原样退回仓库（走 restore，不受筛选规则影响，绝不丢东西）
            this.data.restore(moved);
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(MSG_INV_FULL);
            }
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
            // 潜影盒模式：Shift 点击同样是「交出 1 个满潜影盒」
            if (this.pageContainer.shulkerMode()) {
                this.withdrawShulkerBox(index, player);
                this.syncAllViewers(player);
                return ItemStack.EMPTY;
            }
            // ---- 仓库 → 玩家背包：取出一组 ----
            // 索引只解析这一次（数量排序下视图↔数据是动态映射）
            int dataIndex = this.slotIndexOf(index);
            VoidWarehouseData.Entry entry = this.data.entryAt(dataIndex);
            if (entry == null) {
                return ItemStack.EMPTY;
            }
            ItemStack template = entry.template();
            int actual = this.data.take(dataIndex, entry.maxStack());
            if (actual <= 0) {
                return ItemStack.EMPTY;
            }
            ItemStack moved = template.copyWithCount(actual);
            this.moveItemStackTo(moved, VoidWarehouseData.TOTAL_SLOTS, this.slots.size(), true);
            if (!moved.isEmpty()) {
                // 背包塞不下：原样退回仓库（走 restore，不受筛选规则影响，绝不丢东西）
                this.data.restore(moved);
            }
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
     * 把提示包装成「金色 + [信息] 前缀」的聊天栏消息。
     *
     * <p>只用于两个模式开关（数量排序 / 潜影盒模式）的切换提示，让玩家一眼看出这是一条
     * 「信息类」提醒而非错误。前缀 {@code [信息]} 用加粗金色、正文用金色。</p>
     */
    private static Component infoMessage(String body) {
        return Component.literal("[信息] ")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(ChatFormatting.BOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.GOLD));
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

    /** 本页第 {@code slotId} 格对应的仓库格；该位置是空的（或越界）时返回 null */
    private VoidWarehouseData.Entry entryAt(int slotId) {
        return this.data.entryAt(this.slotIndexOf(slotId));
    }

    /**
     * 本页第 {@code slotId} 格对应的<b>仓库格号</b>。
     *
     * <p>开了「数量排序」时这里会经过一层视图映射，所以结果可能是 {@code -1}（该位置没有东西）。
     * 菜单里所有取物品的路径都<b>先解析一次索引、把结果存进局部变量</b>，
     * 之后 {@code entryAt} 与 {@code take} 用同一个值 ——
     * 避免「解析两次、中间顺序变了导致点到 A 却取走 B」。</p>
     */
    private int slotIndexOf(int slotId) {
        return this.pageContainer.slotIndexOf(slotId);
    }

    /** 一次能取多少 = 该物品的一组 */
    private int batchSize(int slotId) {
        VoidWarehouseData.Entry entry = this.entryAt(slotId);
        return entry == null ? 0 : entry.maxStack();
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
