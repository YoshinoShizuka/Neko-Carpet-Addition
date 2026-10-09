package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link PrinterQueryService} 挂到网络上：注册两个 payload + 一个 C2S 接收端。
 *
 * <p>结构和 {@link PrinterRestockNetwork} 是一对 —— 那边管「取货」，这边管「看库存」。
 * 分成两个类而不是塞进一个是刻意的：两条通道的<b>限流档位完全不同</b>（取货 50ms，
 * 查询 200ms），混在一起就得在一个方法里判断「这次是哪种请求」，很快就会变成一团。</p>
 *
 * <h2>线程安全</h2>
 * <p>同 {@link PrinterRestockNetwork}：Fabric 保证 {@code PlayPayloadHandler} 跑在
 * 服务端主线程，所以这里可以直接读仓库数据，不需要自己包一层 {@code server.execute}。</p>
 *
 * <h2>限流为什么比取货还宽</h2>
 * <p>取货要写存档 + 推背包差分，是重活；查询只遍历一遍内存里的仓库、回一个包，
 * 而且<b>不改任何状态</b>。200ms 的间隔（5 次/秒）已经比客户端的刷新节奏（5 秒一次）
 * 宽了一整个数量级，纯粹是防「改过的客户端疯狂发包」。</p>
 */
public final class PrinterQueryNetwork {

    /**
     * 同一玩家两次查询之间的<b>最短毫秒间隔</b>。
     *
     * <p>客户端的正常节奏是 5 秒一次，这里留 200ms 是给「多开了几个界面 / 切换投影时
     * 连问两次」留的余量，同时把脚本刷取压住。</p>
     */
    private static final long MIN_INTERVAL_MS = 200L;

    private static final Map<UUID, Long> LAST_REQUEST_AT = new ConcurrentHashMap<>();

    private PrinterQueryNetwork() {
    }

    /**
     * 在模组初始化时调用一次。
     *
     * <table border="1">
     *   <tr><th>通道</th><th>方向</th><th>注册方</th></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_query}</td><td>C2S</td><td>服务端（本模组）</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_query_result}</td><td>S2C</td><td>服务端（本模组），客户端幂等补位</td></tr>
     * </table>
     *
     * <p><b>客户端这次会自己注册 C2S</b>（幂等形式），与取货通道不同。原因在
     * {@link PrinterQueryPayload} 之外单说：Fabric 的 API 文档写得很明确 ——
     * 「{@code ClientPlayNetworking} 发出的包必须<b>两端</b>都注册到 serverbound 那张表」。
     * 单人存档里「反正服务端已经注册过」的巧合在<b>专用服务器</b>上不成立
     * （两端是两个进程，客户端那张表是空的），那时 C2S 会被编成原版的
     * {@code DiscardedPayload} —— 不崩，但内容全丢。</p>
     *
     * <h2>版本差异（唯一一处）</h2>
     * <p>1.21.11 的 fabric-api 把这两张表叫 {@code playC2S()} / {@code playS2C()}；
     * 26.x 的 fabric-api（6.3.x）重命名为 {@code serverboundPlay()} /
     * {@code clientboundPlay()}。其余用法完全一致 —— 与 {@link PrinterRestockNetwork}
     * 是同款差异，同步脚本里打两行补丁即可。</p>
     */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(
                PrinterQueryPayload.TYPE, PrinterQueryPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                PrinterQueryResultPayload.TYPE, PrinterQueryResultPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                PrinterQueryPayload.TYPE, PrinterQueryNetwork::handle);

        // 玩家下线就抹掉限流记录，否则长期运行的服务器会一直攒着离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_REQUEST_AT.remove(player.getUUID());
            }
        });
    }

    /**
     * 收到一次查询。
     *
     * <p>只做三件事：过限流 → 转交 {@link PrinterQueryService} → 回包。</p>
     */
    private static void handle(PrinterQueryPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        int nonce = payload.nonce();

        if (isThrottled(player)) {
            reply(player, PrinterQueryResultPayload.of(nonce, PrinterQueryResultPayload.THROTTLED));
            return;
        }

        List<String> requested = payload.itemIds();
        if (requested == null || requested.isEmpty()) {
            reply(player, PrinterQueryResultPayload.of(nonce, PrinterQueryResultPayload.BAD_REQUEST));
            return;
        }

        PrinterQueryService.Snapshot snapshot =
                PrinterQueryService.query(context.server(), requested);
        if (!snapshot.available()) {
            reply(player, PrinterQueryResultPayload.of(nonce, PrinterQueryResultPayload.RULE_OFF));
            return;
        }
        reply(player, new PrinterQueryResultPayload(
                nonce, PrinterQueryResultPayload.OK, snapshot.itemIds(), snapshot.amounts()));
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
            // 与取货那边同一个取舍：撞限流时<b>不</b>更新时间戳，否则疯狂发包会形成
            // 规律放行的节奏。不更新才是「必须先退够时间才可能再成功」。
            return true;
        }
        LAST_REQUEST_AT.put(id, now);
        return false;
    }

    /**
     * 把结果回执出去。
     *
     * <p>发之前先问 {@code canSend}：对端没装对应的打印机（或者版本太老、压根没这条通道）时
     * 就别发，省一次编码。</p>
     */
    private static void reply(ServerPlayer player, PrinterQueryResultPayload payload) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterQueryResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }
}
