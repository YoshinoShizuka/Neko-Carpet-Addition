package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 入口三：<b>对着实体右键</b>。
 *
 * <p>右键实体的公共汇聚点是 {@code Player#interactOn(Entity, InteractionHand)}
 * （客户端 {@code MultiPlayerGameMode#interact} 和服务端都会调到这里），
 * 所以在这一个点就能同时覆盖双端。</p>
 *
 * <p>已核对 1.21.11 字节码：这里只有两个参数，<b>没有</b> 26.x 那个 {@code Vec3}
 * 命中点参数，描述符必须跟着改，否则注入会在运行时失败。</p>
 */
@Mixin(Player.class)
public abstract class PlayerInteractOnMixin {

    @Inject(
            method = "interactOn(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseInteractOn(Entity entity, InteractionHand hand,
                                                           CallbackInfoReturnable<InteractionResult> cir) {
        Player self = (Player) (Object) this;
        ItemStack stack = self.getItemInHand(hand);
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        cir.setReturnValue(VoidWarehouseTrigger.interact(self.level(), self, hand));
    }
}
