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
 * <p>页码是每个玩家各自的状态，不在这里决定：这里只把共享数据交给新的
 * {@link VoidWarehouseMenu}，由它自己新建一个从第 1 页开始的页面视图。</p>
 */
public record VoidWarehouseProvider(VoidWarehouseData data) implements MenuProvider {
    /** 界面标题，同时也是触发用的物品名 */
    public static final String TITLE_TEXT = "虚空仓库";

    private static final Component TITLE = Component.literal(TITLE_TEXT);

    @Override
    public Component getDisplayName() {
        return TITLE;
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new VoidWarehouseMenu(syncId, playerInventory, this.data, player);
    }
}
