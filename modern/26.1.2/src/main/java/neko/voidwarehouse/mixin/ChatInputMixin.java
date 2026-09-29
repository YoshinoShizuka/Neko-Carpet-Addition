package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseWithdraw;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>「按数量取货」的聊天输入入口</b>。
 *
 * <p>纯原版客户端没有文本框，玩家输入数量的唯一通道就是聊天栏。这里拦截
 * {@code handleChat}：当玩家正有一条待处理的取货请求（潜影盒模式下右键了某个仓库格），
 * 他下一条聊天消息就会被当成「数量」解析：</p>
 * <ul>
 *   <li>是数字 → 执行取货，<b>吞掉这条消息</b>（不广播到公屏）；</li>
 *   <li>不是数字 → 取消请求、发提示，但<b>放行</b>这条消息（玩家可能只是想说话）。</li>
 * </ul>
 *
 * <p>没有待处理请求时，第一行就 return，普通聊天一个字都不受影响。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ChatInputMixin {

    @Inject(
            method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$withdrawChatInput(ServerboundChatPacket packet, CallbackInfo ci) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).getPlayer();
        if (player == null) {
            return;
        }
        if (!VoidWarehouseWithdraw.hasPending(player)) {
            return;
        }
        if (VoidWarehouseWithdraw.onChat(player, packet.message())) {
            ci.cancel();
        }
    }
}
