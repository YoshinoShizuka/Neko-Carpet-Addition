package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseRecipePrompt;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管原版「聊天里的自定义点击」（{@code ClickEvent.Custom}），把它接到
 * {@link VoidWarehouseRecipePrompt} 上。
 *
 * <h2>为什么需要这个 mixin</h2>
 * <p>{@code ClickEvent.Custom} 是 1.21.6 起原版提供的通道：客户端点一下 → 发
 * {@code ServerboundCustomClickActionPacket}（id + NBT 负载）→ 服务端按 id 分派。
 * <b>原版客户端自己会发</b>（已 javap 核实：{@code net.minecraft.client.gui.screens.Screen}
 * 里就有构造这条包的字节码），所以不需要任何客户端模组、也不需要注册指令。</p>
 *
 * <p>问题是服务端那一头，原版 {@code MinecraftServer#handleCustomClickAction} 目前只做一件事：</p>
 * <pre>
 *   LOGGER.debug("Received custom click action {} with payload {}", id, payload.orElse(null));
 *   return;
 * </pre>
 * <p>也就是说<b>它谁也不通知</b>（javap -c 实证：整个方法体只有 getstatic / ldc / aload /
 * invokevirtual / invokeinterface / return，没有任何分派）。所以本模组必须自己截下来。
 * 一旦原版将来真的实现了分派，这里的 {@code @Inject} 仍然先跑、先处理我们的 id，
 * 别人的 id 原样放行（{@code ci.cancel()} 只在认领时才调）。</p>
 *
 * <h2>为什么注入在 {@code ServerCommonPacketListenerImpl} 而不是 {@code MinecraftServer}</h2>
 * <p>分派必须知道<b>是谁点的</b>。{@code MinecraftServer#handleCustomClickAction(Identifier, Optional)}
 * 的签名里没有玩家 —— 它是「公共连接」那一层的汇总回调，服务端在那里已经丢了上下文。
 * 而包是先到 {@code ServerCommonPacketListenerImpl#handleCustomClickAction}
 * （{@code ServerboundCustomClickActionPacket#handle} 直接调它），那一层手里有连接，
 * 由连接能拿到 {@code ServerGamePacketListenerImpl#player}。</p>
 *
 * <p>🔴 <b>身份只从连接里取，绝不信负载</b>：负载里只有随机令牌，没有任何 UUID。
 * 就算有人伪造一条包，他最多只能点自己的待确认（令牌也不认识），
 * 拿不到别人的配方本。</p>
 *
 * <h2>只认 play 阶段的连接</h2>
 * <p>同一条包在 configuration 阶段也会被 {@code ServerConfigurationPacketListenerImpl} 处理，
 * 那个类同样继承自 {@code ServerCommonPacketListenerImpl}，但它没有 {@code player}。
 * 所以这里先 {@code instanceof ServerGamePacketListenerImpl} —— 不满足就原样放行。</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomClickMixin {

    /**
     * {@code ServerGamePacketListenerImpl#player} 是 <b>public</b> 字段（26.3 与 1.21.11 均已 javap 核对），
     * 所以这里直接强转读取，不需要 @Accessor。
     */
    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void carpetAutomending$onCustomClick(ServerboundCustomClickActionPacket packet,
                                                 CallbackInfo ci) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) {
            return;
        }
        ServerPlayer player = game.player;
        if (player == null) {
            return;
        }
        try {
            // 只认自己的 id；别的模组的点击原样放行（不 cancel，让原版/对方继续处理）
            if (VoidWarehouseRecipePrompt.handleClick(player, packet.id(), packet.payload())) {
                ci.cancel();
            }
        } catch (RuntimeException ignored) {
            // 旁路功能：点一下没能记住配方，也绝不能把玩家的连接搞崩
        }
    }
}
