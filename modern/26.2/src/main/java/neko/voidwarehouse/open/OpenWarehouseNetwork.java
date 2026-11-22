package neko.voidwarehouse.open;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 把「按键打开虚空仓库」这条通道挂到网络上：注册两个 payload + 一个 C2S 接收端。
 *
 * <p>通道：
 * <ul>
 *   <li>{@code carpet_neko_addition:open_warehouse}（C2S）：客户端按键 → 服务端</li>
 *   <li>{@code carpet_neko_addition:open_warehouse_result}（S2C）：服务端回执</li>
 * </ul></p>
 *
 * <h2>为什么要限流</h2>
 * <p>这是<b>玩家按键</b>触发的通道，正常频率极低（一次按键一次）。但客户端一旦写错
 * （例如放在 {@code ClientTickEvents} 里每 tick 发、或按键状态没做边沿检测），
 * 20 次/秒的服务端开界面调用会把玩家折腾得无法游戏（每次都在重建菜单）。
 * 所以加一道「同一玩家 {@value #MIN_INTERVAL_MS} ms 内只放行一次」的闸门，
 * 与 {@code PrinterRestockNetwork} 的取货限流同一个思路，但<b>分表</b> ——
 * 两条通道的语义完全不同，共用一张表会互相拖后腿。</p>
 *
 * <h2>注册顺序</h2>
 * <p>本模组主体在服务端、初始化更早，所以由这里注册。neko-client 侧的 S2C 注册要写成
 * 「表里已有就跳过」的幂等形式，双保险（同 {@code PrinterRestockNetwork}）。</p>
 */
public final class OpenWarehouseNetwork {

    /** 同一玩家两次开界面请求的最小间隔。按键正常不会有这个频率，纯粹防客户端写错。 */
    private static final long MIN_INTERVAL_MS = 250L;

    /**
     * 限流表：UUID → 上次放行的时间戳。
     *
     * <p>用 {@link WeakHashMap} 不合适（UUID 会被当垃圾丢掉，限流形同不存在），
     * 用普通 {@code HashMap}，靠 {@code DISCONNECT} 事件逐玩家清理，
     * 与 {@code PrinterRestockNetwork} 完全一致。</p>
     */
    private static final Map<UUID, Long> LAST_REQUEST_AT = new java.util.HashMap<>();

    private OpenWarehouseNetwork() {
    }

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(OpenWarehousePayload.TYPE, OpenWarehousePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                OpenWarehouseResultPayload.TYPE, OpenWarehouseResultPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(OpenWarehousePayload.TYPE, OpenWarehouseNetwork::handle);

        // 玩家下线就把限流记录抹掉，否则长期运行的服务器会一直攒着离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_REQUEST_AT.remove(player.getUUID());
            }
        });
    }

    private static void handle(OpenWarehousePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        int nonce = payload.nonce();

        if (isThrottled(player)) {
            reply(player, nonce, OpenWarehouseResultPayload.THROTTLED);
            return;
        }
        int status = OpenWarehouseService.open(context.server(), player);
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
            // 撞限流时不更新时间戳：更新了的话，疯狂发包的客户端会稳定地每隔
            // MIN_INTERVAL_MS 成功一次，反而形成规律放行的节奏。
            return true;
        }
        LAST_REQUEST_AT.put(id, now);
        return false;
    }

    /**
     * 把结果回执出去。
     *
     * <p>发之前先问 {@code canSend}：对端没装 neko-client（或那个版本还没有这条通道）
     * 就别发，否则会往一个没人接收的通道塞包。</p>
     */
    private static void reply(ServerPlayer player, int nonce, int status) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, OpenWarehouseResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new OpenWarehouseResultPayload(nonce, status));
    }
}
