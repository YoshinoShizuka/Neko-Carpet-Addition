package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「方块掉落物直接进虚空仓库」（{@link VoidWarehouseSettings#voidWarehouseBlockDrops}）的上下文、收料口与提示。
 *
 * <h2>为什么要一个「当前正在挖方块的人」</h2>
 * <p>掉落物本身不知道自己是谁挖出来的 —— {@code Block#popResource} 造出来的
 * {@link net.minecraft.world.entity.item.ItemEntity} 只是「世界里多了一个物品实体」，
 * 挖方块、爆掉、怪物死亡、玩家丢弃，走的是<b>同一个</b> {@code ServerLevel#addFreshEntity}。
 * 想把范围严格限制在「玩家挖掉的方块」上，唯一可靠的办法是在
 * {@code ServerPlayerGameMode#destroyBlock} 这一段里挂一个上下文，
 * 让这期间的 {@code addFreshEntity} 知道自己正处在「某人挖方块」之中。</p>
 *
 * <p>所以这里存的是<b>一个 static</b>：服务端的方块破坏全在主线程上跑，
 * 不存在并发；{@code destroyBlock} 也不会自我嵌套（破坏一个方块只跑一次）。
 * 用 static 而不引 ThreadLocal / ScopedValue，是因为后者在 Java 21 上还是预览特性，
 * 而本模组的 1.21.11 那一轨正好锁在 Java 21。</p>
 *
 * <h2>泄漏兜底：tick 戳</h2>
 * <p>{@link #begin} 与 {@link #end} 成对挂在 {@code destroyBlock} 的 HEAD / RETURN 上。
 * 万一 {@code destroyBlock} 抛了异常（模组冲突、方块自己炸了），RETURN 那份不会执行、
 * 上下文就留在了 static 上 —— 那会让<b>之后世界里每一个物品实体</b>都被吸进仓库，
 * 属于最不能接受的一类故障。所以 {@link #begin} 同时记下当时的服务端 tick，
 * {@link #collect} 只接受<b>同一 tick</b> 的掉落物：即使真漏掉了 {@code end}，
 * 最多也就影响本次 tick 的剩余部分，下一 tick 自动失效。
 * 「挖方块产生掉落」本来就全在这一次调用里同步完成，同 tick 这个条件不会漏收。</p>
 *
 * <h2>收完还要说一声（但只准说一句）</h2>
 * <p>东西没进背包、也没掉在地上，玩家看不见任何反馈 —— 不给提示的话，
 * 「规则到底生效了没有」只能靠翻仓库。所以入库成功后要发一条聊天提示，
 * 样式与补货提示（{@code PrinterRestockNetwork#notifyTaken}）同一套：
 * 统一 {@code [虚空仓库] } 前缀 + 浅灰正文 + 亮绿数字（{@link VoidWarehouseText#info}）。</p>
 *
 * <p>说的不只是「收了几个」，还有<b>收完仓库里一共有多少</b>：挖矿时玩家真正关心的是
 * 「够不够了」，光报「挖到 3 个」还得自己心算加上原有库存。库里那个数在拼提示这一刻
 * 现读（{@link VoidWarehouseData#amountExact}，与 {@link #PENDING} 的分组同为
 * 「物品 + 数据组件」口径），所以连着挖好几种时报出来的都是发消息那一刻的真值。</p>
 *
 * <p>但挖掘是<b>高频</b>动作：一棵树、一面墙、一条矿道，一秒能刨掉十来个方块。
 * 若每次入库都发一条，聊天栏会被自家消息淹没，玩家自己的聊天全被顶掉。于是：</p>
 * <ul>
 *   <li><b>先攒后发</b>：入库成功只往 {@link #PENDING} 里记账（物品 + 数据组件相同就累加数量），
 *       一个方块掉出多种物品也只算同一条里的一行；</li>
 *   <li><b>按玩家每秒最多一条</b>（{@link #MESSAGE_INTERVAL_MS}）：窗口内新来的继续累加，
 *       到下一条时一并报数。**不是延迟通知**，而是把「同一段时间挖到的东西」合成一句；</li>
 *   <li><b>一条里最多列 4 种</b>（{@link #MAX_LISTED_KINDS}）：再多就折成「N 种共 M 个」，
 *       免得一条提示比整个聊天框还长；折起来之后<b>不再逐种报库存</b> ——
 *       五个「（仓库共 …）」串在一起，比单纯报总数难读得多。</li>
 * </ul>
 * <p>数字一律用 {@link VoidWarehouseText#count(long)}（千分位）+ {@link VoidWarehouseText#key}，
 * 物品名一律保持「可翻译组件」原样发（{@code getHoverName()}，<b>绝不 {@code getString()}</b>）——
 * 理由与补货提示完全相同：服务端解析会在专用服务器上固定成英文。</p>
 */
public final class VoidWarehouseBlockDrops {

    /** 提示窗口：同一玩家在这个间隔内挖到的方块合并成一条（不是延迟，是合并） */
    static final long MESSAGE_INTERVAL_MS = 1000L;

    /** 一条提示里最多列几种物品，再多就折成「N 种共 M 个」 */
    static final int MAX_LISTED_KINDS = 4;

    /** 攒着待发的记录闲置多久可以清掉。<b>限流时间戳要留着</b>，所以不是发完就删 */
    private static final long STALE_MS = 60_000L;

    /** 正在挖方块的玩家；{@code null} = 当前不在「玩家挖方块」之中 */
    private static ServerPlayer breaker;

    /** {@link #breaker} 生效的那个服务端 tick（泄漏兜底用，见类注释） */
    private static int breakerTick;

    /** 每个玩家攒着待发的一份汇总。key 用 UUID：玩家重连换了 {@code ServerPlayer} 对象也能对上 */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private VoidWarehouseBlockDrops() {
    }

    /** 规则开着吗：主开关 + 本条规则都要开（与 {@code craftEnabled} 同一形状） */
    public static boolean enabled() {
        return VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseBlockDrops;
    }

    /** 进入「某个玩家开始挖一个方块」，{@code destroyBlock} 的 HEAD 调用 */
    public static void begin(ServerPlayer player) {
        if (player == null || !enabled()) {
            // 关着的时候连上下文都不立 —— 平时 addFreshEntity 的代价就只剩一次 null 读
            breaker = null;
            return;
        }
        breaker = player;
        breakerTick = tickOf(player.level() instanceof ServerLevel level ? level.getServer() : null);
    }

    /** 离开「挖方块」，{@code destroyBlock} 的 RETURN 调用（无论成功失败都清） */
    public static void end() {
        breaker = null;
    }

    /**
     * 把一份掉落物收进虚空仓库。
     *
     * @param level 掉落物要进入的那个服务端世界（同 tick 校验用）
     * @param stack 掉落物的堆叠。<b>成功时 {@code insert} 会把它就地清零</b>，
     *              调用方（mixin）据此拦下这个实体，所以世界上不会同时出现两份
     * @return {@code true} = 已经收进仓库（调用方必须拦下这个物品实体）；
     *         {@code false} = 不收，照原样掉在地上
     */
    public static boolean collect(ServerLevel level, ItemStack stack) {
        ServerPlayer player = breaker;
        if (player == null || level == null) {
            return false;
        }
        if (!enabled()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || server.getTickCount() != breakerTick) {
            // 上下文是上一次挖方块漏下来的（见类注释的「tick 戳」），当它不存在
            breaker = null;
            return false;
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            VoidWarehouseData data = VoidWarehouse.data(server);
            if (data == null) {
                return false;
            }
            // insert 的语义就是「入库」：潜影盒按与漏斗一致的方式拆开、其余按物品 + 数据组件归并，
            // 成功时会把 stack 清零。它返回 false（空物品）时 stack 原样不动，正好交给原版继续掉。
            //
            // 提示要用「挖到了什么」，而 insert 会把 stack 清零，所以先留一份拷贝。
            ItemStack dropped = stack.copy();
            if (!data.insert(stack)) {
                return false;
            }
            // 记账只是附带的：它若抛异常，绝不能反过来把「已经入库」判成没入库 ——
            // 那会让 mixin 把实体放进世界，等于凭空复制一份。
            try {
                record(player, dropped);
            } catch (RuntimeException ignored) {
                // 提示失败不影响入库
            }
            return true;
        } catch (RuntimeException ex) {
            // 仓库读不出来（存档闸门）或任何意外：这条规则绝不能把玩家的掉落物弄没
            return false;
        }
    }

    // ================================================================ 提示

    /**
     * 每个 tick 冲刷一次：把攒下的汇总成<b>一条</b>发给对应玩家。
     *
     * <p>由 {@code VoidWarehouseExtension#onTick} 调用，且必须在「规则关着就提前 return」
     * 之前 —— 规则刚被关掉时，攒下的那几条也要发出去，不能吞掉。</p>
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            // 常态：没挖过东西。连时间都不取，这条路零成本
            return;
        }
        long now = System.currentTimeMillis();
        // 「仓库里现在有多少」要现读：玩家关心的是够不够，不是刚收了几份。
        // 同一次冲刷里多个玩家共用一份 data（取数据本身有磁盘/闸门开销，能省就省）。
        VoidWarehouseData data = null;
        boolean dataResolved = false;
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Pending pending = it.next().getValue();
            if (pending.kinds.isEmpty()) {
                // 已经发完，只是还留着限流时间戳；闲置够久就清掉，长跑服里不会越攒越多
                if (now - pending.lastTouchedAt > STALE_MS) {
                    it.remove();
                }
                continue;
            }
            if (!due(pending.lastSentAt, now)) {
                // 窗口没到：继续攒着，下一条一并报数
                continue;
            }
            List<Kind> kinds = take(pending);
            pending.lastSentAt = now;
            pending.lastTouchedAt = now;
            if (kinds == null) {
                continue;
            }
            if (!dataResolved) {
                dataResolved = true;
                try {
                    data = VoidWarehouse.data(server);
                } catch (RuntimeException ignored) {
                    // 仓库读不出来（存档闸门）：提示照发，只是不带库存数字
                }
            }
            try {
                pending.player.sendSystemMessage(summary(kinds, data));
            } catch (RuntimeException ignored) {
                // 发不出去（玩家正在断开、别的模组接管了聊天通道）不能反过来影响挖掘
            }
        }
    }

    /** 限流判据：窗口到了才发。纯函数，方便自检直接咬 */
    static boolean due(long lastSentAt, long now) {
        return now - lastSentAt >= MESSAGE_INTERVAL_MS;
    }

    /** 把「挖到了一份什么」记进待发汇总（物品 + 数据组件相同就累加数量） */
    static void record(ServerPlayer player, ItemStack dropped) {
        if (player == null || dropped == null || dropped.isEmpty()) {
            return;
        }
        Pending pending = PENDING.computeIfAbsent(player.getUUID(), id -> new Pending());
        pending.player = player;
        long now = System.currentTimeMillis();
        pending.lastTouchedAt = now;
        // 潜影盒（非空）入库时会被拆开：提示里要标出来，否则玩家会以为仓库里多了一整个盒子
        boolean unpacked = VoidWarehouseShulker.isShulkerBox(dropped) && !VoidWarehouseShulker.isEmptyBox(dropped);
        for (Kind kind : pending.kinds) {
            if (kind.unpacked == unpacked && ItemStack.isSameItemSameComponents(kind.sample, dropped)) {
                kind.total += dropped.getCount();
                return;
            }
        }
        Kind kind = new Kind(dropped.copyWithCount(1), unpacked);
        kind.total = dropped.getCount();
        pending.kinds.add(kind);
    }

    /** 取走某玩家攒下的一份汇总（{@code null} = 没攒下东西） */
    static List<Kind> takePending(ServerPlayer player) {
        Pending pending = player == null ? null : PENDING.get(player.getUUID());
        return pending == null ? null : take(pending);
    }

    private static List<Kind> take(Pending pending) {
        if (pending.kinds.isEmpty()) {
            return null;
        }
        List<Kind> taken = new ArrayList<>(pending.kinds);
        pending.kinds.clear();
        return taken;
    }

    /**
     * 把一份汇总拼成<b>一行</b>聊天提示。
     *
     * <p>格式与补货提示同一套（{@code [虚空仓库] } + 灰正文 + 亮绿数字），
     * 区别是有两处数字：<b>挖到了几个</b>、<b>仓库里一共有几个</b>。后者绑在各自物品后面，
     * 用括号收住 —— 一句里报好几种时，只有绑在一起才不会让人对错行：</p>
     * <ul>
     *   <li>一种：{@code 挖到 3 个 泥土（仓库共 128 个）}</li>
     *   <li>多种：{@code 挖到 3 个 泥土（仓库共 128 个）、12 个 圆石（仓库共 64 个）}</li>
     *   <li>超过 4 种：{@code 挖到 5 种共 120 个}</li>
     * </ul>
     *
     * <p>两种情况下<b>故意不报库存</b>：</p>
     * <ul>
     *   <li><b>非空潜影盒</b>：入库时被拆开，盒子本身并没进仓库，报它的数量只会让玩家
     *       以为仓库里多了个盒子 —— 所以只留「（已拆开）」；</li>
     *   <li><b>折成「N 种共 M 个」</b>：这时没有「那一种物品」，逐种报库存会把提示拉成一堵墙。</li>
     * </ul>
     *
     * @param kinds 这一轮要报的物品
     * @param data  仓库数据，用来现读库存；{@code null} = 读不到，此时只报挖到的数量
     */
    static Component summary(List<Kind> kinds, VoidWarehouseData data) {
        MutableComponent body = VoidWarehouseText.body("挖到 ");
        if (kinds.size() > MAX_LISTED_KINDS) {
            long total = 0L;
            for (Kind kind : kinds) {
                total += kind.total;
            }
            return VoidWarehouseText.info(body
                    .append(VoidWarehouseText.key(VoidWarehouseText.count(kinds.size())))
                    .append(VoidWarehouseText.body(" 种共 "))
                    .append(VoidWarehouseText.key(VoidWarehouseText.count(total)))
                    .append(VoidWarehouseText.body(" 个")));
        }
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            if (i > 0) {
                body.append(VoidWarehouseText.body("、"));
            }
            body.append(VoidWarehouseText.key(VoidWarehouseText.count(kind.total)))
                    .append(VoidWarehouseText.body(" 个 "))
                    // 物品名保持可翻译组件原样，本地化交给客户端（与服务端 getString() 的区别见 VoidWarehouseText#body(Component)）
                    .append(VoidWarehouseText.body(kind.sample.getHoverName()));
            if (kind.unpacked) {
                body.append(VoidWarehouseText.body("（已拆开）"));
                continue;
            }
            BigInteger after = afterCount(data, kind);
            if (after != null) {
                body.append(VoidWarehouseText.body("（仓库共 "))
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(after)))
                        .append(VoidWarehouseText.body(" 个）"));
            }
        }
        return VoidWarehouseText.info(body);
    }

    /**
     * 这种物品此刻在仓库里一共有多少（含刚收进去的那几个）。
     *
     * <p>口径必须与 {@link #record} 的分组一致 —— {@code amountExact} 内部同样用
     * {@link ItemStack#isSameItemSameComponents}，所以「一条提示里的一项」正好对上「仓库里的一格」。
     * 用 {@code amountOf(Item)} 那种只按物品查的宽口径，会把不同数据组件的几格加在一起，
     * 报出来的数对不上玩家打开仓库看到的那一行。</p>
     *
     * @return {@code null} = 读不到仓库，这条提示就不带库存数字
     */
    private static BigInteger afterCount(VoidWarehouseData data, Kind kind) {
        if (data == null || kind == null) {
            return null;
        }
        try {
            return data.amountExact(kind.sample);
        } catch (RuntimeException ex) {
            // 提示是附带的，读库存出任何问题都只丢这个数字，不影响整条消息
            return null;
        }
    }

    /** 一种物品（物品 + 数据组件）在这一份汇总里的总数 */
    static final class Kind {
        /** 只保留「一个」的样子：名字与数据组件都在，数量另记 */
        final ItemStack sample;
        /** 潜影盒：入库时被拆开了，提示里要说清 */
        final boolean unpacked;
        long total;

        Kind(ItemStack sample, boolean unpacked) {
            this.sample = sample;
            this.unpacked = unpacked;
        }
    }

    private static final class Pending {
        ServerPlayer player;
        final List<Kind> kinds = new ArrayList<>(4);
        /** 上次发提示的时刻（限流用）。0 = 还没发过，第一条立刻发 */
        long lastSentAt;
        /** 最近一次记账的时刻（清理闲置记录用） */
        long lastTouchedAt;
    }

    private static int tickOf(MinecraftServer server) {
        return server == null ? Integer.MIN_VALUE : server.getTickCount();
    }
}
