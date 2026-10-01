package neko.voidwarehouse;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
 * （「数量：N / 左键：取出 1 个 / 右键：取出一组」）。展示堆叠是从 template
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
 *       {@code 2 个潜影壳 + 1 个箱子}（原版配方）。<b>空盒子不拆</b>，直接整盒入库，
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

    // ------------------------------------------------------------------ 一格

    /**
     * 仓库里的一格 = 一种物品 + 一个无上限的数量。
     *
     * <p>{@code template} 的数量恒为 1，只当「长什么样」的样板；真品数量在 {@code amount}。
     * {@code display} / {@code boxDisplay} 是两种模式下各自的展示堆叠缓存（带 lore），
     * 数量一变就一起失效重建。</p>
     */
    public static final class Entry {
        private final ItemStack template;
        private BigInteger amount;
        private ItemStack display;
        /** 潜影盒模式下的展示堆叠（操作说明是「交出盒子」那套，与普通模式不是一回事） */
        private ItemStack boxDisplay;

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
            this.display = null;
            this.boxDisplay = null;
        }

        void subtract(BigInteger delta) {
            if (delta.signum() <= 0) {
                return;
            }
            this.amount = this.amount.subtract(delta);
            if (this.amount.signum() < 0) {
                this.amount = BigInteger.ZERO;
            }
            this.display = null;
            this.boxDisplay = null;
        }

        /** 普通模式的展示堆叠（带数量与操作提示），缓存复用 */
        public ItemStack display() {
            if (this.display == null) {
                this.display = buildDisplay(this.template, this.amount, false);
            }
            return this.display;
        }

        /**
         * 潜影盒模式的展示堆叠，缓存复用。
         *
         * <p>这里<b>不是</b>在普通说明后面追加几行 —— 而是换一套说明：盒模式下
         * 「左键取出 1 个 / 右键取出一组」<b>根本不成立</b>（左键交出的是一整盒），
         * 留着它们既占地方又误导人。两套说明各自精简，行数都控制在 5 行以内。</p>
         */
        public ItemStack displayInBoxMode() {
            if (this.boxDisplay == null) {
                this.boxDisplay = buildDisplay(this.template, this.amount, true);
            }
            return this.boxDisplay;
        }

        /**
         * 造展示堆叠：两种模式共用「数据行」，各自接自己的「操作行」。
         *
         * @param boxMode 潜影盒模式？决定操作行说的是「取出」还是「交出盒子」
         */
        private static ItemStack buildDisplay(ItemStack template, BigInteger amount, boolean boxMode) {
            int max = Math.max(1, template.getMaxStackSize());
            // 格子里永远只画一个：count = 1 时原版不会渲染右下角的数字。
            // 真实数量往往远超一组、甚至到亿级，画在格子里既不合理也会糊到隔壁格，
            // 所以格子只当图标用，真实数量写在下面的 lore 里。
            ItemStack stack = template.copyWithCount(1);
            List<Component> lore = new ArrayList<>(5);

            // ---- 数据行：数量 +（够多时的）盒 / 组 / 个换算 ----
            lore.add(VoidWarehouseText.line("数量：" + format(amount), VoidWarehouseText.VALUE));
            String packing = describePacking(amount, max);
            if (packing != null) {
                lore.add(VoidWarehouseText.line(packing, VoidWarehouseText.VALUE));
            }

            // ---- 操作行：两种模式各说各的，绝不混着显示 ----
            if (boxMode) {
                int capacity = VoidWarehouseShulker.capacity(template);
                lore.add(VoidWarehouseText.line(
                        "左键：交出 1 盒（" + VoidWarehouseText.count(capacity) + " 个）",
                        VoidWarehouseText.ACTION));
                lore.add(VoidWarehouseText.line("右键：按数量取货", VoidWarehouseText.ACTION));
                lore.add(VoidWarehouseText.line("消耗：潜影壳 ×" + VoidWarehouseShulker.SHELL_COST
                        + " + 箱子 ×" + VoidWarehouseShulker.CHEST_COST, VoidWarehouseText.COST));
            } else if (max > 1) {
                lore.add(VoidWarehouseText.line("左键：取出 1 个", VoidWarehouseText.ACTION));
                lore.add(VoidWarehouseText.line(
                        "右键：取出 1 组（" + max + " 个）", VoidWarehouseText.ACTION));
            } else {
                // 不可堆叠物品（剑、工具…）左右键都是取 1 个，合成一行说，不重复两遍
                lore.add(VoidWarehouseText.line("左键 / 右键：取出 1 个", VoidWarehouseText.ACTION));
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
        private static String describePacking(BigInteger amount, int max) {
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
            StringBuilder text = new StringBuilder();
            if (boxes.signum() > 0) {
                text.append(format(boxes)).append(" 盒 ");
            }
            if (max > 1 && stacks.signum() > 0) {
                text.append(format(stacks)).append(" 组 ");
            }
            if (loose.signum() > 0) {
                text.append(format(loose)).append(" 个");
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
                    .forGetter(VoidWarehouseData::decodedEntries)
    ).apply(instance, VoidWarehouseData::new));

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

    /** 数量降序视图的缓存（view 下标 → 仓库格号）；任何改动都会把它置空 */
    private int[] amountOrder;

    public VoidWarehouseData() {
    }

    /**
     * 从解码结果建仓库。
     *
     * <p>好条目进仓库（同物品合并）；坏条目原文留在 {@link #brokenEntries} 里，
     * 下次写盘时原样写回。<b>注意这里绝不能因为「一条都读不出来」就抛异常</b> ——
     * 抛异常会让上层把整个仓库当成读取失败，那才是真正会丢数据的路径。
     * 「读到空」这种情况由 {@link VoidWarehouse#data} 那一层的指纹校验负责拦截。</p>
     */
    VoidWarehouseData(List<Decoded> loaded) {
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
        // 这里不排序：载入进来的顺序可能是旧文件的乱序，但第一次读之前
        // ensureSorted() 一定会把它整成创造栏顺序（见 sortedGeneration 的初值 -1）。
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
        // 顺序变了，写出去的存档也就变了（内容没变，但文件里的排列会更新）
        this.setDirty();
    }

    /** 有新格子追加到末尾时调：下次读之前整体重排一次 */
    private void invalidateOrder() {
        this.sortedGeneration = -1;
    }

    /**
     * 「按数量从大到小」的视图顺序：{@code 视图下标 → 仓库格号}。
     *
     * <p>数量相同的物品<b>保持基础顺序</b>（也就是创造栏顺序），所以一堆数量一样的物品
     * 不会因为没有二次排序而每次刷新都换位置。</p>
     *
     * <p><b>只影响显示</b>：{@link #entries} 本身一个字都不动，谁把界面切回默认排序
     * （或者关掉界面再打开）就立刻回到创造栏顺序。</p>
     *
     * <p>缓存由 {@link #markChanged()} 置空 —— 数量一变（哪怕只是取出 1 个）就会重算，
     * 因为「按数量排序」这个视图本来就依赖数量。没开这个视图时，
     * {@link VoidWarehousePageContainer} 根本不会调到这里，所以不产生任何开销。</p>
     */
    int[] amountOrder() {
        this.ensureSorted();
        if (this.amountOrder != null && this.amountOrder.length == this.entries.size()) {
            return this.amountOrder;
        }
        int size = this.entries.size();
        Integer[] boxed = new Integer[size];
        for (int i = 0; i < size; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, (a, b) -> {
            int byAmount = this.entries.get(b).amount().compareTo(this.entries.get(a).amount());
            // 数量相同 → 用格号当第二关键字，也就是「保持基础（创造栏）顺序」
            return byAmount != 0 ? byAmount : Integer.compare(a, b);
        });
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = boxed[i];
        }
        this.amountOrder = order;
        return order;
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

    /** 总页数，空仓库也算 1 页 */
    public int pageCount() {
        return Math.max(1, (this.entryCount() + PAGE_SIZE - 1) / PAGE_SIZE);
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

    /** 按格号取展示堆叠（带 lore），空格返回 {@link ItemStack#EMPTY} */
    public ItemStack displayAt(int index) {
        Entry entry = this.entryAt(index);
        return entry == null ? ItemStack.EMPTY : entry.display();
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
        if (VoidWarehouseShulker.isShulkerBox(incoming)) {
            return this.unpackShulkerBox(incoming);
        }
        return this.put(incoming);
    }

    /**
     * 把潜影盒<b>拆开</b>入库：盒内物品按各自数量入库，盒子本身变成
     * {@code 2 个潜影壳 + 1 个箱子}（原版配方）。
     *
     * <p><b>空盒子不拆</b>：一个东西都没装的潜影盒，直接当成普通物品整盒入库
     * （占一格，记 1 个「潜影盒」）。这样玩家手里那个空盒子不会被偷偷换成壳和箱子，
     * 语义也最直观 —— 空盒子就是空盒子。</p>
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
        for (ItemStack inside : contents) {
            // contentsOf 给的是拷贝，put 会把它清空，不会影响到盒子本身
            this.put(inside);
        }
        this.put(new ItemStack(Items.SHULKER_SHELL, VoidWarehouseShulker.SHELL_COST));
        this.put(new ItemStack(Items.CHEST, VoidWarehouseShulker.CHEST_COST));
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
        entry.subtract(need);
        if (entry.amount().signum() <= 0) {
            this.entries.remove(entry);
            this.unindexEntry(entry);
        }
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
            entry.subtract(BigInteger.valueOf(available));
            if (entry.amount().signum() <= 0) {
                this.entries.remove(i);
                this.unindexEntry(entry);
            }
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
     * <p>返回实际扣掉的空盒数量（正常应等于 {@code plan.emptyBoxes()}）；
     * 少扣了说明并发操作把空盒抢走了，调用方不必在意 —— 那部分材料本来就不该花出去。</p>
     */
    public int consumeBoxMaterials(BoxMaterials plan) {
        int taken = plan.emptyBoxes() > 0 ? this.takeEmptyBoxes(plan.emptyBoxes()) : 0;
        if (plan.shells() > 0) {
            this.takeItem(Items.SHULKER_SHELL, plan.shells());
        }
        if (plan.chests() > 0) {
            this.takeItem(Items.CHEST, plan.chests());
        }
        return taken;
    }

    /** 一次「装盒」操作的材料账：几个用空盒、剩下要多少潜影壳 / 箱子 */
    public record BoxMaterials(int emptyBoxes, int shells, int chests) {
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
        entry.subtract(actual);
        if (entry.amount().signum() <= 0) {
            this.entries.remove(index);
            this.unindexEntry(entry);
        }
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
        entry.subtract(actual);
        if (entry.amount().signum() <= 0) {
            this.entries.remove(entry);
            this.unindexEntry(entry);
        }
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
        entry.subtract(actual);
        if (entry.amount().signum() <= 0) {
            this.entries.remove(entry);
            this.unindexEntry(entry);
        }
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
