package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseRecipeBook;
import neko.voidwarehouse.VoidWarehouseRecipes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「玩家亲手合成过什么」的唯一记录点 —— 挂在 {@code ResultSlot#onTake} 上。
 *
 * <h2>为什么是这里</h2>
 * <p>{@code ResultSlot#onTake} 是原版里<b>合成产物被取走</b>的专用回调，而且它是
 * <b>所有</b>取走方式的共同出口（已核对 26.3 与 1.21.11 的调用点）：</p>
 * <ul>
 *   <li>单击把产物拿到光标上 → {@code AbstractContainerMenu#doClick} 的 PICKUP 分支；</li>
 *   <li>Shift 快速移动（连续合成直到原料耗尽）→ {@code CraftingMenu#quickMoveStack}，
 *       每一轮合成调一次；</li>
 *   <li>数字键换到快捷栏 → {@code doClick} 的 SWAP 分支。</li>
 * </ul>
 * <p>挂在这里就等于「只要真合成出来了就一定经过这里」，不需要去猜「这次到底是谁干的」。</p>
 *
 * <h2>为什么必须是 {@code HEAD}</h2>
 * <p>合成格是在 {@code onTake} <b>内部</b>被消耗的（已核对 26.3 字节码：
 * {@code craftSlots.removeItem(...)} 出现在方法体中段）。取 {@code RETURN} 就只能读到
 * 一张空网格，记下来的会是一条「产物 ← 什么都不需要」的配方 —— 那等于仓库白送东西。
 * 这是本模组的老规矩：<b>断言只能咬真正被执行的代码，位置也一样，
 * 早一步晚一步都是错的</b>（{@code SlotMixin} 用 {@code RETURN} 是因为那边要读「写完之后」的状态，
 * 这里要读「清空之前」的状态，两者方向相反）。</p>
 *
 * <h2>产物数量从哪来</h2>
 * <p><b>不用</b>回调参数里那个 {@code stack}（它在 Shift 路径上是被搬剩的余量或空气），
 * 而是问 {@code RecipeManager}（见 {@link VoidWarehouseRecipeBook#resultOf}）。</p>
 *
 * <h2>异常一律吞掉</h2>
 * <p>这里是<b>纯粹的旁路记录</b>：仓库坏了、配方解析炸了、存档闸门拦了，
 * 都不该让玩家连合成都做不了。所以整个方法体包在 try/catch 里 ——
 * 记不上一条配方是小事，把玩家的合成动作搞崩是大事。
 * （注意这与 {@code VoidWarehouse#data} 那边「读出空壳就抛异常拒绝服务」并不矛盾：
 * 那条规矩保护的是<b>存档</b>，而这里根本不写盘，只会在内存里少一条配方。）</p>
 */
@Mixin(ResultSlot.class)
public abstract class CraftResultMixin {

    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Inject(method = "onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"))
    private void carpetAutomending$learnRecipe(Player player, ItemStack stack, CallbackInfo ci) {
        if (!VoidWarehouseData.craftEnabled()) {
            return;
        }
        try {
            // 只认服务端：客户端也会跑一遍同样的流程（预测），但客户端那份没有权威存档，
            // 记下来既写不进盘，还会让「单机同时跑客户端与服务端」时重复记两次。
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            MinecraftServer server = level.getServer();
            if (server == null) {
                return;
            }
            CraftingInput input = this.craftSlots.asCraftInput();
            ItemStack result = VoidWarehouseRecipeBook.resultOf(server, level, input);
            if (result.isEmpty()) {
                return;
            }
            VoidWarehouseRecipes.Recipe recipe =
                    VoidWarehouseRecipeBook.fromGrid(this.craftSlots.getItems(), result);
            if (recipe == null) {
                return;
            }
            VoidWarehouseData data = VoidWarehouse.data(server);
            if (data == null) {
                return;
            }
            data.learn(recipe);
        } catch (RuntimeException ignored) {
            // 见类注释「异常一律吞掉」：旁路记录失败绝不能影响合成本身
        }
    }
}
