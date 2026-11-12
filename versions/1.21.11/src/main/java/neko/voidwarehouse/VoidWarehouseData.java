package neko.voidwarehouse;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 虚空仓库的持久化数据（全存档唯一一份）。
 *
 * <h2>存储模型：一种物品 = 一格，数量无上限</h2>
 * <p>仓库不是「一堆堆叠格」，而是一份 <b>物品类型 → 数量</b> 的清单：每种物品
 * （同 Item、同数据组件）在仓库里 <b>只占一格</b>，该格的数量没有上限（内部用
 * {@link BigInteger}，真正的任意精度，既不会溢出也不会饱和），因此不需要「一格装 64 个、
 * 装满再开一格」的散列方式，也不会出现同类物品被拆成好几格的情况。</p>
 *
 * <p>每一格同时保存一份「原样堆叠」{@link Entry#template()}（数量恒为 1），
 * 物品的 NBT / 耐久 / 附魔 / 自定义名全部原封不动留在里面；数量只存在于
 * {@link Entry#amount()}，取物品时用 {@code template.copyWithCount(n)} 现做一份全新的堆叠给玩家。
 * 这样即使一格里有十万个，也不会出现「超过 maxStackSize 的非法堆叠」。</p>
 *
 * <h2>界面展示</h2>
 * <p>{@link Entry#display()} 是给界面看的展示堆叠：格子里<b>永远只画一个</b>
 * （{@code count = 1}，原版就不会在右下角渲染数字）。真实数量动辄大于一组、
 * 甚至远超一组的量级，画在格子里既不合理也会糊到隔壁格，所以格子只当图标用，
 * 真实数量与操作提示全部写在物品描述的 lore 上
 * （「数量：N / 左键：取出 1 个 / 右键：取出 64 个」）。展示堆叠是从 template
 * 复制出来的<b>另一个实例</b>，给它加 lore 永远不会污染仓库里的真品。</p>
 *
 * <h2>排列顺序：只取决于物品本身，与存取历史无关</h2>
 * <p>{@link #entries} <b>永远</b>按 {@link VoidWarehouseSort} 的顺序键（<b>创造模式物品栏顺序</b>）排好。
 * 「谁先存进来谁在前」这套插入顺序已经被彻底废弃 —— 它会让同一件东西
 * <b>取空之后再存回来就掉到队尾</b>（铁砧本来在最前面，取空再存一次就跑到最后）。</p>
 * <p>排序是<b>惰性</b>的：写操作只把新格子追加到末尾并让顺序失效，读操作前
 * {@link #ensureSorted()} 才会真正重排一次。<b>稳定排序</b>保证顺序键相同的条目保持原有相对次序。</p>
 *
 * <h2>两种视图顺序</h2>
 * <ul>
 *   <li><b>默认</b>：就是 {@link #entries} 本身的顺序（创造栏顺序）；</li>
 *   <li><b>数量排序</b>（界面里第 6 行槽位 51 的开关）：{@link #amountOrder()} 给出一份
 *       「数量从大到小」的视图映射（数量相同仍按创造栏顺序，不会同数量的物品互相抖动）。
 *       它<b>只改显示</b>，仓库本体的顺序一个字都不动。</li>
 * </ul>
 *
 * <h2>性能：O(1) 的物品索引（漏斗压力下的关键）</h2>
 * <p>一次入库要回答「仓库里有没有这种物品」，而仓库最多能装下整个物品注册表
 * （26.3 是一千多种），每次线性扫描一遍的话：<b>一个漏斗每 tick 最多 5 次查找</b>，
 * 上千个漏斗同时灌料就是每 tick 几百万次比较，TPS 会被直接压垮。</p>
 * <p>所以这里额外维护一份 {@link #index}（{@code 物品 → 装它的那些格}，增量维护，
 * 只在新增 / 删除格子的那一次动它），把查找从 {@code O(格数)} 降到
 * <b>哈希一次 + 桶内通常只有 1 个元素的比较</b>。桶里之所以可能不止一个，
 * 是因为老存档里可能留下「同一种物品但带数据组件」的条目（现在的筛选规则已经不让它们进来了），
 * 那种情况只在桶内再扫一下，不会退化成全表扫描。</p>
 *
 * <h2>序列化与老存档兼容</h2>
 * <p>26.3 的 SavedData 体系：{@code SavedData#save/load(CompoundTag)} 已删除，改由
 * {@link SavedDataType} 携带 {@link Codec}，{@code SavedData} 只保留 {@code setDirty()/isDirty()}，
 * 写法照抄原版 {@code WeatherData}。</p>
 *
 * <p>新格式：{@code {"items":[{"item":{...},"count":1024}]}}，其中 {@code count} 小数值写 JSON 数字、
 * 超过 {@code long} 的写字符串（不丢精度）。<br>
 * 旧版本（一格最多 64 个）的格式是 {@code {"items":[{"slot":0,"stack":{...}}]}}，
 * 解码时两种都认：旧格式拿 {@code stack} 里的数量当数量、{@code slot} 直接忽略
 * （旧文件是按槽位升序写的，所以列表顺序就等于旧槽位顺序），
 * 载入时自动把同类物品折叠成一格 —— <b>老存档不会丢东西，也不会再散成好几格</b>，
 * 而且载入后的排列会被重排成创造栏顺序，与旧文件里的乱序无关。</p>
 *
 * <h2>入库筛选</h2>
 * <p>仓库<b>不设筛选</b>：任何非空物品（含带 NBT / 数据组件的受损工具、附魔物品、改名物品、
 * 药水、写了字的书……）一律按「同物品 + 同数据组件」各占一格入库。要不要放行某个物品，
 * 由<b>玩家自己在漏斗里过滤</b>（分类物白名单，见 {@link VoidWarehouseHopper}）。</p>
 *
 * <h2>潜影盒：入库时拆开（空盒除外），取出时现造</h2>
 * <p>潜影盒是原版里唯一能让玩家自己决定「一格装多少」的容器，所以它被当作仓库的
 * <b>打包单位</b>，而不是普通物品（{@link VoidWarehouseShulker}）：</p>
 * <ul>
 *   <li><b>入库</b>：非空盒子被<b>拆开</b> —— 盒里的每种物品各按真实数量入库，盒子本身变成
 *       {@code 2 个潜影壳 + 1 个箱子}（原版配方），染色盒再额外吐出 {@code 1 个对应颜色的染料}。
 *       <b>空盒子不拆</b>，直接整盒入库，
 *       占一格记 1 个「潜影盒」；</li>
 *   <li><b>校验</b>：盒内物品不再做数据组件校验（带 NBT 的物品现在也允许入库）。</li>
 * </ul>
 * <p>拆是一个「全成或全不成」的操作：校验全部通过之后才真正写入，中间不存在写一半的情况。</p>
 *
 * <p>存储位置由 {@link VoidWarehouse} 固定取主世界的 {@code SavedDataStorage}，
 * 因此是「全存档唯一、跨维度共享」。</p>
 */
public final class VoidWarehouseData extends SavedData {
    /** 界面每行 9 格（与原版大箱子一致，不可改） */
    public static final int COLUMNS = 9;

    /** 留给仓库内容的行数：6 行里最下面那一行被导航按钮占用 */
    public static final int CONTENT_ROWS = 5;

    /** 每页可存放的格数（每种物品一格，所以也是「每页多少种物品」） */
    public static final int PAGE_SIZE = COLUMNS * CONTENT_ROWS;

    /** 导航行（第 6 行）在容器里的起始下标 */
    public static final int NAV_START = PAGE_SIZE;

    /** 容器总格数：必须保持 54，才能让原版客户端套用大箱子界面（GUI 尺寸不变） */
    public static final int TOTAL_SLOTS = COLUMNS * (CONTENT_ROWS + 1);

    private static final String ITEMS_KEY = "items";
    private static final String ITEM_FIELD = "item";
    private static final String COUNT_FIELD = "count";
    /** 旧格式字段名，只用于解码，不再写出 */
    private static final String LEGACY_STACK_FIELD = "stack";

    /**
     * 配方本的<b>当前</b>字段名：一个字符串列表，一条配方一个元素。
     *
     * <p>历史上这里是「全部配方拼成一个字符串」，卡在 {@code StringTag} 的
     * {@code writeUTF} 硬上限 65,535 字节上 —— 实测 975 条顶格，976 条开始就写不出去，
     * 而且失败是静默的（{@code NbtIo$StringFallbackDataOutput} 把内容换成空串，键还在）。
     * 改成一条一个元素之后，单个元素只有几十字节，这个上限再也碰不到；
     * 列表本身的元素计数是 int，不存在上限。</p>
     *
     * <p>为什么不能直接用 {@code Codec.STRING.listOf()}：它「全成或全不成」，实测只要有一个
     * 元素读不出来就整体失败，进而整个 {@code VoidWarehouseData} 解码失败，上层当成
     * 「文件不存在」<b>静默造空仓库并覆盖旧档</b>。这里用的是
     * {@link RecipeLinesCodec#CODEC}，逐元素独立解析、坏元素只丢它自己。</p>
     */
    private static final String RECIPES_LINES_KEY = "learned_recipes_lines";

    /**
     * 配方本的<b>旧</b>字段名（单字符串、{@code '\n'} 分隔）。<b>只读，不再写出。</b>
     *
     * <p>留着它只为一件事：老存档还能读。读完第一次写盘就自动升级成
     * {@link #RECIPES_LINES_KEY}，不需要任何迁移命令，也不需要服主做任何事。</p>
     */
    private static final String RECIPES_KEY = "learned_recipes";

    /** 旧格式里配方行的分隔符（配方文本本身不含换行） */
    private static final char RECIPE_SEPARATOR = '\n';

    // ------------------------------------------------------------------ 一格

    /**
     * 仓库里的一格 = 一种物品 + 一个无上限的数量。
     *
     * <p>{@code template} 的数量恒为 1，只当「长什么样」的样板；真品数量在 {@code amount}。
     * 展示堆叠缓存（带 lore）<b>按语言各存一份</b>（双语版：中文 / 英文玩家看同一个格，
     * tooltip 各是各的语言），数量一变四份一起失效重建。</p>
     */
    public static final class Entry {
        private final ItemStack template;
        private BigInteger amount;
        private ItemStack displayZh;
        private ItemStack displayEn;
        /** 潜影盒模式下的展示堆叠（操作说明是「交出盒子」那套，与普通模式不是一回事） */
        private ItemStack boxDisplayZh;
        private ItemStack boxDisplayEn;

        Entry(ItemStack template, BigInteger amount) {
            // 只留一份数量为 1 的样板，彻底切断与原堆叠的实例关联（防复制）
            this.template = template.copyWithCount(1);
            this.amount = amount.signum() <= 0 ? BigInteger.ONE : amount;
        }

        /** 样板：数量恒为 1，数据组件完整 */
        public ItemStack template() {
            return this.template;
        }

        /** 真实数量，任意精度 */
        public BigInteger amount() {
            return this.amount;
        }

        /** 该物品一组有多少个（原版上限，16 / 64 / 1，取决于物品本身） */
        public int maxStack() {
            return Math.max(1, this.template.getMaxStackSize());
        }

        /** 累加数量。{@link BigInteger} 没有上限，永远不会溢出、也不会饱和 */
        void add(BigInteger delta) {
            if (delta.signum() <= 0) {
                return;
            }
            this.amount = this.amount.add(delta);
            this.invalidateDisplays();
        }

        void subtract(BigInteger delta) {
            if (delta.signum() <= 0) {
                return;
            }
            this.amount = this.amount.subtract(delta);
            if (this.amount.signum() < 0) {
                this.amount = BigInteger.ZERO;
            }
            this.invalidateDisplays();
        }

        /**
         * 展示缓存全部作废。
         *
         * <p>双语版（方案 B）：缓存按语言各存一份（中文玩家与英文玩家看同一个格，
         * tooltip 各是各的语言），所以一作废就是四份全废 —— 数量一变，
         * 「数量：」行本来就要重写，留着旧语言的也没有意义。</p>
         */
        private void invalidateDisplays() {
            this.displayZh = null;
            this.displayEn = null;
            this.boxDisplayZh = null;
            this.boxDisplayEn = null;
        }

        /** 普通模式的展示堆叠（带数量与操作提示），<b>按语言各缓存一份</b>，复用 */
        public ItemStack display(boolean en) {
            ItemStack cached = en ? this.displayEn : this.displayZh;
            if (cached == null) {
                cached = buildDisplay(this.template, this.amount, false, en);
                if (en) {
                    this.displayEn = cached;
                } else {
                    this.displayZh = cached;
                }
            }
            return cached;
        }

        /**
         * 潜影盒模式的展示堆叠，<b>按语言各缓存一份</b>，复用。
         *
         * <p>这里<b>不是</b>在普通说明后面追加几行 —— 而是换一套说明：盒模式下
         * 「左键取出 1 个 / 右键取出一整组」<b>根本不成立</b>（左键交出的是一整盒），
         * 留着它们既占地方又误导人。两套说明各自精简，行数都控制在 5 行以内。</p>
         */
        public ItemStack displayInBoxMode(boolean en) {
            ItemStack cached = en ? this.boxDisplayEn : this.boxDisplayZh;
            if (cached == null) {
                cached = buildDisplay(this.template, this.amount, true, en);
                if (en) {
                    this.boxDisplayEn = cached;
                } else {
                    this.boxDisplayZh = cached;
                }
            }
            return cached;
        }

        /**
         * 造展示堆叠：两种模式共用「数据行」，各自接自己的「操作行」。
         *
         * @param boxMode 潜影盒模式？决定操作行说的是「取出」还是「交出盒子」
         * @param en      看这个格子的玩家客户端语言是不是英语（tooltip 按它取词）
         */
        private static ItemStack buildDisplay(ItemStack template, BigInteger amount, boolean boxMode, boolean en) {
            int max = Math.max(1, template.getMaxStackSize());
            // 格子里永远只画一个：count = 1 时原版不会渲染右下角的数字。
            // 真实数量往往远超一组、甚至到亿级，画在格子里既不合理也会糊到隔壁格，
            // 所以格子只当图标用，真实数量写在下面的 lore 里。
            ItemStack stack = template.copyWithCount(1);
            List<Component> lore = new ArrayList<>(5);

            // ---- 数据行：数量 +（够多时的）盒 / 组 / 个换算 ----
            // 「数量：」标签用浅灰、数字用亮金 —— 分段着色。玩家扫 tooltip 找的是「有多少」，
            // 让数字跳出来、标签退到背景里；整行一个颜色时两者会互相抢视线。
            // 亮金 #FFD700 不在原版 16 色枚举里（GOLD 偏橙），所以走 RGB 重载。
            lore.add(VoidWarehouseText.pair(VoidWarehouseText.tr(en, "数量：", "Count: "), VoidWarehouseText.LABEL_RGB,
                    format(amount), VoidWarehouseText.VALUE_RGB));
            String packing = describePacking(amount, max, en);
            if (packing != null) {
                lore.add(VoidWarehouseText.line(packing, VoidWarehouseText.VALUE_RGB));
            }

            // ---- 操作行：两种模式各说各的，绝不混着显示 ----
            // 整行统一青色（ACTION_RGB）：与上面金色的数字行形成明确分层，
            // 一眼分得出「哪行是数据、哪行是能做的事」。
            if (boxMode) {
                int capacity = VoidWarehouseShulker.capacity(template);
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en,
                                "左键：交出 1 盒（" + VoidWarehouseText.count(capacity) + " 个）",
                                "Left-click: hand out 1 box (" + VoidWarehouseText.count(capacity) + " items)"),
                        VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "右键：按数量取货", "Right-click: withdraw by amount"),
                        VoidWarehouseText.ACTION_RGB));
                // 这里原先还有一行，写明装盒要额外消耗多少个潜影壳与箱子（红色）。玩家要求去掉：
                // 物品格是「拿东西」的地方，代价那行挤进来只会让人以为「取这一份要额外掏材料」。
                // 材料账本身没变，说明留在「潜影盒模式」开关那个图标的 tooltip 上（VoidWarehouseNav）。
            } else if (max > 1) {
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键：取出 1 个", "Left-click: take 1"),
                        VoidWarehouseText.ACTION_RGB));
                // 直接写数字而不是「1 组（64 个）」：玩家真正要读的是「右键给我多少个」，
                // 括号里那个数字和「组」说的是同一件事，绕一道反而更慢。
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en,
                                "右键：取出 " + max + " 个",
                                "Right-click: take " + max),
                        VoidWarehouseText.ACTION_RGB));
            } else {
                // 不可堆叠物品（剑、工具…）左右键都是取 1 个，合成一行说，不重复两遍
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键 / 右键：取出 1 个", "Left-click / Right-click: take 1"),
                        VoidWarehouseText.ACTION_RGB));
            }

            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        /**
         * 把总数换算成「X 盒 X 组 X 个」，方便玩家一眼估出要带几个潜影盒出门。
         *
         * <p>换算口径与 {@link VoidWarehouseShulker} 完全一致：
         * <b>1 盒 = 27 格 × 该物品的堆叠上限</b>（圆石 1728、末影珍珠 432），
         * <b>1 组 = 该物品的堆叠上限</b>。所以 5000 个圆石 = 2 盒 24 组 8 个。</p>
         *
         * <p><b>这是精确换算而不是估算，所以不带「≈」</b> —— 加约等于号会让人以为数字不准。</p>
         *
         * <p><b>不足一组时返回 {@code null}</b>（那行就不显示）：库存只有 30 个圆石时
         * 写「0 盒 0 组 30 个」纯属噪音。末尾余数为 0 时也省略那一段，
         * 于是「整盒」显示成「3 盒」而不是「3 盒 0 组 0 个」。</p>
         *
         * <p><b>不可堆叠物品（剑、工具…）特殊处理</b>：它们一组就等于 1 个，写「N 组」
         * 纯属废话，所以只在够一盒（27 个）时才显示，而且只说「几盒 + 几个」，
         * 不出现「组」这一级。</p>
         *
         * <p>全程用 {@link BigInteger} 做除法（{@code divideAndRemainder}），
         * 亿级数量也不会溢出 —— 数量本身是任意精度的，换算不能先把它降级成 long。</p>
         */
        private static String describePacking(BigInteger amount, int max, boolean en) {
            // 门槛：够「一组」才值得换算。不可堆叠物品一组 = 1 个，此时只有「盒」有意义，
            // 所以门槛抬到「一盒」（27 个）。
            BigInteger threshold = BigInteger.valueOf(
                    max > 1 ? max : VoidWarehouseShulker.BOX_SLOTS);
            if (amount.compareTo(threshold) < 0) {
                return null;
            }
            BigInteger boxCapacity = BigInteger.valueOf((long) VoidWarehouseShulker.BOX_SLOTS * max);
            BigInteger[] byBox = amount.divideAndRemainder(boxCapacity);
            BigInteger boxes = byBox[0];
            BigInteger stacks = BigInteger.ZERO;
            BigInteger loose = byBox[1];
            if (max > 1) {
                BigInteger[] byStack = loose.divideAndRemainder(BigInteger.valueOf(max));
                stacks = byStack[0];
                loose = byStack[1];
            }
            // 中文「X 盒 Y 组 Z 个」三段连排；英文对应用 "X box(es) Y stacks Z pc"，
            // 单位词用复数含糊化（box(es)/stack(s)）避免为单复数再做一次 BigInteger 比较
            String boxUnit = en ? " box(es) " : " 盒 ";
            String stackUnit = en ? " stack(s) " : " 组 ";
            String looseUnit = en ? " pc" : " 个";
            StringBuilder text = new StringBuilder();
            if (boxes.signum() > 0) {
                text.append(format(boxes)).append(boxUnit);
            }
            if (max > 1 && stacks.signum() > 0) {
                text.append(format(stacks)).append(stackUnit);
            }
            if (loose.signum() > 0) {
                text.append(format(loose)).append(looseUnit);
            }
            return text.toString().trim();
        }

        /** 千分位展示，{@code %,d} 原生支持 BigInteger */
        private static String format(BigInteger amount) {
            return String.format(Locale.ROOT, "%,d", amount);
        }
    }

    // ------------------------------------------------------------------ 序列化

    /**
     * 存档里的一条记录。两种格式都能读：
     * <ul>
     *   <li>新：{@code {"item":{...},"count":1024}}</li>
     *   <li>旧：{@code {"slot":0,"stack":{...64 个...}}}（{@code slot} 走默认忽略，未声明的键不会被读）</li>
     * </ul>
     * 写出去时永远是第一种。{@code count} 兼容 JSON 数字与字符串两种写法：
     * 数值在 {@code long} 范围内写数字（存档可读），超过范围写字符串（不丢精度）。
     */
    private record RawEntry(Optional<ItemStack> item, Optional<ItemStack> legacyStack, Optional<BigInteger> count) {
        /**
         * 数量的编解码：读的时候数字、字符串都收；写的时候小数值写数字、大数值写字符串。
         * {@code bitLength() <= 63} 等价于「非负且能用 long 表示」（2^63-1 的位长就是 63）。
         */
        private static final Codec<BigInteger> AMOUNT_CODEC =
                Codec.either(Codec.LONG, Codec.STRING).xmap(
                        either -> either.map(BigInteger::valueOf, RawEntry::parse),
                        value -> value.bitLength() <= 63
                                ? Either.left(value.longValue())
                                : Either.right(value.toString()));

        static final Codec<RawEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.optionalFieldOf(ITEM_FIELD).forGetter(RawEntry::item),
                ItemStack.CODEC.optionalFieldOf(LEGACY_STACK_FIELD).forGetter(RawEntry::legacyStack),
                AMOUNT_CODEC.optionalFieldOf(COUNT_FIELD).forGetter(RawEntry::count)
        ).apply(instance, RawEntry::new));

        private static BigInteger parse(String text) {
            try {
                return new BigInteger(text.trim());
            } catch (NumberFormatException e) {
                return BigInteger.ZERO;
            }
        }

        Optional<Entry> toEntry() {
            ItemStack source = this.item.or(() -> this.legacyStack).orElse(null);
            if (source == null || source.isEmpty()) {
                return Optional.empty();
            }
            // 新格式的数量在 count 上（样板数量恒为 1）；旧格式的数量在 stack 里。
            // 取两者的较大值，两种格式都能得到正确的数量。
            BigInteger amount = this.count.orElse(BigInteger.ZERO)
                    .max(BigInteger.valueOf(source.getCount()));
            if (amount.signum() <= 0) {
                amount = BigInteger.ONE;
            }
            return Optional.of(new Entry(source, amount));
        }
    }

    /**
     * 一条记录的解码结果：要么是一条可用记录，要么是一条「读不出来的原文」。
     *
     * <p>为什么要区分这两种 —— 见 {@link #ITEMS_CODEC} 的说明。这是「不让一条坏数据
     * 带走整个仓库」的关键：坏的那条被原样留下来（{@link Broken}），好的一条照常进仓库，
     * 解码<b>永远不会整体失败</b>，于是 {@code computeIfAbsent} 也永远不会把仓库
     * 替换成一个空实例。</p>
     */
    private sealed interface Decoded {
        record Ok(RawEntry entry) implements Decoded {
        }

        /** 解析失败的原始 NBT 片段，只为「原样写回」而留，不会进仓库 */
        record Broken(net.minecraft.nbt.Tag raw) implements Decoded {
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("carpet_neko_addition/void_warehouse");

    /**
     * {@code items} 列表的解码器：<b>逐条独立解码，单条失败不牵连整表</b>。
     *
     * <h2>为什么不能直接用 {@code RawEntry.CODEC.listOf()}</h2>
     * <p>{@code Codec.list} 的语义是「全成或全不成」：只要列表里有<b>任意一条</b>解码
     * 出错，整个 {@code DataResult} 就是 error 状态，{@code readSavedData} 拿到 null，
     * 而 26.3 的 {@code SavedDataStorage.computeIfAbsent} 会把 null 当成「文件不存在」，
     * <b>静默造一个空仓库</b>并标脏，退出时一路覆盖写盘 —— 仓库所有物品就此消失。
     * 那条坏数据可能只是某个模组被卸载后残留的一件物品。</p>
     *
     * <p>所以这里自己做列表解码：逐条 {@code RawEntry.CODEC.parse}，
     * 成功的进 {@link Decoded.Ok}，失败的<b>连同原文一起</b>进 {@link Decoded.Broken}
     * （原文留着是为了写盘时原样写回，不至于因为一个读不懂的条目就把它删掉）。
     * 无论多少条失败，这个方法<b>永远返回 success</b>。</p>
     */
    private static final Codec<List<Decoded>> ITEMS_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<com.mojang.datafixers.util.Pair<List<Decoded>, T>> decode(
                DynamicOps<T> ops, T input) {
            return ops.getList(input).map(stream -> {
                List<Decoded> out = new ArrayList<>();
                final int[] failures = {0};
                final int[] index = {0};
                stream.accept(element -> {
                    int at = index[0]++;
                    DataResult<RawEntry> one = RawEntry.CODEC.parse(ops, element);
                    Optional<RawEntry> ok = one.result();
                    if (ok.isPresent()) {
                        out.add(new Decoded.Ok(ok.get()));
                    } else {
                        failures[0]++;
                        // 把失败原因写进日志：以前这条信息只在「整个仓库读不出来」时
                        // 才以一句 Failed to parse 出现，现在精确到「第几条、为什么」。
                        LOGGER.error("虚空仓库第 {} 条记录解码失败，已跳过（其余 {} 条不受影响）：{}",
                                at, out.size(), one.error().map(Object::toString).orElse("未知原因"));
                        // ops 是 NbtOps 时 element 就是 CompoundTag；转成 Tag 存原文。
                        out.add(new Decoded.Broken(element instanceof net.minecraft.nbt.Tag tag
                                ? tag : net.minecraft.nbt.EndTag.INSTANCE));
                    }
                });
                if (failures[0] > 0) {
                    LOGGER.error("虚空仓库共有 {} 条记录解码失败（总 {} 条），已保留可读部分继续运行。",
                            failures[0], out.size());
                }
                return com.mojang.datafixers.util.Pair.of(out, ops.empty());
            });
        }

        @Override
        public <T> DataResult<T> encode(List<Decoded> input, DynamicOps<T> ops, T prefix) {
            com.mojang.serialization.ListBuilder<T> builder = ops.listBuilder();
            for (Decoded decoded : input) {
                if (decoded instanceof Decoded.Ok ok) {
                    builder.add(RawEntry.CODEC.encodeStart(ops, ok.entry()));
                } else if (decoded instanceof Decoded.Broken broken) {
                    // 原文写回：读不懂 ≠ 可以丢。下次说不定就有人能读懂它
                    // （比如那个模组又装回来了），在那之前原样留着。
                    // NbtOps 的元素类型就是 Tag，此时可以直接原样放回去；
                    // 换成别的 ops（理论上不会）时退回「转过去」的通用路径。
                    @SuppressWarnings("unchecked")
                    T raw = (T) broken.raw();
                    builder.add(DataResult.success(raw));
                }
            }
            return builder.build(prefix);
        }
    };

    public static final Codec<VoidWarehouseData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ITEMS_CODEC
                    .optionalFieldOf(ITEMS_KEY, List.of())
                    .forGetter(VoidWarehouseData::decodedEntries),
            // 新格式：一条配方一个元素。列表本身不设上限，单个元素也远不到 65535
            RecipeLinesCodec.CODEC
                    .optionalFieldOf(RECIPES_LINES_KEY)
                    .forGetter(VoidWarehouseData::recipeTextsLines),
            // 旧格式：只读不写（getter 恒 empty ⇒ OptionalFieldCodec 一个字节都不写这个键）。
            // 见 RECIPES_KEY 的注释：留着它只是为了让老存档还能读进来。
            Codec.STRING
                    .optionalFieldOf(RECIPES_KEY)
                    .forGetter(data -> Optional.<String>empty())
    ).apply(instance, VoidWarehouseData::fromCodec));

    /**
     * 两种格式合流的地方：新格式优先，没有新字段才回落到老的单字符串。
     *
     * <p>「两者并存」是升级过程中的正常中间态（写了一半崩了、或者用备份回滚过），
     * 此时以新格式为准 —— 新格式能装下的条数严格更多，信任它。</p>
     */
    private static VoidWarehouseData fromCodec(List<Decoded> items,
                                               Optional<List<String>> recipeLines,
                                               Optional<String> legacyRecipeBlob) {
        if (recipeLines.isPresent()) {
            return new VoidWarehouseData(items, recipeLines.get());
        }
        return new VoidWarehouseData(items, legacyRecipeBlob.orElse(""));
    }

    public static final SavedDataType<VoidWarehouseData> TYPE = new SavedDataType<>(
            "carpet_neko_addition_void_warehouse",
            VoidWarehouseData::new,
            CODEC,
            DataFixTypes.LEVEL);

    // ------------------------------------------------------------------ 状态

    /** 唯一数据源：界面、序列化、数量统计全都读这一份清单（<b>永远</b>按 {@link VoidWarehouseSort} 的顺序排好） */
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 从存档里读不出来的那些条目的<b>原始 NBT</b>。
     *
     * <p>它们不参与仓库的任何逻辑，只是一直跟着走：读出来 → 原样写回去。
     * 这样「某个模组被临时卸载、它的物品现在没人认识」不会导致那些条目被永久抹掉 ——
     * 模组装回来之后它们还能用。见 {@link #ITEMS_CODEC}。</p>
     */
    private final List<net.minecraft.nbt.Tag> brokenEntries = new ArrayList<>();

    /**
     * 从存档载入时，{@code items} 列表里<b>一共读到了多少条</b>（不管读没读懂）。
     *
     * <p>用于 {@link #loadedCount()}：上层拿它和「可读条目数」对比，就能判断
     * 「这份数据到底是本来就是空的，还是文件在那儿、内容却整片读丢了」。</p>
     */
    private int loadedEntryCount;

    /** 载入时读不出来的条数（诊断用） */
    private int loadedBrokenCount;

    /**
     * 「这一份是不是空壳」—— <b>在构造函数里一次性定下来，之后永不改变</b>。
     *
     * <p>必须这么做：判据里有一半是「此刻 {@link #entries} 是不是空的」，而
     * <b>仓库随时会被玩家取空</b>。若等到用的时候才判定，玩家把仓库搬空的那一刻
     * 就会被误判成「读到空壳」，每次访问都抛异常、仓库彻底不可用
     * （实测踩过：载入 2 条 → 清空 → 闸门误报）。「载入成不成」是<b>当时的事实</b>，
     * 只能在当时记账。</p>
     */
    private boolean loadedSuspicious;

    /**
     * 物品 → 装它的那些格（增量维护的查找索引，见类注释「性能」一节）。
     *
     * <p>与 {@link #entries} 严格同步：新增一格、删除一格、清空仓库时都会跟着改。
     * 绝大多数物品只有一格，所以每个桶通常就一个元素。</p>
     */
    private final Map<Item, List<Entry>> index = new HashMap<>();

    /** 统计缓存，-1 / null 表示需要重算 */
    private int cachedCount = -1;
    private BigInteger cachedTotal = null;

    /** {@link #entries} 已经按哪一版顺序排好了；与 {@link VoidWarehouseSort#generation()} 不符就要重排 */
    private int sortedGeneration = -1;

    /** 数量降序视图的缓存（视图下标 → 展示序号）；任何改动都会把它置空 */
    private int[] amountOrder;

    /**
     * 「可兑」条目缓存（{@link #exchangeOffers()}）。
     *
     * <p>算一次要跑 53 次规划，而规划本身是几十次递归 —— 放在渲染路径上每次重算虽然也就毫秒级，
     * 但界面一帧要问 45 次「这一格是谁」，累起来就不划算了。所以缓存，由
     * {@link #markChanged()} 在库存有任何改动时整体作废。</p>
     */
    private List<ExchangeOffer> offers;

    /**
     * 库存变过、{@link #offers} 可能已经不准了。
     *
     * <p>与「直接把 {@link #offers} 置空」的区别，就是本字段存在的唯一理由：
     * 置空之后再读，<b>同一个 tick 内</b>就会被逼着重算一次；而一次重算约 1.6 ms
     * （40 条可兑时要跑 40 次规划）。20 名玩家在同一个 tick 里各入库一次、各刷新一次界面，
     * 就是 20 次重算 ≈ 30 ms ≈ 61% 的 tick 预算 —— 这是实测出来的峰值。
     * 所以这里只标「脏」，真正的重算按 tick 合并（见 {@link #exchangeOffers()}）。</p>
     */
    private boolean offersStale;

    /** {@link #offers} 是在哪个 tick 上算出来的（与 {@link #serverTick} 同口径） */
    private long offersTick = Long.MIN_VALUE;

    /**
     * 库存变动过的物品，等下一次重算「可合成」条目时消费掉。
     *
     * <p><b>为什么要记「是谁变了」，而不是只标一个脏位</b>：{@link #markChanged()} 之后重算的是
     * <b>整张</b>可兑表 —— 满本（1236 条配方、746 个产物）一次 22 ms。但玩家取走 1 个圆石，
     * 真正会变的产物只有 13 个（实测：按物品统计的平均值，占 746 个产物的 1.7%），
     * 其余 733 个的账一个字都不会变。记下变的是谁，就只需要重算那 13 个。</p>
     *
     * <p>往里记的只有三个原语：{@link #consume}（整个仓库唯一一处「减少数量」）、
     * {@link #produce}、{@link #put}（增加端）。{@code entries} 的其它写入路径只有构造期的
     * {@code absorb}，那时缓存还没建过，天然是整批重算。所以这个集合是<b>完备</b>的 ——
     * 少记一处的后果是那件物品相关的产物一直显示旧数字，不会有别的表现。</p>
     *
     * <p>存 {@link Item} 而不是 id 字符串：这三个原语在取货路径上，一次兑换要改好几格，
     * 而 {@code idOf} 每次都要 {@code Identifier.toString()} 分配一个字符串。
     * 转成 id 只在重算时做一次。</p>
     */
    private final java.util.Set<Item> changedItems = new java.util.HashSet<>();

    /**
     * 每个产物当前的「可合成」条目（{@code itemId → 条目}）；{@code null} 值 = 此刻做不出来。
     *
     * <p>键集恒等于 {@link #recipesByOutput} 的键集：有配方就占一格，做不出来就是 {@code null}。
     * <b>「仓库里有货」也必须占一格</b>，否则「数量从 0 变成 1」这个事件就无处标记 ——
     * 而它正是产物从「可合成」消失的触发条件。</p>
     *
     * <p>用 {@link java.util.LinkedHashMap} 且只在整批重算时重新建表：增量更新走的 {@code put}
     * 对已有键<b>不改顺序</b>，于是「同一份库存、同一份配方本」无论走过多少次增量，
     * 遍历顺序都和整批重算一致 —— 展示序稳定靠的是这一条，不是靠排序兜底
     * （{@link VoidWarehouseSort#rankOf} 对每件物品给出唯一秩，同秩时的先后由遍历序决定）。</p>
     */
    private Map<String, ExchangeOffer> craftOffers = new java.util.LinkedHashMap<>();

    /**
     * 产物 id → <b>影响它结果的那些物品 id</b>（依赖集）。只在配方本换版时重建。
     *
     * <p>= 产物自己 ＋ 它每条候选配方整条闭包的全部物品。产物自己必须在里面：
     * 「仓库里有货就不出虚拟格」这条闸门看的是它自己的数量，而绝大多数配方并不把
     * 自己的产物当原料（活塞用的是木板，不是活塞），它很可能不在自己的闭包里。</p>
     *
     * <p>反查方向（物品 → 产物）<b>故意不建索引</b>：一次改动只有一两个物品、产物有几百个，
     * 直接拿脏物品去问每个产物的依赖集是 {@code 产物数 × 脏数} 次哈希查找
     * （满本 746 × 2 ≈ 30 µs），比维护一张反查表省事得多，也不会出现「表没同步」这种事故。
     * 脏物品超过 {@link #CRAFT_DIRTY_LIMIT} 时直接整批重算。</p>
     */
    private Map<String, java.util.Set<String>> craftDeps = new java.util.LinkedHashMap<>();

    /** {@link #craftOffers} / {@link #craftDeps} 是按哪一版配方本算出来的（与 {@link #recipeGeneration} 对照） */
    private int craftCacheGeneration = -1;

    /**
     * 下一次必须整批重算。
     *
     * <p>目前只有 {@link #clearContent()} 会置它：那一处把 {@code entries} 整体清空，
     * 没有逐件记账的机会。置了它也不必重建依赖集（配方结构没变），只是把每个产物都算一遍。</p>
     */
    private boolean craftCacheBusted;

    /**
     * 脏物品多到这个数就放弃增量、整批重算。
     *
     * <p>增量判定的成本是 {@code 产物数 × 脏物品数}；脏物品多到这个量级时，
     * 整批重算（每个产物跑一遍配方探针）反而更省。96 的依据：满本产物 746 个，
     * 96 × 746 ≈ 7 万次哈希查找 ≈ 1 ms，已经接近整批重算的量级。</p>
     */
    private static final int CRAFT_DIRTY_LIMIT = 96;

    /**
     * 当前的服务端 tick，由 {@link VoidWarehouseExtension#onTick} 每 tick 写一次。
     *
     * <p><b>是 static 的，故意的</b>：一来不必为了记一个 tick 戳就去 {@code VoidWarehouse.data(server)}
     * （那个方法在读不到存档时会抛异常拒绝服务，绝不能因此打断整个服务端 tick）；
     * 二来存档数据实例被替换（重载世界）时，缓存和 tick 戳不会各说各话。</p>
     *
     * <p><b>用服务端自己的 tick，而不是自己加一</b>：万一哪天 onTick 不再被调用，
     * 自己加一的计数器会停在原地 —— 那 {@link #offers} 就<b>永远</b>不再重算（比慢更糟）。
     * 用真 tick 则最坏退化成「每 tick 重算一次」，行为仍然正确。</p>
     */
    private static long serverTick;

    /**
     * 统一展示序缓存（{@link #unifiedOrder()}）：真实库存格与可兑条目按创造栏顺序归并后的序列。
     *
     * <p>编码：值 {@code >= 0} = {@link #entries} 下标；值 {@code < 0} = 可兑条目 {@code -(下标 + 1)}。</p>
     */
    private int[] unifiedOrder;

    /**
     * 上面这些缓存是在「哪些开关开着、配方本第几版」的哪种状态下算出来的。
     *
     * <p>规则开关是 Carpet 的 {@code /carpet} 命令改的，<b>不会经过 {@link #markChanged()}</b>，
     * 配方本的变化也不经过它，所以缓存不能只认库存改动。做法是每次取缓存前对一下
     * {@link #virtualEntrySignature()}：指纹一变就把几个缓存整体作废
     * （见 {@link #revalidateExchangeCache()}）。</p>
     */
    private int exchangeCacheSignature;

    /**
     * 配方本：一条配方 = 一行文本（{@link VoidWarehouseRecipes#encode}）。
     *
     * <p>用 {@link java.util.LinkedHashSet} 而不是 {@code List}：<b>文本本身就是这一条配方的标识</b>
     * （见 {@link VoidWarehouseRecipes.Recipe#recipeId()}），所以「去重」和「存储」是同一件事，
     * 不需要额外维护一份键。保序是为了「同一件物品有多条配方时，先试先学到的那条」——
     * 顺序稳定，算出来的账才稳定。</p>
     *
     * <p>这是全服共用的一份（见 {@link VoidWarehouseSettings#voidWarehouseCraft} 的注释）。</p>
     */
    private final java.util.LinkedHashSet<String> recipeTexts = new java.util.LinkedHashSet<>();

    /** {@link #recipeTexts} 解码后的对象形式（派生缓存，任何增删都整体重建） */
    private List<VoidWarehouseRecipes.Recipe> recipes = List.of();

    /** 产物 id → 该产物的所有已学配方（派生缓存，与 {@link #recipes} 同步重建） */
    private Map<String, List<VoidWarehouseRecipes.Recipe>> recipesByOutput = Map.of();

    /**
     * 配方本被改过几次。数字一变，{@link #offers} 与 {@link #unifiedOrder} 就整体作废。
     *
     * <p>为什么不复用 {@link #markChanged()}：那条路会连带 {@code setDirty()} 与统计缓存，
     * 语义是「库存变了」；配方本变了但库存没变，用同一个开关会让「学一条配方」看起来
     * 像一次库存改动。分开记更省事，也更好解释。</p>
     */
    private int recipeGeneration;

    /**
     * 「配方本满了」的告警打过了吗。
     *
     * <p>满本是一个<b>持续状态</b>，不是一次事件：玩家之后每合成一次都会再撞到一次上限。
     * 每撞一次打一条日志会把 latest.log 刷爆，所以只报第一次。
     * 这一条告警本身也是补的旧漏 —— 以前撞上限是彻底静默的，玩家只会觉得
     * 「怎么合成过它还是不能从仓库取」。</p>
     */
    private boolean capWarned;

    public VoidWarehouseData() {
    }

    /**
     * 从解码结果建仓库（<b>当前格式</b>：配方一条一个元素）。
     *
     * <p>好条目进仓库（同物品合并）；坏条目原文留在 {@link #brokenEntries} 里，
     * 下次写盘时原样写回。<b>注意这里绝不能因为「一条都读不出来」就抛异常</b> ——
     * 抛异常会让上层把整个仓库当成读取失败，那才是真正会丢数据的路径。
     * 「读到空」这种情况由 {@link VoidWarehouse#data} 那一层的指纹校验负责拦截。</p>
     */
    VoidWarehouseData(List<Decoded> loaded, List<String> recipeLines) {
        for (Decoded decoded : loaded) {
            if (decoded instanceof Decoded.Ok ok) {
                ok.entry().toEntry().ifPresent(this::absorb);
            } else if (decoded instanceof Decoded.Broken broken) {
                this.brokenEntries.add(broken.raw());
            }
        }
        this.loadedEntryCount = this.entries.size();
        this.loadedBrokenCount = this.brokenEntries.size();
        // 「读到空壳」这件事只在<b>此刻</b>有意义 —— entries 之后会随着玩家存取变化，
        // 那一半判据必须就地冻结。全是坏条目（一条都读不懂）不算空壳：
        // 那是 ITEMS_CODEC 有意保留下来的原文，会在下次写盘时原样写回。
        this.loadedSuspicious = !loaded.isEmpty() && this.entries.isEmpty() && this.brokenEntries.isEmpty();
        // 配方逐条解码：坏行静默跳过（只可能来自手工改档），绝不牵连仓库本身。
        // 回填时用的是<b>解码后的规范文本</b>而不是原始行 —— 于是坏行当场丢掉，
        // 不会再出现「数得出 5 条、实际只有 4 条能用」这种幽灵计数。
        if (recipeLines != null && !recipeLines.isEmpty()) {
            this.recipes = List.copyOf(VoidWarehouseRecipes.decodeAll(recipeLines));
            for (VoidWarehouseRecipes.Recipe recipe : this.recipes) {
                this.recipeTexts.add(recipe.recipeId());
            }
            this.recipesByOutput = VoidWarehouseRecipes.byOutput(this.recipes);
        }
        // 这里不排序：载入进来的顺序可能是旧文件的乱序，但第一次读之前
        // ensureSorted() 一定会把它整成创造栏顺序（见 sortedGeneration 的初值 -1）。
    }

    /**
     * 从解码结果建仓库（<b>旧格式</b>：全部配方拼成一个 {@code '\n'} 分隔的字符串）。
     *
     * <p>只用于读老存档。写盘永远走新格式（见 {@link #RECIPES_KEY}）。</p>
     */
    VoidWarehouseData(List<Decoded> loaded, String legacyRecipeBlob) {
        this(loaded, splitLegacyBlob(legacyRecipeBlob));
    }

    /** 把旧格式的单字符串拆成一行一条（空行丢掉）。 */
    private static List<String> splitLegacyBlob(String legacyRecipeBlob) {
        if (legacyRecipeBlob == null || legacyRecipeBlob.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : legacyRecipeBlob.split(String.valueOf(RECIPE_SEPARATOR))) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 把 {@link #recipeTexts} 重新解成对象与分组（学一条、读档各调一次；条数很少，代价可忽略） */
    private void rebuildRecipes() {
        this.recipes = List.copyOf(VoidWarehouseRecipes.decodeAll(this.recipeTexts));
        this.recipesByOutput = VoidWarehouseRecipes.byOutput(this.recipes);
    }

    /**
     * 配方本在存档里怎么写的（{@link #CODEC} 的 getter）：<b>一条一个元素</b>。
     *
     * <p>空配方本返回 {@link Optional#empty()} 而不是空列表 —— 这样新档里压根不会有这个键，
     * 存档更干净，也省得区分「从没学过」和「学了又清空」。</p>
     */
    private Optional<List<String>> recipeTextsLines() {
        if (this.recipeTexts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(this.recipeTexts));
    }

    // ------------------------------------------------------------------ 配方本

    /**
     * 「配方扣料」规则开着吗。
     *
     * <p>与 {@link #exchangeEnabled()} 同一形状：主开关 {@link VoidWarehouseSettings#voidWarehouse}
     * 与专用开关都要开。主开关关了界面都打不开，这里一并视为关闭。</p>
     */
    public static boolean craftEnabled() {
        return VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseCraft;
    }

    /**
     * {@link #learnWithResult} 的四种结局。
     *
     * <h2>为什么非得是枚举</h2>
     * <p>{@link #learn} 只回一个 {@code boolean}，可 {@code false} 藏着<b>三种完全不同的原因</b>：
     * 规则没开、这条已经记过、本子满了。玩家该看到的东西也完全不同 ——
     * 规则没开该<b>彻底静默</b>（他压根没打算用这个功能），重复不该打扰（合成本来就是重复动作），
     * 只有「满了」必须说一声：不说的话玩家以为记住了，等到要用的时候才发现界面里压根没这一格，
     * 而那时候他已经想不起来是哪一次合成没记上。</p>
     *
     * <p>三件事挤在一个 {@code false} 里，调用方就只能猜。所以多开一条能说清的路，
     * 旧的 {@link #learn} 保留原语义（{@code == LEARNED}），既有调用点一行不用改。</p>
     */
    public enum LearnResult {
        /** 新记下一条 */
        LEARNED,
        /** 这条已经在配方本里（重复合成是常态，不该每合一次都写盘） */
        DUPLICATE,
        /** 配方本已满，这条没记 */
        FULL,
        /** 规则没开，什么都没做 */
        DISABLED
    }

    /**
     * 记下一条配方（玩家刚刚在合成台上真的做出来的那一条）。
     *
     * <p>规则没开时<b>直接返回 {@code false} 且什么都不做</b> —— 关着的规则不该悄悄
     * 往存档里长东西。同一个道理，配方本满了也不再记（见
     * {@link VoidWarehouseRecipeBook#MAX_RECIPES}）。</p>
     *
     * <p>返回 {@code true} 表示这是一条<b>新</b>配方；已经记过的返回 {@code false}
     * （重复合成同一件东西是常态，不该每合一次都写一次盘）。</p>
     *
     * <p><b>需要知道「为什么没记住」时用 {@link #learnWithResult}</b> ——
     * 这个布尔版分不出「重复」和「满了」。</p>
     */
    public boolean learn(VoidWarehouseRecipes.Recipe recipe) {
        return this.learnWithResult(recipe) == LearnResult.LEARNED;
    }

    /**
     * 同 {@link #learn}，但把结局说清楚（见 {@link LearnResult}）。
     *
     * <p>落盘、重建、缓存失效这套动作与旧版逐字节相同，唯一区别是返回值更细 ——
     * 所以这是一次<b>纯增量</b>改动，不改任何既有行为。</p>
     */
    public LearnResult learnWithResult(VoidWarehouseRecipes.Recipe recipe) {
        if (!craftEnabled() || recipe == null) {
            return LearnResult.DISABLED;
        }
        if (this.recipeTexts.contains(recipe.recipeId())) {
            return LearnResult.DUPLICATE;
        }
        if (this.recipeTexts.size() >= VoidWarehouseRecipeBook.MAX_RECIPES) {
            if (!this.capWarned) {
                this.capWarned = true;
                LOGGER.warn("虚空仓库配方本已满（上限 {} 条），新配方不再记录。"
                                + "现有 {} 条仍可正常用；要记更多请调大 VoidWarehouseRecipeBook.MAX_RECIPES",
                        VoidWarehouseRecipeBook.MAX_RECIPES, this.recipeTexts.size());
            }
            return LearnResult.FULL;
        }
        this.recipeTexts.add(recipe.recipeId());
        this.rebuildRecipes();
        this.recipeGeneration++;
        // 新配方可能多出一格「可合成」虚拟条目 → 展示序与条目缓存都要重算
        this.offers = null;
        this.unifiedOrder = null;
        this.amountOrder = null;
        this.setDirty();
        return LearnResult.LEARNED;
    }

    /** 配方本满了吗（聊天提示与界面都用它，别各写一份 {@code size() >= MAX}） */
    public boolean recipeBookFull() {
        return this.recipeTexts.size() >= VoidWarehouseRecipeBook.MAX_RECIPES;
    }

    /** 配方本现在用掉多少条（提示里要报「已用 N / 上限」） */
    public int recipeBookUsed() {
        return this.recipeTexts.size();
    }

    /**
     * 同一个产物已经记下的配方，<b>按 {@code recipeId} 排序</b>。
     *
     * <p>排序不是为了好看：提示里只列前几条，顺序不稳的话同一件事两次提示会列出不同的配方，
     * 玩家会以为配方本自己变了。{@code recipeId} 由配方内容算出（见
     * {@link VoidWarehouseRecipes.Recipe}），天然稳定。</p>
     *
     * <p>⚠️ 调用方要在 {@link #learnWithResult} <b>之前</b>取：learn 之后新配方已经进本子，
     * 再取就会把它自己列进「已有配方」，提示里出现「木棍还能这样合：…木棍」这种自指。</p>
     */
    public List<VoidWarehouseRecipes.Recipe> recipesFor(String outputId) {
        if (outputId == null) {
            return List.of();
        }
        List<VoidWarehouseRecipes.Recipe> list = this.recipesByOutput.get(outputId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<VoidWarehouseRecipes.Recipe> sorted = new ArrayList<>(list);
        sorted.sort(java.util.Comparator.comparing(VoidWarehouseRecipes.Recipe::recipeId));
        return sorted;
    }

    /**
     * {@link #learnCapturingOthers} 的结果：结局 + <b>学之前</b>同产物已经有哪些配方。
     *
     * @param result       这次学的结果
     * @param othersBefore 调 {@link #learnWithResult} <b>之前</b>取的 {@link #recipesFor}，
     *                     所以里面<b>一定不含刚学进去的这一条</b>
     */
    public record LearnOutcome(LearnResult result, List<VoidWarehouseRecipes.Recipe> othersBefore) {
        public LearnOutcome {
            othersBefore = List.copyOf(othersBefore);
        }
    }

    /**
     * 学一条，同时把「学之前同产物还有哪些配方」一起交出来。
     *
     * <h2>为什么把这两步并成一个方法</h2>
     * <p>聊天提示要列出「木棍还能这样合：2 橡木木板 → 4 木棍」，而这份列表<b>必须在
     * {@link #learnWithResult} 之前取</b>：learn 之后新配方已经进了本子，再取就会把它自己
     * 算进「已有」，提示里出现「木棍还能这样合：…木棍」这种自己配自己的句子。</p>
     *
     * <p>这个先后顺序是整件事里最容易写反的一处，<b>而且写反了不会报错</b>，只是提示多一条。
     * 留在调用方（{@code CraftResultMixin}）里两行代码的话，自检够不着 —— 那要真玩家。
     * 固化成一个方法之后，「反了没有」就变成一条能断言的返回值。</p>
     */
    public LearnOutcome learnCapturingOthers(VoidWarehouseRecipes.Recipe recipe) {
        if (recipe == null) {
            return new LearnOutcome(LearnResult.DISABLED, List.of());
        }
        // ⚠️ 这两行的顺序就是全部要点：先取「已有」，再学。
        List<VoidWarehouseRecipes.Recipe> othersBefore = this.recipesFor(recipe.output());
        LearnResult result = this.learnWithResult(recipe);
        return new LearnOutcome(result, othersBefore);
    }

    /** 配方本里现在有几条<b>能用的</b>（自检与界面提示用；读不出来的那种不算，见构造函数） */
    public int learnedRecipeCount() {
        return this.recipes.size();
    }

    /** 这个产物有没有已学会的配方（界面判断「这一格该不该显示成可合成」用） */
    public boolean hasRecipeFor(String itemId) {
        return itemId != null && this.recipesByOutput.containsKey(itemId);
    }

    /**
     * 带命名空间的物品 id → 仓库里有多少个<b>纯净</b>物品（口径同 {@link #amountOf(Item)}）。
     *
     * <p>配方本的 id 是完整命名空间的（见 {@link VoidWarehouseRecipeBook#idOf(Item)}），
     * 与 9 合 1 那套只留 path 的 id 不是一回事，所以这里单独走
     * {@link VoidWarehouseRecipeBook#itemOf(String)} 解析。</p>
     */
    public BigInteger amountOfId(String itemId) {
        return this.amountOf(VoidWarehouseRecipeBook.itemOf(itemId));
    }

    /**
     * 统一合并入口：同一种物品只保留一格，数量累加。
     * 老存档里「同类物品散在多格」的情况在这里被折叠成一格。
     */
    private void absorb(Entry entry) {
        Entry existing = this.find(entry.template());
        if (existing == null) {
            this.entries.add(entry);
            this.indexEntry(entry);
            this.invalidateOrder();
        } else {
            existing.add(entry.amount());
        }
    }

    /**
     * 按「同 Item + 同数据组件」找那一格；找不到返回 null。
     *
     * <p>先按 {@link Item} 哈希到桶（通常是 1 个元素），再在桶内比对数据组件 ——
     * 所以对「纯净物品」这个主流情况来说就是一次哈希查找，与仓库里有多少种物品无关。</p>
     */
    private Entry find(ItemStack stack) {
        List<Entry> bucket = this.index.get(stack.getItem());
        if (bucket == null) {
            return null;
        }
        for (int i = 0; i < bucket.size(); i++) {
            Entry entry = bucket.get(i);
            if (ItemStack.isSameItemSameComponents(entry.template(), stack)) {
                return entry;
            }
        }
        return null;
    }

    /** 新增一格时把它登记进索引 */
    private void indexEntry(Entry entry) {
        this.index.computeIfAbsent(entry.template().getItem(), key -> new ArrayList<>(1)).add(entry);
    }

    /** 删除一格时把它从索引里摘掉（桶空了就把键一起删掉，不让索引白占内存） */
    private void unindexEntry(Entry entry) {
        Item item = entry.template().getItem();
        List<Entry> bucket = this.index.get(item);
        if (bucket == null) {
            return;
        }
        // Entry 没有覆写 equals，所以 List#remove(Object) 按身份移除，正好是我们想要的
        bucket.remove(entry);
        if (bucket.isEmpty()) {
            this.index.remove(item);
        }
    }

    // ------------------------------------------------------------------ 排列顺序

    /**
     * 保证 {@link #entries} 按当前顺序键排好。<b>惰性 + 幂等</b>，所有读路径的开头都会调它。
     *
     * <p>用 {@link List#sort}（底层 TimSort）而不是自己写比较 —— 它是<b>稳定排序</b>，
     * 顺序键相同的条目会保持原有相对次序，不会出现「两个物品来回换位」的抖动。
     * 而顺序键本身就是「物品 → 唯一序号」，本来也不会重复。</p>
     *
     * <p>只在版本对不上时才真正动手，所以稳态下每次读只是一次 int 比较；
     * 已经排好的列表再排一次因为 TimSort 能识别有序段，也只是 O(n)。</p>
     */
    private void ensureSorted() {
        int generation = VoidWarehouseSort.generation();
        if (this.sortedGeneration == generation) {
            return;
        }
        this.entries.sort(Comparator.comparingInt(entry -> VoidWarehouseSort.rankOf(entry.template())));
        this.sortedGeneration = generation;
        // 基础顺序变了 → 与可兑条目归并出来的统一展示序也作废
        this.unifiedOrder = null;
        // 顺序变了，写出去的存档也就变了（内容没变，但文件里的排列会更新）
        this.setDirty();
    }

    /** 有新格子追加到末尾时调：下次读之前整体重排一次 */
    private void invalidateOrder() {
        this.sortedGeneration = -1;
        this.unifiedOrder = null;
    }

    // ------------------------------------------------------------------ 读

    /** 仓库里有多少种物品 = 占了多少格 */
    public int entryCount() {
        this.recompute();
        return this.cachedCount;
    }

    /** 仓库里一共有多少个物品（任意精度，不会溢出） */
    public BigInteger totalAmount() {
        this.recompute();
        return this.cachedTotal;
    }

    private void recompute() {
        if (this.cachedCount >= 0 && this.cachedTotal != null) {
            return;
        }
        BigInteger total = BigInteger.ZERO;
        for (Entry entry : this.entries) {
            total = total.add(entry.amount());
        }
        this.cachedCount = this.entries.size();
        this.cachedTotal = total;
    }

    /**
     * 总页数，空仓库也算 1 页。
     *
     * <p>按 {@link #displayCount()}（真实格 + 可兑虚拟条目）算，不是按物品种类数 ——
     * 少算一页的话，落在最后一页的可兑条目会<b>永远翻不到</b>：
     * 那不仅是个死格，玩家还会以为「这里明明说能兑，界面里却没有」。</p>
     */
    public int pageCount() {
        return Math.max(1, (this.displayCount() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public boolean isEmpty() {
        this.ensureSorted();
        return this.entries.isEmpty();
    }

    /** 按格号取那一格；越界或空格返回 null */
    public Entry entryAt(int index) {
        if (index < 0) {
            return null;
        }
        this.ensureSorted();
        if (index >= this.entries.size()) {
            return null;
        }
        return this.entries.get(index);
    }

    /** 按格号取展示堆叠（带 lore，中文），空格返回 {@link ItemStack#EMPTY}（诊断 / 自检用） */
    public ItemStack displayAt(int index) {
        Entry entry = this.entryAt(index);
        return entry == null ? ItemStack.EMPTY : entry.display(false);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 入库。<b>潜影盒会被拆开</b>（空盒除外，见 {@link #unpackShulkerBox}），
     * 其余物品（含带数据组件的物品）一律按「同物品 + 同数据组件」入库。</p>
     *
     * <p>同一种物品只会占一格，数量直接累加到那一格上（无上限）；
     * 仓库里还没有这种物品时才新增一格 —— 新格子<b>追加在末尾</b>，位置由下一次读之前的
     * {@link #ensureSorted()} 统一纠正成创造栏顺序，跟「第几个存进来」无关。</p>
     *
     * <p>收下时<b>会就地清空 {@code incoming}</b>（数量置 0）；被拒收时
     * <b>原样不动 {@code incoming}</b>，调用方据此把物品留给玩家 / 留在漏斗里。
     * 内部用 {@code copyWithCount(1)} 造样板，绝不会把同一个 {@link ItemStack} 实例
     * 同时挂在两处，从根上杜绝复制。</p>
     *
     * @return 是否收下（{@code false} = 空物品）
     */
    public boolean insert(ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return false;
        }
        // <b>仓库终端本身不是货物</b>：它是打开仓库的钥匙（见 {@link VoidWarehouseTrigger}）。
        // 收进来的话，仓库里就多了一格「取出来又能当终端用」的东西 —— 而它本身只是
        // 改过名的工作台，存进来没有任何收益，只会让玩家困惑「为什么能取出来却存不回去」。
        // 所以从<b>所有</b>入库路径一起挡掉：菜单存入、漏斗进货、方块掉落、打印机返还。
        if (VoidWarehouseTrigger.isVoidWarehouse(incoming)) {
            return false;
        }
        if (VoidWarehouseShulker.isShulkerBox(incoming)) {
            return this.unpackShulkerBox(incoming);
        }
        return this.put(incoming);
    }

    /**
     * 把潜影盒<b>拆开</b>入库：盒内物品按各自数量入库，盒子本身变成
     * {@code 2 个潜影壳 + 1 个箱子}（原版配方）；<b>染色盒再额外吐出 1 个对应颜色的染料</b>
     * （原版：染色盒 = 潜影盒 + 1 染料）。见 {@link VoidWarehouseShulker#dyeOf(ItemStack)}。
     *
     * <p><b>空盒子不拆</b>：一个东西都没装的潜影盒，直接当成普通物品整盒入库
     * （占一格，记 1 个「潜影盒」）。这样玩家手里那个空盒子不会被偷偷换成壳和箱子，
     * 语义也最直观 —— 空盒子就是空盒子。染色空盒同理，整盒入库、颜色原样保留。</p>
     *
     * <p><b>盒内物品照单全收</b>：不再校验盒内物品是否带数据组件（带 NBT 的物品
     * 现在也允许入库），拆出来的每件都按各自数量入库。</p>
     *
     * <p>拆是一个「全成或全不成」的操作：校验全部通过之后才真正写入，中间不存在写一半的情况。</p>
     *
     * @return 是否收下
     */
    private boolean unpackShulkerBox(ItemStack box) {
        if (VoidWarehouseShulker.isEmptyBox(box)) {
            // 空盒子：当普通物品整盒入库（不拆成壳 + 箱）
            this.put(box);
            this.markChanged();
            return true;
        }
        List<ItemStack> contents = VoidWarehouseShulker.contentsOf(box);
        // 盒子里装着仓库终端 → 整笔拒收：拆开就等于「绕过 insert 把终端收进仓库」。
        // 拆盒是「全成或全不成」的操作，所以必须在写任何一格之前判完 —— 判在循环里
        // 就只能把已经拆出来的几样留下，反而更难解释。
        for (ItemStack inside : contents) {
            if (VoidWarehouseTrigger.isVoidWarehouse(inside)) {
                return false;
            }
        }
        for (ItemStack inside : contents) {
            // contentsOf 给的是拷贝，put 会把它清空，不会影响到盒子本身
            this.put(inside);
        }
        this.put(new ItemStack(Items.SHULKER_SHELL, VoidWarehouseShulker.SHELL_COST));
        this.put(new ItemStack(Items.CHEST, VoidWarehouseShulker.CHEST_COST));
        // 染色盒：原版是「潜影盒 + 1 染料」合成的，拆开时染料必须一起还回来。
        // 无色盒这里拿到的是 EMPTY，直接跳过（不要 put 一个空堆叠）。
        ItemStack dye = VoidWarehouseShulker.dyeOf(box);
        if (!dye.isEmpty()) {
            this.put(dye);
        }
        // 盒子本身已被「拆解」消耗掉
        box.setCount(0);
        this.markChanged();
        return true;
    }

    /**
     * 放进仓库（不做筛选），返回是否成功。
     *
     * <p>只有 {@link #insert} 与 {@link #restore} 会走到这里。</p>
     */
    private boolean put(ItemStack incoming) {
        BigInteger count = BigInteger.valueOf(incoming.getCount());
        this.changedItems.add(incoming.getItem());
        Entry entry = this.find(incoming);
        if (entry == null) {
            Entry created = new Entry(incoming, count);
            this.entries.add(created);
            this.indexEntry(created);
            // 新格子是追加在末尾的，顺序作废 —— 下次读之前 ensureSorted() 会把它插回正确位置
            this.invalidateOrder();
        } else {
            entry.add(count);
        }
        incoming.setCount(0);
        this.markChanged();
        return true;
    }

    /**
     * 回滚专用：把<b>刚刚从仓库里取出来、却没地方放</b>的物品原样放回去。
     *
     * <p>不受筛选规则限制 —— 这些物品本来就在仓库里（可能是本规则生效之前存进来的），
     * 放回去是「归还」而不是「新存」，绝不能让它们卡在筛选规则外丢掉。</p>
     */
    void restore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        this.put(stack);
    }

    // ------------------------------------------------------------------ 扣减 / 增加的两个原语

    /**
     * 从某一格扣掉 {@code amount} 个；扣到 0 就把这一格连同索引一起摘掉。
     *
     * <p><b>这是整个仓库唯一一处「减少数量」的实现</b>：取出 1 个、取走一组、
     * 聊天按量取货、漏斗出料、打印机补货、装盒扣材料、9 合 1 兑换……全部走这里。
     * 早先这段「{@code subtract} + {@code entries.remove} + {@code unindexEntry}」
     * 在 5 个方法里各抄了一份，靠人眼保证它们一致；现在只有一份，
     * 想漏掉 {@code unindexEntry}（那会导致索引里挂着一格已经不在清单里的幽灵，
     * 后续 {@code find} 返回一个 numbers 已经归零的对象）都不可能。</p>
     *
     * <p><b>不标脏</b>：调用方负责。理由是一次兑换要改好几格，
     * 中间标脏会让「数量排序」视图缓存反复失效重建，纯属浪费。</p>
     */
    private void consume(Entry entry, BigInteger amount) {
        // 记账必须先于 subtract：这一格可能因为扣到 0 而被摘掉，摘掉之后照样能拿到 template，
        // 但「先记后改」读起来没有歧义，也不依赖 Entry 的生命周期。
        this.changedItems.add(entry.template().getItem());
        entry.subtract(amount);
        if (entry.amount().signum() <= 0) {
            // Entry 没有覆写 equals，List#remove(Object) 按身份移除，正好只能摘掉这一格
            this.entries.remove(entry);
            this.unindexEntry(entry);
        }
    }

    /**
     * 给某件<b>纯净物品</b>增加 {@code amount} 个；仓库里还没有这种物品就新开一格。
     *
     * <p>目前唯一的调用方是 9 合 1 兑换里的「找零」：拆一个铁块取出 1 个铁锭，
     * 剩下的 8 个要作为<b>铁锭</b>退回仓库 —— 仓库原来可能一个铁锭都没有，
     * 那就得凭空开出一格来。</p>
     *
     * <p>这里造的是<b>纯净</b>物品（{@code new ItemStack(item)}，无任何数据组件），
     * 与 {@link #insert} 那条「玩家存进来的东西」完全不同：找零本来就是合成产物，
     * 不可能带附魔或改名。所以它<b>不能</b>走 {@code insert}（那条路会拆潜影盒、会判筛选规则），
     * 也不该走 {@code put}（那是给玩家存货用的，语义不同）。</p>
     *
     * <p>同样<b>不标脏</b>，由调用方统一收口。</p>
     */
    private void produce(Item item, BigInteger amount) {
        if (item == null || item == Items.AIR || amount.signum() <= 0) {
            return;
        }
        this.changedItems.add(item);
        Entry entry = this.find(new ItemStack(item));
        if (entry == null) {
            Entry created = new Entry(new ItemStack(item), amount);
            this.entries.add(created);
            this.indexEntry(created);
            // 新格子是追加在末尾的，顺序作废（与 put 一致）
            this.invalidateOrder();
        } else {
            entry.add(amount);
        }
    }

    // ------------------------------------------------------------------ 按物品查询 / 扣减

    /**
     * 仓库里现在有多少个「这种<b>纯净</b>物品」。
     *
     * <p>只按物品本身查（不考虑数据组件），因为潜影壳 / 箱子这类装盒原料本来就是纯净物品。
     * 需要按「物品 + 数据组件」精确查时用 {@link #amountExact(ItemStack)}。</p>
     */
    public BigInteger amountOf(Item item) {
        if (item == null || item == Items.AIR) {
            return BigInteger.ZERO;
        }
        Entry entry = this.find(new ItemStack(item));
        return entry == null ? BigInteger.ZERO : entry.amount();
    }

    /**
     * 按「物品 + 数据组件」精确查数量（与 {@link #takeExact} 同一口径）。
     *
     * <p>取消 NBT 限制之后，仓库里同一 Item 可能有多格（不同数据组件各一格），
     * 所以「取某个具体堆叠」之前必须用它的样板精确查，而不是 {@link #amountOf(Item)}
     * 那种只按物品查的宽口径。</p>
     */
    public BigInteger amountExact(ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return BigInteger.ZERO;
        }
        Entry entry = this.find(sample);
        return entry == null ? BigInteger.ZERO : entry.amount();
    }

    /**
     * 按「纯净物品」扣减数量，用于潜影盒装盒时消耗潜影壳 / 箱子。
     *
     * <p>数量不够时<b>什么都不改</b>并返回 {@code false}，调用方据此放弃整次操作。
     * 扣到 0 的那一格会被移除（与 {@link #take} 一致，不留空洞）。</p>
     */
    public boolean takeItem(Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (item == null || item == Items.AIR) {
            return false;
        }
        Entry entry = this.find(new ItemStack(item));
        BigInteger need = BigInteger.valueOf(count);
        if (entry == null || entry.amount().compareTo(need) < 0) {
            return false;
        }
        this.consume(entry, need);
        this.markChanged();
        return true;
    }

    // ------------------------------------------------------------------ 空潜影盒库存

    /**
     * 仓库里现在有多少个<b>空潜影盒</b>（各种颜色都算）。
     *
     * <p>空潜影盒是「整盒入库」的（见 {@link #unpackShulkerBox}），所以它们各自占一格
     * （不同颜色各一格）。这里把所有空盒的格子数量累加，用于「装盒时优先用空盒」：
     * 有空盒就用空盒装，省下潜影壳和箱子。</p>
     */
    public BigInteger emptyBoxCount() {
        BigInteger total = BigInteger.ZERO;
        for (Entry entry : this.entries) {
            if (VoidWarehouseShulker.isEmptyBox(entry.template())) {
                total = total.add(entry.amount());
            }
        }
        return total;
    }

    /**
     * 消耗至多 {@code wanted} 个空潜影盒，返回实际扣掉的个数。
     *
     * <p>从各种颜色的空盒格子里依次扣，扣到 0 的格子会被移除。库存不足时扣到没有为止，
     * 返回实际消耗数（调用方据此决定剩下的盒子需要多少个潜影壳 + 箱子）。</p>
     */
    public int takeEmptyBoxes(int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        int taken = 0;
        // 反向遍历，边扣边移除是安全的
        for (int i = this.entries.size() - 1; i >= 0 && taken < wanted; i--) {
            Entry entry = this.entries.get(i);
            if (!VoidWarehouseShulker.isEmptyBox(entry.template())) {
                continue;
            }
            int available = entry.amount().min(BigInteger.valueOf(wanted - taken)).intValue();
            if (available <= 0) {
                continue;
            }
            this.consume(entry, BigInteger.valueOf(available));
            taken += available;
        }
        if (taken > 0) {
            this.markChanged();
        }
        return taken;
    }

    /**
     * 「装 {@code totalBoxes} 个盒子」需要哪些材料。
     *
     * <p>= 尽量用<b>仓库里已有的空盒</b>，用完了的那部分才退化成原版配方
     * （每盒 {@code SHELL_COST} 个潜影壳 + {@code CHEST_COST} 个箱子）。</p>
     *
     * <p>两条使用口（界面点击取一盒 / 聊天按数量取货）必须共用这一份算法：
     * 早期只有「聊天按数量取货」做了空盒优先，界面里点格子却硬要壳 + 箱，
     * 于是玩家会遇到「同一个操作，在聊天里能取、点格子却说材料不够」的分裂行为。</p>
     *
     * @param totalBoxes 这一次一共要几个盒子（含「散货顺手装出来的那个」）
     */
    public BoxMaterials planBoxMaterials(long totalBoxes) {
        BigInteger want = BigInteger.valueOf(Math.max(0L, totalBoxes));
        BigInteger empty = this.emptyBoxCount().min(want);
        BigInteger craft = want.subtract(empty);
        return new BoxMaterials(
                empty.intValueExact(),
                craft.multiply(BigInteger.valueOf(VoidWarehouseShulker.SHELL_COST)).intValueExact(),
                craft.multiply(BigInteger.valueOf(VoidWarehouseShulker.CHEST_COST)).intValueExact());
    }

    /**
     * 这些材料的库存够不够。
     *
     * <p>只在 {@code plan} 里「需要现合成」的那些盒子对应的壳 / 箱上做校验 ——
     * 空盒能吃下的部分不需要再看壳 + 箱。</p>
     */
    public boolean hasBoxMaterials(BoxMaterials plan) {
        if (plan.shells() > 0 && this.amountOf(Items.SHULKER_SHELL)
                .compareTo(BigInteger.valueOf(plan.shells())) < 0) {
            return false;
        }
        return plan.chests() <= 0
                || this.amountOf(Items.CHEST).compareTo(BigInteger.valueOf(plan.chests())) >= 0;
    }

    /**
     * 按 {@link #planBoxMaterials} 的结果扣账。
     *
     * <h2>为什么「少扣了」可以补壳箱，而不能当成没发生</h2>
     * <p>原先这里少扣几个空盒就默默少扣了，调用方也不看返回值，注释还写着「那部分材料
     * 本来就不该花出去」。这个说法只在「空盒被别人抢走」时成立，一旦差额来自
     * <b>同一笔操作的取货</b>（目标格本身就是空盒格时 {@code take()} 会先把这一格取空），
     * 就变成「外盒白送一个」—— 送回仓库拆出 2 壳 + 1 箱，来回几轮就能无限刷盒子
     * （2026-11 事故，根因已由 {@link VoidWarehouseShulker#isBoxable} 堵掉）。</p>
     *
     * <p>所以这里不再「少扣就算」，而是<b>把差额换算成原版配方补扣</b>
     * （每缺 1 个空盒补 2 潜影壳 + 1 箱子）：账永远是平的，差额只可能表现为
     * 「玩家多花了壳箱」而不是「凭空多出一个盒子」。真补不上时留一条 WARN ——
     * 那是异常路径，必须能在日志里看见。</p>
     *
     * @return 实际扣掉的空盒数量（正常等于 {@code plan.emptyBoxes()}）
     */
    public int consumeBoxMaterials(BoxMaterials plan) {
        int taken = plan.emptyBoxes() > 0 ? this.takeEmptyBoxes(plan.emptyBoxes()) : 0;
        int shortfall = plan.emptyBoxes() - taken;
        int shells = plan.shells();
        int chests = plan.chests();
        if (shortfall > 0) {
            // 空盒没扣够 → 按原版配方把差额换算成壳 + 箱，绝不让外盒变成免费的
            shells += shortfall * VoidWarehouseShulker.SHELL_COST;
            chests += shortfall * VoidWarehouseShulker.CHEST_COST;
            LOGGER.warn("装盒材料少了 {} 个空盒可扣，按原版配方改用 {} 潜影壳 + {} 箱子补扣（"
                            + "若这条日志反复出现，说明取货路径与空盒材料共用了同一格）",
                    shortfall, shortfall * VoidWarehouseShulker.SHELL_COST,
                    shortfall * VoidWarehouseShulker.CHEST_COST);
        }
        if (shells > 0 && !this.takeItem(Items.SHULKER_SHELL, shells)) {
            LOGGER.warn("潜影壳不足，{} 个未能扣掉（仓库账目会少扣这点材料）", shells);
        }
        if (chests > 0 && !this.takeItem(Items.CHEST, chests)) {
            LOGGER.warn("箱子不足，{} 个未能扣掉（仓库账目会少扣这点材料）", chests);
        }
        return taken;
    }

    /** 一次「装盒」操作的材料账：几个用空盒、剩下要多少潜影壳 / 箱子 */
    public record BoxMaterials(int emptyBoxes, int shells, int chests) {
    }

    // ------------------------------------------------------------------ 9 合 1 兑换

    /**
     * 「9 合 1 兑换」这条规则现在生效吗。
     *
     * <p>两个条件都要满足：主开关 {@link VoidWarehouseSettings#voidWarehouse}（关了它连界面都开不了）
     * 与兑换开关 {@link VoidWarehouseSettings#voidWarehouseExchange}。
     * <b>所有兑换路径都必须先过这一关</b>，包括「可兑」虚拟条目 —— 规则关掉时
     * 那些格子必须立刻消失，否则玩家会看到一个点不动的幽灵格。</p>
     */
    public static boolean exchangeEnabled() {
        return VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange;
    }

    /**
     * 影响「虚拟条目」内容的全部开关的指纹：两个规则开关 + 配方本修订号。
     *
     * <p>为什么要合成一个 int 而不是分别比较：三个开关任意一个变了，{@link #offers} 与
     * {@link #unifiedOrder} 都必须整体作废，而 {@link #amountOrder} 的<b>长度</b>依赖
     * {@code unifiedOrder}，忘了清哪个都会留下幽灵格或者下标越界。
     * 合成一个「只要变了就整体清空」的指纹，漏清的可能性从「三处」降成「一处」。</p>
     */
    private int virtualEntrySignature() {
        int signature = this.recipeGeneration << 2;
        if (VoidWarehouseSettings.voidWarehouse) {
            if (VoidWarehouseSettings.voidWarehouseExchange) {
                signature |= 1;
            }
            if (VoidWarehouseSettings.voidWarehouseCraft) {
                signature |= 2;
            }
        }
        return signature;
    }

    /**
     * 开关或配方本变过就把「虚拟条目」与「统一展示序」几个缓存整体作废。
     *
     * <p>库存改动走 {@link #markChanged()} 已经清过一次，但<b>规则开关不走那条路</b> ——
     * 它是 {@code /carpet voidWarehouseExchange false} 直接改的静态字段。
     * 少了这一下，关掉规则后界面上那些「可兑」幽灵格会一直留着。</p>
     *
     * <p>开销就是一次 int 比较（外加两次布尔读），所以可以放在每次取缓存之前。</p>
     */
    private void revalidateExchangeCache() {
        int signature = this.virtualEntrySignature();
        if (this.exchangeCacheSignature != signature) {
            this.exchangeCacheSignature = signature;
            this.offers = null;
            // 这里是「开关 / 配方本变了」，不是「库存变了」：必须真作废，不能像
            // markChanged() 那样只标脏 —— 否则关掉规则后界面上那些「可兑」幽灵格
            // 还要多挂一个 tick。
            this.offersStale = false;
            this.offersTick = Long.MIN_VALUE;
            this.unifiedOrder = null;
            this.amountOrder = null;
        }
    }

    /**
     * 一个「虚拟条目」：仓库里<b>一件都没有</b>这种物品，但能靠换算/合成拿出来。
     *
     * <p>两种来源共用这一个类型（{@link #craft} 区分）：</p>
     * <ul>
     *   <li><b>9 合 1 兑换</b>：原版那 47 条 9:1 / 4:1 的换算，有完整的价值体系防刷；</li>
     *   <li><b>配方扣料</b>：照玩家亲手合成过的那条配方扣原料，只做一层、不做换算。</li>
     * </ul>
     * <p>打平成一个类型，是因为界面层对两者的处理<b>逐字相同</b>（同一套分页、同一个
     * {@code -(下标+1)} 编码、同一条点击路径）。分成两个列表会让「第几格是谁」出现第二个真相，
     * 而那正是历史上最容易出错的地方。</p>
     *
     * @param itemId     物品 id（9 合 1 是 {@code "iron_ingot"} 这种只留 path 的口径；
     *                   配方扣料是 {@code "minecraft:iron_ingot"} 这种全命名空间的口径）。
     *                   <b>这两个口径确实会在 {@link #exchangeOffers()} 里相遇</b>：那里要让
     *                   「同时命中 9 合 1 与配方」的物品只出一格，比较之前先归一成全名。
     *                   所以<b>别把这个字段直接当去重键</b>用，除非你自己先归一化
     * @param template   这一件物品的样板（数量恒 1，无数据组件）
     * @param obtainable 现在最多能拿出多少个
     * @param bulk       普通模式右键「取一组」实际能取到的量。<b>不等于
     *                   {@code min(堆叠上限, obtainable)}</b> —— 仓库里只有铜块时那个算式给 64，
     *                   而真值是 63（7 块 = 63；要 64 就得拆 8 块、在仓库里留下 8 个零头）。
     *                   tooltip 必须写这个数，否则又变成「数字对不上」
     * @param boxed      潜影盒模式左键「交出 1 盒」实际能装进去的量，同理必须问规划器
     * @param totalCost  把这 {@code obtainable} 个<b>一次性全拿出来</b>的账目：
     *                   负数 = 要从仓库扣走的，正数 = 退回仓库的
     * @param craft      这是「配方扣料」的条目吗（{@code false} = 9 合 1 兑换）。<b>只影响文案</b>，
     *                   取货路径两者完全共用
     *
     * <p><b>为什么是总量而不是单价</b>：拆零场景下每一步的边际成本都不一样，
     * 「单价 × 个数」会算出仓库里根本不存在的账。手上只有 1 个铁块时兑 9 个铁锭，
     * 真账是「铁块 −1」（第一步拆出 9 个铁锭，后面几个直接用退回的那些）；
     * 而单价是「铁块 −1、铁锭 +8」，乘 9 会得到「铁块 −9」这种明显荒谬的消耗 ——
     * 拿它去显示，玩家一眼就看出数字对不上。所以这里直接存规划器给出的那一份真账。</p>
     */
    public record ExchangeOffer(String itemId, ItemStack template, BigInteger obtainable, BigInteger bulk,
                                BigInteger boxed, List<VoidWarehouseExchange.Delta> totalCost,
                                boolean craft) {
        public ExchangeOffer {
            totalCost = List.copyOf(totalCost);
        }
    }

    /**
     * 一个展示格背后的东西：真实库存格、可兑虚拟条目、或空。
     *
     * <p>三种形态打平成一个类型，是因为界面层的 45 个格子要一起渲染、一起响应点击，
     * 分成两条并行的映射（真实一套、虚拟一套）会让「第几格是谁」这件事出现两个真相。</p>
     */
    public static final class DisplaySlot {
        private static final DisplaySlot EMPTY = new DisplaySlot(-1, null, null);

        private final int realIndex;
        private final Entry entry;
        private final ExchangeOffer offer;

        private DisplaySlot(int realIndex, Entry entry, ExchangeOffer offer) {
            this.realIndex = realIndex;
            this.entry = entry;
            this.offer = offer;
        }

        /** 空的展示格 */
        public static DisplaySlot empty() {
            return EMPTY;
        }

        /** 这个是空的吗 */
        public boolean isEmpty() {
            return this.entry == null && this.offer == null;
        }

        /** 这是「可兑」虚拟条目吗 */
        public boolean isExchange() {
            return this.offer != null;
        }

        /** 真实格在 {@link VoidWarehouseData#entries} 里的下标；虚拟条目与空格都是 {@code -1} */
        public int realIndex() {
            return this.realIndex;
        }

        /** 真实库存格；虚拟条目与空格返回 {@code null} */
        public Entry entry() {
            return this.entry;
        }

        /** 可兑条目；真实格与空格返回 {@code null} */
        public ExchangeOffer offer() {
            return this.offer;
        }

        /** 这一格的样板堆叠（真实格给库存样板、虚拟条目给那件物品），空格返回 {@link ItemStack#EMPTY} */
        public ItemStack template() {
            if (this.entry != null) {
                return this.entry.template();
            }
            return this.offer == null ? ItemStack.EMPTY : this.offer.template();
        }
    }

    /**
     * 给兑换引擎看的库存快照。
     *
     * <p>只数<b>纯净物品</b>（{@link #amountOf(Item)} 的口径）：兑换表描述的全是原版合成，
     * 附魔书、药水、改过名的工具<b>不算</b>「同类物品」，拿它们来合成等于把数据丢掉。</p>
     *
     * <p>做成快照而不是让引擎直接回调仓库，是因为一次规划要把整个递归走几十上百次，
     * 每次都去查索引既慢，更麻烦的是<b>规划期间仓库必须冻住</b> ——
     * 否则「先算出来 3 个可行、执行时库存已经变了」就会变成一次部分成功。</p>
     */
    public Map<String, BigInteger> exchangeStock() {
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.active();
        Map<String, BigInteger> stock = new HashMap<>();
        for (String itemId : table.items()) {
            Item item = VoidWarehouseExchangeBootstrap.itemOf(itemId);
            stock.put(itemId, item == null ? BigInteger.ZERO : this.amountOf(item));
        }
        return stock;
    }

    /**
     * 规划「用兑换<b>正好</b>拿到 {@code want} 个 {@code sample}」。
     *
     * <p>只读、不改仓库。返回 {@code null} 的三种情形：规则没开 / 这件物品不在兑换表里 /
     * 现有库存凑不出来（凑不出来时仓库<b>一个字节都没动</b>，调用方据此整次拒绝）。</p>
     */
    public VoidWarehouseExchange.Plan planExchange(ItemStack sample, BigInteger want) {
        return this.planExchange(sample, want, false);
    }

    /**
     * 规划「用兑换<b>最多</b>拿到 {@code limit} 个」—— 拿不满就拿能拿的最大值。
     *
     * <p>界面上右键「取一组」用的是这一个：仓库里只有 10 个铁粒时，
     * 点铁锭那一格给出 1 个（不是「凑不出 64 个所以整次失败」）。</p>
     */
    public VoidWarehouseExchange.Plan planExchangeMax(ItemStack sample, BigInteger limit) {
        return this.planExchange(sample, limit, true);
    }

    private VoidWarehouseExchange.Plan planExchange(ItemStack sample, BigInteger amount, boolean allowPartial) {
        if (sample == null || sample.isEmpty() || amount == null || amount.signum() <= 0) {
            return null;
        }
        // ① 9 合 1 兑换（原版换算，有完整价值体系）
        if (exchangeEnabled()) {
            VoidWarehouseExchange.Plan plan = this.planNineInOne(sample, amount, allowPartial);
            if (plan != null) {
                return plan;
            }
        }
        // ② 配方扣料（玩家亲手合成过的配方，只做一层）
        //    排在 9 合 1 之后：9 合 1 是「原版真有这条换算」，口径最硬、谁也改不了；
        //    配方扣料是「玩家自己做过的那一次」，灵活但没有价值体系。
        //    两者都命中时按更硬的走，这样「铁粒换铁锭」永远不会被某条奇怪的玩家配方挤掉。
        if (craftEnabled()) {
            return this.planCraft(sample, amount, allowPartial);
        }
        return null;
    }

    /** 9 合 1 的规划（原来 {@code planExchange} 的全部内容，抽出来给上面那两级用） */
    private VoidWarehouseExchange.Plan planNineInOne(ItemStack sample, BigInteger amount, boolean allowPartial) {
        String itemId = VoidWarehouseExchangeBootstrap.idOf(sample.getItem());
        if (itemId == null) {
            return null;
        }
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.active();
        if (!table.contains(itemId)) {
            return null;
        }
        VoidWarehouseExchange.Stock stock = VoidWarehouseExchange.Stock.of(this.exchangeStock());
        return allowPartial
                ? VoidWarehouseExchange.planMax(table, itemId, amount, stock)
                : VoidWarehouseExchange.plan(table, itemId, amount, stock);
    }

    /**
     * 规划「按已学配方扣料拿到 {@code amount} 个 {@code sample}」。
     *
     * <p>返回的是一份 {@link VoidWarehouseExchange.Plan} —— 这是本功能最省事的一点：
     * <b>计划只是一串「谁加多少、谁减多少」</b>，它的来源（9 合 1 换算出来的，还是照配方扣出来的）
     * 对执行层完全不可见。于是 {@code applyExchange} / {@code Withdrawal} / 回滚 / 漏斗 /
     * 打印机这<b>五条既有链路一行都不用改</b>，配方扣料天然接在它们身上。</p>
     *
     * <h2>成品已有库存时必须先扣库存</h2>
     * <p>这里最容易出错的地方：{@code amount} 是「一共要给玩家多少」，其中<b>已经躺在仓库里</b>
     * 的那部分不该再做一遍 —— 否则「仓库里有 5 个活塞、取 10 个」会变成扣 10 份原料
     * 却只拿走 5 个新做的，多出来的 5 个凭空蒸发（或者反过来凭空出现，取决于账怎么写）。
     * 所以三笔账分开记：</p>
     * <ul>
     *   <li>仓库现成的 {@code fromStock} 个 → <b>扣掉</b>（产物那一项负 {@code fromStock}）；</li>
     *   <li>剩下 {@code need} 个，按配方做 {@code crafts} 次 → 原料负扣；</li>
     *   <li>做出来的比要的多（木板这种一次 4 个）→ 多出来的 {@code surplus} <b>入账</b>回仓库。</li>
     * </ul>
     * <p>三笔合起来净值恰好是「仓库少了原料、多了余量，玩家拿到 amount 个」，一个字节不差。</p>
     */
    private VoidWarehouseExchange.Plan planCraft(ItemStack sample, BigInteger amount, boolean allowPartial) {
        String itemId = VoidWarehouseRecipeBook.idOf(sample.getItem());
        if (itemId == null) {
            return null;
        }
        List<VoidWarehouseRecipes.Recipe> candidates = this.recipesByOutput.get(itemId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Item product = VoidWarehouseRecipeBook.itemOf(itemId);
        if (product == null) {
            return null;
        }
        BigInteger have = this.amountOf(product);
        BigInteger fromStock = have.min(amount);
        BigInteger need = amount.subtract(fromStock);

        // 成品本身已经够（或刚好够）—— 不需要做任何合成，只把库存这几笔记进账里。
        // 这条路正常走不到（调用方都是「成品没货」才来问），但留着它是为了「账永远完整」：
        // 缺了这段，那些调用方一旦改成「不管有没有货都先问一句」，就会扣了原料还留着成品。
        if (need.signum() <= 0) {
            if (fromStock.signum() <= 0) {
                return null;
            }
            Map<String, BigInteger> deltas = new java.util.LinkedHashMap<>();
            deltas.merge(itemId, fromStock.negate(), BigInteger::add);
            return this.toExchangePlan(itemId, fromStock, deltas);
        }

        // 同一件物品可能有多条已学配方（用橡木木板做过一次、用云杉木板又做过一次）。
        // 挑哪一条，「尽量给」与「要多少给多少」是两条不同的规则：
        //
        //   · 「尽量给」（右键取一组 / 取一盒）：先挑<b>做得最多</b>的那条。
        //     界面上的「可合成 N 个」是 collectCraftOffers 取的「各候选 maxCraftable 的最大值」，
        //     执行时若按别的规则挑，就会出现「界面写 64、右键实际只给 1」—— 那是 1.0.15 修过的
        //     一处真偏差。**两边的口径必须始终是同一个**，别再各写一套。
        //   · 精确请求（左键取 1 个 / 打印机按需取）：没有「最多」可言，按学会顺序逐条试，
        //     第一条能一次性满足的就是它 —— 顺序稳定，账才稳定。
        if (allowPartial) {
            VoidWarehouseRecipes.Recipe bestRecipe = null;
            Map<String, BigInteger> bestStock = null;
            BigInteger bestOut = BigInteger.ZERO;
            for (VoidWarehouseRecipes.Recipe recipe : candidates) {
                Map<String, BigInteger> stock = this.craftStock(recipe, itemId, fromStock);
                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);
                // 严格大于才换：持平取先学会的那条，与 collectCraftOffers 的 tie-break 保持一致
                if (maxOut.compareTo(bestOut) > 0) {
                    bestOut = maxOut;
                    bestRecipe = recipe;
                    bestStock = stock;
                }
            }
            if (bestRecipe == null) {
                return null;   // 哪一条都做不出来
            }
            return this.planCraftWith(bestRecipe, bestStock, itemId, fromStock, need.min(bestOut));
        }
        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = this.craftStock(recipe, itemId, fromStock);
            VoidWarehouseExchange.Plan plan = this.planCraftWith(recipe, stock, itemId, fromStock, need);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    /**
     * 某条配方可用的料：仓库闭包 + 把<b>已经划给玩家的</b> {@code fromStock} 个产物先扣掉。
     *
     * <p>不扣就会出问题：像「产物自己就是原料」的配方（地图缩放：1 地图 + 8 纸 → 1 地图；
     * 地图复制：1 地图 + 1 空地图 → 2 地图；潜影盒染色同理）会把同一份库存数两遍 ——
     * 一份算给玩家、一份算进配方，那就是凭空造物。扣掉之后这类配方的结果是「做不出来」
     * （保守失败），而不是「做出来了但账对不上」。</p>
     */
    private Map<String, BigInteger> craftStock(VoidWarehouseRecipes.Recipe recipe, String itemId,
                                               BigInteger fromStock) {
        Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);
        if (fromStock.signum() > 0 && stock.containsKey(itemId)) {
            stock.put(itemId, stock.get(itemId).subtract(fromStock).max(BigInteger.ZERO));
        }
        return stock;
    }

    /**
     * 用<b>指定的某一条</b>配方把 {@code wantOut} 个做出来，收成一份可执行计划。
     *
     * <p>规划失败返回 {@code null}（调用方决定「换下一条」还是「整次拒绝」）。</p>
     *
     * <p>玩家真正拿到手上的「新做出来的」数量就是 {@code wantOut}：为什么不再写
     * {@code produced − surplus} —— 往下钻之后 surplus 是一个 map（每一层都可能做出零头），
     * 拿它跟单个数字比就错了。而按定义，做 crafts 次、给 wantOut 个，玩家拿到的就是 wantOut 个，
     * 多做的部分全部记在 surplus 里回仓库。这两个写法必须等价，所以自检里有一条
     * 「账平」断言盯着它（做出来的 − 入账的 == 玩家拿到的）。</p>
     */
    private VoidWarehouseExchange.Plan planCraftWith(VoidWarehouseRecipes.Recipe recipe,
                                                     Map<String, BigInteger> stock, String itemId,
                                                     BigInteger fromStock, BigInteger wantOut) {
        VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock, this.recipesByOutput);
        if (plan == null) {
            return null;
        }
        Map<String, BigInteger> deltas = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigInteger> entry : plan.consumed().entrySet()) {
            deltas.merge(entry.getKey(), entry.getValue().negate(), BigInteger::add);
        }
        // 余量全部入账（含根产物做多的零头，以及往下钻时中间产物多出来的部分）
        for (Map.Entry<String, BigInteger> entry : plan.surplus().entrySet()) {
            if (entry.getValue().signum() > 0) {
                deltas.merge(entry.getKey(), entry.getValue(), BigInteger::add);
            }
        }
        deltas.merge(itemId, fromStock.negate(), BigInteger::add);
        return this.toExchangePlan(itemId, fromStock.add(wantOut), deltas);
    }

    /**
     * 把一份「物品 → 增减」的账收成 {@link VoidWarehouseExchange.Plan}。
     *
     * <p>顺手做两件事，都是为了让 {@code applyExchange} 那边一行不用改：</p>
     * <ol>
     *   <li><b>合并同 id</b>：配方扣料里产物可能同时出现在「扣库存」和「收余量」两笔里
     *       （比如拿木板取木板），合并之后每件物品最多出现一次，账目和 tooltip 都不会重复列；</li>
     *   <li><b>丢掉净零</b>：{@code 0} 的增减没有任何意义，留着只会让 {@code isEmpty()} 判错。</li>
     * </ol>
     */
    private VoidWarehouseExchange.Plan toExchangePlan(String itemId, BigInteger amount,
                                                      Map<String, BigInteger> deltas) {
        List<VoidWarehouseExchange.Delta> out = new ArrayList<>(deltas.size());
        for (Map.Entry<String, BigInteger> entry : deltas.entrySet()) {
            if (entry.getValue().signum() != 0) {
                out.add(new VoidWarehouseExchange.Delta(entry.getKey(), entry.getValue()));
            }
        }
        return new VoidWarehouseExchange.Plan(itemId, amount, out);
    }

    /**
     * 计划里的物品 id → {@link Item}。<b>两套 id 口径的分岔只在这一处收口。</b>
     *
     * <p>9 合 1 那套 id 只有 path（{@code "iron_ingot"}），配方本那套带命名空间
     * （{@code "minecraft:iron_ingot"}）。差别不在风格上，而在解析函数上：
     * {@code VoidWarehouseExchangeBootstrap.itemOf} 会把整个字符串当成 path 拼到
     * {@code minecraft:} 后面，喂给它一个带冒号的 id 会拼出
     * {@code minecraft:minecraft:iron_ingot} 这种非法标识并抛异常。</p>
     *
     * <p>判据就是「有没有冒号」—— 9 合 1 的表里<b>从来没有</b>冒号，配方本写出去的时候
     * <b>永远带</b>冒号，所以这个判据不是猜，是两边格式的硬事实。</p>
     */
    private static Item resolveItem(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        return itemId.indexOf(':') < 0
                ? VoidWarehouseExchangeBootstrap.itemOf(itemId)
                : VoidWarehouseRecipeBook.itemOf(itemId);
    }

    /**
     * 执行一份兑换计划（{@link #planExchange} 的结果）。
     *
     * <p><b>原子</b>：计划里的每一笔一次性改完，最后只标一次脏。中途不存在「扣了铁粒还没退回铁锭」
     * 这种半截状态 —— 因为计划本身是一份完整的账，没有任何一步需要读仓库的中间状态。</p>
     *
     * <p><b>回滚也走这里</b>：把计划的 {@link VoidWarehouseExchange.Plan#negate()} 再执行一遍，
     * 加减量精确到个位数地反向抵消。背包放不下要退回时就是这么做 ——
     * 不需要重新规划（重新规划时库存已经变了，算出来的账和刚才那一笔未必一样）。</p>
     */
    public void applyExchange(VoidWarehouseExchange.Plan plan) {
        if (plan == null || plan.isEmpty()) {
            return;
        }
        for (VoidWarehouseExchange.Delta delta : plan.deltas()) {
            Item item = resolveItem(delta.itemId());
            if (item == null) {
                // 启动时已经复核过表，这里正常到不了；真到了也只是跳过这一笔（绝不清零）
                continue;
            }
            BigInteger change = delta.change();
            if (change.signum() > 0) {
                this.produce(item, change);
            } else if (change.signum() < 0) {
                Entry entry = this.find(new ItemStack(item));
                if (entry != null) {
                    this.consume(entry, change.negate());
                }
            }
        }
        this.markChanged();
    }

    /**
     * 一次「从仓库取出若干个」的结果，并且<b>可以精确撤销</b>。
     *
     * <h2>为什么要一个专门的类型</h2>
     * <p>「取出」在界面 / 打印机 / 漏斗三条路上都必须是 all-or-nothing：
     * 先把东西挪进背包，挪不下就<b>一点都不能扣</b>。历史上这件事靠
     * {@code take(...)} 之后再来一句 {@link VoidWarehouseData#restore}，
     * 之所以成立，是因为扣的和退的<b>是同一种物品</b>，加回去就完事了。</p>
     *
     * <p>9 合 1 兑换打破了这个前提：那次操作可能扣了 9 个铁粒、还退回了 8 个铁锭，
     * 是<b>一笔多物品的账</b>。想撤销，唯一的正确做法是把整笔账反向执行一遍
     * （{@link VoidWarehouseExchange.Plan#negate()}），再按真正放进背包的数量重新规划一次 ——
     * 而不是去猜「该退回哪个物品多少个」。</p>
     *
     * <h2>两条路互斥</h2>
     * <ul>
     *   <li>{@link #viaExchange()} 为 {@code false}：走的是老路，直接扣成品，
     *       撤销就是 {@link VoidWarehouseData#restore}；</li>
     *   <li>{@link #viaExchange()} 为 {@code true}：走的是兑换，撤销就是「反向执行 + 重做
     *       实际用掉的那部分」。<b>成品有货时绝不会走到这条</b>（见 {@link #withdraw}）。</li>
     * </ul>
     */
    public static final class Withdrawal {
        private final VoidWarehouseData data;
        private final ItemStack sample;
        private final ItemStack stack;
        private final VoidWarehouseExchange.Plan plan;

        Withdrawal(VoidWarehouseData data, ItemStack sample, ItemStack stack,
                   VoidWarehouseExchange.Plan plan) {
            this.data = data;
            this.sample = sample;
            this.stack = stack;
            this.plan = plan;
        }

        /** 一件都没取到 */
        public boolean isEmpty() {
            return this.stack.isEmpty();
        }

        /** 实际取出了多少个 */
        public int taken() {
            return this.stack.getCount();
        }

        /** 取出的堆叠（带完整数据组件，可直接塞进背包 / 漏斗） */
        public ItemStack stack() {
            return this.stack;
        }

        /** 这次是通过 9 合 1 兑换拿到的吗 */
        public boolean viaExchange() {
            return this.plan != null;
        }

        /**
         * 把「没真正用出去的那部分」还回仓库。
         *
         * @param kept 真正放进了背包 / 漏斗的数量（{@code 0} = 整笔撤销）
         */
        public void returnUnplaced(int kept) {
            int keep = Math.max(0, Math.min(kept, this.stack.getCount()));
            int back = this.stack.getCount() - keep;
            if (back <= 0 || this.sample == null) {
                return;
            }
            if (this.plan == null) {
                // 直接扣成品那条：把剩下的原样放回去（归还语义，不受任何筛选规则影响）
                this.data.restore(this.sample.copyWithCount(back));
                return;
            }
            // 兑换那条：先把整笔账反向执行（此时仓库回到取货前的状态），
            // 再按「真正用掉的 keep 个」重新规划并执行一次。
            // 库存完全相同 ⇒ 规划是确定性的 ⇒ 重新算出来的账和刚才那笔一字不差。
            this.data.applyExchange(this.plan.negate());
            if (keep <= 0) {
                return;
            }
            VoidWarehouseExchange.Plan redo =
                    this.data.planExchange(this.sample, BigInteger.valueOf(keep));
            if (redo == null) {
                // 理论上不可能（刚才的规划连更大的量都能满足）。真出现了也绝不凭空补货：
                // 宁可少给玩家 keep 个，也不让仓库的账对不上。
                return;
            }
            this.data.applyExchange(redo);
        }
    }

    /**
     * 取出最多 {@code want} 个 {@code sample}：<b>有货就直接扣成品，成品一件都没有时才走兑换</b>。
     *
     * <p>「有货就绝不兑换」在这里是结构性保证：{@link #withdraw} 一进来先看成品库存，
     * 只要 {@code > 0} 就立刻走老路返回，后面那些兑换代码根本不会被执行。
     * 所以玩家不会遇到「明明有铁锭，取的时候却被扣了铁粒」。</p>
     *
     * <p>取不满时按「有多少给多少」返回（不是整次失败）—— 这一条是给界面右键
     * 「取一组」和打印机补货用的，它们要的是「尽量给」。</p>
     *
     * @return 结果对象；取不到任何东西时 {@link Withdrawal#isEmpty()} 为 {@code true}
     */
    public Withdrawal withdraw(ItemStack sample, BigInteger want) {
        if (sample == null || sample.isEmpty() || want == null || want.signum() <= 0) {
            return new Withdrawal(this, sample, ItemStack.EMPTY, null);
        }
        int cap = Math.max(1, sample.getMaxStackSize());
        BigInteger limit = want.min(BigInteger.valueOf(cap));

        // ① 成品有货：走老路，绝不兑换
        Entry entry = this.find(sample);
        if (entry != null && entry.amount().signum() > 0) {
            int ask = limit.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
            ItemStack taken = this.takeStack(sample, ask);
            return new Withdrawal(this, sample, taken, null);
        }

        // ② 成品一件都没有：规则允许时用 9 合 1 兑换
        VoidWarehouseExchange.Plan plan = this.planExchangeMax(sample, limit);
        if (plan == null || plan.amount().signum() <= 0) {
            return new Withdrawal(this, sample, ItemStack.EMPTY, null);
        }
        this.applyExchange(plan);
        return new Withdrawal(this, sample, sample.copyWithCount(plan.amount().intValue()), plan);
    }

    /**
     * 当前所有「虚拟条目」（9 合 1 可兑 + 已学配方可合成），按创造模式物品栏顺序排好
     * （缓存，任何改动即失效）。
     *
     * <p>入选条件两条，缺一不可：</p>
     * <ol>
     *   <li>这件物品<b>仓库里一件都没有</b> —— 有货就不该出现虚拟格，
     *       否则玩家会看到一个假的库存量，而且点下去会意外消耗别的东西；</li>
     *   <li>现有库存<b>真的能拿出至少 1 个</b> —— 只列「理论上能」会把兑换表里几十个物品
     *       加配方本里几百条全列出来，其中绝大多数当前做不动，纯属噪音。</li>
     * </ol>
     *
     * <p><b>两类的先后与去重</b>：9 合 1 先算，配方扣料后算，且<b>已有 9 合 1 条目的物品
     * 不再生成配方条目</b> —— 同一件东西在界面上出现两格（一格写「可兑」一格写「可合成」）
     * 会让玩家以为能拿两份。优先级给 9 合 1，因为它是「原版真有这条换算」，
     * 有完整价值体系防刷，口径比玩家自选配方更硬。</p>
     */
    public List<ExchangeOffer> exchangeOffers() {
        this.revalidateExchangeCache();
        if (this.offers != null && (!this.offersStale || this.offersTick == serverTick)) {
            // 第二个条件就是「按 tick 合并」的全部内容：
            //   · 本 tick 已经算过一次 → 这一 tick 里再怎么改库存，返回的仍是这一份；
            //   · 界面只在 tick 边界渲染，所以同一 tick 内所有人看到的本来就是同一帧，
            //     多算的那 N−1 次纯属浪费（每次 1.6 ms）。
            // 下一个 tick 的第一次读会因为 offersTick != serverTick 而重算，最多旧一 tick。
            //
            // 这是**有意的取舍**：用「最多 1 tick 的陈旧」换「tick 时间有上界」。
            // 拿旧名单去点击是安全的 —— 取出路径一律重新规划（VoidWarehouseMenu#withdrawFrom
            // → data.withdraw() / #withdrawShulkerBox → planExchangeMax），凑不出来就是空操作，
            // 不会凭空造物；名单下标也有界检查（见 displaySlotAt），不会越界。
            return this.offers;
        }
        // 「标脏了、但其实什么都没变」是一条真实且高频的路径：容器的 setChanged() 会调
        // markChanged()，而它在一个格子的点击里能被调好几次。这种情况直接沿用上一份 ——
        // 9 合 1 与配方扣料都是库存的纯函数，库存没变就没有任何理由重算。
        //
        // 判据是 changedItems 为空：三个原语只要真的动了数量就一定往里记过，
        // 所以「空」等价于「一格都没变」（完备性见 changedItems 的注释）。
        // craftCacheBusted 必须一起看：clearContent() 会把库存整体清空并置起这个标志，
        // 但它<b>不逐件记账</b>（changedItems 是空的），少了这个判断就会把上一份
        // 「还带着已经不存在的物品」的表当新表返回。这个洞是写完自检才想到的 ——
        // M 组每次都先 clearContent() 再灌料，脏集合立刻又非空，正好盖住了它。
        if (this.offers != null && this.changedItems.isEmpty() && !this.craftCacheBusted) {
            this.offersStale = false;
            this.offersTick = serverTick;
            return this.offers;
        }
        List<ExchangeOffer> built = new ArrayList<>();
        if (exchangeEnabled()) {
            this.collectNineInOneOffers(built);
        }
        if (craftEnabled()) {
            this.rebuildCraftCache();
            // 已经有 9 合 1 条目的物品不重复出现（见本方法的注释）。
            //
            // <b>比较之前必须先把两边的 id 口径统一</b>：9 合 1 表的 itemId 只留 path
            // （{@code "iron_ingot"}，见 VoidWarehouseExchangeBootstrap），而 collectCraftOffers
            // 手里的是 VoidWarehouseRecipeBook.idOf 给的全名（{@code "minecraft:iron_ingot"}）。
            // 拿 path 去 contains 全名<b>永远为 false</b> —— 这段去重曾经一次都没生效过：
            // 只要玩家学了一条「铁锭 → 铁块」这种恰好也在 9 合 1 表里的配方，同一件东西就会
            // 占两格（一格写「可兑」一格写「可合成」），玩家会以为能拿两份。之前没暴露，是因为
            // 手头的语料里 9 合 1 与配方本没有交集。两边唯一的共同真相是 Item 对象本身，
            // 所以只在边界上翻译一次；翻译不出来的（这个版本没这件物品）原样留着，至少不比丢。
            java.util.Set<String> taken = new java.util.HashSet<>(built.size() * 2);
            for (ExchangeOffer offer : built) {
                // 先补命名空间再翻译。<b>不能</b>拿 9 合 1 的 itemOf 直接吃全名：
                // Identifier#withDefaultNamespace 对已经带冒号的字符串会拼成
                // {@code minecraft:minecraft:iron_block} 并当场抛 IdentifierException
                // （实测过，把整组自检炸掉了）。而 RecipeBook.itemOf 走 tryParse，
                // 两种口径都认，于是这里是唯一需要知道口径差异的地方。
                String raw = offer.itemId();
                String full = raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
                String canonical = VoidWarehouseRecipeBook.idOf(VoidWarehouseRecipeBook.itemOf(full));
                taken.add(canonical != null ? canonical : raw);
            }
            this.collectCraftOffers(built, taken);
        }
        built.sort(Comparator.comparingInt(offer -> VoidWarehouseSort.rankOf(offer.template())));
        this.offers = List.copyOf(built);
        this.offersStale = false;
        this.offersTick = serverTick;
        return this.offers;
    }

    /**
     * 由 {@link VoidWarehouseExtension#onTick} 每 tick 调用一次，记下当前的服务端 tick。
     *
     * <p>只服务于 {@link #exchangeOffers()} 的「按 tick 合并」：同一 tick 内不重复重算。
     * 必须是 static（原因见 {@link #serverTick}），所以调它<b>不需要</b>先拿到数据实例。</p>
     */
    public static void noteServerTick(int serverTick) {
        VoidWarehouseData.serverTick = serverTick;
    }

    /** 收集 9 合 1 的「可兑」条目（原 {@code exchangeOffers} 的主体） */
    private void collectNineInOneOffers(List<ExchangeOffer> built) {
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.active();
        VoidWarehouseExchange.Stock stock = VoidWarehouseExchange.Stock.of(this.exchangeStock());
        for (String itemId : table.items()) {
            Item item = VoidWarehouseExchangeBootstrap.itemOf(itemId);
            if (item == null || this.amountOf(item).signum() > 0) {
                continue;
            }
            // 可兑数量的上限<b>由本族库存推出来</b>，不是一个固定值。
            // 曾经写死过「一组 64」（盒模式下永远只装 1/27 盒），又改成「一盒容量 1728」
            // （仓库里只有 81 个铁锭时，铁块那格显示 1728 —— 跟仓库里有什么毫无关系）。
            // 两次都被玩家指出不合理。见 VoidWarehouseExchange#maxObtainable。
            // 顺带的好事：本族没料时上界是 0，直接跳过，省掉一次注定失败的规划。
            BigInteger cap = VoidWarehouseExchange.maxObtainable(table, itemId, stock);
            if (cap.signum() <= 0) {
                continue;
            }
            VoidWarehouseExchange.Plan plan = VoidWarehouseExchange.planMax(table, itemId, cap, stock);
            if (plan == null || plan.amount().signum() <= 0) {
                continue;
            }
            // 「一次取货实际能拿到多少」一律交给规划器收敛，**不许自己算**：
            // min(堆叠上限, obtainable) 会给出 64，而真值是 63（按整块拆、不留零头，
            // 见 VoidWarehouseExchange#drain 第 ③ 条路）。这两个数写反就是「tooltip 说 64、
            // 实际给 63」—— 跟玩家连着报两次的「凭空常数」是同一类毛病。
            ItemStack sample = new ItemStack(item);
            BigInteger bulk = takeAmount(table, itemId, plan.amount(),
                    Math.max(1, sample.getMaxStackSize()), stock);
            BigInteger boxed = takeAmount(table, itemId, plan.amount(),
                    VoidWarehouseShulker.capacity(sample), stock);
            built.add(new ExchangeOffer(itemId, sample, plan.amount(), bulk, boxed, plan.deltas(), false));
        }
    }

    /**
     * 把缓存里<b>此刻做不出来</b>（{@code null}）的产物滤掉，其余按遍历序交给上层排序。
     *
     * <p>{@code taken} 是本次 9 合 1 已经占掉的物品 id —— 同一件东西在界面上出现两格
     * 会让玩家以为能拿两份，优先级给 9 合 1（理由见 {@link #exchangeOffers()}）。</p>
     *
     * <p><b>{@code taken} 的口径必须与 {@code offer.itemId()} 一致</b>（这里一律是全命名空间）。
     * 曾经这里装的是 9 合 1 的 path 口径，而比的是全名 —— {@code Set#contains} 恒为
     * {@code false}，整段去重是死代码。翻译放在调用方而不是这里，是因为只有
     * {@link #exchangeOffers()} 同时看得见两种口径。</p>
     *
     * <p>滤在<b>取表时</b>而不是算的时候：9 合 1 的条目每个 tick 都可能变，
     * 但那不该让配方账重算一遍。</p>
     */
    private void collectCraftOffers(List<ExchangeOffer> built, java.util.Set<String> taken) {
        for (ExchangeOffer offer : this.craftOffers.values()) {
            if (offer == null || taken.contains(offer.itemId())) {
                continue;
            }
            built.add(offer);
        }
    }

    /**
     * 只重算<b>受影响的那些产物</b>的「可合成」条目。
     *
     * <h2>为什么不是「按 (配方, 库存版本) 缓存到 tick 级」</h2>
     * <p>原本的设计是给 {@code maxCraftable} 加一张 {@code (recipeId, stockVersion)} 的缓存，
     * 让列表与取货路径共用。实测<b>命中率 0%</b>：一次取货必然改库存，
     * {@link #markChanged()} 随之推进版本，于是「同一个版本里再问同一条配方」在真实操作序列里
     * 根本不会发生（60 次取货、65 次调用，重复 0 次）。真正在重复的是<b>整张表</b>：
     * 改一格库存，746 个产物全部重算，而其中只有 13 个的结果会变。</p>
     *
     * <h2>凭什么「只有 13 个会变」</h2>
     * <p>产物的结果只依赖它候选配方<b>整条闭包</b>里那些物品的数量（外加它自己的数量，
     * 决定它出不出现在界面上）。把这张依赖集记下来，改动一格就只碰「依赖集里含那一格」的产物
     * —— 实测平均 13.0 个（占 746 个产物的 1.7%），中位数 1~5 个，最坏是
     * {@code minecraft:oak_log}：184 个（24.7%），所以最坏情况也不会退化成整批。</p>
     *
     * <p>依赖集只在配方本换版时重建（{@link #buildCraftDeps()}）：闭包由配方结构决定，
     * 与数量无关，库存怎么变都不会改变依赖关系。</p>
     */
    private void rebuildCraftCache() {
        if (this.craftCacheGeneration != this.recipeGeneration) {
            this.craftDeps = this.buildCraftDeps();
            this.craftOffers = new java.util.LinkedHashMap<>(Math.max(16, this.craftDeps.size() * 2));
            for (String itemId : this.craftDeps.keySet()) {
                this.craftOffers.put(itemId, this.computeCraftOffer(itemId));
            }
        } else if (this.craftCacheBusted || this.changedItems.size() > CRAFT_DIRTY_LIMIT) {
            for (String itemId : this.craftDeps.keySet()) {
                this.craftOffers.put(itemId, this.computeCraftOffer(itemId));
            }
        } else if (!this.changedItems.isEmpty()) {
            java.util.Set<String> dirty = new java.util.HashSet<>(this.changedItems.size() * 2);
            for (Item item : this.changedItems) {
                String itemId = VoidWarehouseRecipeBook.idOf(item);
                if (itemId != null) {
                    dirty.add(itemId);
                }
            }
            for (Map.Entry<String, java.util.Set<String>> entry : this.craftDeps.entrySet()) {
                java.util.Set<String> deps = entry.getValue();
                boolean hit = false;
                for (String itemId : dirty) {
                    if (deps.contains(itemId)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    this.craftOffers.put(entry.getKey(), this.computeCraftOffer(entry.getKey()));
                }
            }
        }
        // 三个分支共用这一处收口：中途抛异常时 generation 不会前进、脏集合也不会被清掉，
        // 下一次自然是整批重来 —— 失败方向必须是「多算」而不是「少算」。
        this.craftCacheGeneration = this.recipeGeneration;
        this.craftCacheBusted = false;
        this.changedItems.clear();
    }

    /**
     * 产物 id → 影响它结果的物品 id 集合。
     *
     * <p>配方本空时返回空表。除此之外每一项<b>必然</b>包含产物自己（理由见
     * {@link #craftDeps}），再并上每条候选配方整条闭包的键集 ——
     * 只要键集不要值，因为键集由配方结构决定、与数量无关。</p>
     */
    private Map<String, java.util.Set<String>> buildCraftDeps() {
        Map<String, java.util.Set<String>> deps = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<VoidWarehouseRecipes.Recipe>> group : this.recipesByOutput.entrySet()) {
            java.util.Set<String> items = new java.util.HashSet<>();
            items.add(group.getKey());
            for (VoidWarehouseRecipes.Recipe recipe : group.getValue()) {
                items.addAll(VoidWarehouseRecipes.stockClosure(recipe, this.recipesByOutput,
                        key -> BigInteger.ZERO).keySet());
            }
            deps.put(group.getKey(), items);
        }
        return deps;
    }

    /**
     * 算一个产物此刻的「可合成」条目（仓库里没货、且有已学配方、且<b>此刻真的做得出来</b>）。
     *
     * <h2>为什么这里不用 9 合 1 的「探针」那一套</h2>
     * <p>9 合 1 的 {@code planMax} 要从上界往下逐个试，因为「能拿 5 个不一定能拿 4 个」（拆整块会跳档）。
     * 配方扣料<b>没有这个跳档</b>：原料按倍数扣，能做 {@code n} 次就一定能做 {@code 1..n} 次
     * （{@code ceil} 是单调的，往下钻的层数也只会变少）。所以这里只需要一次上界 + 一次二分，
     * 探针数是 {@code ⌈log₂(上界+1)⌉}，而不是把上界一路试到 63、62……</p>
     *
     * <p>⚠️ 这里以前写的是「上界虚高最多退 64 次，正常数据里第一次就成」——<b>那句话是错的</b>，
     * 而且是本轮压测直接证伪的：满本 1236 条配方、每种原料上万时，上界的相对误差只有 6%~33%，
     * 但真值本身在万位，于是<b>绝对</b>虚高也在千位（实测 P50 = 3,328 次），64 次退让永远走不到，
     * 21% 的配方被判成「做不出」而整格从界面消失。改成二分之后，探针数与虚高多少无关。
     * 详见 {@code docs/配方本满本并发测试报告.md}。</p>
     */
    private ExchangeOffer computeCraftOffer(String itemId) {
        Item item = VoidWarehouseRecipeBook.itemOf(itemId);
        if (item == null || this.amountOf(item).signum() > 0) {
            return null;
        }
        List<VoidWarehouseRecipes.Recipe> candidates = this.recipesByOutput.get(itemId);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // 同一产物可能有多条配方：取「做得最多」的那一条来算上限与账单，
        // 与 planCraft 的「逐条试、第一条能做的就用」不冲突 —— 那边保证能给出至少这么多个。
        VoidWarehouseRecipes.Recipe best = null;
        BigInteger maxOut = BigInteger.ZERO;
        Map<String, BigInteger> bestStock = Map.of();
        for (VoidWarehouseRecipes.Recipe recipe : candidates) {
            Map<String, BigInteger> stock = VoidWarehouseRecipeBook.stockFor(this, recipe, this.recipesByOutput);
            BigInteger out = VoidWarehouseRecipes.maxCraftable(recipe, stock, this.recipesByOutput);
            if (out.compareTo(maxOut) > 0) {
                maxOut = out;
                best = recipe;
                bestStock = stock;
            }
        }
        if (best == null || maxOut.signum() <= 0) {
            return null;
        }
        // 账单 = 做满 maxOut 个要扣的原料（全部是负数）。余量不入账目：
        // tooltip 的「用 X 12 个」说的是成本，「找回」那一段对配方扣料是噪音
        // （产物多出来的部分会静静回到仓库，玩家不需要为此读一行字）。
        VoidWarehouseRecipes.Plan plan = VoidWarehouseRecipes.plan(best, maxOut, bestStock, this.recipesByOutput);
        if (plan == null) {
            return null;
        }
        List<VoidWarehouseExchange.Delta> cost = new ArrayList<>(plan.consumed().size());
        for (Map.Entry<String, BigInteger> entry : plan.consumed().entrySet()) {
            if (entry.getValue().signum() > 0) {
                cost.add(new VoidWarehouseExchange.Delta(entry.getKey(), entry.getValue().negate()));
            }
        }
        ItemStack sample = new ItemStack(item);
        // 一次动作实际能拿多少：配方扣料是「做 n 次就给 n 份」，没有拆整块的跳档，
        // 所以这里就是「上限与可做数量的较小者」—— 这个算式在<b>这条路径上</b>是精确的，
        // 与 9 合 1 那边必须问规划器的情况不同（理由见 takeAmount 的注释）。
        long maxStack = Math.max(1, sample.getMaxStackSize());
        BigInteger bulk = maxOut.min(BigInteger.valueOf(maxStack));
        BigInteger boxed = maxOut.min(BigInteger.valueOf(Math.max(1, VoidWarehouseShulker.capacity(sample))));
        return new ExchangeOffer(itemId, sample, maxOut, bulk, boxed, cost, true);
    }

    /**
     * 一次取货动作（左键一盒 / 右键一组）<b>实际能拿到多少</b>：把上限交给规划器收敛，取回真值。
     *
     * <p>为什么不直接写 {@code min(上限, obtainable)}：那个算式在「仓库里只有上级」时会高估 ——
     * 9 个铜块、一组 64，算式给 64，而实际只能给 63（拆 7 块 = 63；要 64 就得拆 8 块、
     * 把 8 个零头留在仓库里，而这个动作已经被否掉了）。
     * 所以 tooltip 上的每一个数字都要走这里，宁可多跑一次规划，也不让界面写出一个做不到的数。</p>
     */
    private static BigInteger takeAmount(VoidWarehouseExchange.Table table, String itemId,
                                         BigInteger obtainable, long cap,
                                         VoidWarehouseExchange.Stock stock) {
        BigInteger limit = obtainable.min(BigInteger.valueOf(Math.max(1, cap)));
        if (limit.signum() <= 0) {
            return BigInteger.ZERO;
        }
        VoidWarehouseExchange.Plan plan = VoidWarehouseExchange.planMax(table, itemId, limit, stock);
        return plan == null ? BigInteger.ZERO : plan.amount();
    }

    // ------------------------------------------------------------------ 统一展示序

    /**
     * 统一展示序：把「真实库存格」与「可兑虚拟条目」<b>按创造模式物品栏顺序合并</b>成一条序列。
     *
     * <p>编码：值 {@code >= 0} = {@link #entries} 的下标；值 {@code < 0} = 可兑条目
     * {@code -(下标 + 1)}。用一个 {@code int[]} 而不是对象数组，是因为界面每渲染一帧
     * 就要问 45 次「这一格是谁」，走基本类型数组没有装箱开销。</p>
     *
     * <p><b>为什么要合并而不是把虚拟条目接在末尾</b>：库里只有铁粒时，
     * 「铁锭（可兑）」这一格应该出现在铁粒附近、按创造栏的位次排，而不是被挤到第 7 页去。
     * 两边的输入都已经是有序的（{@code entries} 由 {@link #ensureSorted()} 保证、
     * 可兑条目在 {@link #exchangeOffers()} 里排过），所以这就是一次 O(n) 归并。</p>
     */
    private int[] unifiedOrder() {
        this.revalidateExchangeCache();
        this.ensureSorted();
        if (this.unifiedOrder != null) {
            return this.unifiedOrder;
        }
        List<ExchangeOffer> offers = this.exchangeOffers();
        int real = this.entries.size();
        int[] order = new int[real + offers.size()];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < real && j < offers.size()) {
            int realRank = VoidWarehouseSort.rankOf(this.entries.get(i).template());
            int offerRank = VoidWarehouseSort.rankOf(offers.get(j).template());
            // 位次相同时真实格优先（真实格代表「真有货」）
            if (realRank <= offerRank) {
                order[k++] = i++;
            } else {
                order[k++] = -(j++) - 1;
            }
        }
        while (i < real) {
            order[k++] = i++;
        }
        while (j < offers.size()) {
            order[k++] = -(j++) - 1;
        }
        this.unifiedOrder = order;
        return order;
    }

    /** 一共要画几格 = 真实库存格 + 可兑虚拟条目（分页按这个数算） */
    public int displayCount() {
        return this.unifiedOrder().length;
    }

    /**
     * 当前统一展示序数组的<b>引用</b>（给 {@link VoidWarehousePageContainer} 的搜索过滤当失效基准）。
     *
     * <p>仓库任何变动都会让 {@code unifiedOrder} 整体重建（新数组），
     * 所以调用方只需做一次引用比较就能察觉「底层变了，我那层过滤视图该重算了」——
     * 不需要自己的失效时钟，也绝不泄漏可变状态（调用方只比对引用、不写入）。</p>
     */
    int[] unifiedOrderRef() {
        return this.unifiedOrder();
    }

    /** 第 {@code displayIndex} 个展示格背后是什么（越界返回空） */
    public DisplaySlot displaySlotAt(int displayIndex) {
        int[] order = this.unifiedOrder();
        if (displayIndex < 0 || displayIndex >= order.length) {
            return DisplaySlot.empty();
        }
        int code = order[displayIndex];
        if (code >= 0) {
            return new DisplaySlot(code, this.entries.get(code), null);
        }
        List<ExchangeOffer> offers = this.exchangeOffers();
        int offerIndex = -code - 1;
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return DisplaySlot.empty();
        }
        return new DisplaySlot(-1, null, offers.get(offerIndex));
    }

    /** 展示序里一共有几个可兑条目（界面提示用） */
    public int exchangeOfferCount() {
        return this.exchangeOffers().size();
    }

    /**
     * 「按数量从大到小」的<b>展示</b>顺序：视图下标 → 展示序号。
     *
     * <p>真实格用库存数量、可兑条目用「现在能兑出多少」当排序键 ——
     * 两者都是「这一格现在能给我多少个东西」，口径是一致的。
     * 数量相同的格<b>保持创造栏顺序</b>（用展示序号当第二关键字），所以不会互相抖动。</p>
     *
     * <p><b>只影响显示</b>：{@link #entries} 本身一个字都不动。</p>
     */
    int[] displayAmountOrder() {
        int[] base = this.unifiedOrder();
        if (this.amountOrder != null && this.amountOrder.length == base.length) {
            return this.amountOrder;
        }
        int size = base.length;
        Integer[] boxed = new Integer[size];
        for (int i = 0; i < size; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> {
            int byAmount = this.amountAtDisplay(b).compareTo(this.amountAtDisplay(a));
            // 数量相同 → 用展示序号当第二关键字，也就是「保持创造栏顺序」
            return byAmount != 0 ? byAmount : Integer.compare(a, b);
        });
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = boxed[i];
        }
        this.amountOrder = order;
        return order;
    }

    /** 某个展示格「现在能给出多少个东西」：真实格 = 库存数量，可兑条目 = 能兑出的数量 */
    private BigInteger amountAtDisplay(int displayIndex) {
        DisplaySlot slot = this.displaySlotAt(displayIndex);
        if (slot.entry() != null) {
            return slot.entry().amount();
        }
        return slot.offer() == null ? BigInteger.ZERO : slot.offer().obtainable();
    }

    /**
     * 「可兑」虚拟条目的展示堆叠。
     *
     * <p>数据只有<b>一行</b>，口径与聊天里的补货提示完全一致（灰底文字 + 亮绿数字），
     * 所以直接复用 {@link VoidWarehouseText#body}/{@link VoidWarehouseText#key}：</p>
     * <ul>
     *   <li>向上合成：{@code 可合成 2 个，用 绿宝石 18 个}；</li>
     *   <li>拆上级找零：{@code 可拆出 9 个，拆 铁块 1 个}，有找零时接 {@code ，找回 铁锭 8 个}。</li>
     * </ul>
     *
     * <p><b>为什么合成一行</b>：原先拆成「能兑多少」「消耗什么」两行，玩家反馈
     * <b>双排冗余</b> —— 两行说的是同一件事（这一格现在能拿走什么、要付什么），
     * 分成两行反而要来回对数字。合成一行后，「能拿几个」和「要付什么」挨着读，
     * 像补货提示那样一句读完。</p>
     *
     * <p><b>数量写的是总量，不是单价</b>：账目直接取 {@link ExchangeOffer#totalCost()}，
     * 那是规划器为「正好兑出 {@link ExchangeOffer#obtainable()} 个」算出来的真账。
     * 早先写的是单价（「消耗：铁粒 ×9」），玩家一眼看成「点一下就要 9 个」，
     * 而仓库里有 18 个铁粒时真账是 18 —— 数字对不上，所以改成总量。
     * 而且<b>单价根本算不出真账</b>：手上只有 1 个铁块时兑 9 个铁锭，
     * 单价是「铁块 −1、铁锭 +8」，乘 9 会得到「铁块 −9」这种仓库里不存在的消耗。</p>
     *
     * <p><b>「可合成」还是「可拆出」不是拍脑袋定的</b>：看被扣走的那几样
     * 在兑换表里的<b>价值</b> —— 比目标便宜 = 向上合成，比目标值钱 = 拆上级找零。
     * 光看「有没有找零」会判错：手里只有 1 个铁块时兑 9 个铁锭，
     * 真账是「铁块 −1」而<b>一个找回都没有</b>（9 个正好用完），那明明是拆零。</p>
     *
     * <p>物品名一律走 {@code getHoverName()} 保持成组件，<b>不 {@code getString()}</b> ——
     * 否则专用服务器上会解析成英文名（说明见 {@link VoidWarehouseText#tinted(Component, ChatFormatting)}）。</p>
     *
     * <p><b>操作行里的数量同样不许自己算</b>：无论是「右键：兑换 64 个」还是「交出 1 盒（N 个）」，
     * 数字都必须来自规划器的真值（{@link ExchangeOffer#bulk()} / {@link ExchangeOffer#boxed()}），
     * 而不是 {@code min(堆叠上限, 可兑数量)} 之类的算式 —— 后者在「仓库里只有上级」时会高估：
     * 9 个铜块时它给 64，而实际只能给 63（拆 7 块 = 63；要 64 就得拆 8 块、把 8 个零头留在仓库里）。
     * 界面写出一个做不到的数，跟可兑数量本身写死一样，都属于「凭空常数」。</p>
     *
     * @param boxMode 潜影盒模式（操作行换成「交出盒子」那一套，与普通格的口径保持一致）
     */
    public static ItemStack buildExchangeDisplay(ExchangeOffer offer, boolean boxMode, boolean en) {
        // 两类虚拟条目共用这一套渲染，只有「动词」和「角标」不同 —— 其余（数量、账单、
        // 一次能拿多少、盒模式那一套）逐字相同，所以不复制一份方法，只把词换掉。
        // 「兑换」说的是仓库替你做了一次换算，「合成」说的是仓库替你摆了合成格，
        // 对玩家来说这一步的差别就在于「用的是哪套规则」，角标承担这个信息。
        String verb = offer.craft() ? VoidWarehouseText.tr(en, "合成", "craft")
                : VoidWarehouseText.tr(en, "兑换", "exchange");
        String tag = offer.craft() ? VoidWarehouseText.tr(en, "（可合成）", " (craftable)")
                : VoidWarehouseText.tr(en, "（可兑）", " (exch.)");

        ItemStack stack = offer.template().copyWithCount(1);
        stack.set(DataComponents.CUSTOM_NAME,
                VoidWarehouseText.tinted(offer.template().getHoverName(), VoidWarehouseText.FACT)
                        .append(VoidWarehouseText.line(tag, VoidWarehouseText.FACT)));

        List<Component> lore = new ArrayList<>(4);
        lore.addAll(exchangeSummary(offer, en));

        // 操作行：与普通格一样的说法，口径统一（盒模式下就换成盒模式那一套）
        int max = Math.max(1, offer.template().getMaxStackSize());
        if (boxMode) {
            // 盒里实际能装多少：同样来自规划器（offer.boxed），不是「容量」也不是
            // 「min(可兑数量, 容量)」。可兑数量可能远超一盒（上限由库存推出来，不是固定值），
            // 所以这里必须写「实际装进去的个数」。
            long packed = offer.boxed().longValue();
            lore.add(VoidWarehouseText.line(en
                    ? "Left-click: hand out 1 box (" + VoidWarehouseText.count(packed) + " items)"
                    : "左键：交出 1 盒（" + VoidWarehouseText.count(packed) + " 个）",
                    VoidWarehouseText.ACTION_RGB));
            lore.add(VoidWarehouseText.line(
                    VoidWarehouseText.tr(en, "右键：按数量取货", "Right-click: withdraw by amount"),
                    VoidWarehouseText.ACTION_RGB));
        } else {
            // 右键一次「取一组」，但一次实际能拿走多少必须问规划器（offer.bulk），
            // 不能写 min(一组, 可兑数量)：仓库里只有铜块时那个算式给 64，
            // 而真值是 63（拆 7 块 = 63；要 64 就得拆 8 块、仓库里留下 8 个零头）。
            // 照抄那个算式就是在数量后面又写一个跟仓库无关的常数。
            long bulk = offer.bulk().longValue();
            if (bulk <= 1) {
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键 / 右键：" + verb + " 1 个",
                                "Left-click / Right-click: " + verb + " 1"),
                        VoidWarehouseText.ACTION_RGB));
            } else if (bulk >= max) {
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键：" + verb + " 1 个",
                                "Left-click: " + verb + " 1"),
                        VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "右键：" + verb + " " + max + " 个",
                                "Right-click: " + verb + " " + max),
                        VoidWarehouseText.ACTION_RGB));
            } else if (bulk >= offer.obtainable().longValue()) {
                // 仓库里一共就这么多可兑 —— 一次就能全拿走
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键：" + verb + " 1 个",
                                "Left-click: " + verb + " 1"),
                        VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line(en
                        ? "Right-click: " + verb + " " + VoidWarehouseText.count(bulk)
                        + " (as many as " + (offer.craft() ? "craftable" : "available") + ")"
                        : "右键：" + verb + " " + VoidWarehouseText.count(bulk)
                        + " 个（最多" + (offer.craft() ? "做得出" : "兑得出") + "这么多）",
                        VoidWarehouseText.ACTION_RGB));
            } else {
                // 仓库里能兑更多，但一次取不到一组 —— 因为上级只能按整块拆
                lore.add(VoidWarehouseText.line(
                        VoidWarehouseText.tr(en, "左键：" + verb + " 1 个",
                                "Left-click: " + verb + " 1"),
                        VoidWarehouseText.ACTION_RGB));
                lore.add(VoidWarehouseText.line(en
                        ? "Right-click: " + verb + " " + VoidWarehouseText.count(bulk)
                        + " (whole blocks only, no leftovers)"
                        : "右键：" + verb + " " + VoidWarehouseText.count(bulk)
                        + " 个（按整块拆，不留零头）",
                        VoidWarehouseText.ACTION_RGB));
            }
        }
        lore.add(VoidWarehouseText.line(offer.craft()
                ? VoidWarehouseText.tr(en, "仓库里没有这种物品，靠已学会的配方扣原料取出",
                "Not in stock: taken by consuming ingredients of a known recipe")
                : VoidWarehouseText.tr(en, "仓库里没有这种物品，靠合成兑换取出",
                "Not in stock: taken via crafting exchange"),
                VoidWarehouseText.NOTE));

        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /**
     * 账目本体。只有一项原料时是一句话 —— {@code 可合成 2 个，用 绿宝石 18 个} /
     * {@code 可拆出 9 个，拆 铁块 1 个}；两项以上转清单，见下方「两种排法」。
     *
     * <p>配色与聊天提示同一套：<b>无关紧要的文字灰、关键数字亮绿</b>。
     * 「哪个是要拿的量、哪个是要付的量」靠紧挨着的词就能读出来。</p>
     *
     * <h2>两种排法：一项写完，多项成清单</h2>
     * <p>原版 tooltip <b>不会自动折行</b> —— 一行有多长，框就撑多宽，撑出屏幕的部分直接被裁掉。
     * 配方扣料的账目动辄六七种原料（灰色地毯那种：线 / 骨粉 / 凋灵玫瑰 / 白色羊毛 / 黑色染料 /
     * 白色染料 / 骨头），拼成一行一百多个半角宽，右边必然看不见。所以按项数分两种排法：</p>
     * <pre>
     *   一项：可合成 2 个，用 绿宝石 18 个          ← 一句话写完，不为一项摆个清单
     *
     *   多项：可合成 3,708 个                      ← 首行只写「能拿多少」
     *         - 线 9,136 个                        ← 一项一行，横向永远不会出界
     *         - 骨粉 2 个
     *         - 凋灵玫瑰 1,154 个
     * </pre>
     *
     * <p><b>为什么不是「能兑几个」+「消耗什么」两行</b>：玩家反馈过那是<b>双排冗余</b>
     * —— 两行说的是同一件事，分成两行反而要来回对数字。清单不是那个东西：首行只有
     * 「能拿多少」，下面每行是<b>一项原料</b>，逐项左对齐，扫一眼就能把账对上。</p>
     *
     * <p><b>拆零场景用「拆 / 找回」分组</b>（{@code - 拆 铁块 1 个} / {@code - 找回 铁锭 8 个}）：
     * 一进一出写在同一套缩进里，不用回头读第一句就知道哪行是掏出去的、哪行是找回来的。</p>
     *
     * <p><b>账目里一项都解析不出来</b>时只留「可合成 N 个」，不留孤零零的「，用 」。</p>
     */
    private static List<Component> exchangeSummary(ExchangeOffer offer, boolean en) {
        boolean split = splitsFromAbove(offer);
        List<MutableComponent> costs = ledgerChunks(offer, true, en);
        List<MutableComponent> refunds = ledgerChunks(offer, false, en);

        MutableComponent head = VoidWarehouseText.body(split
                ? VoidWarehouseText.tr(en, "可拆出 ", "Uncraftable now: ")
                : VoidWarehouseText.tr(en, "可合成 ", "Craftable now: "));
        head.append(VoidWarehouseText.key(VoidWarehouseText.count(offer.obtainable())));
        head.append(VoidWarehouseText.body(en ? " pc" : " 个"));

        List<Component> lines = new ArrayList<>(costs.size() + refunds.size() + 1);
        int total = costs.size() + refunds.size();
        if (total == 0) {
            lines.add(head);
            return lines;
        }
        if (total == 1) {
            // 只有一项：仍然一句话写完 —— 为一项摆个清单，只是把同一句话拆成两行
            String tag = !costs.isEmpty()
                    ? (split ? VoidWarehouseText.tr(en, "，拆 ", ", uncraft ")
                            : VoidWarehouseText.tr(en, "，用 ", ", using "))
                    : VoidWarehouseText.tr(en, "，找回 ", ", refunds ");
            head.append(VoidWarehouseText.body(tag)).append(costs.isEmpty() ? refunds.get(0) : costs.get(0));
            lines.add(head);
            return lines;
        }
        // 两项以上：一项一行
        lines.add(head);
        String costTag = split ? VoidWarehouseText.tr(en, "拆 ", "uncraft ") : "";
        for (MutableComponent chunk : costs) {
            lines.add(ledgerLine(costTag, chunk));
        }
        String refundTag = VoidWarehouseText.tr(en, "找回 ", "refund ");
        for (MutableComponent chunk : refunds) {
            lines.add(ledgerLine(refundTag, chunk));
        }
        return lines;
    }

    /** 清单里的一行：{@code - [标签] 物品 数量 个}（标签只有拆零场景才有） */
    private static Component ledgerLine(String tag, MutableComponent chunk) {
        MutableComponent line = VoidWarehouseText.body("- " + tag);
        line.append(chunk);
        return line;
    }

    /**
     * 把账目拆成一项一块：{@code 线 9,136 个}。只拆不排，排版交给
     * {@link #exchangeSummary(ExchangeOffer, boolean)}。
     *
     * <p>必须走 {@link #resolveItem}：账单里可能混着配方本那套「带命名空间的 id」
     * （配方扣料的条目就是这样），直接喂给 9 合 1 的解析器会拼出非法标识。
     * 解析不出来的项直接跳过 —— 宁可少写一项，也不能在 tooltip 里塞一坨非法字符。</p>
     *
     * <p>物品名保持成组件（{@code getHoverName()}），<b>不 {@code getString()}</b> ——
     * 否则专用服务器上会解析成英文名（见 {@link VoidWarehouseText#tinted(Component, ChatFormatting)}）。</p>
     */
    private static List<MutableComponent> ledgerChunks(ExchangeOffer offer, boolean negatives, boolean en) {
        List<MutableComponent> chunks = new ArrayList<>(4);
        for (VoidWarehouseExchange.Delta delta : offer.totalCost()) {
            if (negatives != (delta.change().signum() < 0)) {
                continue;
            }
            Item item = resolveItem(delta.itemId());
            if (item == null) {
                continue;
            }
            MutableComponent chunk = Component.empty();
            chunk.append(VoidWarehouseText.body(new ItemStack(item).getHoverName()));
            chunk.append(VoidWarehouseText.body(" "));
            chunk.append(VoidWarehouseText.key(VoidWarehouseText.count(delta.change().abs())));
            chunk.append(VoidWarehouseText.body(en ? " pc" : " 个"));
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 这份账目是「拆上级找零」还是「向上合成」？
     *
     * <p>判据是<b>被扣走的物品在兑换表里的价值</b>（{@code 9 个下级 = 1 个上级} 那个价值）：
     * 比目标物品值钱 = 拆零，比目标便宜 = 合成。只要被扣的里面有一个比目标值钱，
     * 就按拆零说 —— 这是能让玩家一眼看懂的口径，
     * 因为「拆」和「用」指向的是完全不同的仓库格子。</p>
     *
     * <p>为什么不用「层级」：表里现在同时有 9:1 与 4:1 两种比例，
     * 海晶石和海晶石砖离海晶碎片都只有一跳，但一个值 4、一个值 9 ——
     * 层级分不出谁大谁小，价值才分得出。</p>
     */
    private static boolean splitsFromAbove(ExchangeOffer offer) {
        // 配方扣料的条目一律算「可合成」：它扣的全是配方原料，不存在「拆上级」这回事。
        // 不加这一句的话，产物恰好也在兑换表里时（比如铁锭），会被拿去做价值比较，
        // 万一某个原料比它值钱，界面就会把「扣 4 个圆石合成活塞」写成「可拆出」。
        if (offer.craft()) {
            return false;
        }
        VoidWarehouseExchange.Table table = VoidWarehouseExchange.active();
        if (!table.contains(offer.itemId())) {
            return false;
        }
        BigInteger target = table.valueOf(offer.itemId());
        for (VoidWarehouseExchange.Delta delta : offer.totalCost()) {
            if (delta.change().signum() < 0 && table.contains(delta.itemId())
                    && table.valueOf(delta.itemId()).compareTo(target) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个堆叠允不允许存进虚空仓库。
     *
     * <p>规则：<b>只要非空就收</b>。带 NBT（数据组件）的物品 —— 受损工具 / 附魔物品 /
     * 改过名的物品 / 药水 / 写了字的书 —— 现在<b>一律放行</b>，不再在仓库这一层做筛选。
     * 「一格一种形态」仍然成立：带不同数据组件的物品各占一格，数量照常无上限。</p>
     *
     * <p>是否放行某个物品，改由<b>玩家自己在漏斗里过滤</b>（分类物白名单，见
     * {@link VoidWarehouseHopper} / {@link VoidWarehouseClassifier}），仓库本身不替玩家做主。</p>
     *
     * <p><b>潜影盒是例外</b>：它虽然常常带 {@code CONTAINER} 组件，但不走这条路 ——
     * 它在 {@link #insert} 里就被拆成原料了，见 {@link #isInsertable}。</p>
     */
    public static boolean isStorable(ItemStack stack) {
        return stack != null && !stack.isEmpty();
    }

    /**
     * 这个堆叠能不能「交给仓库处理」（可以被 {@link #insert} 收下或拆解）。
     *
     * <p>= 任意非空物品 <b>或</b> 潜影盒（非空潜影盒会被拆开，空盒整盒入库）。
     * 漏斗那类「先筛一遍再决定要不要搬」的调用方要用的是这一个，而不是 {@link #isStorable}。</p>
     */
    public static boolean isInsertable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // 仓库终端不收（与 {@link #insert} 同一口径）。放在这里是为了让漏斗的 drain
        // 少白跑一趟 insert，也让「上方容器整格搬」的循环不把终端吸进来又原样吐回去。
        if (VoidWarehouseTrigger.isVoidWarehouse(stack)) {
            return false;
        }
        return VoidWarehouseShulker.isShulkerBox(stack) || isStorable(stack);
    }

    /**
     * 从第 {@code index} 格取出至多 {@code wanted} 个，返回实际取出的数量。
     *
     * <p>取空的那一格会被移除，后面的格子整体前移（「一种物品一格」，不留空洞）。</p>
     */
    public int take(int index, int wanted) {
        Entry entry = this.entryAt(index);
        if (entry == null || wanted <= 0) {
            return 0;
        }
        BigInteger actual = entry.amount().min(BigInteger.valueOf(wanted));
        if (actual.signum() <= 0) {
            return 0;
        }
        this.consume(entry, actual);
        this.markChanged();
        // actual <= wanted（int），安全
        return actual.intValue();
    }

    /**
     * 按「分类物」扣减：用手里这个堆叠当<b>样板</b>，从仓库里拿走至多 {@code wanted} 个。
     *
     * <p>与 {@link #take(int, int)} 的区别是「按格号取」和「按拿着的那件东西取」。
     * 漏斗出料时手里只有漏斗格里的那一个分类物，并没有仓库格号 ——
     * 而且仓库的格号会随着取空/入库不断变化，拿格号当凭据不安全，
     * 所以这里用<b>物品 + 数据组件</b>精确匹配（与 {@link #find} 同一套判定，
     * 也就是「仓库里那一格到底存的是不是这种东西」的唯一定义）。</p>
     *
     * <p>取空的那一格会被移除（不留空洞），与其它取出路径完全一致。</p>
     *
     * @return 实际取出的数量（0 = 仓库里没有这种物品，或数量本来就不够/参数非法）
     */
    public int takeExact(ItemStack sample, int wanted) {
        if (sample == null || sample.isEmpty() || wanted <= 0) {
            return 0;
        }
        Entry entry = this.find(sample);
        if (entry == null) {
            return 0;
        }
        BigInteger actual = entry.amount().min(BigInteger.valueOf(wanted));
        if (actual.signum() <= 0) {
            return 0;
        }
        this.consume(entry, actual);
        this.markChanged();
        // actual <= wanted（int），安全
        return actual.intValue();
    }

    /**
     * 与 {@link #takeExact} 一样按样板扣减，但<b>把扣下来的东西作为真实堆叠返回</b>。
     *
     * <p>为什么需要它：打印机这类「替玩家把货放进背包」的场景，光知道
     * 「扣成功了」不够 —— 还得拿到那个带完整数据组件的 {@link ItemStack} 才能塞进
     * 背包槽位，否则玩家拿到的是凭空 {@code new ItemStack(item)}，附魔书、
     * 药水一类的数据会在搬运过程中被抹掉。</p>
     *
     * <p>数量不够时返回「能给的最大数量」而不是整批失败 —— 仓库里只剩 3 个圆石时，
     * 能拿 3 个总比一个都拿不到强。</p>
     *
     * @param wanted 想要多少个（会被该物品的原版堆叠上限夹住）
     * @return 取出的堆叠；一件都没有（或参数非法）时返回<b>空堆叠</b>，绝不返回 null
     */
    public ItemStack takeStack(ItemStack sample, int wanted) {
        if (sample == null || sample.isEmpty() || wanted <= 0) {
            return ItemStack.EMPTY;
        }
        Entry entry = this.find(sample);
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemStack template = entry.template();
        int limit = Math.min(wanted, Math.max(1, template.getMaxStackSize()));
        BigInteger actual = entry.amount().min(BigInteger.valueOf(limit));
        if (actual.signum() <= 0) {
            return ItemStack.EMPTY;
        }
        this.consume(entry, actual);
        this.markChanged();
        // copyWithCount：切断与 template 的实例关联（template 恒为 1 个样用）
        return template.copyWithCount(actual.intValue());
    }

    /** 清空整个仓库（目前没有界面入口，保留给指令 / 调试使用） */
    public void clearContent() {
        if (this.entries.isEmpty()) {
            return;
        }
        this.entries.clear();
        this.index.clear();
        // 整体清空没有逐件记账的机会：让下一次重算把每个产物都算一遍。
        // 依赖集不用重建 —— 配方结构没变，只是全部库存归零。
        this.craftCacheBusted = true;
        this.changedItems.clear();
        this.markChanged();
    }

    // ------------------------------------------------------------------ 载入指纹

    /**
     * 这份数据<b>是不是从一份「本该有内容、却一条都没读出来」的存档加载出来的</b>。
     *
     * <p>用途只有一个：{@link VoidWarehouse#data} 在把实例交出去之前用它做最后一道
     * 闸门 —— 一旦为 true，说明数据层出了严重问题（文件里有记录，却没能变成任何一格），
     * 此时<b>绝不能</b>让这个空壳继续工作并写盘，必须直接拒绝服务。</p>
     *
     * <p>判据很严：必须「文件里确实有条目」且「一条都没变成可用的格子」，也不存在
     * 被特意保留下来的坏条目。</p>
     *
     * <p><b>这个结论在构造的那一刻就定死了</b>（见 {@link #loadedSuspicious}）：
     * 玩家可以随时把仓库里的东西取空，那时 {@link #entries} 为空是<b>正常的</b>，
     * 不能反过来说它是空壳 —— 实测踩过：载入 2 条合法数据 → 玩家清空 → 闸门误报、
     * 整个仓库再也访问不了。true 空仓库（新世界 / 玩家自己清空的）不会误报。</p>
     */
    public boolean isSuspiciouslyEmpty() {
        return this.loadedSuspicious;
    }

    /** 从存档里一共读到了多少条（含读不懂的）。0 表示文件本来就是空的 / 新建的。 */
    public int loadedCount() {
        return this.loadedEntryCount;
    }

    /** 载入时读不懂的条数 */
    public int loadedBrokenCount() {
        return this.loadedBrokenCount;
    }

    /** 读不懂、但仍会原样写回的那些条目条数 */
    public int brokenCount() {
        return this.brokenEntries.size();
    }

    /**
     * 任何改动都要走这里：让统计缓存与「数量排序」视图缓存失效，并标记存档为脏。
     *
     * <p>注意<b>不动</b> {@link #sortedGeneration}：删格子（取空）与改数量都不会破坏
     * 基础顺序，只有「新增一种物品」才会（那由 {@link #invalidateOrder()} 单独负责）。
     * 这样普通的取出/存入不会触发一次无谓的重排。</p>
     */
    void markChanged() {
        this.cachedCount = -1;
        this.cachedTotal = null;
        this.amountOrder = null;
        // 库存一动，「能兑出什么、能兑多少」就全变了。
        //
        // offers 只标脏、**不置空**：置空会让「同一 tick 内的下一次读」立刻重算一次，
        // 而一次重算约 1.6 ms。20 个玩家在同一 tick 里各做一次动作、各刷新一次界面，
        // 就是 20 次重算 ≈ 30 ms（实测占掉 61% 的 tick 预算）。重算按 tick 合并，
        // 见 exchangeOffers()。
        //
        // unifiedOrder 必须**立刻**作废：它存的是 entries 的下标，取空一格就会让后面
        // 全部前移，留着旧数组轻则显示错格、重则数组越界。它重建只要一次 O(n) 归并
        // （几十微秒），不是瓶颈，这里不许偷懒。
        this.offersStale = true;
        this.unifiedOrder = null;
        this.setDirty();
    }

    // ------------------------------------------------------------------ 序列化输出

    /**
     * 写盘用的条目列表：仓库里的每一格 + 读不出来的原文（原样带回去）。
     *
     * <p>坏原文排在最后，顺序稳定；它们不参与仓库的任何逻辑，只是「借道」存续。</p>
     */
    private List<Decoded> decodedEntries() {
        List<Decoded> list = new ArrayList<>(this.entries.size() + this.brokenEntries.size());
        for (Entry entry : this.entries) {
            list.add(new Decoded.Ok(
                    new RawEntry(Optional.of(entry.template()), Optional.empty(), Optional.of(entry.amount()))));
        }
        for (net.minecraft.nbt.Tag raw : this.brokenEntries) {
            list.add(new Decoded.Broken(raw));
        }
        return list;
    }
}
