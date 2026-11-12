package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseRecipeBook;
import neko.voidwarehouse.VoidWarehouseRecipeNotice;
import neko.voidwarehouse.VoidWarehouseRecipePrompt;
import neko.voidwarehouse.VoidWarehouseRecipes;
import neko.voidwarehouse.VoidWarehouseText;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("carpet_neko_addition/recipe");

    /**
     * 异常日志的条数预算。
     *
     * <p>一个「每次都抛」的坏配方会跟着每一次合成都抛，不封顶就是把日志刷穿 ——
     * 而这个日志的用处只是「让人知道这里有东西坏了」，前几条就够了。</p>
     */
    private static final AtomicInteger WARN_BUDGET = new AtomicInteger(3);

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
            // ---- 问一句，还是直接记？ ----
            // 1.0.17 起默认「问了才记」：合成这一刻<b>只发问、不写配方本</b>，等玩家点了才写。
            // 三种情况不问（已记过 / 本满 / 玩家本局选过「总是记住」或对这条点过「不要」），
            // 直接落到下面原来的老路，文案与节流一个字都不变。
            //
            // 「已经挂着同一条待确认」要单独判：shift 连合同一条配方会连续走到这里，
            // 不拦的话每次合成都会再挂一条（并把前一条挤掉），聊天栏立刻刷屏。
            if (player instanceof ServerPlayer asker
                    && VoidWarehouseRecipePrompt.shouldAsk(asker, data, recipe,
                            VoidWarehouseRecipeBook.MAX_RECIPES)) {
                if (!VoidWarehouseRecipePrompt.isPending(asker, recipe)) {
                    Component ask = VoidWarehouseRecipePrompt.ask(
                            asker, VoidWarehouseText.isEnglish(asker), data, recipe,
                            server.getTickCount());
                    if (ask != null) {
                        asker.sendSystemMessage(ask);
                    }
                }
                return;
            }
            // ⚠️ 用 learnCapturingOthers 而不是自己写「先 recipesFor 再 learn」：
            // 那个先后顺序写反了不会报错，只会让提示多列一条自己。固化进数据层之后
            // 自检能直接断言「交出来的 othersBefore 里没有刚学的那条」。
            VoidWarehouseData.LearnOutcome outcome = data.learnCapturingOthers(recipe);
            // 提示在这里发，不塞进数据层：数据层的定位是纯状态，一旦它自己去
            // sendSystemMessage 就绑死了「必须有真玩家」这个前提（自检跑不了、
            // 打印机那种无人路径也没法复用）。同 PrinterRestockNetwork 的分层规矩。
            // 整个方法体在 try/catch 里 —— 发消息炸了也不能让玩家合不成本。
            if (player instanceof ServerPlayer serverPlayer) {
                for (Component line : VoidWarehouseRecipeNotice.onCrafted(
                        player.getUUID(), VoidWarehouseText.isEnglish(serverPlayer),
                        outcome.result(), recipe, outcome.othersBefore(),
                        data.recipeBookUsed(), VoidWarehouseRecipeBook.MAX_RECIPES,
                        server.getTickCount())) {
                    serverPlayer.sendSystemMessage(line);
                }
            }
        } catch (RuntimeException ex) {
            // 见类注释「异常一律吞掉」：旁路记录失败绝不能影响合成本身。
            //
            // 但「照样吞」不等于「一声不响」。1.0.17 的实机测试报过「合成后没有提示」，
            // 能查的东西只有存档里那几条新配方 —— 日志里一个字都没有，因为异常被这里吞了，
            // 于是「配方记下来了、提示没发出去」这种情况在事后完全无法与「一切正常」区分。
            // 所以这里照样不影响合成，但要留一条痕迹；条数有预算，不会刷屏。
            if (WARN_BUDGET.getAndDecrement() > 0) {
                LOGGER.warn("虚空仓库：记录配方时抛异常，本次合成已照常完成（后续同类异常不再重复打印）", ex);
            }
        }
    }
}
