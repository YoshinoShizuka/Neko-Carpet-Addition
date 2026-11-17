package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 给 {@code ServerPlayer#openMenu(MenuProvider)} 用的菜单工厂。
 *
 * <p><b>标题为什么用字面量而不是翻译键：</b>方案 A 允许纯原版客户端进入，
 * 而原版客户端没有我们的语言文件，翻译键会被原样显示成
 * {@code container.carpet_neko_addition.void_warehouse}。所以标题必须由服务端直接给出成品文本。</p>
 *
 * <p><b>双语版（方案 B）</b>：标题不再是一份写死的常量，而是<b>跟着触发名走</b> ——
 * 玩家把工作台改成什么名字（「虚空仓库」或 "Void Warehouse"），打开的界面就叫什么。
 * 触发入口（{@link VoidWarehouseTrigger#interact}）把手里那件触发物的名字传进来，
 * 按-数量取货结束重开界面（{@link VoidWarehouseWithdraw}）按玩家语言给同款标题。</p>
 *
 * <p>页码是每个玩家各自的状态，不在这里决定：这里只把共享数据交给新的
 * {@link VoidWarehouseMenu}，由它自己新建一个从第 1 页开始的页面视图。</p>
 */
public record VoidWarehouseProvider(VoidWarehouseData data, Component title) implements MenuProvider {
    /**
     * 触发用的<b>中文</b>物品名（触发判定与中文标题都用它）。
     * 英文别名见 {@link VoidWarehouseText#TITLE_TEXT_EN}。
     */
    public static final String TITLE_TEXT = "虚空仓库";

    /** 缺省标题（中文）：给没有触发物上下文的旧调用路径兜底 */
    public static final Component DEFAULT_TITLE = Component.literal(TITLE_TEXT);

    /**
     * 按玩家语言取<b>默认标题</b>（「虚空仓库」/ {@code Void Warehouse}）。
     *
     * <p>两处用它：</p>
     * <ul>
     *   <li>{@code VoidWarehouseTrigger#interact} 里「手里不是仓库」的那两条入口 ——
     *       右键已放置的终端、右键接了漏斗的工作台，<b>空手也能开</b>，
     *       因此不能拿手里的东西当标题（空手时 {@code ItemStack.EMPTY.getHoverName()}
     *       是「空气」，界面标题就显示成「空气」）；</li>
     *   <li>按数量取货结束后重开界面（{@code VoidWarehouseWithdraw}）。</li>
     * </ul>
     */
    public static Component defaultTitle(ServerPlayer player) {
        return defaultTitle(VoidWarehouseText.isEnglish(player));
    }

    /**
     * 同上，语言直接给（{@code true} = 英文）。
     *
     * <p>拆出这一层是为了让自检<b>不依赖假玩家</b>：{@code openMenu} 之后标题只活在
     * {@code ClientboundOpenScreenPacket} 里，菜单本身不留副本、事后读不回来 ——
     * 唯一能直接断言的就是「给定语言 + 手持物 → 标题」这个纯函数。</p>
     */
    static Component defaultTitle(boolean en) {
        return Component.literal(VoidWarehouseText.tr(en, TITLE_TEXT, VoidWarehouseText.TITLE_TEXT_EN));
    }

    @Override
    public Component getDisplayName() {
        return this.title;
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new VoidWarehouseMenu(syncId, playerInventory, this.data, player);
    }
}
