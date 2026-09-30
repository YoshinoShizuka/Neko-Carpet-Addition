package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseClassifier;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 入库分类物白名单的<b>契约层闸门</b> —— 挂在 {@link Container#canPlaceItem(int, ItemStack)} 上。
 *
 * <h2>为什么不能只挂 {@code HopperBlockEntity#canPlaceItemInContainer}</h2>
 * <p>那个方法是原版的 <b>{@code private static} 私有实现</b>，只服务 vanilla 自己的搬运路径。
 * 用户实机（2026-09-30）复现：装了 <b>Lithium</b> 之后，漏斗搬运被换成了
 * {@code HopperHelper#tryMoveSingleItem}，它读的是容器公共契约
 * （{@code Container#canPlaceItem} + {@code WorldlyContainer#canPlaceItemThroughFace}），
 * <b>一次都不调 canPlaceItemInContainer</b> —— 于是白名单在实机上整条失效：
 * 箱子里的圆石照样以 1 个/gt 被吸进仓库。</p>
 *
 * <p>而服务端自检（{@code runServer}）环境里没有装 Lithium，走的还是 vanilla 路径，
 * 所以自检一直是绿的 —— 这就是「自检全绿、实机仍复现」的全部原因。</p>
 *
 * <h2>为什么挂在 {@code canPlaceItem} 上是对的</h2>
 * <p>vanilla 的 {@code canPlaceItemInContainer} 本身就是
 * 「{@code canPlaceItem} && {@code canPlaceItemThroughFace}」；
 * Lithium 的 {@code tryMoveSingleItem} 也是同样这两个。
 * 所以 {@code Container#canPlaceItem} 是<b>两条路都绕不过的公共契约</b>：
 * 主闸门挂在这里，vanilla、Lithium、以及其它任何「先问容器放不放」的优化模组都被覆盖。</p>
 *
 * <h2>不会限制玩家</h2>
 * <p>漏斗界面里的槽位是普通 {@code Slot}，其 {@code mayPlace(ItemStack)} 直接
 * {@code return true}（已核对 26.3 字节码），玩家手动往漏斗里放分类物走的是
 * {@code Slot#setByPlayer}，<b>根本不经过 canPlaceItem</b> ——
 * 所以「分类物只能由玩家的手来声明」这条规则不受影响。</p>
 *
 * <p>判定逻辑收敛在 {@link VoidWarehouseClassifier#blockMachineInsert} 里，
 * 与 {@code HopperBlockEntityMixin} 上那份防御性重复注入共用同一份代码。</p>
 */
@Mixin(Container.class)
public interface ContainerClassifierMixin {

    /**
     * 机器往容器里塞东西时问的那一句。只有「入库漏斗 + 玩家声明过分类物」才会被拦下。
     */
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    default void neko$classifierGate(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (VoidWarehouseClassifier.blockMachineInsert((Container) this, slot, stack)) {
            cir.setReturnValue(false);
        }
    }
}
