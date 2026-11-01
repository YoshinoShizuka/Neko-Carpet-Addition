package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
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
    /** 触发用的中文名，精确匹配（区分大小写、不做 trim） */
    public static final String ITEM_NAME = VoidWarehouseProvider.TITLE_TEXT;

    /**
     * 触发用的英文名：{@code Void Warehouse}。
     *
     * <p>双语版（方案 B）：英文玩家改不出中文，触发判定同时接受这个别名。
     * <b>匹配口径与中文不同</b>：中文是玩家社群约定俗成的名字，维持原判
     * （精确、区分大小写、不 trim）；英文别名<b>忽略大小写、容忍首尾空白</b> ——
     * 英文玩家习惯打 "void warehouse" 或 "Void Warehouse"，不应该因为大小写而触发失败。</p>
     */
    public static final String ITEM_NAME_EN = VoidWarehouseText.TITLE_TEXT_EN;

    private VoidWarehouseTrigger() {
    }

    /** 是不是「虚空仓库」（中文名精确匹配，或英文名忽略大小写） */
    public static boolean isVoidWarehouse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() != Items.CRAFTING_TABLE) {
            return false;
        }
        var customName = stack.getCustomName();
        if (customName == null) {
            return false;
        }
        String name = customName.getString();
        // 中文名：维持原判 —— 精确等于「虚空仓库」，多一个空格都不是
        if (ITEM_NAME.equals(name)) {
            return true;
        }
        // 英文别名：忽略大小写、容忍首尾空白（铁砧前后多敲了空格也能用）
        return ITEM_NAME_EN.equalsIgnoreCase(name.trim());
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
        // GUI 标题跟随触发名：玩家把工作台改成什么名字，打开的界面就叫什么
        // （中文玩家看到「虚空仓库」，英文玩家看到他敲的 "Void Warehouse" 大小写原样）。
        // 只有 mixins 判定 isVoidWarehouse 通过才会走到这里，手里的必然是触发物。
        ItemStack triggerStack = player.getItemInHand(hand);
        Component title = triggerStack.getHoverName();
        if (!VoidWarehouse.open(serverPlayer, title)) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
