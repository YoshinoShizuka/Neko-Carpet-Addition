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
     */
    public static void init() {
        // ---- 通道 A：服务端自家的 carpet_neko_addition:*（旧客户端仍在用）----
        // C2S：打印机 → 服务端，取货请求
        PayloadTypeRegistry.serverboundPlay().register(PrinterTakePayload.TYPE, PrinterTakePayload.CODEC);
        // S2C：服务端 → 打印机，取货回执
        PayloadTypeRegistry.clientboundPlay().register(PrinterTakeResultPayload.TYPE, PrinterTakeResultPayload.CODEC);

        // ---- 通道 B：打印机专属的 litematica_printer:* ----
        // 客户端模组也装在客户端上时，它会注册自己那套；这两套名字不同，
        // 所以既不会撞「already registered」，也不会撞 codec。
        PayloadTypeRegistry.serverboundPlay().register(PrinterTakePayload.PRINTER_TYPE, PrinterTakePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PrinterTakeResultPayload.PRINTER_TYPE, PrinterTakeResultPayload.CODEC);

        // 两套 C2S 通道都接到同一个处理器上 —— 请求本身的语义完全相同
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

    private static void reply(ServerPlayer player, int nonce, int status) {
        if (player == null) {
            return;
        }
        if (player == null) {
            return;
        }
        PrinterTakeResultPayload payload = new PrinterTakeResultPayload(nonce, status);
        // 沿「请求走哪条通道、回执就走哪条」的对应关系发回去。
        // 客户端只登记了它自己那一条的处理方式，发错通道的包在它那边是未知类型，会被丢弃。
        ServerPlayNetworking.send(player, payload);
        if (ServerPlayNetworking.canSend(player, PrinterTakeResultPayload.PRINTER_TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
