package neko.cooking;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import neko.NekoConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「混凝土粉末 → 对应颜色的染色玻璃」这 16 条熔炉配方。
 *
 * <h2>为什么不写成数据包（{@code data/<ns>/recipe/*.json}）</h2>
 * <p>数据包的配方是<b>开机时一次性加载</b>的，而 Carpet 规则要在<b>运行期</b>随意开关
 * （{@code /carpet concretePowderToGlass true}）—— 写成数据包就意味着「改规则要重启服务器」，
 * 也可以让规则关着时配方照样存在于配方书里。所以这里走运行期方案：配方<b>不进
 * {@code RecipeManager} 的配方表</b>，而是自己做一张 16 条的表，由两个 Mixin 在查表失败时补上。</p>
 *
 * <pre>
 * RecipeManager.getRecipeFor(type, input, level)   ← 熔炉方块实体找配方走这里
 *   ├─ 原版查表（我们没注册 ⇒ 空）
 *   └─ 空 → {@link #findRecipe} 兜底 ─┐
 *                                     ├─ 规则关 / 不是 SMELTING / 不是 SingleRecipeInput ⇒ 原样返回空
 * RecipeManager.byKey(id)              │   ⇒ 与原版行为完全一致
 *   └─ 用来给玩家发经验（原版烧完要按 id 反查）─┘
 *
 * AbstractFurnaceMenu.canSmelt(stack)  ← 影响「Shift 点击能不能把粉末送进输入槽」
 *   └─ false 且是普通熔炉 → {@link #isEnabledInput} 兜底
 * </pre>
 *
 * <h2>为什么 {@code canSmelt} 也要拦</h2>
 * <p>26.3 的 {@code AbstractFurnaceMenu#canSmelt} 只做一件事：
 * {@code return this.acceptedInputs.test(stack);}（javap 实证），
 * 而 {@code acceptedInputs} 是<b>开机时按已注册配方算出来的 {@code RecipePropertySet}</b>。
 * 我们的配方没注册，所以它里面永远没有混凝土粉末 —— 结果是「Shift 点击时粉末被当成燃料，
 * 不进输入槽」。这条不修，玩家就得手动一个一个摆。</p>
 *
 * <h2>经验与时长</h2>
 * <p>抄原版「沙子 → 玻璃」：{@code 0.1} 点经验、{@code 200} tick。同一条链上的产物，
 * 手感一致比另定一个数字重要。</p>
 *
 * @see neko.cooking.mixin.RecipeManagerMixin
 * @see neko.cooking.mixin.AbstractFurnaceMenuMixin
 */
public final class ConcretePowderToGlass {

    /** 烧一次给的经验，与原版「沙子 → 玻璃」一致 */
    public static final float EXPERIENCE = 0.1F;

    /** 烧一次耗时，与原版「沙子 → 玻璃」一致（tick） */
    public static final int COOKING_TIME = 200;

    private ConcretePowderToGlass() {
    }

    // ---------------------------------------------------------------- 查表 API

    /**
     * 熔炉在配方表里没查到东西时，来这儿问一句。
     *
     * <p>三个前置条件缺一不可，缺任何一个都返回空 —— 这是「规则关掉后和原版一模一样」的保证：</p>
     * <ul>
     *   <li>规则开着；</li>
     *   <li>查的是 {@link RecipeType#SMELTING}（不是高炉的 {@code BLASTING}、不是烟熏炉的
     *       {@code SMOKING}，也不是营火）；</li>
     *   <li>输入形状是 {@link SingleRecipeInput}（熔炉只有一个输入格）。</li>
     * </ul>
     */
    public static Optional<RecipeHolder<?>> findRecipe(RecipeType<?> type, RecipeInput input) {
        if (!CookingSettings.concretePowderToGlass) {
            return Optional.empty();
        }
        if (type != RecipeType.SMELTING) {
            return Optional.empty();
        }
        if (!(input instanceof SingleRecipeInput single)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bag.BY_INPUT.get(single.item().getItem()));
    }

    /**
     * 按配方 id 反查。
     *
     * <p>原版烧完一个物品后会拿 {@code this.recipeUsed} 的 id 回来查一次，
     * 用于把经验发出去（{@code AbstractFurnaceBlockEntity#getRecipesToAwardAndPopExperience}
     * → {@code RecipeManager#byKey}）。我们不过这一关，烧出来的玻璃就<b>没有经验</b>。</p>
     *
     * <p>同样受规则开关约束：关掉之后 {@code byKey} 必须与原版完全一致（我们的 id 查不到东西）。</p>
     */
    public static Optional<RecipeHolder<?>> findRecipeById(ResourceKey<Recipe<?>> id) {
        if (!CookingSettings.concretePowderToGlass) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bag.BY_ID.get(id));
    }

    /** 这件物品是不是「开了规则之后就能进普通熔炉」的混凝土粉末 */
    public static boolean isEnabledInput(ItemStack stack) {
        return CookingSettings.concretePowderToGlass
                && !stack.isEmpty()
                && Bag.BY_INPUT.containsKey(stack.getItem());
    }

    // ---------------------------------------------------------------- 自检 / 诊断用

    /**
     * 某个颜色对应的配方 id。
     *
     * <p>命名空间用本模组 id、路径 {@code concrete_powder_to_glass/<颜色>}，
     * 一眼能看出是谁加的、对应哪个颜色。</p>
     */
    public static ResourceKey<Recipe<?>> recipeId(DyeColor color) {
        return ResourceKey.create(Registries.RECIPE,
                Identifier.fromNamespaceAndPath(NekoConstants.MOD_ID,
                        "concrete_powder_to_glass/" + color.getSerializedName()));
    }

    /**
     * 该颜色的混凝土粉末。
     *
     * <p>这个版本里 16 种混凝土粉末还是<b>各自独立的 {@code Items} 字段</b>
     * （{@code Items.WHITE_CONCRETE_POWDER}…），26.2 起才统一收进
     * {@code ColorCollection}（{@code Items.CONCRETE_POWDER.pick(color)}），所以这里枚举一遍。</p>
     */
    public static Item powderOf(DyeColor color) {
        return powderItem(color);
    }

    /**
     * 颜色 → 混凝土粉末物品。
     *
     * <p>{@link DyeColor} 的 16 个常量与 {@code Items} 里的字段一一对应，但字段名是
     * 「颜色名 + {@code _CONCRETE_POWDER}」，只能用 {@code switch} 显式列出来 ——
     * 拿枚举名拼字符串再用反射查字段，拼错了要等运行期才知道，不值得。</p>
     */
    private static Item powderItem(DyeColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_CONCRETE_POWDER;
            case ORANGE -> Items.ORANGE_CONCRETE_POWDER;
            case MAGENTA -> Items.MAGENTA_CONCRETE_POWDER;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_CONCRETE_POWDER;
            case YELLOW -> Items.YELLOW_CONCRETE_POWDER;
            case LIME -> Items.LIME_CONCRETE_POWDER;
            case PINK -> Items.PINK_CONCRETE_POWDER;
            case GRAY -> Items.GRAY_CONCRETE_POWDER;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_CONCRETE_POWDER;
            case CYAN -> Items.CYAN_CONCRETE_POWDER;
            case PURPLE -> Items.PURPLE_CONCRETE_POWDER;
            case BLUE -> Items.BLUE_CONCRETE_POWDER;
            case BROWN -> Items.BROWN_CONCRETE_POWDER;
            case GREEN -> Items.GREEN_CONCRETE_POWDER;
            case RED -> Items.RED_CONCRETE_POWDER;
            case BLACK -> Items.BLACK_CONCRETE_POWDER;
        };
    }

    /** 该颜色的染色玻璃（也就是配方产出） */
    public static Item glassOf(DyeColor color) {
        return glassItem(color);
    }

    /**
     * 颜色 → 染色玻璃物品。
     *
     * <p>同上，这个版本还是 16 个独立字段（{@code Items.WHITE_STAINED_GLASS}…）。</p>
     */
    private static Item glassItem(DyeColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_STAINED_GLASS;
            case ORANGE -> Items.ORANGE_STAINED_GLASS;
            case MAGENTA -> Items.MAGENTA_STAINED_GLASS;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_STAINED_GLASS;
            case YELLOW -> Items.YELLOW_STAINED_GLASS;
            case LIME -> Items.LIME_STAINED_GLASS;
            case PINK -> Items.PINK_STAINED_GLASS;
            case GRAY -> Items.GRAY_STAINED_GLASS;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_STAINED_GLASS;
            case CYAN -> Items.CYAN_STAINED_GLASS;
            case PURPLE -> Items.PURPLE_STAINED_GLASS;
            case BLUE -> Items.BLUE_STAINED_GLASS;
            case BROWN -> Items.BROWN_STAINED_GLASS;
            case GREEN -> Items.GREEN_STAINED_GLASS;
            case RED -> Items.RED_STAINED_GLASS;
            case BLACK -> Items.BLACK_STAINED_GLASS;
        };
    }

    // ---------------------------------------------------------------- 配方的构建

    /**
     * 16 条配方的容器。
     *
     * <p>用内部类做<b>延迟初始化</b>：静态字段写在 {@code ConcretePowderToGlass} 自己身上时，
     * 只要这个类被「碰」一下（哪怕只是被 Mixin 引用到）就会立刻去碰 {@code Items}。
     * 放到内部类里，构建时机就推迟到<b>第一次真的要用配方</b>的时候 —— 那时注册表早就
     * bootstrap 完了。真正要防的是「在物品注册表就绪前构建 ⇒ 抛 ExceptionInInitializerError ⇒
     * 这个类在整个 JVM 生命周期里永久变砖」。</p>
     */
    private static final class Bag {
        /**
         * 输入物品 → 配方。
         *
         * <p>用 {@link IdentityHashMap}：{@code Items} 里的物品都是注册表单例，
         * {@code Items.CONCRETE_POWDER.pick(color)} 每次返回的也是同一个对象，
         * 「按对象身份查」正是这里的语义，也免得将来有人给 {@code Item} 加上 {@code equals}。</p>
         */
        static final Map<Item, RecipeHolder<?>> BY_INPUT = new IdentityHashMap<>();

        /** 配方 id → 配方（供 {@code byKey} 用） */
        static final Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> BY_ID = new HashMap<>();

        static {
            for (DyeColor color : DyeColor.values()) {
                register(color);
            }
        }

        private static void register(DyeColor color) {
            Item powder = powderOf(color);
            Item glass = glassOf(color);
            ResourceKey<Recipe<?>> id = recipeId(color);

            // 1.21.11 的 SmeltingRecipe 还是老式构造（String group + ItemStack 产物），
            // 26.x 才换成 Recipe.CommonInfo + ItemStackTemplate。
            SmeltingRecipe recipe = new SmeltingRecipe(
                    "",
                    CookingBookCategory.BLOCKS,
                    Ingredient.of(powder),
                    new ItemStack(glass),
                    EXPERIENCE,
                    COOKING_TIME);

            RecipeHolder<SmeltingRecipe> holder = new RecipeHolder<>(id, recipe);
            BY_INPUT.put(powder, holder);
            BY_ID.put(id, holder);

            // 16 条都必须落到表里，少一条就说明取色取歪了（或者两个颜色指到同一个物品）
            if (BY_INPUT.size() != color.ordinal() + 1) {
                throw new IllegalStateException(
                        "混凝土粉末配方构建异常：处理到 " + color + " 时表里只有 "
                                + BY_INPUT.size() + " 条（期望 " + (color.ordinal() + 1) + " 条）");
            }
        }

        private Bag() {
        }
    }
}
