package neko.voidwarehouse.restock;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseText;

/**
 * 「直接放置从虚空仓库扣料」的聊天提示。
 *
 * <h2>为什么要有这条提示</h2>
 * <p>打印机开直放时，方块是<b>凭空变成</b>的：手上没有物品被消耗，背包也不动，
 * 玩家能看到的唯一变化是仓库里少了东西 —— 而那要打开界面才知道。
 * 对比之下「取货」有 {@code PrinterRestockNetwork#notifyTaken} 明确报一句
 * 「取走 N 个 X，仓库剩余 M 个」。两条路都是扣仓库，凭什么只有取货有回执？
 * 所以这里补上同一套提示，文案按语义改成「放置消耗」。</p>
 *
 * <h2>为什么不能一放一条：这是全模组最高频的动作</h2>
 * <p>实机日志实测（26.3 + 打印机直放）：单个 200 tick（10 秒）窗口能下发
 * <b>334 次</b>放置，峰值约 34 次/秒。一次一条的话，聊天栏每秒滚过 34 行，
 * 玩家自己的聊天记录会被彻底顶掉 —— 这比没有提示还糟。</p>
 *
 * <p>所幸同一个仓库里已经有现成的解法：「方块掉落物直接进虚空仓库」
 * （{@link neko.voidwarehouse.VoidWarehouseBlockDrops}）面对的是同量级的高频场景，
 * 它的做法是<b>先攒后发</b>。本类完全照搬那套，保持全模组只有一种提示风格：</p>
 * <ul>
 *   <li><b>先攒后发</b>：每次放置成功只往 {@link #PENDING} 里记账
 *       （物品 + 数据组件相同就累加数量），不立刻发；</li>
 *   <li><b>按玩家每秒最多一条</b>（{@link #MESSAGE_INTERVAL_MS}）：窗口内新来的继续累加，
 *       到下一条时一并报数。**不是延迟通知**，而是把「这一秒放下去的方块」合成一句；</li>
 *   <li><b>一条里最多列 4 种</b>（{@link #MAX_LISTED_KINDS}）：再多就折成「N 种共 M 个」，
 *       免得一条提示比整个聊天框还长。</li>
 * </ul>
 * <p>换句话说：同一时刻放了几十个方块，聊天栏也只多<b>一行</b>；
 * 持续打印时稳定在 1 行/秒，且这行是「这一秒的增量」，读起来本身就是进度条。</p>
 *
 * <h2>库存为什么在发消息那一刻现读</h2>
 * <p>和入库提示同一个理由：玩家关心的是「还剩多少、够不够」，不是「刚扣了几个」。
 * 攒一秒再读，报出来的是<b>发消息这一刻</b>的真值，而不是每个方块各自的快照。
 * 口径用 {@link VoidWarehouseData#amountExact}（物品 + 数据组件），与本类的分组一致，
 * 所以报出来的数正好对得上玩家打开仓库看到的那一行。</p>
 *
 * <h2>物品名为什么不能 {@code getString()}</h2>
 * <p>与 {@code PrinterRestockNetwork#notifyTaken} 完全相同：{@code getHoverName()} 是
 * 可翻译组件，必须在<b>客户端</b>按玩家自己的语言渲染。服务端 {@code getString()} 会用
 * 服务端的语言表现场解析（专用服务器固定 {@code en_us}），中文客户端会看到英文物品名。</p>
 */
public final class PrinterPlaceNotifier {

    /** 提示窗口：同一玩家在这个间隔内扣掉的料合并成一条（不是延迟，是合并） */
    static final long MESSAGE_INTERVAL_MS = 1000L;

    /** 一条提示里最多列几种物品，再多就折成「N 种共 M 个」 */
    static final int MAX_LISTED_KINDS = 4;

    /** 攒着待发的记录闲置多久可以清掉。<b>限流时间戳要留着</b>，所以不是发完就删 */
    private static final long STALE_MS = 60_000L;

    /** 每个玩家攒着待发的一份汇总。key 用 UUID：玩家重连换了 {@code ServerPlayer} 对象也能对上 */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private PrinterPlaceNotifier() {
    }

    /**
     * 记一笔「刚刚从仓库扣掉了什么」。
     *
     * <p>由网络层在放置成功后调用。传 {@link PrinterPlaceService.PlaceOutcome#deducted()}
     * 为假的结局（被拒、没货、规则关）一律不进这里 —— 那些本来就没扣料，
     * 报出来只会让玩家以为自己亏了。</p>
     *
     * <p>这个方法只记账、不发消息，且<b>必须廉价</b>：它在放置热路径上，
     * 每次放置调用一次（峰值 34 次/秒）。</p>
     */
    public static void record(ServerPlayer player, PrinterPlaceService.PlaceOutcome outcome) {
        if (player == null || outcome == null || !outcome.deducted()) {
            return;
        }
        ItemStack sample = outcome.sample();
        if (sample == null || sample.isEmpty()) {
            return;
        }
        Pending pending = PENDING.computeIfAbsent(player.getUUID(), id -> new Pending());
        pending.player = player;
        long now = System.currentTimeMillis();
        pending.lastTouchedAt = now;
        // 分组口径与 amountExact 一致：同一格（物品 + 数据组件）累加。
        // 用 count 1 的样板做 key，数量单独累加 —— 与 VoidWarehouseBlockDrops#record 同一手法。
        for (Kind kind : pending.kinds) {
            if (ItemStack.isSameItemSameComponents(kind.sample, sample)) {
                kind.total += outcome.consumed();
                return;
            }
        }
        Kind kind = new Kind(sample.copyWithCount(1));
        kind.total = outcome.consumed();
        pending.kinds.add(kind);
    }

    /**
     * 每个 tick 冲刷一次：把攒下的汇总成<b>一条</b>发给对应玩家。
     *
     * <p>由 {@code VoidWarehouseExtension#onTick} 调用，且必须在「规则关着就提前 return」
     * 之前 —— 规则刚被关掉时，攒下的那几条也要发出去，不能吞掉。</p>
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            // 常态：没在打印。连时间都不取，这条路零成本
            return;
        }
        long now = System.currentTimeMillis();
        // 「仓库里现在还剩多少」要现读。同一次冲刷里多个玩家共用一份 data
        // （取数据本身有磁盘 / 闸门开销，能省就省）。
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
                pending.player.sendSystemMessage(summary(kinds, data,
                        VoidWarehouseText.isEnglish(pending.player)));
            } catch (RuntimeException ignored) {
                // 发不出去（玩家正在断开、别的模组接管了聊天通道）不能反过来影响打印
            }
        }
    }

    /** 限流判据：窗口到了才发。纯函数，方便自检直接咬 */
    static boolean due(long lastSentAt, long now) {
        return now - lastSentAt >= MESSAGE_INTERVAL_MS;
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
     * <p>与取货提示（{@code 取走 64 个 石头，仓库剩余 1984 个}）同一套配色
     * （{@link VoidWarehouseText#info}：{@code [虚空仓库] } 前缀 + 浅灰正文 + 亮绿数字），
     * 只是把动词换成「放置消耗」，并且为了能在一句里报好几种，
     * 把库存数字用括号绑在各自物品后面 —— 只有绑在一起才不会让人对错行：</p>
     * <ul>
     *   <li>一种：{@code 放置消耗 64 个 石头（仓库剩余 1984 个）}</li>
     *   <li>多种：{@code 放置消耗 64 个 石头（仓库剩余 1984 个）、32 个 圆石（仓库剩余 64 个）}</li>
     *   <li>超过 4 种：{@code 放置消耗 5 种共 120 个}</li>
     * </ul>
     *
     * <p>折成「N 种共 M 个」时<b>故意不报库存</b>：那时没有「那一种物品」，
     * 逐种报会把提示拉成一堵墙。</p>
     *
     * @param kinds 这一轮要报的物品
     * @param data  仓库数据，用来现读库存；{@code null} = 读不到，此时只报扣减量
     * @param en    接收玩家的客户端语言是不是英语
     */
    static Component summary(List<Kind> kinds, VoidWarehouseData data, boolean en) {
        MutableComponent body = VoidWarehouseText.body(en ? "Placed " : "放置消耗 ");
        if (kinds.size() > MAX_LISTED_KINDS) {
            long total = 0L;
            for (Kind kind : kinds) {
                total += kind.total;
            }
            return VoidWarehouseText.info(en, body
                    .append(VoidWarehouseText.key(VoidWarehouseText.count(kinds.size())))
                    .append(VoidWarehouseText.body(en ? " kinds, " : " 种共 "))
                    .append(VoidWarehouseText.key(VoidWarehouseText.count(total)))
                    .append(VoidWarehouseText.body(en ? " items" : " 个")));
        }
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            if (i > 0) {
                body.append(VoidWarehouseText.body(en ? "; " : "、"));
            }
            body.append(VoidWarehouseText.key(VoidWarehouseText.count(kind.total)))
                    .append(VoidWarehouseText.body(en ? " " : " 个 "))
                    // 物品名保持可翻译组件原样，本地化交给客户端
                    .append(VoidWarehouseText.body(kind.sample.getHoverName()));
            BigInteger after = afterCount(data, kind);
            if (after != null) {
                body.append(VoidWarehouseText.body(en ? " (stock remaining " : "（仓库剩余 "))
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(after)))
                        .append(VoidWarehouseText.body(en ? " items)" : " 个）"));
            }
        }
        return VoidWarehouseText.info(en, body);
    }

    /**
     * 这种物品此刻在仓库里还剩多少（已扣掉本次放置）。
     *
     * <p>口径必须与 {@link #record} 的分组一致 —— {@code amountExact} 内部同样用
     * {@link ItemStack#isSameItemSameComponents}，所以「一条提示里的一项」正好对上
     * 「仓库里的一格」。用 {@code amountOf(Item)} 那种只按物品查的宽口径，
     * 会把不同数据组件的几格加在一起，报出来的数对不上玩家打开仓库看到的那一行。</p>
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
        long total;

        Kind(ItemStack sample) {
            this.sample = sample;
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
}
