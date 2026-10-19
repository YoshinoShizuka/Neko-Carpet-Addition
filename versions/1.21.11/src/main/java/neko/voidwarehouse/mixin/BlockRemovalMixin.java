package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseTerminals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>注销仓库终端</b>：工作台方块被破坏 / 被替换 / 被活塞推走时，把它的登记删掉。
 *
 * <p>挂在 {@code BlockBehaviour#affectNeighborsAfterRemoval}（26.3 里 {@code onRemove} 的后继，
 * 由 {@code LevelChunk#setBlockState} 在方块被换掉时调用，已核对字节码）。
 * 这一层覆盖了原版所有移除途径：挖掉、爆炸、{@code /setblock} 覆盖、活塞推移。</p>
 *
 * <p>为什么非要注销：登记表是按坐标存的，坐标一旦被回收（原地又放了个工作台），
 * 就会变成「假终端」—— 右键能开出仓库、漏斗能往里灌东西，但玩家以为那只是个普通工作台。
 * 这一个钩子把这条路封死（配合 {@code setPlacedBy} 里的兜底注销，双保险）。</p>
 *
 * <p>注入点在所有方块的移除路径上，所以第一行就是最省的判定：不是工作台立刻返回。</p>
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockRemovalMixin {

    @Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
    private void carpetAutomending$unregisterTerminal(BlockState state, ServerLevel level, BlockPos pos,
                                                      boolean movedByPiston, CallbackInfo ci) {
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            return;
        }
        VoidWarehouseTerminals.remove(level, pos);
    }
}
