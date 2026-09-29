package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link PrinterTakeService} 挂到网络上：注册两个 payload + 一个 C2S 接收端。
 *
 * <h2>线程安全</h2>
 * <p>Fabric networking 的 {@code PlayPayloadHandler} <b>保证跑在服务端主线程</b>：
 * 包从 netty 线程进来时，底层 {@code AbstractChanneledNetworkAddon.handle} 会抛
 * {@code RunningOnDifferentThreadException}，由 mixin 捕获后重新 {@code server.execute(...)}
 * 再走一遍。第二次就已经在主线程了，所以这里可以直接读写背包与仓库数据，
 * 不需要自己再包一层 {@code execute}。</p>
 *
 * <h2>为什么要限流</h2>
 * <p>每一次请求都会真的写一次 SavedData、并推一整套背包差分。
 * 客户端万一写错（或者被人改过）而疯狂发包，服务端会白烧 CPU。
 * 超限的请求直接回 {@code THROTTLED}，让客户端知道该退避，而不是傻等到超时。</p>
 */
public final class PrinterRestockNetwork {

    /**
     * 同一玩家两次取货请求之间的<b>最短毫秒间隔</b>。
     *
     * <p>50ms → 上限 20 次/秒 → 每秒最多领走 20 组。真实建造根本用不到这个速度
     * （打印机只在缺料时请求一次），留这么大余量是为了不拖慢正常打印，
     * 同时把脚本刷取压在一个可接受的水平。</p>
     */
    private static final long MIN_INTERVAL_MS = 50L;

    /**
     * 每个玩家上次请求的墙钟时间，用于限流。
     *
     * <p>用 {@link ConcurrentHashMap}：处理器虽然在主线程，但没必要拿
     * 「未来 Fabric 会不会改线程模型」去赌；这张表写多读少，并发容器在这里毫无负担。</p>
     */
    private static final Map<UUID, Long> LAST_REQUEST_AT = new ConcurrentHashMap<>();

    private PrinterRestockNetwork() {
    }

    /**
     * 在模组初始化时调用一次。
     *
     * <p>两个 {@code PayloadTypeRegistry} 的注册必须早：它们是客户端与服务端
     * 「自定义包能不能收发」的协商依据，晚于连接建立再注册就来不及了。</p>
     *
     * <h2>为什么<b>只</b>注册自家那两条通道</h2>
     * <p>本模组的 {@code environment} 是 {@code "*"}，也就是说它<b>也会在客户端进程里跑</b>
     * （单人存档、局域网主机、以及任何把 Carpet 模组丢进客户端 mods 的玩法都是这种情形）。
     * 早先这里顺手把 {@code litematica_printer:*} 那套也注册了，理由是「名字不同就不会撞」——
     * <b>这个理由是错的</b>：Fabric 的注册表在同一进程里只有一个，
     * 装了 {@code litematica-printer} 客户端模组时，两边会抢着注册同一个
     * {@code litematica_printer:printer_take_result}，后注册的一方拿到</p>
     * <pre>{@code IllegalArgumentException: Packet type ... is already registered!}</pre>
     * <p>而崩溃点会落在客户端的 {@code onInitializeClient}（启动即崩）。</p>
     *
     * <h2>两条命名空间的方向划分（这条是硬约束）</h2>
     * <p>C2S 与 S2C 是两套<b>各自独立</b>的注册表，各自按通道名去重。
     * 把「谁注册哪个方向」写错一格就会撞。</p>
     * <table border="1">
     *   <tr><th>通道</th><th>方向</th><th>注册方</th></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_take}</td><td>C2S</td><td>服务端（本模组）</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_take_result}</td><td>S2C</td><td>服务端（本模组）</td></tr>
     *   <tr><td>{@code litematica_printer:printer_take}</td><td>C2S</td><td>服务端（本模组）</td></tr>
     *   <tr><td>{@code litematica_printer:printer_take_result}</td><td>S2C</td><td><b>打印机客户端，本模组绝不注册</b></td></tr>
     * </table>
     * <p>服务端发这条回执并不需要先注册：{@code ServerPlayNetworking.send} 只做
     * 「构造包 + 交给连接发出」，不查本地注册表；是否需要发由
     * {@link ServerPlayNetworking#canSend} 依据客户端协商阶段上报的可接收集合决定。</p>
     *
     * <h2>版本差异</h2>
     * <p>1.21.11 的 fabric-api 这里叫 {@code playC2S()} / {@code playS2C()}；
     * 26.x 的 fabric-api（6.3.x）重命名为 {@code serverboundPlay()} / {@code clientboundPlay()}。
     * 其余用法完全一致 —— 这是唯一一处跨版本的 networking API 差异。</p>
     */
    public static void init() {
        // ---- 通道 A：服务端自家的 carpet_neko_addition:* ----
        // C2S：打印机 → 服务端，取货请求
        PayloadTypeRegistry.playC2S().register(PrinterTakePayload.TYPE, PrinterTakePayload.CODEC);
        // S2C：服务端 → 打印机，取货回执（旧客户端仍在收这条）
        PayloadTypeRegistry.playS2C().register(PrinterTakeResultPayload.TYPE, PrinterTakeResultPayload.CODEC);

        // ---- 通道 B：打印机专属 litematica_printer:* ----
        // 只登记 C2S 一侧（服务端要「收」新版客户端的请求，解包必须有 codec）。
        // S2C 一侧（printer_take_result）刻意<b>不</b>登记 —— 所有权在客户端，理由见上。
        PayloadTypeRegistry.playC2S().register(PrinterTakePayload.PRINTER_TYPE, PrinterTakePayload.CODEC);

        // 两套 C2S 通道接到同一个处理器上 —— 请求语义完全相同，只是通道名不同
        ServerPlayNetworking.registerGlobalReceiver(PrinterTakePayload.TYPE, PrinterRestockNetwork::handle);
        ServerPlayNetworking.registerGlobalReceiver(PrinterTakePayload.PRINTER_TYPE, PrinterRestockNetwork::handle);

        // 玩家下线就把限流记录抹掉，否则长期运行的服务器会一直攒着离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_REQUEST_AT.remove(player.getUUID());
            }
        });
    }

    /**
     * 收到取货请求。
     *
     * <p>只做三件事：过限流 → 转交 {@link PrinterTakeService} → 把结果码原样发回去。
     * 具体判断都写在 service 里，这里保持薄，便于单独验证。</p>
     */
    private static void handle(PrinterTakePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        int nonce = payload.nonce();

        if (isThrottled(player)) {
            reply(player, nonce, PrinterTakeResultPayload.THROTTLED);
            return;
        }

        int status = PrinterTakeService.take(context.server(), player, payload.candidates());
        reply(player, nonce, status);
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

        Long previous = LAST_REQUEST_AT.get(id);
        if (previous != null && now - previous < MIN_INTERVAL_MS) {
            // 撞限流时<b>不</b>更新时间戳。更新了的话，疯狂发包的客户端会稳定地
            // 每隔 MIN_INTERVAL_MS 成功一次，反而形成规律放行的节奏；
            // 不更新才是「必须先老实退够 MIN_INTERVAL_MS 才可能再成功」。
            return true;
        }
        LAST_REQUEST_AT.put(id, now);
        return false;
    }

    /**
     * 把结果按「客户端能收哪条就发哪条」回执出去。
     *
     * <p>两条 S2C 通道的归属不同：{@code carpet_neko_addition:printer_take_result}
     * 是本模组注册的（老客户端认这条）；{@code litematica_printer:printer_take_result}
     * 由打印机客户端自己注册。所以先问 {@code canSend}——它查的是客户端在
     * 通道协商阶段上报的可接收集合，等价于「对面到底登记了哪个类型」。</p>
     */
    private static void reply(ServerPlayer player, int nonce, int status) {
        if (player == null) {
            return;
        }
        PrinterTakeResultPayload payload = new PrinterTakeResultPayload(nonce, status);
        ServerPlayNetworking.send(player, payload);
        if (ServerPlayNetworking.canSend(player, PrinterTakeResultPayload.PRINTER_TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
