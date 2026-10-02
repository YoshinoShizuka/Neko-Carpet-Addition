package neko.voidwarehouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 「潜影盒打包 / 拆解」的全部规则，集中在这一个类里。
 *
 * <h2>为什么要这么做</h2>
 * <p>仓库的模型是「一种物品 = 一格，数量无上限」。潜影盒恰好是原版里唯一能让玩家
 * <b>自己决定一格装多少</b>的容器，所以把它当作仓库的「打包单位」最自然：
 * 存的时候把盒子拆成原料，取的时候再按原料现造一个盒子。
 * 这样仓库里永远只有「纯净的散件」，不会有成千上万个「装着不同东西的潜影盒」把格子撑爆。</p>
 *
 * <h2>换算规则（原版配方）</h2>
 * <pre>
 *   1 个潜影盒 = 2 个潜影壳 + 1 个箱子
 *   1 个染色潜影盒 = 2 个潜影壳 + 1 个箱子 + 1 个对应颜色的染料
 *   1 盒能装 = 27 格 × 该物品的堆叠上限
 *              （圆石这类 64 → 1728；末影珍珠这类 16 → 432；床这类 1 → 27）
 * </pre>
 * <p>所以「取出 1 个装满圆石的潜影盒」= 扣 1728 个圆石 + 2 个潜影壳 + 1 个箱子；
 * 「存入 1 个装着 3 种东西的潜影盒」= 加那 3 种物品各自的真实数量 + 2 个潜影壳 + 1 个箱子。
 * 两个方向都严格守恒，不多不少。</p>
 *
 * <p><b>染色盒的染料也要还</b>：原版里染色盒是「任意潜影盒 + 1 个对应染料」合成的，
 * 所以拆一个染色盒必须吐出 {@code 2 壳 + 1 箱 + 1 染料} —— 只还壳和箱等于把玩家那个
 * 染料吞了。见 {@link #dyeOf(ItemStack)}。</p>
 *
 * <h2>识别</h2>
 * <p>26.3 里没有 {@code ShulkerBoxItem} 这个类，潜影盒是 <b>普通 {@code BlockItem}</b>
 * （方块是 {@link ShulkerBoxBlock}），所以用「物品是 BlockItem 且它的方块是潜影盒」
 * 来识别 —— 这样<b>带颜色的潜影盒也一并认出来</b>，不需要枚举 16 种颜色。</p>
 */
public final class VoidWarehouseShulker {
    /** 一个潜影盒的容纳格数（原版固定 27 格） */
    public static final int BOX_SLOTS = 27;

    /** 合成 1 个潜影盒需要的潜影壳数量（原版配方） */
    public static final int SHELL_COST = 2;

    /** 合成 1 个潜影盒需要的箱子数量（原版配方） */
    public static final int CHEST_COST = 1;

    /** 合成 1 个<i>染色</i>潜影盒需要的染料数量（原版配方：染色盒 = 潜影盒 + 1 染料） */
    public static final int DYE_COST = 1;

    private VoidWarehouseShulker() {
    }

    /**
     * 这个堆叠是不是一个潜影盒（任何颜色都算）。
     *
     * <p>26.3 没有 {@code ShulkerBoxItem}，潜影盒就是方块 {@link ShulkerBoxBlock} 的
     * {@link BlockItem}，所以从方块那一侧反查最省事，也顺手覆盖了 16 种染色盒。</p>
     */
    public static boolean isShulkerBox(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * 这个盒子的颜色；<b>无色盒返回 {@code null}</b>。
     *
     * <p>颜色记在<b>方块</b>上（{@link ShulkerBoxBlock#getColor()}），物品只是个
     * {@code BlockItem}，所以要从方块反查。</p>
     */
    public static DyeColor colorOf(ItemStack box) {
        if (box == null || box.isEmpty()) {
            return null;
        }
        return box.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock shulker
                ? shulker.getColor() : null;
    }

    /**
     * 拆解这个盒子时应当<b>归还的染料</b>（无色盒返回 {@link ItemStack#EMPTY}）。
     *
     * <p>原版配方是「任意潜影盒 + 1 个对应染料 → 染色盒」，所以拆染色盒时除了
     * {@code 2 壳 + 1 箱} 还得把那个染料吐回来。少了这一步，玩家每存一次染色盒就
     * 平白少一个染料 —— 而仓库对外承诺的是「拆开 = 逆向合成、严格守恒」。</p>
     *
     * <p>这个版本里 16 种染料还是<b>各自独立的 {@code Items} 字段</b>（{@code Items.RED_DYE}…），
     * 26.2 起才统一收进 {@code ColorCollection}（{@code Items.DYE.pick(color)}），
     * 所以这里枚举一遍。</p>
     */
    public static ItemStack dyeOf(ItemStack box) {
        DyeColor color = colorOf(box);
        return color == null ? ItemStack.EMPTY : new ItemStack(dyeItem(color), DYE_COST);
    }

    /**
     * 颜色 → 染料物品。
     *
     * <p>{@link DyeColor} 的 16 个常量与 {@code Items} 里的 16 个染料字段一一对应，
     * 但字段名是「颜色名 + _DYE」，只能用 {@code switch} 显式列出来 —— 拿枚举名拼字符串
     * 再用反射查字段，拼错了要等运行期才知道，不值得。</p>
     */
    private static Item dyeItem(DyeColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_DYE;
            case ORANGE -> Items.ORANGE_DYE;
            case MAGENTA -> Items.MAGENTA_DYE;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_DYE;
            case YELLOW -> Items.YELLOW_DYE;
            case LIME -> Items.LIME_DYE;
            case PINK -> Items.PINK_DYE;
            case GRAY -> Items.GRAY_DYE;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;
            case CYAN -> Items.CYAN_DYE;
            case PURPLE -> Items.PURPLE_DYE;
            case BLUE -> Items.BLUE_DYE;
            case BROWN -> Items.BROWN_DYE;
            case GREEN -> Items.GREEN_DYE;
            case RED -> Items.RED_DYE;
            case BLACK -> Items.BLACK_DYE;
        };
    }

    /** 一盒装满能装多少个「这种物品」 */
    public static int capacity(ItemStack template) {
        return BOX_SLOTS * Math.max(1, template.getMaxStackSize());
    }

    /**
     * 读出盒子里所有<b>非空</b>的内容。
     *
     * <p>返回的每一份都是<b>拷贝</b>（{@code nonEmptyItemCopyStream}），
     * 调用方可以放心地清空它们，不会反过来改到盒子本身。</p>
     */
    public static List<ItemStack> contentsOf(ItemStack box) {
        if (!isShulkerBox(box)) {
            return List.of();
        }
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>();
        contents.nonEmptyItemsCopy().forEach(out::add);
        return out;
    }

    /** 空盒子？（盒子里一个东西都没有） */
    public static boolean isEmptyBox(ItemStack box) {
        return isShulkerBox(box) && contentsOf(box).isEmpty();
    }

    /**
     * 把 {@code count} 个 {@code template} 打包成一个潜影盒。
     *
     * <p>从第 0 格开始按「每格装满」往后铺，装不下的部分留在下一格（原版潜影盒
     * 本来就允许有空位），所以 {@code count} 小于一盒容量时得到的就是一个「没装满的盒子」。</p>
     *
     * <p>{@code template} 只当样板用（数量无所谓），内部一律走
     * {@code copyWithCount(n)} 造新堆叠，<b>绝不把同一个实例挂在两处</b>。</p>
     */
    public static ItemStack pack(ItemStack template, long count) {
        int max = Math.max(1, template.getMaxStackSize());
        List<ItemStack> slots = new ArrayList<>(BOX_SLOTS);
        long remaining = Math.max(0L, count);
        while (remaining > 0 && slots.size() < BOX_SLOTS) {
            int n = (int) Math.min(max, remaining);
            slots.add(template.copyWithCount(n));
            remaining -= n;
        }
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        if (!slots.isEmpty()) {
            // fromItems 会把内容压到前面（vanilla 的潜影盒本来就只存「非空的那几格」）。
            // 一个都没装时不写组件，交出一个真正「干净」的空盒子。
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        }
        return box;
    }
}
