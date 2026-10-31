package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link PrinterReturnService} 挂到网络上：注册两个 payload + 一个 C2S 接收端。
 *
 * <p><b>这份是 1.21.11 专用</b>：fabric-api 在这里把注册入口叫 {@code playC2S()} / {@code playS2C()}，
 * 26.x 的 fabric-api 6.3.x 改名为 {@code serverboundPlay()} / {@code clientboundPlay()}。
 * 除了这两行，本文件与 26.x 那份逐字节相同 —— 保持分开而不是加预处理标记，
 * 是因为本仓库（carpet-Neko-Addition）从来不用 preprocess，各版本源码本来就是独立副本。</p>
 *
 * <h2>与取货分开成两条网络的理由</h2>
 * <p>取货那条路已经在现网跑过；返还是新能力，独立成类就不会在改动时牵连到已验证的路径。
 * 通道名、限流表也都分开：返还紧跟着取货发生，共用一张限流表会让两件事互相拖后腿。</p>
 *
 * <h2>为什么不发聊天提示</h2>
 * <p>返还是后台清理，一次建造可能退几十批，每条都发会把聊天栏刷爆。
 * 仓库数量玩家随时可以开界面看。</p>
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
     * <table border="1">
     *   <tr><th>通道</th><th>方向</th><th>注册方</th></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return}</td><td>C2S</td>
     *       <td>服务端（本模组），客户端幂等补位</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return_result}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     * </table>
     *
     * <p>C2S 这条客户端也要注册：真正的多人环境里客户端进程有自己的
     * {@code SERVERBOUND_PLAY} 表，没登记过的类型在编码阶段会退化成
     * {@code DiscardedPayload}（数据整包丢掉）。两边都注册、撞名异常吞掉，
     * 单人（服务端先注册）与多人（客户端自己注册）两条路才都能编码。</p>
     */
    public static void init() {
        PayloadTypeRegistry.playC2S().register(PrinterReturnPayload.TYPE, PrinterReturnPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);

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
            reply(player, nonce, PrinterReturnResultPayload.THROTTLED, 0);
            return;
        }

        PrinterReturnService.ReturnOutcome outcome =
                PrinterReturnService.returnSlots(context.server(), player, payload.slots());
        reply(player, nonce, outcome.status(), outcome.returnedCount());
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
            // 撞限流时不更新时间戳：更新了就等于「疯狂发包的客户端反而能稳定按节奏放行」。
            return true;
        }
        LAST_RETURN_AT.put(id, now);
        return false;
    }

    /**
     * 把结果回执出去。
     *
     * <p>{@code canSend} 查的是对端上报的可接收集合 —— 对面是很旧、还没有这条通道的
     * 打印机版本就别发：老客户端收到不认识的包会直接断连。</p>
     */
    private static void reply(ServerPlayer player, int nonce, int status, int returned) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new PrinterReturnResultPayload(nonce, status, returned));
    }
}
