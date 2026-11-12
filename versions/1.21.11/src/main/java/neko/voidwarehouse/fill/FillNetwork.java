package neko.voidwarehouse.fill;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import neko.voidwarehouse.VoidWarehouseMenu;
import neko.voidwarehouse.VoidWarehouseSettings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link FillRequestPayload} 挂到网络上：一条 C2S 通道 + 一个接收端。
 *
 * <p>注册写法、限流表、{@code DISCONNECT} 清理全部照抄
 * {@code neko.voidwarehouse.search.SearchNetwork} 的先例（那一套又是从 restock 抄的）。
 * 唯一的实质差异是：这里<b>一定会改状态</b>（扣仓库、动背包），所以校验必须更严 ——
 * 见 {@link FillRequestPayload} 的「四道校验」。</p>
 *
 * <h2>线程安全</h2>
 * <p>Fabric 保证 {@code PlayPayloadHandler} 跑在服务端主线程，可以直接读
 * {@code player.containerMenu} / 背包 / 仓库数据，不需要 {@code server.execute}。</p>
 *
 * <h2>版本差异（铺版本时要改这两行）</h2>
 * <p>1.21.11 的 fabric-api 叫 {@code playC2S()}；26.x（6.3.x）叫 {@code serverboundPlay()}（本文件是 1.21.11 的写法）。</p>
 */
public final class FillNetwork {

    /**
     * 同一玩家两次请求之间的最短毫秒间隔。
     *
     * <p>与取货同档 50ms：这是有副作用的操作，而且一次就可能搬动上千个物品。
     * 正常玩家点不出比这更快的节奏（连点两下 Ctrl+Alt+左键最多丢掉第二下），
     * 但脚本刷包会被压住。</p>
     */
    private static final long FILL_INTERVAL_MS = 50L;

    private static final Map<UUID, Long> LAST_FILL_AT = new ConcurrentHashMap<>();

    private FillNetwork() {
    }

    /**
     * 在模组初始化时调用一次（{@code AutoMendingMod#onInitialize}）。
     *
     * <p><b>C2S 只由服务端注册这一份</b>：客户端模组自己幂等注册（撞名吞掉），
     * 与 restock / search 的 C2S 双端注册是同一条红线 —— 专用服务器上两端是两个进程，
     * 客户端不发自己那份注册就退化成 {@code DiscardedPayload}，包整包丢掉。</p>
     */
    public static void init() {
        PayloadTypeRegistry.playC2S().register(
                FillRequestPayload.TYPE, FillRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                FillRequestPayload.TYPE, FillNetwork::handleFill);

        // 玩家下线就抹掉限流记录，长期运行的服务器不攒离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_FILL_AT.remove(player.getUUID());
            }
        });
    }

    /**
     * 收包 → 四道校验 → 交给菜单。
     *
     * <p>任何一道不过就<b>静默丢弃</b>：这条通道是「我点了一下」，不是请求应答，
     * 没有客户端在等回执。真要报错的地方（背包满 / 仓库不够）由
     * {@code VoidWarehouseWithdraw#fill} 自己发一条聊天提示。</p>
     */
    private static void handleFill(FillRequestPayload payload,
                                   ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (player == null || throttled(player)) {
            return;
        }
        if (!VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        // 🔴 必须真的是虚空仓库界面：别的容器（箱子/漏斗/自己的工作台）一律不认。
        //    没有这一条，任何人都能在开着自己背包时发一个 slotId 来碰仓库。
        if (!(player.containerMenu instanceof VoidWarehouseMenu menu)) {
            return;
        }
        menu.fillToBackpack(player, payload.slotId());
    }

    /**
     * @return {@code true} = 发得太快，本次请求不作数（不更新时间戳，
     *         必须先退够时间才可能再成功 —— 与 restock / search 同一个取舍）
     */
    private static boolean throttled(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        Long previous = LAST_FILL_AT.get(id);
        if (previous != null && now - previous < FILL_INTERVAL_MS) {
            return true;
        }
        LAST_FILL_AT.put(id, now);
        return false;
    }
}
