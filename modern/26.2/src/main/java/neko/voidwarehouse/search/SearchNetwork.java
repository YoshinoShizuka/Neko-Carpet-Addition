package neko.voidwarehouse.search;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import neko.voidwarehouse.VoidWarehouseMenu;
import neko.voidwarehouse.VoidWarehouseSettings;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link SearchService} 挂到网络上：注册三条通道 + 两个 C2S 接收端。
 *
 * <table border="1">
 *   <tr><th>通道</th><th>方向</th><th>内容</th><th>限流</th></tr>
 *   <tr><td>{@code search_hello}</td><td>C2S</td><td>空（探测 + 要目录）</td><td>100ms</td></tr>
 *   <tr><td>{@code search_catalog}</td><td>S2C</td><td>仓库物品 id 目录</td><td>——</td></tr>
 *   <tr><td>{@code search_apply}</td><td>C2S</td><td>匹配集合（空 = 清除）</td><td>50ms</td></tr>
 * </table>
 *
 * <h2>与 restock 三对通道的同与不同</h2>
 * <p>注册写法、{@code canSend} 判定、限流表结构全部照抄
 * {@code PrinterQueryNetwork} / {@code PrinterReturnNetwork} 的先例。
 * 关键差异是<b>处理器必须确认玩家当前开的菜单是 VoidWarehouseMenu</b>：
 * 目录跟随菜单走（问的是「我现在开着的这个界面」），过滤也只作用于那一个菜单 ——
 * 纯原版客户端永远不发这两条包，改过的客户端问到的也只是自己仓库的目录。</p>
 *
 * <h2>线程安全</h2>
 * <p>Fabric 保证 {@code PlayPayloadHandler} 跑在服务端主线程，可以直接读
 * {@code player.containerMenu} 与仓库数据，不需要 {@code server.execute}。</p>
 *
 * <h2>版本差异（与 restock 同款）</h2>
 * <p>1.21.11 的 fabric-api 叫 {@code playC2S()} / {@code playS2C()}；
 * 26.x（6.3.x）重命名为 {@code serverboundPlay()} / {@code clientboundPlay()}。
 * 只出 26.3 时不用管；将来铺版本时这里要打补丁。</p>
 */
public final class SearchNetwork {

    /**
     * 同一玩家两次 hello 之间的最短毫秒间隔。
     *
     * <p>客户端的正常节奏是「打开界面发一次 + 重新聚焦搜索框时刷新一次」，
     * 100ms 比取货的 50ms 宽一倍，足够把脚本刷包压住。</p>
     */
    private static final long HELLO_INTERVAL_MS = 100L;

    /** 同一玩家两次 apply 之间的最短毫秒间隔（与取货/返还同档：紧跟输入发生的重活） */
    private static final long APPLY_INTERVAL_MS = 50L;

    private static final Map<UUID, Long> LAST_HELLO_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_APPLY_AT = new ConcurrentHashMap<>();

    private SearchNetwork() {
    }

    /**
     * 在模组初始化时调用一次（{@code AutoMendingMod#onInitialize}）。
     *
     * <p><b>C2S 只由服务端注册这一份</b>：客户端模组自己幂等注册（撞名吞掉），
     * 与 restock 的 C2S 双端注册是同一条红线 —— 专用服务器上两端是两个进程，
     * 客户端不发自己那份注册就退化成 {@code DiscardedPayload}，包整包丢掉。</p>
     */
    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(
                SearchHelloPayload.TYPE, SearchHelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                SearchApplyPayload.TYPE, SearchApplyPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                SearchCatalogPayload.TYPE, SearchCatalogPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                SearchHelloPayload.TYPE, SearchNetwork::handleHello);
        ServerPlayNetworking.registerGlobalReceiver(
                SearchApplyPayload.TYPE, SearchNetwork::handleApply);

        // 玩家下线就抹掉限流记录，长期运行的服务器不攒离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                LAST_HELLO_AT.remove(player.getUUID());
                LAST_APPLY_AT.remove(player.getUUID());
            }
        });
    }

    /**
     * hello：玩家正开着虚空仓库界面才回目录，否则静默忽略。
     *
     * <p>「静默」是刻意的：客户端拿不到目录就不显示搜索框，
     * 不需要额外的「你不是虚空仓库」回执（那等于多一条通道，还把
     * 「这个箱子不是虚空仓库」这个信息主动告诉了可疑客户端）。</p>
     */
    private static void handleHello(SearchHelloPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (throttled(LAST_HELLO_AT, HELLO_INTERVAL_MS, player)) {
            return;
        }
        if (!VoidWarehouseSettings.voidWarehouse
                || !(player.containerMenu instanceof VoidWarehouseMenu menu)) {
            return;
        }
        send(player, new SearchCatalogPayload(SearchService.catalog(menu.data())));
    }

    /**
     * apply：把匹配集合挂到玩家当前打开的那个菜单上（空集合 = 清除）。
     *
     * <p>菜单里做全量重发（{@code sendAllDataToRemote}），与翻页/排序切换同一条路 ——
     * 客户端对「整页换人」的任何预测残留都会被绝对真相覆盖。</p>
     */
    private static void handleApply(SearchApplyPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (throttled(LAST_APPLY_AT, APPLY_INTERVAL_MS, player)) {
            return;
        }
        if (!VoidWarehouseSettings.voidWarehouse
                || !(player.containerMenu instanceof VoidWarehouseMenu menu)) {
            return;
        }
        Set<Item> filter = SearchService.resolveFilter(payload.itemIds());
        menu.applySearchFilter(filter);
    }

    /**
     * @return {@code true} = 发得太快，本次请求不作数（不更新时间戳，
     *         必须先退够时间才可能再成功 —— 与 restock 同一个取舍）
     */
    private static boolean throttled(Map<UUID, Long> table, long intervalMs, ServerPlayer player) {
        if (player == null) {
            return false;
        }
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        Long previous = table.get(id);
        if (previous != null && now - previous < intervalMs) {
            return true;
        }
        table.put(id, now);
        return false;
    }

    /** 发之前先问 {@code canSend}：对端没这条通道就别发，省一次编码 */
    private static void send(ServerPlayer player, SearchCatalogPayload payload) {
        if (player == null
                || !ServerPlayNetworking.canSend(player, SearchCatalogPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }
}
