package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>「对空气右键」的主拦截层（服务端），比任何第三方回调都早。</b>
 *
 * <h2>为什么必须做到这一层</h2>
 * <p>原来「对空气右键」只有 {@code ItemUseMixin}（注入 {@code Item#use} 的 HEAD）。
 * 但 {@code Item#use} 是所有模组共同的钩子坑位，注入顺序不归我们掌握 ——
 * 谁后执行，谁的 {@code setReturnValue} 就生效。</p>
 *
 * <p>实测修复过一次、再跑一遍又坏（同一份代码两次结果相反），这才把整条链路查到底：</p>
 * <pre>
 *   fabric-api 0.161.0+26.3 / fabric-events-interaction-v0 5.3.6
 *     ServerPlayerGameModeMixin#interactItem        ← refmap 里的旧名 = ServerPlayerGameMode#useItem
 *         └─ UseItemCallback.EVENT.invoker().interact(player, level, hand)
 *              └─ if (result != PASS) { cir.setReturnValue(result); cir.cancel(); }
 *
 *   ItemStackMixin#handleUseEvent                  ← 用 MixinExtras 包住 Item#use
 *         └─ ItemEvents.USE
 * </pre>
 * <p>所以 <b>{@code UseItemCallback} 的触发点正是 {@code ServerPlayerGameMode#useItem}</b> ——
 * 恰好就是我们上移一层之后落脚的那个方法。同一方法 + 同一 HEAD，两边谁后执行谁赢，
 * 结果就随 JVM 启动顺序漂了。</p>
 *
 * <h2>这一层凭什么稳</h2>
 * <p>{@code ServerGamePacketListenerImpl#handleUseItem} 是服务端「收到 use-item 数据包」
 * 的处理入口，Fabric API 在那个类里只 hook 了 {@code handleInteract} 和 pick 系列
 * （见它的 mixins 列表），<b>碰都没碰这个方法</b> —— 这里没有同坑竞争者。</p>
 *
 * <p>注入点选在方法体内 <b>即将调用 {@code ServerPlayerGameMode#useItem} 的那一步</b>
 * （而不是 HEAD）：再往上一点，包序号回执、朝向同步、{@code resetLastActionTime} 还没做；
 * 再往下一点就已经进 {@code useItem}、第三方回调的副作用就发生了。cancel 掉这一步，
 * 前面的准备动作照常完成，后面的 {@code Item#use} 一次都不会调用。</p>
 *
 * <p>「对着实体右键」走的是另一条包（ServerboundInteractPacket），最终会回落到
 * {@code ServerPlayerGameMode#useItem}，那一层由 {@code ServerPlayerGameModeUseItemMixin}
 * 兜底 —— 那里仍然有竞争，但那是次要路径，有它比没有强。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Inject(
            method = "handleUseItem(Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"
            ),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseHandleUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null || !VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        InteractionHand hand = packet.hand();
        ItemStack stack = player.getItemInHand(hand);
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        // 到这一步为止必要的准备（包回执 / 朝向 / 动作时间）都已做完。
        // 直接取消对 ServerPlayerGameMode#useItem 的调用：Fabric 的 UseItemCallback 挂在
        // 那个方法的 HEAD 上，只要不进那个方法，第三方回调一次都不会被调用。
        VoidWarehouseTrigger.interact(player.level(), player, hand);
        ci.cancel();
    }
}
