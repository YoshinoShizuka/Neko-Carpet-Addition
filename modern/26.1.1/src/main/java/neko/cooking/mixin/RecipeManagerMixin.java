package neko.cooking.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import neko.cooking.ConcretePowderToGlass;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 让熔炉能「看见」那 16 条没进配方表的混凝土粉末配方。
 *
 * <h2>为什么只挂 3 参的 {@code getRecipeFor}</h2>
 * <p>26.3 的 {@code RecipeManager} 有三个 {@code getRecipeFor} 重载，javap 把调用链看穿了：</p>
 * <pre>
 * getRecipeFor(type, input, level, ResourceKey)     → getRecipeFor(type, input, level, RecipeHolder)
 * getRecipeFor(type, input, level, RecipeHolder)    → getRecipeFor(type, input, level)   ← 全部汇到这里
 * getRecipeFor(type, input, level)                  ← 只需挂这一个
 * </pre>
 * <p>而熔炉方块实体走的是 {@code quickCheck}（{@code RecipeManager.createCheck(SMELTING)} 造出来的
 * {@code RecipeManager$1}），它内部调的正是<b>4 参</b>那一版 —— 也就是说
 * <b>挂了 3 参就等于把 4 参那条路一起覆盖了</b>。只挂一个点，少一处可能失配的地方。</p>
 *
 * <h2>为什么是 RETURN 而不是 HEAD</h2>
 * <p>RETURN 让原版先查一遍：原版配方<b>永远优先</b>，我们只在「原版说没有」时补一句。
 * 这样哪怕将来 Mojang 真给混凝土粉末加了熔炼配方，也不会被我们盖掉。</p>
 *
 * <h2>{@code byKey} 是干嘛的</h2>
 * <p>{@code AbstractFurnaceBlockEntity#getRecipesToAwardAndPopExperience} 会拿
 * {@code recipeUsed} 的 id 调 {@code RecipeManager#byKey} 反查配方、再取它的经验值发给玩家。
 * 我们的配方不在表里，不补这一下，烧出来的玻璃<b>不给经验</b>。</p>
 */
@Mixin(RecipeManager.class)
public class RecipeManagerMixin {

    @Inject(
            method = "getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;"
                    + "Lnet/minecraft/world/item/crafting/RecipeInput;"
                    + "Lnet/minecraft/world/level/Level;)Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true)
    private void neko$concretePowderToGlass$getRecipeFor(RecipeType<?> type, RecipeInput input, Level level,
                                                         CallbackInfoReturnable<Optional> cir) {
        Optional<?> vanilla = cir.getReturnValue();
        if (vanilla != null && vanilla.isPresent()) {
            return;
        }
        Optional<RecipeHolder<?>> ours = ConcretePowderToGlass.findRecipe(type, input);
        if (!ours.isEmpty()) {
            cir.setReturnValue(ours);
        }
    }

    @Inject(method = "byKey", at = @At("RETURN"), cancellable = true)
    private void neko$concretePowderToGlass$byKey(ResourceKey<Recipe<?>> id,
                                                   CallbackInfoReturnable<Optional> cir) {
        Optional<?> vanilla = cir.getReturnValue();
        if (vanilla != null && vanilla.isPresent()) {
            return;
        }
        Optional<RecipeHolder<?>> ours = ConcretePowderToGlass.findRecipeById(id);
        if (!ours.isEmpty()) {
            cir.setReturnValue(ours);
        }
    }
}
