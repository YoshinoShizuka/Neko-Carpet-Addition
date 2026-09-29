package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseHopperFilter;
import neko.voidwarehouse.VoidWarehouseSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「玩家手动把物品放进漏斗」这件事的唯一出口 —— 用来声明分类物。
 *
 * <h2>为什么挂在这里</h2>
 * <p>{@code Slot#setByPlayer} 是原版里<b>玩家改变某个槽位内容</b>的专用方法
 * （已核对 26.3 字节码）：</p>
 * <ul>
 *   <li>直接点击放置 / 换位 / 光标存回 → {@code AbstractContainerMenu#doClick} 调它；</li>
 *   <li>Shift 点击快速移动 → {@code moveItemStackTo} 调它；</li>
 *   <li>拖动分配、双击收集 → {@code safeInsert} 内部也调它。</li>
 * </ul>
 * <p>而机器搬运（上方容器吸料、邻居漏斗推料、掉落物被吸入）走的是
 * {@code Container#setItem}，<b>永远不会经过 {@code Slot#setByPlayer}</b>。
 * 所以「挂在这个方法上」就等于「只认玩家的手」，一个字节都不用去猜
 * 「这次写入到底是谁干的」。</p>
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>玩家<b>放进</b>物品（{@code stack} 非空）→ 打开该漏斗的分类物过滤模式：
 *       你放进去的东西从此就是<b>入库白名单</b>；</li>
 *   <li>玩家<b>把漏斗掏空</b> → 自动关掉：分类物都没了，这个漏斗回到
 *       「漏斗里有什么就搬什么」的原规则；</li>
 *   <li>只是取走一部分、漏斗里还有别的东西 → 不动开关（剩下的仍然是分类物）。</li>
 * </ul>
 *
 * <p>过滤模式只对「口朝向仓库终端」的漏斗生效（见 {@code VoidWarehouseHopper#isIntake}），
 * 所以对一个普通漏斗做这些事，除了多存一个布尔标记以外，行为与原版完全一致。</p>
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Shadow
    @Final
    public Container container;

    /**
     * 用 {@code RETURN} 而不是 {@code HEAD}：要在这次写入<b>生效之后</b>才能判断
     * 「漏斗是不是被掏空了」—— 取走最后一个物品时，{@code HEAD} 处那一格还是满的。
     */
    @Inject(method = "setByPlayer(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
    private void carpetAutomending$detectClassifierSeeding(ItemStack stack, CallbackInfo ci) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(this.container instanceof HopperBlockEntity hopper)) {
            return;
        }
        // 只认服务端：客户端也会跑一遍预测，但客户端那份方块实体不是权威，改了也没意义
        if (!(hopper.getLevel() instanceof ServerLevel)) {
            return;
        }
        VoidWarehouseHopperFilter filter = (VoidWarehouseHopperFilter) hopper;
        if (hopper.isEmpty()) {
            if (filter.hasClassifierFilter()) {
                // 掏空了 = 没有分类物了 → 回到原规则
                filter.setClassifierFilter(false);
                hopper.setChanged();
            }
            return;
        }
        if (stack.isEmpty()) {
            // 只取走一部分：漏斗里剩下的东西仍然是分类物
            return;
        }
        if (!filter.hasClassifierFilter()) {
            filter.setClassifierFilter(true);
            hopper.setChanged();
        }
    }
}
