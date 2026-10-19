package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 入口二：<b>服务端「对空气右键」</b>，只在服务端生效。
 *
 * <h2>为什么要有这一层</h2>
 * <p>原来「对空气右键」只靠 {@code ItemUseMixin}（注入 {@code Item#use} 的 HEAD）。
 * 但 {@code Item#use} 是<b>所有模组共同的钩子坑位</b> —— Fabric API 的
 * {@code UseItemCallback} 就挂在同一个方法的同一个 HEAD 上，而注入顺序不归我们掌握，
 * 谁后执行谁的结果就生效。</p>
 *
 * <p>实测（用户实机 RO26.3，163 mods）：装了 QuickShulker 之后，
 * <b>对空气右键虚空仓库会打开一个原版合成界面</b>，而对着方块右键却一切正常。
 * 反汇编 {@code quickshulker-v3.2.11} 拿到确凿的机制：</p>
 * <pre>
 *   net/kyrptonaught/quickshulker/QuickShulkerMod#onInitialize
 *     └─ UseItemCallback.EVENT.register((player, world, hand) -> {
 *            if (!level.isClientSide()
 *                &amp;&amp; config.rightClickToOpen
 *                &amp;&amp; Util.isOpenableItem(stack) &amp;&amp; Util.canOpenInHand(stack)) {
 *                Util.openItem(player, 0, slot);              // 打开
 *                return InteractionResult.SUCCESS_SERVER;     // 并把结果写回 cir
 *            }
 *            return InteractionResult.PASS;
 *        });
 *
 *   registerProviders() 里注册的 6 个「可快速打开」方块，其中一个是：
 *     class net/minecraft/world/level/block/CraftingTableBlock   → container.crafting
 * </pre>
 * <p>虚空仓库的触发物<b>恰恰就是改名的工作台</b>（{@link VoidWarehouseTrigger#ITEM_NAME}），
 * 于是这条回调判定「可以打开」→ 打开合成界面 → 返回 {@code SUCCESS_SERVER}，
 * 把我们的 {@code SUCCESS_SERVER + 开仓库} 覆盖掉了。</p>
 *
 * <h2>为什么它只是「兜底」</h2>
 * <p>把这一层加进去之后，同一份代码两次跑出了相反的结果 —— 于是顺着
 * Fabric API 反汇编查到：{@code UseItemCallback} 的触发点<b>恰好也是</b>
 * {@code ServerPlayerGameMode#useItem} 的 HEAD（fabric-events-interaction-v0 的
 * {@code ServerPlayerGameModeMixin#interactItem}）。撞在同一个方法的同一个 HEAD 上，
 * 谁后执行谁生效，结果就随 JVM 启动顺序漂 —— 这一层<b>靠不住</b>。</p>
 *
 * <p>正确答案是再往上走一层到 {@code ServerGamePacketListenerImpl#handleUseItem}
 * （见 {@code ServerGamePacketListenerImplMixin}），那里没有竞争者。
 * 这一层保留下来是因为「对着实体右键」走的是另一个包，最终会回落到
 * {@code useItem} —— 这种次要路径有兜底总比没有强，
 * 所以把 priority 调到最低（0），让它的回调尽量排在最后、覆盖别人的结果。
 *
 * @see ServerGamePacketListenerImplMixin 主拦截层
 */
@Mixin(value = net.minecraft.server.level.ServerPlayerGameMode.class, priority = 0)
public abstract class ServerPlayerGameModeUseItemMixin {

    @Inject(
            method = "useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void carpetAutomending$voidWarehouseUseItem(ServerPlayer player, Level level, ItemStack stack,
                                                        InteractionHand hand,
                                                        CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide() || !VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return;
        }
        // 直接返回，不再往 Item#use 里走 —— 第三方注册在 Item#use 上的回调（含 Fabric
        // UseItemCallback）就不会有任何机会把这次右键抢去做别的事。
        cir.setReturnValue(VoidWarehouseTrigger.interact(level, player, hand));
    }
}
