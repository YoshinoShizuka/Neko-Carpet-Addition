package neko.voidwarehouse;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * 虚空仓库的<b>排列顺序</b>：按<b>创造模式物品栏</b>的顺序排。
 *
 * <h2>为什么需要它</h2>
 * <p>仓库之前是「谁先存进来谁在前」的插入顺序，于是同一件东西
 * <b>取空之后再存回来就跑到最后去了</b> —— 铁砧本来在很前面，取空再存一次就掉到队尾。
 * 位置取决于「玩家什么时候存的」，这既反直觉又会让玩家每次都要重新找。
 * 现在顺序只取决于<b>物品本身</b>，与存取历史彻底无关。</p>
 *
 * <h2>排序键</h2>
 * <ol>
 *   <li><b>创造模式物品栏序号</b>：把原版所有分类（建筑 / 装饰 / 自然 / 功能 / 红石 /
 *       工具 / 战斗 / 食物 / 材料 / 刷怪蛋 …）按界面上的先后顺序铺开，每种物品取<b>第一次出现</b>
 *       的位置当序号。玩家都见过这个排列，找东西最快。</li>
 *   <li><b>注册表 ID 兜底</b>：不在任何分类里的物品（例如需要权限的「管理员方块」，
 *       我们按普通玩家视角构建，所以那个分类是空的）按注册顺序排在最后一段。</li>
 * </ol>
 *
 * <h2>怎么拿到创造栏</h2>
 * <p>26.3 里 {@code CreativeModeTabs} 是<b>服务端也在</b>的类（不在 client 包里），
 * 所以不需要额外发包、也不需要客户端装模组：</p>
 * <pre>
 *   CreativeModeTabs.tryRebuildTabContents(FeatureFlags.VANILLA_SET, false /* 非 OP *&#47;, registryAccess);
 *   for (CreativeModeTab tab : CreativeModeTabs.tabs()) tab.getDisplayItems();
 * </pre>
 * <p>{@code registryAccess} 直接由 {@code ServerLevel#registryAccess()} 提供
 * （{@code RegistryAccess} 本身就是 {@code HolderLookup.Provider}）。</p>
 *
 * <h2>绝不会因为排序把仓库搞崩</h2>
 * <p>整段构建包在 {@code try} 里，任何异常都只是<b>保持注册表顺序</b>这个兜底，
 * 不向调用方抛出。兜底顺序同样是「只取决于物品本身」的稳定顺序，所以就算创造栏取不到，
 * 「取出再存入跑队尾」这个毛病也不会回来 —— 只是排列没有创造栏那么顺手。</p>
 *
 * <p>物品注册表在服务端是静态的、不会变，所以这份表<b>成功构建一次就永久有效</b>；
 * 第一次构建时机由 {@link VoidWarehouse#data} 保证（读存档之前就准备好）。</p>
 */
final class VoidWarehouseSort {
    /**
     * 不在创造栏里的物品用的基数。
     *
     * <p>必须大于任何创造栏序号：创造栏一共一千多种物品，留到一百万足够宽裕。
     * 加上注册表 ID 之后仍远小于 {@link Integer#MAX_VALUE}，不会溢出。</p>
     */
    private static final int FALLBACK_BASE = 1_000_000;

    /** 索引 = 物品注册表 ID，值 = 创造栏序号；{@code -1} = 该物品不在任何创造栏分类里。{@code null} = 还没构建成功 */
    private static int[] creativeRank;

    /**
     * 当前有效顺序的版本号。
     *
     * <p>换了一份新的表就 +1；各仓库把自己的「已排好的版本」记下来，对不上就重排。
     * 这样即使表是在仓库载入<b>之后</b>才构建成功的，顺序也会被纠正过来。</p>
     */
    private static int generation;

    private VoidWarehouseSort() {
    }

    /** 当前顺序的版本号 */
    static int generation() {
        return generation;
    }

    /**
     * 构建创造栏顺序表。<b>幂等</b>：成功后不再重复计算。
     *
     * @param registries 注册表访问器（{@code ServerLevel#registryAccess()} 即可）；
     *                   传 {@code null} 表示这次拿不到，保持兜底顺序
     */
    static void prepare(RegistryAccess registries) {
        if (creativeRank != null || registries == null) {
            return;
        }
        try {
            // 非 OP 视角：与普通玩家在创造模式里看到的物品栏一致（「管理员方块」分类会被跳过）
            CreativeModeTabs.tryRebuildTabContents(FeatureFlags.VANILLA_SET, false, registries);

            int size = BuiltInRegistries.ITEM.size();
            int[] table = new int[size];
            Arrays.fill(table, -1);

            int next = 0;
            for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
                if (tab.getType() == CreativeModeTab.Type.SEARCH) {
                    // 搜索页只是「全部物品」的另一份副本，会把分类顺序打乱，跳过。
                    // （INVENTORY / HOTBAR 那两页的 getDisplayItems() 天然是空的，不用特意排除。）
                    continue;
                }
                for (ItemStack stack : tab.getDisplayItems()) {
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    int id = BuiltInRegistries.ITEM.getId(stack.getItem());
                    if (id < 0 || id >= size || table[id] >= 0) {
                        continue; // 越界 / 已经在更靠前的分类里出现过：保留第一次的位置
                    }
                    table[id] = next++;
                }
            }

            if (next == 0) {
                // 一个物品都没排出来（分类还没构建？）：当作没成功，下次再试
                return;
            }
            creativeRank = table;
            generation++;
        } catch (Exception | LinkageError e) {
            // 排序是锦上添花，绝不能因此让仓库出事：吞掉，继续用注册表顺序。
            // 打印一行，万一真的降级了至少能在日志里看到。
            System.err.println("[carpet-Neko-Addition] 创造模式物品栏顺序不可用，虚空仓库改用物品注册顺序："
                    + e);
        }
    }

    /**
     * 该堆叠的排序键，<b>越小越靠前</b>。
     *
     * <p>纯函数、O(1) 查表，可以放心在比较器里高频调用，不需要为每个条目缓存结果。</p>
     */
    static int rankOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Item item = stack.getItem();
        int id = BuiltInRegistries.ITEM.getId(item);
        if (id < 0) {
            return Integer.MAX_VALUE; // 理论上到不了：所有物品都在注册表里
        }
        int[] table = creativeRank;
        if (table != null && id < table.length && table[id] >= 0) {
            return table[id];
        }
        return FALLBACK_BASE + id;
    }
}
