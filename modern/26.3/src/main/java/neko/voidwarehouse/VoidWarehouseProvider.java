package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
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

    @Override
    public Component getDisplayName() {
        return this.title;
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new VoidWarehouseMenu(syncId, playerInventory, this.data, player);
    }
}
