package neko.voidwarehouse;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「某一页」的容器视图。
 *
 * <p>它<b>不是</b>全局共享的那一份：每个玩家可以停在不同的页码、也可以各自开关
 * 「潜影盒模式」与「数量排序」，所以每个 {@link VoidWarehouseMenu} 各持有一个本类实例。
 * 真正共享的是背后的 {@link VoidWarehouseData}。</p>
 *
 * <h2>两个界面：仓库页 / 合成页</h2>
 * <p>2026-11-17 起，同一个 54 格容器有两种内容：</p>
 * <ul>
 *   <li><b>仓库页</b>（第 1 张）：真实库存，一格一种，顺序按创造栏；</li>
 *   <li><b>合成页</b>（第 2 张）：{@link VoidWarehouseData#exchangeOffers()} 的全部「可兑 / 可合成」
 *       条目，<b>与仓库里有没有货无关</b>。</li>
 * </ul>
 * <p>以前这两样是<b>混在同一条序列里</b>的，后果是：仓库里只要有 1 个某物品，那件东西的
 * 合成条目就整体消失（合成入口被库存绑住），而且混排既占页码又让人分不清「这是我存的，
 * 还是我能做的」。现在底栏第 45 格固定是「合成台 / 返回仓库」的切换按钮，
 * 两个世界各占一张，互不干扰。</p>
 *
 * <h2>🔴 位置钉住：一页的顺序在玩家动手期间不重排</h2>
 * <p>「数量排序」的排序键是<b>数量</b>，而玩家每取走一组，数量就变、名次就变。
 * 于是老实现里出现过这种事：点第 12 格拿走一组圆石 → 下一 tick 45 格几乎全变 →
 * 玩家几十毫秒后的第二击落在<b>另一件物品</b>上。创造序之所以稳，是因为它的键只有
 * 「物品身份」，与数量和存取历史都无关。</p>
 *
 * <p>所以这一页现在是<b>钉住</b>的：{@link #pin} 记住「这一格是谁」（真实格记
 * {@link VoidWarehouseData.Entry} 本身，合成页记 itemId），顺序只在四个时机重建 ——
 * 打开界面、翻页、切排序 / 搜索、以及<b>停手满 {@link #IDLE_TICKS} tick</b> 之后。
 * 动手期间一格都不挪；格子被取空了就留一个<b>空洞</b>（不左移），
 * 等下一次重排再补上。点一个已经空掉的格子是<b>空操作</b> ——
 * 绝不会把现在占着这个位置的另一件东西交出去。</p>
 *
 * <p>数量本身照常实时刷新（格子上写的数字会变），只有<b>名次</b>冻结。</p>
 *
 * <h2>为什么这里几乎全是「只读」的</h2>
 * <p>仓库的写操作只有一种正确姿势：{@link VoidWarehouseData#insert(ItemStack)}（数量累加）和
 * 扣减原语（见 {@link VoidWarehouseData#takeEntry}）。原版那些直接往槽位里写物品的路径
 * （Shift 点击、拖拽、双击取物）如果绕过这两条，就会把「一种物品一格、数量无上限」的账目写乱，
 * 甚至凭空生造物品。所以：</p>
 * <ul>
 *   <li>{@link #canPlaceItem(int, ItemStack)} 对<b>所有</b>格子都返回 false；</li>
 *   <li>{@link #setItem(int, ItemStack)} 直接忽略写入；</li>
 *   <li>{@link #removeItem(int, int)} / {@link #removeItemNoUpdate(int)} 仍按真实数量扣减。</li>
 * </ul>
 */
final class VoidWarehousePageContainer implements Container {
    /**
     * 停手多少 tick 之后才允许「归位」（重排）。
     *
     * <p>20 tick = 1 秒，这里取 2 秒：连点取货的手速通常在 3~6 次/秒，
     * 2 秒的空隙足够把「连点」和「停下来看看」分开。太短会重新引入抖动，
     * 太长则取空之后界面上挂着一片空洞。</p>
     */
    static final int IDLE_TICKS = 40;

    /**
     * 空格子的占位图标（屏障方块），<b>按语言各缓存一份</b>。
     *
     * <p>只在<b>展示层</b>出现：{@link #getItem(int)} 对「本页该位置没有物品」的内容格返回它，
     * 客户端（纯原版大箱子）就会画一个屏障图标。它<b>永不被读 / 写 / 取</b>。</p>
     */
    private static final ItemStack PLACEHOLDER_ZH = buildPlaceholder(false);
    private static final ItemStack PLACEHOLDER_EN = buildPlaceholder(true);

    private static ItemStack buildPlaceholder(boolean en) {
        ItemStack placeholder = new ItemStack(Items.BARRIER);
        placeholder.set(DataComponents.CUSTOM_NAME,
                VoidWarehouseText.line(VoidWarehouseText.tr(en, "空位", "Empty"), VoidWarehouseText.OFF));
        placeholder.set(DataComponents.LORE, new ItemLore(List.of(
                VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "物品入库后这里会显示真实物品",
                                "Shows the real item once stored"),
                        VoidWarehouseText.NOTE))));
        return placeholder;
    }

    /**
     * 这个堆叠是不是本容器画出来的<b>占位图标</b>（屏障 + 我们那段名字与说明）。
     *
     * <p>为什么要有个「认得出自己」的办法：占位图标是<b>真的屏障物品</b>，只是永远不该
     * 离开界面。客户端是纯原版大箱子，它<b>不认识</b> {@code PlaceholderSlot} 的
     * {@code mayPickup}，看见格子里的屏障就会照原版语义做本地预测 —— 于是玩家
     * 「用按键卡进背包」看到的那一份，只存在于客户端。服务端能把这一份抹掉的前提，
     * 就是<b>分得清「我们画的假屏障」和「玩家真的存进去的屏障」</b>：判据用自定义名
     * 与说明两段文案，玩家自己在创造模式里拿的屏障没有这两段，不会被误删。</p>
     *
     * <p>用法见 {@code VoidWarehouseMenu#discardPrediction} 与 {@code #removed}。</p>
     */
    static boolean isPlaceholder(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.BARRIER)) {
            return false;
        }
        var name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            return false;
        }
        return name.equals(PLACEHOLDER_ZH.get(DataComponents.CUSTOM_NAME))
                || name.equals(PLACEHOLDER_EN.get(DataComponents.CUSTOM_NAME));
    }

    private final VoidWarehouseData data;

    /**
     * 打开这个界面的玩家客户端语言是不是英语。
     *
     * <p>本容器是<b>每个菜单一份</b>（= 每个看界面的玩家一份），所以「这份视图该发哪种语言的
     * tooltip」在这里定死一次就够了。共享的 {@link VoidWarehouseData} 里的 tooltip 缓存按语言
     * 各存一份（见 {@code VoidWarehouseData.Entry#display(boolean)}）。</p>
     */
    private final boolean en;

    /**
     * 这个玩家自己的视图状态（页码 + 两个开关 + 停在哪个界面）。
     *
     * <p>它<b>不跟着菜单走</b>：在 {@link VoidWarehouseViews} 里按玩家 UUID 持久化，
     * 所以关掉界面再打开（甚至重启服务器）都还是他离开时的样子。</p>
     */
    private final VoidWarehouseViews.View view;

    // ------------------------------------------------------------------ 搜索过滤

    /**
     * 搜索过滤集合（{@code null} = 不过滤）：只展示集合内物品的那几格。
     *
     * <p>由客户端模组把「中文 / 拼音命中」的物品 id 集合通过 {@code search_apply} 通道发上来。
     * 纯原版客户端永远不发这条通道，集合恒为 {@code null}，行为与从前完全一致。
     * <b>两个界面都吃这一层过滤</b> —— 合成页满本有七百多条，没有搜索基本没法用。</p>
     */
    private Set<Item> searchFilter;

    /** 过滤后的<b>内容序号</b>数组（仓库页 = 库存格下标；合成页 = 可兑条目下标） */
    private int[] filteredOrder;

    /** 计算 {@link #filteredOrder} 时所依据的底层序列（引用比较，仓库一变就重算） */
    private Object filteredBase;

    /** 计算 {@link #filteredOrder} 时的过滤集合与排序开关 / 界面（防同引用不同语义的重算遗漏） */
    private Set<Item> filteredFor;
    private boolean filteredSort;
    private boolean filteredCraft;

    // ------------------------------------------------------------------ 位置钉住

    /**
     * 本页 45 个位置各是谁：{@link VoidWarehouseData.Entry}（仓库页）/
     * {@link String} 形式的 itemId（合成页）/ {@code null}（该位置本来就没东西）。
     *
     * <p>它是<b>会话级</b>的：不进存档、不跨菜单，玩家关掉界面就丢掉。</p>
     */
    private final Object[] pin = new Object[VoidWarehouseData.PAGE_SIZE];

    private boolean pinValid;

    /** 钉住时的上下文 —— 任何一项变了都必须立刻重建（这是「玩家主动换视角」） */
    private int pinPage;
    private boolean pinSort;
    private boolean pinCraft;
    private Set<Item> pinFilter;

    /** 钉住时所依据的底层序列引用：它变了说明仓库 / 清单动过，但要不要跟，得看手停没停 */
    private Object pinBase;

    /** 本菜单最后一次「玩家自己动手」的 tick（点击 / 取满 / 翻页），空闲重排的基准 */
    private long touchTick;

    /** 合成页的有序下标缓存（跟着清单引用与排序开关失效） */
    private int[] craftOrder;
    private Object craftOrderBase;
    private boolean craftOrderSort;

    /** 合成页的 itemId → 条目索引（跟着清单引用失效，避免每格线性扫七百条） */
    private Map<String, VoidWarehouseData.ExchangeOffer> offerIndex;
    private Object offerIndexBase;

    VoidWarehousePageContainer(VoidWarehouseData data, VoidWarehouseViews.View view, boolean english) {
        this.data = data;
        this.view = view;
        this.en = english;
        this.touchTick = VoidWarehouseData.nowTick();
    }

    // ------------------------------------------------------------------ 界面切换

    /** 这个界面收件人用的是不是英语（聊天提示也要按它取词） */
    boolean isEnglish() {
        return this.en;
    }

    /** 现在是不是「合成页」 */
    boolean craftScreen() {
        return this.view.craftScreen();
    }

    /**
     * 切到另一个界面，并把页码各自复位。
     *
     * <p>两个界面的页数完全不同（仓库页按库存种类、合成页按可合成条目），
     * 共用一份页码必然越界，所以 {@link VoidWarehouseViews.View} 里各存一个。</p>
     */
    void setCraftScreen(boolean craft) {
        this.view.setCraftScreen(craft);
        this.invalidatePin();
    }

    /** 玩家刚动过手（点格子 / 翻页 / 取满）：把空闲计时清零，这一下不重排 */
    void noteInteraction() {
        this.touchTick = VoidWarehouseData.nowTick();
    }

    // ------------------------------------------------------------------ 搜索过滤

    /**
     * 挂上 / 清除搜索过滤（空集合 = 清除），并把页码回到第一页。
     *
     * <p>调用方（菜单 → 网络 → 客户端）负责之后做全量重发；这里只管状态本身。</p>
     */
    void setSearchFilter(Set<Item> filter) {
        this.searchFilter = (filter == null || filter.isEmpty()) ? null : filter;
        this.filteredOrder = null;
        this.invalidatePin();
        // 搜索结果从第一页看起是唯一合理的起点
        this.setPage(0);
    }

    /** 当前是否挂着搜索过滤（诊断 / 自检用） */
    boolean hasSearchFilter() {
        return this.searchFilter != null;
    }

    /** 本界面的内容格总数：仓库页 = 库存种类数；合成页 = 可合成条目数 */
    private int contentCount() {
        return this.craftScreen() ? this.data.exchangeOffers().size() : this.data.displayCount();
    }

    /** 本页数（有过滤时按过滤后的格数算） */
    int pageCount() {
        int[] filtered = this.filteredView();
        int count = filtered != null ? filtered.length : this.orderedView().length;
        return Math.max(1, (count + VoidWarehouseData.PAGE_SIZE - 1) / VoidWarehouseData.PAGE_SIZE);
    }

    /**
     * 过滤后的内容序号：一串<b>内容下标</b>（仓库页 = {@code entries} 的下标，
     * 合成页 = 清单下标），只是跳过了不命中搜索集合的格子。
     *
     * <p>底层序列换了（仓库变了 / 排序切了 / 过滤集合换了 / 换了界面）就整体重算 ——
     * 与底层序列的缓存作废机制天然对齐：仓库任何变动都会让
     * {@code VoidWarehouseData} 生成新的数组，引用一变这里就跟着重算，
     * <b>没有自己的失效时钟</b>，也就没有「数量变了顺序没变」这类漂移。</p>
     */
    private int[] filteredView() {
        if (this.searchFilter == null) {
            this.filteredOrder = null;
            this.filteredBase = null;
            this.filteredFor = null;
            return null;
        }
        boolean craft = this.craftScreen();
        int[] ordered = this.orderedView();
        if (this.filteredOrder != null && this.filteredFor == this.searchFilter
                && this.filteredCraft == craft && this.filteredBase == ordered) {
            return this.filteredOrder;
        }
        int[] picked = new int[ordered.length];
        int n = 0;
        for (int index : ordered) {
            if (this.matchesFilterAt(craft, index)) {
                picked[n++] = index;
            }
        }
        this.filteredOrder = n == ordered.length ? picked : java.util.Arrays.copyOf(picked, n);
        this.filteredBase = ordered;
        this.filteredFor = this.searchFilter;
        this.filteredCraft = craft;
        return this.filteredOrder;
    }

    /**
     * 本界面的<b>有序内容下标</b>（过滤之前）：仓库页按创造栏 / 数量，合成页按可合成数量 / 创造栏。
     *
     * <p>「内容下标」在仓库页 = {@code entries} 的下标，在合成页 = {@code exchangeOffers()} 的下标。
     * 分开只是因为两者是两条完全不同的序列，取值规则没必要统一。</p>
     */
    private int[] orderedView() {
        if (this.craftScreen()) {
            return this.craftOrder();
        }
        if (!this.view.amountSort()) {
            int n = this.data.displayCount();
            int[] order = new int[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            return order;
        }
        return this.data.displayAmountOrder();
    }

    /**
     * 合成页的排列：关闭数量排序时按创造栏，打开时按「还能做多少个」从大到小。
     *
     * <p>与仓库页同一套口径 —— 排序键是「这一格现在能给我多少个东西」。
     * 结果按清单引用缓存：清单每 tick 至多重算一次，而这个方法在渲染一帧里会被问好几次
     * （导航行、翻页、钉住重建），不缓存就是每帧排 746 条。</p>
     */
    private int[] craftOrder() {
        List<VoidWarehouseData.ExchangeOffer> offers = this.data.exchangeOffers();
        if (this.craftOrder != null && this.craftOrderBase == offers
                && this.craftOrderSort == this.view.amountSort()) {
            return this.craftOrder;
        }
        int n = offers.size();
        Integer[] boxed = new Integer[n];
        for (int i = 0; i < n; i++) {
            boxed[i] = i;
        }
        if (this.view.amountSort()) {
            java.util.Arrays.sort(boxed, (a, b) -> {
                int byAmount = offers.get(b).obtainable().compareTo(offers.get(a).obtainable());
                return byAmount != 0 ? byAmount : Integer.compare(a, b);
            });
        }
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = boxed[i];
        }
        this.craftOrder = order;
        this.craftOrderBase = offers;
        this.craftOrderSort = this.view.amountSort();
        return order;
    }

    /** 内容序号 {@code index} 那一格的物品是否命中搜索集合（解析不出来 = 不命中） */
    private boolean matchesFilterAt(boolean craft, int index) {
        ItemStack template;
        if (craft) {
            List<VoidWarehouseData.ExchangeOffer> offers = this.data.exchangeOffers();
            template = index >= 0 && index < offers.size() ? offers.get(index).template() : null;
        } else {
            VoidWarehouseData.DisplaySlot slot = this.data.displaySlotAt(index);
            template = slot.entry() != null ? slot.entry().template() : null;
        }
        return template != null && !template.isEmpty()
                && this.searchFilter.contains(template.getItem());
    }

    // ------------------------------------------------------------------ 页码

    /** 当前页码：把存着的页码按<b>实际页数</b>夹一下再返回（只在读的时候夹，不回写） */
    int page() {
        int raw = this.craftScreen() ? this.view.craftPage() : this.view.page();
        return Math.max(0, Math.min(raw, this.pageCount() - 1));
    }

    void setPage(int page) {
        if (this.craftScreen()) {
            this.view.setCraftPage(page);
        } else {
            this.view.setPage(page);
        }
        this.invalidatePin();
    }

    boolean shulkerMode() {
        return this.view.shulkerMode();
    }

    void setShulkerMode(boolean enabled) {
        this.view.setShulkerMode(enabled);
        // 盒模式只改渲染，不改顺序；但格子里的说明换了，客户端要看新的 —— 由调用方重发
    }

    boolean amountSort() {
        return this.view.amountSort();
    }

    void setAmountSort(boolean enabled) {
        this.view.setAmountSort(enabled);
        // 「玩家主动换视角」⇒ 立刻重排，不等空闲
        this.invalidatePin();
    }

    // ------------------------------------------------------------------ 位置钉住

    /**
     * 下一次询问必须重建（换页 / 换排序 / 换过滤 / 换界面 / <b>配方本被删</b>）。
     *
     * <p>包内可见是为了让 {@code VoidWarehouseMenu#refreshAfterRecipesChanged()} 能
     * <b>立刻</b>作废这一页 —— 删除之后等 {@code IDLE_TICKS} 的那 2 秒里，
     * 旧 {@code ExchangeOffer} 仍会按删除前的配方树出料。</p>
     */
    void invalidatePin() {
        this.pinValid = false;
    }

    /**
     * 保证本页的 45 个位置是钉住的，必要时重建。
     *
     * <p>重建的判据只有两条：① 玩家自己换了视角（翻页 / 排序 / 搜索 / 界面）；
     * ② 底层序动过 <b>且</b> 玩家已经停手超过 {@link #IDLE_TICKS}。
     * 第二条就是「连点不重排、停下来才归位」的全部实现。</p>
     */
    private void ensurePin() {
        boolean craft = this.craftScreen();
        int page = this.page();
        boolean sort = this.view.amountSort();
        Object base = craft ? this.data.exchangeOffers() : this.data.unifiedOrderRef();

        boolean rebuild = !this.pinValid
                || this.pinPage != page
                || this.pinSort != sort
                || this.pinCraft != craft
                || this.pinFilter != this.searchFilter;

        if (!rebuild && base != this.pinBase) {
            // 底层动过，但只在手停下来之后才跟
            rebuild = VoidWarehouseData.nowTick() - this.touchTick >= IDLE_TICKS;
        }
        if (!rebuild) {
            return;
        }

        int[] filtered = this.filteredView();
        int[] ordered = filtered != null ? filtered : this.orderedView();
        int from = page * VoidWarehouseData.PAGE_SIZE;
        for (int i = 0; i < VoidWarehouseData.PAGE_SIZE; i++) {
            int at = from + i;
            if (at >= ordered.length) {
                this.pin[i] = null;
                continue;
            }
            int content = ordered[at];
            this.pin[i] = craft ? this.offerIdAt(content) : this.entryAt(content);
        }
        this.pinBase = base;
        this.pinPage = page;
        this.pinSort = sort;
        this.pinCraft = craft;
        this.pinFilter = this.searchFilter;
        this.pinValid = true;
    }

    /** 仓库页：内容序号 → 库存格（越界 / 空位返回 null） */
    private VoidWarehouseData.Entry entryAt(int contentIndex) {
        return this.data.displaySlotAt(contentIndex).entry();
    }

    /** 合成页：内容序号 → itemId（越界返回 null） */
    private String offerIdAt(int contentIndex) {
        List<VoidWarehouseData.ExchangeOffer> offers = this.data.exchangeOffers();
        return contentIndex >= 0 && contentIndex < offers.size() ? offers.get(contentIndex).itemId() : null;
    }

    /** 合成页：itemId → 当前那一份条目（数量 / 成本跟着实时刷新，位置仍是钉住的） */
    private VoidWarehouseData.ExchangeOffer offerById(String itemId) {
        if (itemId == null) {
            return null;
        }
        List<VoidWarehouseData.ExchangeOffer> offers = this.data.exchangeOffers();
        if (this.offerIndex == null || this.offerIndexBase != offers) {
            Map<String, VoidWarehouseData.ExchangeOffer> built = new HashMap<>(offers.size() * 2);
            for (VoidWarehouseData.ExchangeOffer offer : offers) {
                built.put(offer.itemId(), offer);
            }
            this.offerIndex = built;
            this.offerIndexBase = offers;
        }
        return this.offerIndex.get(itemId);
    }

    /**
     * 第 {@code slot} 格背后到底是什么（真实库存格 / 可兑条目 / 空）。
     *
     * <p><b>界面层唯一一处「这一格是谁」的答案</b>：渲染它、点击它、Shift 点它，
     * 全都先问这里一次、把结果存进局部变量，绝不允许「先解析一次渲染、再解析一次扣账」——
     * 数量排序与「条目会随库存出现 / 消失」都会让两次解析给出不同结果。</p>
     */
    VoidWarehouseData.DisplaySlot slotAt(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.PAGE_SIZE) {
            return VoidWarehouseData.DisplaySlot.empty();
        }
        this.ensurePin();
        Object ref = this.pin[slot];
        if (ref == null) {
            return VoidWarehouseData.DisplaySlot.empty();
        }
        if (ref instanceof VoidWarehouseData.Entry entry) {
            // 钉住之后才被取空：留空洞，绝不让后面那件东西顶上来
            return entry.amount().signum() <= 0
                    ? VoidWarehouseData.DisplaySlot.empty()
                    : VoidWarehouseData.DisplaySlot.ofEntry(entry, -1);
        }
        return VoidWarehouseData.DisplaySlot.ofOffer(this.offerById((String) ref));
    }

    // ------------------------------------------------------------------ Container

    @Override
    public int getContainerSize() {
        return VoidWarehouseData.TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return this.data.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.TOTAL_SLOTS) {
            return ItemStack.EMPTY;
        }
        if (slot >= VoidWarehouseData.NAV_START) {
            return VoidWarehouseNav.build(
                    slot - VoidWarehouseData.NAV_START,
                    this.page(),
                    this.pageCount(),
                    this.data.entryCount(),
                    this.data.totalAmount(),
                    this.data.exchangeOfferCount(),
                    this.view.shulkerMode(),
                    this.view.amountSort(),
                    this.craftScreen(),
                    this.en);
        }
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.isEmpty()) {
            return this.en ? PLACEHOLDER_EN : PLACEHOLDER_ZH;
        }
        if (target.isExchange()) {
            // 合成页与仓库页共用「可兑 / 可合成」条目，动作行两边同源
            // （左键 1 个 / 右键一组；盒模式下换成交出一盒 + 按数量取货）。
            if (this.craftScreen()) {
                long inStock = this.data.amountOf(target.offer().template().getItem()).longValue();
                return VoidWarehouseData.buildCatalogDisplay(target.offer(), this.view.shulkerMode(), inStock, this.en);
            }
            return VoidWarehouseData.buildExchangeDisplay(target.offer(), this.view.shulkerMode(), this.en);
        }
        return this.view.shulkerMode()
                ? target.entry().displayInBoxMode(this.en)
                : target.entry().display(this.en);
    }

    /** 取出若干（真正扣减仓库数量）；返回的是一份崭新的堆叠，与仓库里的样板无关 */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot < 0 || slot >= VoidWarehouseData.NAV_START || amount <= 0) {
            return ItemStack.EMPTY;
        }
        return this.takeDisplayed(slot, amount);
    }

    /**
     * 取出「一格」。因为一格可能装着远超一组的数量，这里只交出一组 —— 绝不整格清空。
     */
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= VoidWarehouseData.NAV_START) {
            return ItemStack.EMPTY;
        }
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return this.takeDisplayed(slot, target.entry() == null
                ? Math.max(1, target.offer().template().getMaxStackSize())
                : target.entry().maxStack());
    }

    /**
     * 真实格才按真实数量扣减；<b>可兑虚拟条目一律返回空</b>。
     *
     * <p>虚拟条目背后<b>根本没有一格</b>：它的「取出」是一笔可能跨好几种物品的兑换
     * （扣 9 个铁粒、还可能退回 8 个铁锭）。从这里放行等于把一笔复杂的账塞进一个
     * 「取出 n 个」的接口里，一旦半途失败就没有任何地方能补偿。所以虚拟条目的取出
     * <b>只认 {@link VoidWarehouseMenu#clicked} 这一条路</b>。</p>
     *
     * <p>扣减走 {@link VoidWarehouseData#takeEntry}（按格子对象），不是按格号 ——
     * 钉住的那一页记的是 {@code Entry} 本身，仓库的下标随时会变。</p>
     */
    private ItemStack takeDisplayed(int slot, int amount) {
        VoidWarehouseData.DisplaySlot target = this.slotAt(slot);
        if (target.entry() == null) {
            return ItemStack.EMPTY;
        }
        VoidWarehouseData.Entry entry = target.entry();
        // 必须在 take 之前记住样板：整格取空时那一格会被移除
        ItemStack template = entry.template();
        int actual = this.data.takeEntry(entry, amount);
        return actual <= 0 ? ItemStack.EMPTY : template.copyWithCount(actual);
    }

    /**
     * <b>忽略所有写入</b>。入库只有一条合法路径：
     * {@link VoidWarehouseMenu} 收到点击后调用 {@link VoidWarehouseData#insert(ItemStack)}。
     */
    @Override
    public void setItem(int slot, ItemStack stack) {
        // intentionally empty
    }

    /**
     * 关键闸门：<b>一律 false</b>（内容格也一样）。
     *
     * <p>原版的 Shift 点击、拖拽分配、双击取物在动某个槽位之前都会先问
     * {@code slot.mayPlace(...)}，这里返回 false 就等于把「原版直接写容器」这条路彻底封死。</p>
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItem(Container container, int slot, ItemStack stack) {
        return slot >= 0 && slot < VoidWarehouseData.NAV_START;
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public void setChanged() {
        this.data.markChanged();
    }

    /** 生效 / 失效的判定统一放在 {@link VoidWarehouseMenu#stillValid(Player)}，这里没有「有效范围」概念 */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.data.clearContent();
    }
}
