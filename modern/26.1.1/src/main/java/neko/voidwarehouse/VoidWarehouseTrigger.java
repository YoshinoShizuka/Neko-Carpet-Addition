package neko.voidwarehouse;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * 触发判定：判断手里的东西是不是「虚空仓库」，以及被右键时该怎么办。
 *
 * <p>触发物就是<b>原版工作台</b>（不是新注册的物品），判定条件是
 * 「物品是工作台 且 自定义名（铁砧改名）精确等于「虚空仓库」」。</p>
 *
 * <p>因为原版工作台是已经注册好的单例，无法替换它的 Item 实例，
 * 所以三个右键入口（空气 / 方块 / 实体）由 Mixin 接管，见 {@code neko.voidwarehouse.mixin} 包。</p>
 */
public final class VoidWarehouseTrigger {
    /** 触发用的名字，精确匹配（区分大小写、不做 trim） */
    public static final String ITEM_NAME = VoidWarehouseProvider.TITLE_TEXT;

    private VoidWarehouseTrigger() {
    }

    /** 是不是「虚空仓库」 */
    public static boolean isVoidWarehouse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() != Items.CRAFTING_TABLE) {
            return false;
        }
        var customName = stack.getCustomName();
        return customName != null && ITEM_NAME.equals(customName.getString());
    }

    /**
     * 三个右键入口统一走这里。
     *
     * <p>服务端权威：客户端只返回一个「已处理」用来抑制原版的方块预放置预测，
     * 真正的开界面动作只在服务端发生。</p>
     */
    public static InteractionResult interact(Level level, Player player, InteractionHand hand) {
        // 规则关掉时完全交还原版（改名的工作台恢复成普通工作台）
        if (!VoidWarehouseSettings.voidWarehouse) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!VoidWarehouse.open(serverPlayer)) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
