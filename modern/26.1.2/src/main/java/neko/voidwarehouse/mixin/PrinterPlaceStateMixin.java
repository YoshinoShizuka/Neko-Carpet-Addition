package neko.voidwarehouse.mixin;

import neko.voidwarehouse.restock.PrinterPlaceServuxBridge;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lower-priority mixins prepend HEAD injections after Servux (950); run this scoped handler first. */
@Mixin(value = BlockItem.class, priority = 900)
public abstract class PrinterPlaceStateMixin {
    @Shadow protected abstract boolean canPlace(BlockPlaceContext context, BlockState state);

    @Inject(method = "getPlacementState", at = @At("HEAD"), cancellable = true)
    private void neko$structuredPlacement(BlockPlaceContext context, CallbackInfoReturnable<BlockState> result) {
        BlockItem item = (BlockItem) (Object) this;
        if (!PrinterPlaceServuxBridge.matches(item, context)) return;
        BlockState state = PrinterPlaceServuxBridge.scopedState(item, context);
        result.setReturnValue(state != null && state.canSurvive(context.getLevel(), context.getClickedPos())
                && this.canPlace(context, state)
                && context.getLevel().isUnobstructed(state, context.getClickedPos(),
                    net.minecraft.world.phys.shapes.CollisionContext.placementContext(context.getPlayer())) ? state : null);
    }
}
