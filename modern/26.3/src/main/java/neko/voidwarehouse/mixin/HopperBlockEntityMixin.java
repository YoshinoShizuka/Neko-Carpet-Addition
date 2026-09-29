package neko.voidwarehouse.mixin;

import neko.voidwarehouse.VoidWarehouseClassifier;
import neko.voidwarehouse.VoidWarehouseExtractor;
import neko.voidwarehouse.VoidWarehouseHopper;
import neko.voidwarehouse.VoidWarehouseHopperFilter;
import neko.voidwarehouse.VoidWarehouseSettings;
import neko.voidwarehouse.VoidWarehouseTerminals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 漏斗与虚空仓库之间的两个方向 + 分类物白名单。
 *
 * <h2>① 入库（无速率上限）—— 漏斗口<b>朝向</b>终端</h2>
 * <p>挂在 {@code HopperBlockEntity#pushItemsTick} 的 HEAD：这是原版漏斗每 tick 的入口
 * （Ticker），它在最开头递减冷却、然后在 {@code !isOnCooldown() && ENABLED} 时才去搬运，
 * 搬成功一次就把冷却设成 8 —— 这就是「8 gt 搬 1 个」的来源。我们在最前面先把漏斗里能存的
 * 物品<b>一次搬空</b>（{@link VoidWarehouseHopper#drain}），搬得再多也不设冷却。</p>
 * <p><b>不取消原版逻辑</b>，原因有两个：</p>
 * <ol>
 *   <li>原版只往「容器」里推东西，而工作台不是容器，所以原版这一段对本仓库是空操作，
 *       不会把物品推到别处去；</li>
 *   <li>保留原版逻辑，冷却才会照常递减（否则一旦我们 cancel，冷却会冻在原值上，
 *       把漏斗永久卡死），「上方容器 → 漏斗」那一段也能维持原版速率。</li>
 * </ol>
 *
 * <h2>② 出料 —— 终端压在漏斗<b>正上方</b></h2>
 * <p>同一次注入里顺手判一下：正上方是不是也是终端。是的话就换成
 * {@link VoidWarehouseExtractor#pull}，按漏斗 5 格里的<b>分类物</b>把仓库的料补进漏斗。
 * 两个方向靠「漏斗口朝哪」区分；两者同时成立时<b>以入库为准</b>
 * （{@link VoidWarehouseExtractor#isExtractor} 里说明），所以本轮之前搭好的机器行为不变。</p>
 *
 * <h2>③ 分类物保护 —— 接管 {@code ejectItems}</h2>
 * <p>出料模式下，漏斗每格物品的<b>最后 1 个</b>是分类物，绝不能被下游容器吸走
 * （不然仓库一抽空，分类就没了）。原版 {@code ejectItems} 是「从头扫、谁非空就搬 1 个」，
 * 会毫不犹豫地把最后一并搬走，所以这里在 HEAD 处整段接管，换成
 * {@link VoidWarehouseExtractor#eject}（逻辑与原版只差「跳过数量 ≤ 1 的格子」这一处，
 * 下游容器的各种限制仍然照旧生效）。</p>
 * <p>非出料模式的漏斗一个字节都不碰，走的还是原版方法体。</p>
 *
 * <h2>④ 入库分类物白名单 —— 接管 {@code canPlaceItemInContainer}</h2>
 * <p>{@code canPlaceItemInContainer} 是<b>机器把物品放进一个容器的唯一关卡</b>
 * （已核对字节码：{@code addItem → tryMoveInItem → canPlaceItemInContainer}，
 * 上方容器吸料、邻居漏斗推料、掉落物被吸入三条路都从这里过）。
 * 玩家手动放物品走的是 {@code Slot#setByPlayer}，<b>不经过这里</b> ——
 * 所以在这个关卡上做过滤，效果正好是「只有分类物进得来、玩家的手不受限制」。</p>
 * <p>过滤模式本身是个<b>跟着方块实体一起存进存档</b>的布尔标记（见
 * {@link VoidWarehouseHopperFilter}），由 {@code SlotMixin} 在玩家手动放东西时打开、
 * 在漏斗被掏空时关掉。标记只在「口朝向终端」的入库漏斗上生效，
 * <b>普通漏斗即使带着标记也完全按原版跑</b>。</p>
 * <p>这一处注入写的是 {@code require = 0}：{@code canPlaceItemInContainer} 是
 * {@code private static} 的私有实现，原版以后把它内联进 {@code tryMoveInItem} 是完全可能的。
 * 真到那一天，我们宁可「这一个功能悄悄失效」，也不要「整个模组加载失败、把所有人的服务器带崩」 ——
 * 而「失效」这件事本身在真实服务端的自检里是逐条断言过的，不会没人发现。</p>
 *
 * <h2>红石语义不变</h2>
 * <p>漏斗被红石锁存时原版 {@code ENABLED = false}、整个漏斗停摆，这里同样直接返回，
 * 不会「无视红石往仓库里灌」、也不会「无视红石往外倒料」。</p>
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin implements VoidWarehouseHopperFilter {

    /** 存档里那个布尔标记的键名（与 {@code TransferCooldown} 平级，只在本模组用到时才写） */
    private static final String CLASSIFIER_FILTER_TAG = "CarpetNekoAdditionClassifierFilter";

    /**
     * 这个漏斗有没有被玩家声明过分类物。
     *
     * <p>{@code @Unique} = 这个字段是混入 {@code HopperBlockEntity} 的，
     * 每个漏斗一份、跟方块实体同生共死；默认 {@code false}（没有声明过 = 原版行为）。</p>
     */
    @Unique
    private boolean carpetAutomending$classifierFilter;

    /**
     * 原版的搬运冷却字段（{@code cooldownTime}，private）。无上限吸取复用了原版
     * {@code addItem}，它会顺手设 8 gt 冷却，这里要把它读回来清零（见 {@link #resetCooldown}）。
     */
    @Shadow
    private int cooldownTime;

    @Override
    public boolean hasClassifierFilter() {
        return this.carpetAutomending$classifierFilter;
    }

    @Override
    public void setClassifierFilter(boolean enabled) {
        this.carpetAutomending$classifierFilter = enabled;
    }

    @Override
    public void resetCooldown() {
        this.cooldownTime = 0;
    }

    /**
     * 把标记写进方块实体自己的存档（只在打开过时才写，普通漏斗的 NBT 一个字节都不多）。
     */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void carpetAutomending$saveClassifierFilter(ValueOutput output, CallbackInfo ci) {
        if (this.carpetAutomending$classifierFilter) {
            output.putBoolean(CLASSIFIER_FILTER_TAG, true);
        }
    }

    /**
     * 从存档读回标记：<b>服务器重启、区块卸载重载之后，白名单照样在</b>。
     * 标签不存在时读成 {@code false}（老存档 / 普通漏斗）。
     */
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void carpetAutomending$loadClassifierFilter(ValueInput input, CallbackInfo ci) {
        this.carpetAutomending$classifierFilter = input.getBooleanOr(CLASSIFIER_FILTER_TAG, false);
    }

    @Inject(method = "pushItemsTick", at = @At("HEAD"))
    private static void carpetAutomending$voidWarehouseUnlimited(Level level, BlockPos pos, BlockState state,
                                                                 HopperBlockEntity hopper, CallbackInfo ci) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 红石锁存时与原版一致：整个漏斗停摆
        if (!state.getValue(HopperBlock.ENABLED)) {
            return;
        }
        // 漏斗口指向的方块是不是仓库终端？（下面的面：原版没有朝上的漏斗，天然排除）
        if (VoidWarehouseHopper.isIntake(serverLevel, pos, state)) {
            VoidWarehouseHopperFilter self = (VoidWarehouseHopperFilter) hopper;
            if (self.hasClassifierFilter() && !VoidWarehouseClassifier.hasAny(hopper)) {
                // 分类物都被拿走了 → 自动退出过滤模式，回到「漏斗里有什么就搬什么」的原规则
                self.setClassifierFilter(false);
                hopper.setChanged();
            }
            VoidWarehouseHopper.drain(serverLevel, hopper, self.hasClassifierFilter());
            return;
        }
        // 正上方是不是仓库终端？（终端压在漏斗上 → 反过来，从仓库往漏斗里出料）
        if (VoidWarehouseExtractor.isExtractor(serverLevel, pos, state)) {
            VoidWarehouseExtractor.pull(serverLevel, hopper);
        }
    }

    /**
     * <b>入库漏斗的「上方容器 → 漏斗」无上限吸取</b>：接管原版 {@code suckInItems}。
     *
     * <p>原版 {@code suckInItems} 有两个分支：上方是容器 → 从容器吸 1 个
     * （每 8 gt 才 1 个，节流来自 {@code tryMoveItems} 的 {@code setCooldown(8)}）；
     * 上方不是容器 → 吸掉落到漏斗口的物品。这里<b>只接管前者</b>：
     * 当「这是入库漏斗」<b>且</b>「上方是容器」时，换成 {@link VoidWarehouseHopper#suckUnlimited}
     * 一次吸满，返回 {@code false} 让 {@code tryMoveItems} 不设 8 gt 冷却，并顺手把
     * {@code addItem} 内部设的冷却清零 —— 于是每个 tick 都能继续吸，实现无上限。</p>
     * <p>其余情况（不是入库漏斗、或上方不是容器）第一行就 return，原版方法体一个字不碰。</p>
     */
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void carpetAutomending$unlimitedIntake(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(hopper instanceof HopperBlockEntity blockEntity)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!VoidWarehouseHopper.isIntake(serverLevel, pos, state)) {
            // 不是入库漏斗 → 原版（含吸掉落物分支）
            return;
        }
        // 上方是不是容器？不是（掉落物 / 完整方块）就交给原版吸掉落物
        if (HopperBlockEntity.getContainerAt(level, pos.above()) == null) {
            return;
        }
        boolean moved = VoidWarehouseHopper.suckUnlimited(serverLevel, blockEntity);
        // 抵消 addItem 内部设的 8 gt 冷却，让下一个 tick 原版 tryMoveItems 继续调 suckInItems
        ((VoidWarehouseHopperFilter) blockEntity).resetCooldown();
        // 恒返回 false：不想要原版 tryMoveItems 的 setCooldown(8) 节流
        cir.setReturnValue(moved);
    }

    /**
     * <b>机器往漏斗里塞东西的唯一关卡</b>：分类物白名单就落在这里。
     *
     * <p>只对「开着过滤标记 + 口朝向终端」的漏斗生效；其余情况第一行就返回原版判断，
     * 一个字节都不动。方法签名与原版完全一致（{@code private static}），
     * 所以普通漏斗这一段的开销就是一次 {@code instanceof} + 一次布尔读。</p>
     */
    @Inject(method = "canPlaceItemInContainer", at = @At("HEAD"), cancellable = true, require = 0)
    private static void carpetAutomending$classifierFilter(Container container, ItemStack stack, int slot,
                                                           Direction direction,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(container instanceof HopperBlockEntity hopper)) {
            return;
        }
        if (!((VoidWarehouseHopperFilter) hopper).hasClassifierFilter()) {
            return;
        }
        if (!(hopper.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!VoidWarehouseHopper.isIntake(serverLevel, hopper.getBlockPos(), hopper.getBlockState())) {
            // 标记归标记：这个漏斗不是在往仓库里入库 → 完全按原版跑
            return;
        }
        if (!VoidWarehouseClassifier.hasAny(hopper)) {
            // 防御：一件分类物都没有就没有白名单可言，放行（正常路径上开关已经被关掉了）
            return;
        }
        if (!VoidWarehouseClassifier.matchesAny(hopper, stack)) {
            // 不是分类物 → 原样留在上游容器 / 上游漏斗里，不进这个漏斗
            cir.setReturnValue(false);
        }
    }

    /**
     * 出料模式下接管原版的「把东西推进下游容器」。
     *
     * <p>方法签名与原版完全一致（{@code private static boolean ejectItems(Level, BlockPos, HopperBlockEntity)}），
     * 用完整描述符指定，避免以后原版加重载时注错方法。</p>
     */
    @Inject(
            method = "ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void carpetAutomending$keepClassifiers(Level level, BlockPos pos, HopperBlockEntity hopper,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!VoidWarehouseExtractor.isExtractor(serverLevel, pos, level.getBlockState(pos))) {
            return;
        }
        cir.setReturnValue(VoidWarehouseExtractor.eject(serverLevel, pos, hopper));
    }
}
