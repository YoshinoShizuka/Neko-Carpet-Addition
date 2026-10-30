package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseBlockDrops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 上下文边界：把 {@code ServerPlayerGameMode#destroyBlock} 这一次调用圈起来，
 * 期间产生的物品实体才算「玩家挖方块掉的」（见 {@link VoidWarehouseBlockDrops}）。
 *
 * <h2>为什么不用 {@code @WrapMethod}</h2>
 * <p>WrapMethod（MixinExtras）能用 try/finally 天然兜住异常，但它在编译期要多一个依赖，
 * 而本项目五个版本共用一套源码、类路径各不相同。这里改用「HEAD 立上下文 + RETURN 清上下文」，
 * 并把「万一 RETURN 没跑到」这件事交给 {@code collect} 里的 tick 戳来兜 ——
 * 效果等价（最多影响本次 tick 的剩余部分），代价是零依赖。</p>
 *
 * <p>HEAD 不带 cancellable：这条规则只改「掉落物去哪」，不改「方块怎么被破坏」。
 * 挖掘耗时、工具耐久、统计、成就、`playerDestroy` 该跑的一条不少。</p>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeDestroyMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"))
    private void carpetAutomending$beginBlockDrops(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        VoidWarehouseBlockDrops.begin(this.player);
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", at = @At("RETURN"))
    private void carpetAutomending$endBlockDrops(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        VoidWarehouseBlockDrops.end();
    }
}
