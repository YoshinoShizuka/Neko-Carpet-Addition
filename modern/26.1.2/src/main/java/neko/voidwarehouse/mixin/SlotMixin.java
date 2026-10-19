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
 *   <li>玩家<b>往某一格放进</b>物品 → 打开该漏斗的分类物过滤模式，并<b>把这一格记进
 *       分类物清单</b>：你亲手放进去的这些东西就是<b>入库白名单</b>；</li>
 *   <li>玩家<b>把某一格清空</b> → 把这一格从清单里抹掉（分类物是逐格认的，
 *       不是「漏斗里有什么就算什么」）；</li>
 *   <li>玩家<b>把漏斗整个掏空</b> → 自动关掉：分类物都没了，这个漏斗回到
 *       「漏斗里有什么就搬什么」的原规则。</li>
 * </ul>
 *
 * <h2>为什么必须逐格记（实测事故）</h2>
 * <p>早先只有「开关打开 / 关闭」这一件事，白名单本身是<b>靠反推漏斗当前内容</b>得来的。
 * 于是玩家动手那一刻漏斗里残留的任何货物都被一并认成分类物：上方容器流下来的石头
 * 被 {@code drain} 留了 {@code KEEP} 个，从此永久占着第二格 ——
 * 玩家只摆了橡木，看到的却是 {@code 橡木 石头 空 空 空}。
 * 现在清单只认玩家的手，机器写不进来，这一整类问题从根上没有发生的可能。</p>
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
        // 这次动手的是玩家，动的是哪一格 —— 以「动手之后这一格装着什么」为准记进清单：
        // 放进东西 = 指定这一格的分类物；把这一格清空 = 抹掉这一格的分类物。
        //
        // 这一步是整个白名单的<b>唯一写入口</b>。机器搬运走 Container#setItem，
        // 永远到不了这里 —— 所以「漏斗里的填充物只能由玩家来指定」是结构性保证的，
        // 不是靠事后反推漏斗内容猜出来的（反推那版会把残留的货物也认成分类物）。
        int slot = ((Slot) (Object) this).getContainerSlot();
        filter.setClassifierSeed(slot, hopper.getItem(slot));

        if (hopper.isEmpty()) {
            if (filter.hasClassifierFilter()) {
                // 掏空了 = 没有分类物了 → 回到原规则（清单也一并清干净）
                for (int i = 0; i < hopper.getContainerSize(); i++) {
                    filter.setClassifierSeed(i, ItemStack.EMPTY);
                }
                filter.setClassifierFilter(false);
                hopper.setChanged();
            }
            return;
        }
        if (!filter.hasClassifierFilter()) {
            filter.setClassifierFilter(true);
            hopper.setChanged();
        }
    }
}
