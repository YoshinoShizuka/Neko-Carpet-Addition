package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseTerminals;
import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>服务端「对方块右键」的最上游拦截</b>：{@code ServerPlayerGameMode#useItemOn}
 * 是服务端处理方块右键真正的最上游，比「方块自己的交互」和 {@code BlockItem#useOn} 都早。
 *
 * <h2>三个入口</h2>
 * <ol>
 *   <li><b>手里拿着「虚空仓库」</b>右键任意方块 → 开界面（保留原来的用法，不会误放下去）；</li>
 *   <li><b>右键一个已放置的仓库终端</b>（{@link VoidWarehouseTerminals}）→ 开界面，
 *       空手也行、手里拿着别的东西也行；</li>
 *   <li><b>右键一个接了漏斗的原版工作台</b>（{@link VoidWarehouseTerminals#isHopperConnected}）→
 *       开界面 —— 搭好流水线的普通工作台也能当入口。</li>
 * </ol>
 *
 * <h2>潜行 = 放置</h2>
 * <p>原版语义里「潜行 + 手上有物品」会绕过方块交互、直接走物品自己的逻辑。
 * 这里照抄这条规则原样放行，于是 <b>潜行 + 右键方块</b>就能把改名工作台放下去
 * （{@code BlockPlacedByMixin} 会把它登记成终端）；不潜行时右键永远只是开界面，
 * 玩家不会不小心把仓库摆一地。</p>
 *
 * <h2>抹掉客户端的幽灵方块</h2>
 * <p>纯原版客户端并不知道「这个工作台是仓库终端」，右键时会先本地预测「把手里的方块放下」，
 * 于是终端隔壁会闪出一个幽灵方块。服务端既然接管了这次交互，就顺手把两个位置的真实状态
 * 发回给该玩家，把预测彻底抹掉（否则错误预测会一直留在客户端，直到区块重载）。</p>
 */
@Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Inject(
            method = "useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseUseItemOn(ServerPlayer player, Level level, ItemStack stack,
                                                          InteractionHand hand, BlockHitResult hitResult,
                                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide() || !VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        // 原版语义：潜行 + 手上有物品 = 绕过方块交互。必须放行，
        // 否则玩家没有任何办法把「虚空仓库」放到地上。
        if (player.isSecondaryUseActive() && !stack.isEmpty()) {
            return;
        }
        boolean terminal = level instanceof ServerLevel serverLevel
                && VoidWarehouseTerminals.isTerminal(serverLevel, hitResult.getBlockPos());
        boolean hopperLinked = level instanceof ServerLevel serverLevel2
                && VoidWarehouseTerminals.isHopperConnected(serverLevel2, hitResult.getBlockPos());
        if (!terminal && !hopperLinked && !VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        InteractionResult result = VoidWarehouseTrigger.interact(level, player, hand);
        if (result.consumesAction() && level instanceof ServerLevel serverLevel) {
            discardPredictedPlacement(serverLevel, player, hitResult);
        }
        cir.setReturnValue(result);
    }

    /**
     * 把「客户端可能已经预测放下的那个方块」的真实状态发回给该玩家。
     *
     * <p>原版 {@code ItemStack#useOn} 会放在点击面的相邻位置；如果那个位置是可替换方块
     * （草丛之类），则会替换点击位置本身 —— 所以两个位置都发一遍，最省事也最保险。</p>
     */
    private static void discardPredictedPlacement(ServerLevel level, ServerPlayer player, BlockHitResult hit) {
        if (player.connection == null) {
            // 自检里手工构造的 ServerPlayer 没有连接：不值得为此报错
            return;
        }
        player.connection.send(new ClientboundBlockUpdatePacket(level, hit.getBlockPos()));
        BlockPos adjacent = hit.getBlockPos().relative(hit.getDirection());
        if (!adjacent.equals(hit.getBlockPos())) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, adjacent));
        }
    }
}
