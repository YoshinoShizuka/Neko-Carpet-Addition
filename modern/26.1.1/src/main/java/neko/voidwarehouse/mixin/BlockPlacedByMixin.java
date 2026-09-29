package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseTerminals;
import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>登记仓库终端</b>：用「虚空仓库」物品放下一个工作台时，把那个坐标记进
 * {@link VoidWarehouseTerminals}。
 *
 * <p>{@code BlockItem#place} 放好方块后会调 {@code Block#setPlacedBy}（已核对 26.3 字节码），
 * 那是唯一同时拿得到「最终落点 + 用的哪个物品堆叠」的地方，所以挂在这里最稳。</p>
 *
 * <p>两条分支：</p>
 * <ul>
 *   <li>堆叠是「虚空仓库」→ <b>登记</b>这个坐标；</li>
 *   <li>堆叠是<b>普通</b>工作台 → <b>顺手注销</b>这个坐标。这样即使登记表因为
 *       外部编辑存档而留下了脏数据，也不会出现「普通工作台被当成仓库」的假终端。</li>
 * </ul>
 *
 * <p>只认工作台方块（其它方块直接返回），所以对原版放置流程的开销是一次方块判定。</p>
 */
@Mixin(Block.class)
public abstract class BlockPlacedByMixin {

    @Inject(method = "setPlacedBy", at = @At("HEAD"))
    private void carpetAutomending$registerTerminal(Level level, BlockPos pos, BlockState state,
                                                    LivingEntity placer, ItemStack stack, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            return;
        }
        if (VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            VoidWarehouseTerminals.add(serverLevel, pos);
        } else {
            VoidWarehouseTerminals.remove(serverLevel, pos);
        }
    }
}
