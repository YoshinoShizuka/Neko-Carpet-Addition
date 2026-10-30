package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseBlockDrops;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 收料口：{@code ServerLevel#addFreshEntity} 的 HEAD。
 *
 * <h2>为什么是这里</h2>
 * <p>方块掉落物在服务端的唯一落地方式就是「造一个 {@link ItemEntity}、把它加进世界」——
 * {@code Block#popResource} 的最后一步就是 {@code level.addFreshEntity(itemEntity)}。
 * 所以拦在这里，等于拦住了<b>所有</b>方块掉落物，且不用去碰任何一个具体方块的掉落逻辑
 * （附魔、幸运、时运、方块实体里的东西，全都照原版算完再交给我们）。</p>
 *
 * <p>拦在 HEAD 而不是里面更深的地方，是因为这里的语义最干净：实体还没进世界，
 * 拦下来就等于「它从来没存在过」。反过来若让它先进了世界再删，中间那一帧的
 * 拾取判定、声音、粒子都得自己收拾。</p>
 *
 * <h2>不会被误伤的东西</h2>
 * <p>只有 {@link ItemEntity} 会被看一眼，而且还得满足「此刻正在某个玩家挖方块」
 * （{@link VoidWarehouseBlockDrops#collect} 的判据）。经验球、掉落发射物、
 * 怪物死亡掉落、玩家丢弃、爆炸掉落全都不满足，一律原样进世界。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelAddEntityMixin {

    @Inject(
            method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$blockDropsIntoWarehouse(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!(entity instanceof ItemEntity item)) {
            return;
        }
        ItemStack stack = item.getItem();
        if (VoidWarehouseBlockDrops.collect((ServerLevel) (Object) this, stack)) {
            // 已经进仓库了，这一份不能再进世界 —— 否则就是复制
            cir.setReturnValue(false);
        }
    }
}
