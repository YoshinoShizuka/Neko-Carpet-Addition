package neko.voidwarehouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 第 6 行（容器下标 45..53）的固定导航图标。
 *
 * <pre>
 *   45 红玻璃片   46 红玻璃片    47 潜影盒模式   48 上一页◀   49 页码
 *   50 下一页▶    51 数量排序    52 红玻璃片     53 红玻璃片
 * </pre>
 *
 * <h2>为什么是这个形状：以页码为轴心的左右镜像</h2>
 * <p>整行以第 49 格（页码指示）为轴左右对称 —— 把任意一格的横坐标按轴翻折，
 * 得到的仍是同一类功能的格子：</p>
 * <pre>
 *   45 ←→ 53   红玻璃片 ←→ 红玻璃片
 *   46 ←→ 52   红玻璃片 ←→ 红玻璃片
 *   47 ←→ 51   潜影盒模式 ←→ 数量排序      （两个「模式开关」互为镜像）
 *   48 ←→ 50   上一页 ◀  ←→  下一页 ▶      （两个「翻页」互为镜像）
 *   49         页码（轴心，自己不镜像）
 * </pre>
 * <p>翻页的两个按钮紧贴页码两侧，视线从页码往左往右各一格就是「上一页 / 下一页」，
 * 不需要在整行里找；两个模式开关则分居两侧最靠里的位置，一眼能看出它们是一对。</p>
 *
 * <h2>首页 / 末页已经去掉</h2>
 * <p>原来 45 / 53 是「跳第一页 / 跳最后一页」。仓库的页数通常只有个位数（每页 45 格，
 * 1000 种物品也才 23 页），点上一页/下一页两步就到，那两个按钮纯属占位 ——
 * 去掉之后腾出来的格子正好用来做对称。</p>
 *
 * <h2>空位用红色染色玻璃片占位</h2>
 * <p>空着的位置（45 / 46 / 52 / 53）放<b>红色染色玻璃片</b>（26.3 里染色物品统一收进了
 * {@code ColorCollection}，所以是 {@code Items.STAINED_GLASS_PANE.red()}，
 * 没有 {@code Items.RED_STAINED_GLASS_PANE} 这个字段了）。点它是<b>完全的空操作</b>：
 * 它既不在 {@link #isButton(int)} 里，槽位本身也是不可拾取、不可放置的 {@code NavSlot}。</p>
 *
 * <p>按钮图标是固定的（上一页永远用同样的物品），可用与否只体现在名称颜色和
 * 说明文字上，这样位置和图形都不会跳。两个模式开关同样是「固定位置」的按钮，
 * 它们额外的状态（开 / 关）只体现在名称颜色与说明文字里，<b>槽位和图形不跳</b>。</p>
 */
final class VoidWarehouseNav {
    /** 行内下标（0..8），绝对槽位 = {@link VoidWarehouseData#NAV_START} + 该值 */
    static final int SLOT_SHULKER = 2;    //    潜影盒模式开关
    static final int SLOT_PREV = 3;       // ◀  上一页
    static final int SLOT_INDICATOR = 4;  //    第 N / M 页（整行的对称轴）
    static final int SLOT_NEXT = 5;       //    下一页 ▶
    static final int SLOT_SORT = 6;       //    数量排序开关（罗盘）
    // 0、1、7、8 是红色玻璃片占位格

    private VoidWarehouseNav() {
    }

    /** 该格是不是「翻页按钮」 */
    static boolean isPageButton(int rowIndex) {
        return rowIndex == SLOT_PREV || rowIndex == SLOT_NEXT;
    }

    /** 该格是不是潜影盒模式开关 */
    static boolean isShulkerToggle(int rowIndex) {
        return rowIndex == SLOT_SHULKER;
    }

    /** 该格是不是数量排序开关 */
    static boolean isSortToggle(int rowIndex) {
        return rowIndex == SLOT_SORT;
    }

    /** 该格是不是「可点击的按钮」（页码指示格与占位用的红玻璃都不是） */
    static boolean isButton(int rowIndex) {
        return isPageButton(rowIndex) || isShulkerToggle(rowIndex) || isSortToggle(rowIndex);
    }

    /**
     * 生成导航行某一格的显示内容。
     *
     * @param rowIndex    行内下标 0..8
     * @param page        当前页（0 起）
     * @param pageCount   总页数（至少 1）
     * @param entryCount  仓库里有多少种物品（占了多少格）
     * @param totalAmount 仓库里一共有多少个物品
     * @param shulkerMode 潜影盒模式是否开启（每个玩家各自的状态）
     * @param amountSort  数量排序是否开启（每个玩家各自的状态）
     */
    static ItemStack build(int rowIndex, int page, int pageCount, int entryCount, BigInteger totalAmount,
                           boolean shulkerMode, boolean amountSort) {
        return switch (rowIndex) {
            case SLOT_SHULKER -> shulkerToggle(shulkerMode);
            case SLOT_PREV -> button(Items.ARROW, "◀  上一页", "往前翻一页",
                    page > 0, "已经在第一页");
            case SLOT_INDICATOR -> indicator(page, pageCount, entryCount, totalAmount, amountSort);
            case SLOT_NEXT -> button(Items.ARROW, "下一页  ▶", "往后翻一页",
                    page < pageCount - 1, "已经在最后一页");
            case SLOT_SORT -> amountSortToggle(amountSort);
            default -> filler();
        };
    }

    /** 占位格：红色染色玻璃片，带一行说明，点了什么都不会发生 */
    private static ItemStack filler() {
        ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.red());
        stack.set(DataComponents.CUSTOM_NAME, VoidWarehouseText.line("占位符", VoidWarehouseText.OFF));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                VoidWarehouseText.line("点击无任何作用", VoidWarehouseText.NOTE))));
        return stack;
    }

    /**
     * 翻页按钮的图标 + 提示。
     *
     * <p>颜色按 {@link VoidWarehouseText} 的语义走：可用时名字是金（{@code VALUE} 一类的亮色），
     * 不可用时整个灰掉；底部「怎么用」统一深灰。</p>
     */
    private static ItemStack button(Item icon, String label, String hint, boolean enabled, String disabledHint) {
        ItemStack stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, VoidWarehouseText.line(label,
                enabled ? VoidWarehouseText.VALUE : VoidWarehouseText.OFF));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                VoidWarehouseText.line(enabled ? hint : disabledHint,
                        enabled ? VoidWarehouseText.VALUE : VoidWarehouseText.NOTE),
                VoidWarehouseText.line("左键翻页，不可取出", VoidWarehouseText.NOTE))));
        return stack;
    }

    /**
     * 潜影盒模式开关。
     *
     * <p>图标固定是潜影盒，开 / 关只体现在名字颜色和说明文字里，所以位置和图形完全不跳。
     * 说明只留「现在是什么模式 / 要点什么代价 / 什么情况会被拒」这三件事，
     * 每件事一行，不再把边界说明和「怎么切换」重复写两遍。</p>
     *
     * <p><b>不再写「1 盒 = 27 格 × 堆叠上限」那条换算</b>（玩家要求去掉）：潜影盒本来
     * 就是 27 格，玩家自己数得出来；把「圆石 1,728」这种算例摆在开关上，既占一行，
     * 又容易让人以为「这个数字是这个模式特有的规矩」。想知道具体能装多少，看物品格
     * 上那行换算（{@code VoidWarehouseData#buildDisplay} 的 {@code 「N 盒 M 组 K 个」}）就够。</p>
     */
    private static ItemStack shulkerToggle(boolean on) {
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CUSTOM_NAME, VoidWarehouseText.name("潜影盒模式：" + (on ? "开" : "关"), on));
        List<Component> lore = new ArrayList<>(4);
        // 当前模式
        lore.add(VoidWarehouseText.line(
                on ? "取出改为交出「装满的潜影盒」" : "当前：按普通方式取出", VoidWarehouseText.FACT));
        // 一盒多大的换算行按玩家要求去掉了（见方法 javadoc）
        // 代价
        lore.add(VoidWarehouseText.line("消耗：潜影壳 ×" + VoidWarehouseShulker.SHELL_COST
                + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST + "（优先用空盒）", VoidWarehouseText.COST));
        // 开了才需要知道的边界（什么时候拿不到满盒、什么时候整个被拒）；关着时不写，省一行
        if (on) {
            lore.add(VoidWarehouseText.line("不足一盒 → 给装不满的盒；材料不够 → 整次拒绝",
                    VoidWarehouseText.COST));
        }
        lore.add(VoidWarehouseText.line("左键切换，不可取出", VoidWarehouseText.NOTE));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /**
     * 数量排序开关（图标是罗盘）。
     *
     * <p>关着的时候仓库按<b>创造模式物品栏顺序</b>排（与存取历史无关，铁砧永远在它该在的地方）；
     * 打开之后改成<b>数量从大到小</b>，一眼就能看出仓库里什么最多。</p>
     */
    private static ItemStack amountSortToggle(boolean on) {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponents.CUSTOM_NAME, VoidWarehouseText.name("数量排序：" + (on ? "开" : "关"), on));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                // 当前排列
                VoidWarehouseText.line(on ? "当前：数量从大到小" : "当前：创造模式物品栏顺序",
                        VoidWarehouseText.FACT),
                // 只改显示不改存放（玩家最容易误会的一点，单独一行说清）
                VoidWarehouseText.line("只改显示顺序，不改仓库里的存放", VoidWarehouseText.NOTE),
                VoidWarehouseText.line("左键切换，不可取出", VoidWarehouseText.NOTE))));
        return stack;
    }

    private static ItemStack indicator(int page, int pageCount, int entryCount, BigInteger totalAmount,
                                       boolean amountSort) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.CUSTOM_NAME,
                VoidWarehouseText.name("第 " + (page + 1) + " / " + pageCount + " 页", null));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                // 最关心的两个数字合并成一行：多少种 + 多少个
                VoidWarehouseText.line("已存 " + entryCount + " 种 · 合计 "
                        + VoidWarehouseText.count(totalAmount) + " 个", VoidWarehouseText.VALUE),
                VoidWarehouseText.line("每种物品一格，数量无上限", VoidWarehouseText.NOTE),
                // 当前排列，与排序开关呼应
                VoidWarehouseText.line("排列：" + (amountSort ? "数量从大到小" : "创造模式物品栏顺序"),
                        VoidWarehouseText.FACT))));
        return stack;
    }
}
