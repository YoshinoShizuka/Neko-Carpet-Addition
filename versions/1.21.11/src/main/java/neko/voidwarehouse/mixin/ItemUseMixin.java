package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 入口一：<b>对空气右键</b>。
 *
 * <p>{@code Item#use(Level, Player, InteractionHand)} 是物品右键的公共汇聚点，
 * 客户端和服务端都会走到（客户端走的是预测路径），所以在这里拦截
 * 既能开界面、又能阻止客户端做无用的预测。</p>
 *
 * <p>判定不通过时立即 return，对其它物品零影响。</p>
 */
@Mixin(Item.class)
public abstract class ItemUseMixin {

    @Inject(
            method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseUse(Level level, Player player, InteractionHand hand,
                                                    CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = player.getItemInHand(hand);
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        cir.setReturnValue(VoidWarehouseTrigger.interact(level, player, hand));
    }
}
