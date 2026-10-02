package neko.cooking.mixin;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.ItemStack;

import neko.cooking.ConcretePowderToGlass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Shift 点击能把混凝土粉末送进普通熔炉的输入槽。
 *
 * <h2>为什么光修配方还不够</h2>
 * <p>26.3 的 {@code AbstractFurnaceMenu#canSmelt} 整个方法体只有一句（javap 实证）：</p>
 * <pre>return this.acceptedInputs.test(stack);</pre>
 * <p>{@code acceptedInputs} 是开机时按<b>已注册配方</b>算出来的 {@code RecipePropertySet} ——
 * 我们的配方没注册，所以它里面永远没有混凝土粉末。而 {@code canSmelt} 的唯一调用点就在
 * {@code quickMoveStack} 里：</p>
 * <pre>else if (index == 0 || index == 1) moveItemStackTo(stack, 3, 39, false)   // 燃料/输入 → 背包
else if (canSmelt(stack))              moveItemStackTo(stack, 0, 1, false)   // → 输入槽
else if (isFuel(stack))                moveItemStackTo(stack, 1, 2, false)   // → 燃料槽</pre>
 * <p>所以不补这一下，玩家 Shift 点击混凝土粉末时它会走 {@code isFuel} 分支
 * （混凝土粉末<b>确实</b>是可燃的），被当成燃料塞进下面那格 —— 看起来像「模组没生效」。</p>
 *
 * <h2>为什么限定 {@code FurnaceMenu}</h2>
 * <p>只放行普通熔炉。高炉 / 烟熏炉的 {@code canSmelt} 走同一个方法，但它们对应的
 * {@code RecipeType} 不是 {@code SMELTING}，配方那边本来就不会命中；这里再挡一道，
 * 免得出现「高炉界面允许摆放、放进去却烧不动」的怪异状态。</p>
 */
@Mixin(AbstractFurnaceMenu.class)
public class AbstractFurnaceMenuMixin {

    @Inject(method = "canSmelt", at = @At("RETURN"), cancellable = true)
    private void neko$concretePowderToGlass$canSmelt(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        // 混入类不继承 AbstractFurnaceMenu，所以 this 要先当 Object 用才判得了
        if (!(((Object) this) instanceof FurnaceMenu)) {
            return;
        }
        if (ConcretePowderToGlass.isEnabledInput(stack)) {
            cir.setReturnValue(true);
        }
    }
}
