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
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
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
 * <p>挂在 {@code HopperBlockEntity#pushItemsTick} 的 HEAD 与 TAIL 两处，配合起来把
 * 「8 gt 搬 1 个」这条节流彻底拆掉：</p>
 * <ul>
 *   <li><b>HEAD</b>（ {@link #carpetAutomending$voidWarehouseUnlimited}）：每 tick 一次
 *       <b>全量清扫</b> —— 先搬空漏斗、再把上方容器 / 上方路过的掉落物能收的全收进来、
 *       收完当场再搬空一次（{@link VoidWarehouseHopper#intake}）。HEAD 是原版 ticker 的必经之处，
 *       <b>不看冷却脸色</b>，所以真正的搬运不再受 8 gt 限制；</li>
 *   <li><b>TAIL</b>（ {@link #carpetAutomending$unlimitedCooldown}）：整条 tick 走完之后把
 *       {@code cooldownTime} 清零，让原版 {@code tryMoveItems} 那两条路径
 *       （{@code suckInItems} 本体、{@code entityInside} 触发的那次）也不被 8 gt 卡着。
 *       必须放在这里 —— 早先放在 {@code suckInItems} 中途是<b>无效的</b>，
 *       {@code tryMoveItems} 收尾会立刻把 {@code setCooldown(8)} 盖回去。</li>
 * </ul>
 * <p><b>不取消原版逻辑</b>，原因有两个：</p>
 * <ol>
 *   <li>原版只往「容器」里推东西，而工作台不是容器，所以原版这一段对本仓库是空操作，
 *       不会把物品推到别处去；</li>
 *   <li>保留原版逻辑，红石 /  comparator 等一套语义都还成立，我们只是把「能搬多少」
 *       这一件事改成不受限。</li>
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
 * <p>过滤是<b>两道</b>：先 {@link VoidWarehouseClassifier#matchesAny} 问
 * 「是不是白名单上的物品」，再 {@link VoidWarehouseClassifier#isOwnSlot} 问
 * 「这一格是不是它的格子」。第二道不能省 —— 少了它，分类物会被原版
 * {@code addItem} 的逐格试放摊到 5 个格子上（详见
 * {@link VoidWarehouseClassifier#isOwnSlot} 里记的那次事故）。</p>
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

    /** 存档里「玩家指定的分类物清单」的键名（定长 5 条，空格写 {@code ItemStack.EMPTY}） */
    private static final String CLASSIFIER_SEED_TAG = "CarpetNekoAdditionClassifierSeeds";

    /** 漏斗的格子数（原版固定 5，这里写成常量省得每次问容器） */
    private static final int HOPPER_SLOTS = 5;

    /**
     * 这个漏斗有没有被玩家声明过分类物。
     *
     * <p>{@code @Unique} = 这个字段是混入 {@code HopperBlockEntity} 的，
     * 每个漏斗一份、跟方块实体同生共死；默认 {@code false}（没有声明过 = 原版行为）。</p>
     */
    @Unique
    private boolean carpetAutomending$classifierFilter;

    /**
     * <b>玩家亲手指定的分类物清单</b>（按格子下标对齐，{@code null} / 空 = 这一格没指定）。
     *
     * <p>与 {@link #carpetAutomending$classifierFilter} 的区别：开关只说「这个漏斗有分类物吗」，
     * 这份清单才真正回答「是哪几件、分别在哪一格」。
     * <b>绝不能靠反推漏斗当前内容来回答后一个问题</b> —— 那会把任何残留在漏斗里的
     * 货物一并认成分类物（实测：玩家只摆了橡木，漏斗却变成 {@code 橡木 石头 空 空 空}）。</p>
     */
    @Unique
    private ItemStack[] carpetAutomending$classifierSeeds = new ItemStack[HOPPER_SLOTS];

    /**
     * 原版的搬运冷却字段（{@code cooldownTime}，private）。
     * 入库漏斗在整条 tick 收尾处把它清零（见 {@link #carpetAutomending$unlimitedCooldown}），
     * 让原版 {@code tryMoveItems} 不再被「每 8 gt 才动一次」卡住。
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
    public ItemStack getClassifierSeed(int slot) {
        if (slot < 0 || slot >= this.carpetAutomending$classifierSeeds.length) {
            return ItemStack.EMPTY;
        }
        ItemStack seed = this.carpetAutomending$classifierSeeds[slot];
        return seed == null ? ItemStack.EMPTY : seed;
    }

    @Override
    public void setClassifierSeed(int slot, ItemStack stack) {
        if (slot < 0 || slot >= this.carpetAutomending$classifierSeeds.length) {
            return;
        }
        // 只存「种类」：数量归一是刻意的 —— 分类物回答的是「这是什么」，
        // 与这一格此刻有几个无关（货物灌进来时数量会在 1 和 64 之间反复横跳）
        this.carpetAutomending$classifierSeeds[slot] =
                stack == null || stack.isEmpty() ? null : stack.copyWithCount(1);
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
            // 按格子独立写 key，<b>空槽一律跳过</b>：ItemStack.CODEC 拒绝 air（count=0），
            // 当初图省事用定长 list 把 5 格全写进去，空槽的 EMPTY 会触发
            // 「Failed to append ... minecraft:air」警告并静默丢弃 —— 虽然不影响对齐，
            // 但会把日志刷爆，也埋下「读回来数量对不上」的隐患。
            for (int i = 0; i < HOPPER_SLOTS; i++) {
                ItemStack seed = this.getClassifierSeed(i);
                if (!seed.isEmpty()) {
                    output.store(CLASSIFIER_SEED_TAG + i, ItemStack.CODEC, seed);
                }
            }
        }
    }

    /**
     * 从存档读回标记与分类物清单：<b>服务器重启、区块卸载重载之后，白名单照样在</b>。
     *
     * <p>标签不存在时读成 {@code false} / 全空（老存档 / 普通漏斗）。
     * 老存档只有开关、没有清单 —— 那种情况由 {@link VoidWarehouseClassifier#adoptLegacy}
     * 就地把当时的漏斗内容认作分类物，玩家摆过的东西不会丢。</p>
     */
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void carpetAutomending$loadClassifierFilter(ValueInput input, CallbackInfo ci) {
        this.carpetAutomending$classifierFilter = input.getBooleanOr(CLASSIFIER_FILTER_TAG, false);
        for (int i = 0; i < HOPPER_SLOTS; i++) {
            // 按格子独立读：写着的那几格读回来，没写的那几格保持空（没被指定过）
            final int slot = i;
            input.read(CLASSIFIER_SEED_TAG + i, ItemStack.CODEC)
                    .ifPresent(seed -> this.setClassifierSeed(slot, seed));
        }
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
            if (self.hasClassifierFilter() && !VoidWarehouseClassifier.hasAnySeed(self)) {
                // 一份分类物都没指定（老存档可能只有开关、没有清单）→ 就地认下当前内容，
                // 免得升级之后玩家摆过的白名单凭空消失（详见 adoptLegacy）。
                VoidWarehouseClassifier.adoptLegacy(hopper, self);
                hopper.setChanged();
            }
            if (self.hasClassifierFilter() && !VoidWarehouseClassifier.hasAnySeed(self)) {
                // 连当前内容也是空的 → 真的没有分类物 → 自动退出过滤模式，
                // 回到「漏斗里有什么就搬什么」的原规则
                self.setClassifierFilter(false);
                hopper.setChanged();
            }
            // 每 tick 一次全量清扫：搬空 + 进货 + 再搬空（见 VoidWarehouseHopper#intake）
            VoidWarehouseHopper.intake(serverLevel, hopper, self.hasClassifierFilter());
            return;
        }
        // 正上方是不是仓库终端？（终端压在漏斗上 → 反过来，从仓库往漏斗里出料）
        if (VoidWarehouseExtractor.isExtractor(serverLevel, pos, state)) {
            VoidWarehouseExtractor.pull(serverLevel, hopper);
        }
    }

    /**
     * <b>把 8 gt 节流真正拆掉</b>：整条 tick 走完之后，把入库漏斗的搬运冷却清零。
     *
     * <p>为什么必须放在 {@code pushItemsTick} 的<b>收尾</b>而不是中途（逐个核对字节码后的结论）：</p>
     * <pre>{@code
     * tryMoveItems(...) {
     *     if (hopper.isOnCooldown()) return false;     // ← 冷却未清，这里直接出队
     *     ...
     *     flag = ejectItems(...);                      // 出料
     *     flag |= supplier.getAsBoolean();             // ← 26.3 里它 == suckInItems
     *     if (flag) { hopper.setCooldown(8); ... }     // ← 无条件写回 8
     *     return flag;
     * }
     * }</pre>
     * <p>早先的版本在 {@code suckInItems} 的 HEAD 里调 {@code resetCooldown()}，
     * 那一步<b>紧接着就被上面的 {@code setCooldown(8)} 盖掉</b>，等于白做 ——
     * 「无上限吸取」实际一直是 8 gt 一次。所以清零必须发生在 {@code tryMoveItems} <b>之后</b>。</p>
     *
     * <p>这一手与 Carpet-TIS 的 {@code hopperCountersUnlimitedSpeed} 收尾的
     * {@code setCooldown(0)} 是同一个意思：让漏斗永远处于「冷却已过」的状态。</p>
     *
     * <p>注意：<b>搬运本身已经不依赖这个cool down了</b>（{@code intake} 挂在 HEAD，每 tick 必跑），
     * 这里清零只是顺手把原版那两条路径（{@code suckInItems} 本体、
     * 以及 {@code entityInside} 触发的 {@code tryMoveItems}）也放开，
     * 让它们不再被 8 gt 卡着。只对入库漏斗生效，普通漏斗一个字节都不碰。</p>
     */
    @Inject(method = "pushItemsTick", at = @At("TAIL"))
    private static void carpetAutomending$unlimitedCooldown(Level level, BlockPos pos, BlockState state,
                                                            HopperBlockEntity hopper, CallbackInfo ci) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!VoidWarehouseHopper.isIntake(serverLevel, pos, state)) {
            return;
        }
        ((VoidWarehouseHopperFilter) hopper).resetCooldown();
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
        VoidWarehouseHopperFilter filter = (VoidWarehouseHopperFilter) hopper;
        if (!VoidWarehouseClassifier.hasAnySeed(filter)) {
            // 防御：一件分类物都没有就没有白名单可言，放行（正常路径上开关已经被关掉了）
            return;
        }
        if (!VoidWarehouseClassifier.matchesAny(filter, stack)) {
            // 不是分类物 → 原样留在上游容器 / 上游漏斗里，不进这个漏斗
            cir.setReturnValue(false);
            return;
        }
        if (!VoidWarehouseClassifier.isOwnSlot(filter, stack, slot)) {
            // 是分类物，但这一格不是它的格子 → 拒绝。
            //
            // 少了这一句就是「分类物摊开」事故：原版 addItem 按 slot 0→N 逐个试，
            // tryMoveInItem 对空格子直接 setItem 整摞写入，于是 slot0 那格被补满之后，
            // 多出来的那 1 个就去开垦 slot1 —— 一 tick 下来 5 格全变成 1 1 1 1 1
            // （玩家手动摆的分类只剩第一格，后面四格被货物占住了）。
            // 这里卡住之后，分类物只进得了自己的格子，「哪格是哪种」就永远是玩家摆的样子。
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
