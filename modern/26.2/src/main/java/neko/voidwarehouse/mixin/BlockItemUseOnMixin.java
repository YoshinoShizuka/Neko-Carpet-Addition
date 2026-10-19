package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 入口二：<b>对着方块右键（物品放置路径）</b>。
 *
 * <p>工作台是 {@link BlockItem}，右键方块时物品这一侧走的是 {@code BlockItem#useOn}。
 * 在这里拦截有两个作用：</p>
 * <ul>
 *   <li>客户端：返回「已处理」可以阻止客户端做「已经放了一个方块」的预放置预测，
 *       避免出现幽灵方块；</li>
 *   <li>服务端：兜底（真正的开界面在 {@code ServerPlayerGameModeMixin} 那一层就拦掉了，
 *       一般走不到这里）。</li>
 * </ul>
 *
 * <h2>潜行 = 放置</h2>
 * <p>{@code player.isSecondaryUseActive()} 为真（潜行）时<b>原样放行</b>，
 * 让原版把工作台真的放下去 —— 这是放置仓库终端的唯一入口，
 * 服务端放下后由 {@code BlockPlacedByMixin} 登记坐标。</p>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemUseOnMixin {

    @Inject(
            method = "useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseUseOn(UseOnContext context,
                                                      CallbackInfoReturnable<InteractionResult> cir) {
        Player player = context.getPlayer();
        if (player == null) {
            return;
        }
        // 潜行 = 原版的「绕过交互、直接放置」：交还原版，把工作台放下去
        if (player.isSecondaryUseActive()) {
            return;
        }
        ItemStack stack = context.getItemInHand();
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        cir.setReturnValue(VoidWarehouseTrigger.interact(context.getLevel(), player, context.getHand()));
    }
}
