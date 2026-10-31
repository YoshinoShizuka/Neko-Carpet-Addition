package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link PrinterReturnService} 挂到网络上：注册三个 payload + 一个 C2S 接收端。
 *
 * <p>与 {@code PrinterRestockNetwork}（取货）刻意分成两个类，理由是互不牵连：
 * 取货那条路已经在现网跑过，任何改动都要重新验一遍；返还是一条新能力，
 * 只要它自己的五个注册项不出错，就不会碰到取货。两边的通道名、限流表也都是分开的
 * （返还紧接着取货发生，共用一张限流表会让两件事互相拖后腿）。</p>
 *
 * <h2>限流为什么和取货一样是 50ms</h2>
 * <p>一次返还 = 一次 SavedData 写盘 + 一整套背包差分，成本和取货同级。
 * 但两者的节奏不同：取货是「缺料才发」，本来就稀疏；返还是「背包满了就退一堆」，
 * 打印机疯狂刷方块时可能连续触发。所以这里的表<b>独立</b>，
 * 保证返还撞限流时不会连带把取货也卡住。</p>
 *
 * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>取货只有成功且真的放了东西才发一条（玩家需要知道仓库在给他补货），
 * 而返还是<b>后台清理</b> —— 一次建造可能退几十批，服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>发出去（逐物品件数与仓库剩余量走
 * {@link PrinterReturnDetailPayload}，销账用的总量走 {@link PrinterReturnResultPayload}），
 * 提示由客户端自己拼、自己控节奏。</p>
 *
 * <h2>回执为什么拆成两条通道</h2>
 * <p>明细本来可以追加进 {@link PrinterReturnResultPayload} 的末尾，但那样
 * <b>新客户端配旧服务端</b>会解码失败（旧服务端后面没有字节）。拆成独立通道后，
 * 认不认得出这条通道由 {@code canSend} 在发包前逐个判定 —— 新旧客户端的任意组合都安全，
 * 而且两个包各自演进互不影响。</p>
 */
public final class PrinterReturnNetwork {

    /** 同一玩家两次返还请求之间的最短毫秒间隔（与取货同量级）。 */
    private static final long MIN_INTERVAL_MS = 50L;

    /** 每个玩家上次返还请求的墙钟时间。写多读少，用并发容器最省心。 */
    private static final Map<UUID, Long> LAST_RETURN_AT = new ConcurrentHashMap<>();

    private PrinterReturnNetwork() {
    }

    /**
     * 在模组初始化时调用一次。
     *
     * <h2>通道分配</h2>
     * <table border="1">
     *   <tr><th>通道</th><th>方向</th><th>注册方</th></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return}</td><td>C2S</td>
     *       <td>服务端（本模组），客户端幂等补位</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return_result}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return_detail}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     * </table>
     *
     * <p>C2S 这条客户端<b>为什么也要注册</b>：与取货不同 —— 取货那条路径当年只在
     * 单人环境验过（同进程时服务端的注册表就是客户端那张，编码查得到 codec）。
     * 到了真正的多人环境，客户端进程里那张 {@code SERVERBOUND_PLAY} 表是<b>自己</b>的，
     * 没登记过的类型在编码阶段会退化成 {@code DiscardedPayload}（数据整个丢掉）。
     * 所以这里两边都注册，撞名异常吞掉：单人时服务端先注册、多人时客户端自己注册，
     * 两条路都能编码。</p>
     *
     * <h2>版本差异</h2>
     * <p>1.21.11 的 fabric-api 叫 {@code playC2S()} / {@code playS2C()}；
     * 26.x 的 fabric-api（6.3.x）改名为 {@code serverboundPlay()} / {@code clientboundPlay()}。
     * 这是唯一一处跨版本差异，所以每个版本的这份文件只有 register 两行不同。</p>
     */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(PrinterReturnPayload.TYPE, PrinterReturnPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                PrinterReturnDetailPayload.TYPE, PrinterReturnDetailPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(PrinterReturnPayload.TYPE, PrinterReturnNetwork::handle);

        // 玩家下线就把限流记录抹掉，否则长期运行的服务器会一直攒着离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_RETURN_AT.remove(player.getUUID());
            }
        });
    }

    /**
     * 收到返还请求：过限流 → 转交 {@link PrinterReturnService} → 回执。
     *
     * <p>服务端主线程保证见 {@code PrinterRestockNetwork} 的类注释
     * （Fabric 的 {@code PlayPayloadHandler} 会把包重新丢回服务端主线程），
     * 所以这里可以直接读写背包与仓库数据。</p>
     */
    private static void handle(PrinterReturnPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        int nonce = payload.nonce();

        if (isThrottled(player)) {
            reply(player, PrinterReturnResultPayload.of(nonce, PrinterReturnResultPayload.THROTTLED));
            return;
        }

        PrinterReturnService.ReturnOutcome outcome =
                PrinterReturnService.returnSlots(context.server(), player, payload.slots());
        if (outcome.status() == PrinterReturnResultPayload.OK) {
            // 顺序有讲究：明细必须走在回执前面。同一连接上的包是有序的，
            // 客户端处理回执时明细已经在手里，不需要「等一等再拼提示」这种时序把戏。
            sendDetail(player, nonce, outcome);
        }
        reply(player, new PrinterReturnResultPayload(nonce, outcome.status(), outcome.returnedCount()));
    }

    /**
     * 成功路径：把那批的逐物品明细单独发出去。
     *
     * <p>明细不塞进回执的理由见 {@link PrinterReturnDetailPayload}。这里用 {@code canSend}
     * 逐个通道判定：对面是很旧、还没有这条通道的客户端时就不发，它照样能靠回执销账，
     * 只是看不到「退了什么、还剩多少」。</p>
     *
     * <p>三个清单一次性从 {@code moved} 摊平 —— 服务端算好、客户端只负责显示，
     * 谁都不用在客户端重算「仓库还剩多少」。</p>
     */
    private static void sendDetail(ServerPlayer player, int nonce, PrinterReturnService.ReturnOutcome outcome) {
        List<PrinterReturnService.Moved> moved = outcome.moved();
        if (player == null || moved.isEmpty()) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnDetailPayload.TYPE)) {
            return;
        }
        List<String> itemIds = new ArrayList<>(moved.size());
        List<Integer> counts = new ArrayList<>(moved.size());
        List<Long> remainings = new ArrayList<>(moved.size());
        for (PrinterReturnService.Moved entry : moved) {
            itemIds.add(entry.itemId());
            counts.add(entry.count());
            remainings.add(entry.remaining());
        }
        ServerPlayNetworking.send(player,
                new PrinterReturnDetailPayload(nonce, itemIds, counts, remainings));
    }

    /**
     * @return {@code true} = 发得太快，本次请求不作数
     */
    private static boolean isThrottled(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();

        Long previous = LAST_RETURN_AT.get(id);
        if (previous != null && now - previous < MIN_INTERVAL_MS) {
            // 撞限流时不更新时间戳：更新了就等于「疯狂发包的客户端反而能稳定按节奏放行」，
            // 不更新才是「必须先老实退够 MIN_INTERVAL_MS 才可能再成功」。
            return true;
        }
        LAST_RETURN_AT.put(id, now);
        return false;
    }

    /**
     * 把结果回执出去。
     *
     * <p>{@code canSend} 查的是对端在通道协商阶段上报的可接收集合 ——
     * 对面没装打印机（或者是很旧、还没有这条通道的版本）就干脆别发。
     * 这条判断对返还尤其重要：老版本客户端收到一个不认识的包会直接断连。</p>
     */
    private static void reply(ServerPlayer player, PrinterReturnResultPayload payload) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }
}
