package neko.voidwarehouse.recipe;

import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseMenu;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 {@link RecipeService} 挂到网络上：注册七条通道 + 三个 C2S 接收端 + 一条变更广播。
 *
 * <table border="1">
 *   <tr><th>通道</th><th>方向</th><th>内容</th><th>限流</th></tr>
 *   <tr><td>{@code recipe_hello}</td><td>C2S</td><td>空（能力探测 + 要索引）</td><td>100 ms</td></tr>
 *   <tr><td>{@code recipe_index}</td><td>S2C</td><td>产物 id 清单 + 两个总数 + canDelete</td><td>——</td></tr>
 *   <tr><td>{@code recipe_query}</td><td>C2S</td><td>页码 / 页大小 / 排序 / 产物白名单</td><td>50 ms</td></tr>
 *   <tr><td>{@code recipe_page}</td><td>S2C</td><td>一页明细</td><td>——</td></tr>
 *   <tr><td>{@code recipe_delete}</td><td>C2S</td><td>recipeId 列表（≤ 64）</td><td>1000 ms</td></tr>
 *   <tr><td>{@code recipe_delete_result}</td><td>S2C</td><td>逐条回执</td><td>——</td></tr>
 *   <tr><td>{@code recipe_changed}</td><td>S2C</td><td>版本号变了，重取当前页</td><td>只发浏览者</td></tr>
 * </table>
 *
 * <h2>三道闸门（缺一不可）</h2>
 * <ol>
 *   <li><b>规则</b>：{@code voidWarehouse} + {@code voidWarehouseCraft} 都开
 *       （{@link VoidWarehouseData#craftEnabled()}）—— 规则关着时连读都不给，
 *       保持「关着的规则不碰存档」这条不变式；</li>
 *   <li><b>会话</b>：{@code player.containerMenu} 必须是 {@link VoidWarehouseMenu} ——
 *       照抄搜索通道的既有边界：能问的前提是<b>自己正开着仓库界面</b>；</li>
 *   <li><b>权限</b>：按产品决定，规则开启后<b>视为所有玩家都有删除权</b>，
 *       所以这里没有额外的 op 判断；{@code canDelete} 字段仍然回 {@code true}，
 *       留着是为了将来收紧时不用改协议。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>Fabric 保证 {@code PlayPayloadHandler} 跑在服务端主线程，可以直接读
 * {@code player.containerMenu} 与仓库数据，不需要 {@code server.execute}。</p>
 *
 * <h2>版本差异</h2>
 * <p>1.21.11 的 fabric-api 叫 {@code PayloadTypeRegistry.playC2S()/playS2C()}；
 * 26.x（6.3.x）重命名为 {@code serverboundPlay()/clientboundPlay()}。
 * 本文件在 26.x 与 1.21.11 两份副本里只有这两行不同，其余逐字节一致。</p>
 */
public final class RecipeNetwork {

    /** 同一玩家两次 hello 的最短间隔（与搜索目录同档：打开界面时发一次） */
    private static final long HELLO_INTERVAL_MS = 100L;

    /** 同一玩家两次 query 的最短间隔（翻页 / 改过滤是紧跟输入的动作） */
    private static final long QUERY_INTERVAL_MS = 50L;

    /** 同一玩家两次 delete 的最短间隔：删除要重建整本 + 落盘，1 秒一次足够人手操作 */
    private static final long DELETE_INTERVAL_MS = 1000L;

    /**
     * 浏览会话的有效期。
     *
     * <p>「正在看配方本的人」= 最近 {@code SESSION_TTL_MS} 内问过索引或页面的玩家。
     * 超过就认为人已经走了（关界面不一定有包，客户端可能直接下线 / 切维度），
     * 变更广播不再打扰他。60 秒足够覆盖「翻一页想一会儿」的停顿。</p>
     */
    private static final long SESSION_TTL_MS = 60_000L;

    private static final Map<UUID, Long> LAST_HELLO_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_QUERY_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DELETE_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BROWSING_AT = new ConcurrentHashMap<>();

    private RecipeNetwork() {
    }

    /**
     * 在模组初始化时调用一次（{@code AutoMendingMod#onInitialize}）。
     *
     * <p>C2S 只由服务端注册这一份；客户端模组自己幂等注册（撞名吞掉），
     * 与 restock / search 是同一条红线 —— 专用服务器上两端是两个进程，
     * 客户端不发自己那份注册就退化成 {@code DiscardedPayload}，包整包丢掉。</p>
     */
    public static void init() {
        PayloadTypeRegistry.playC2S().register(RecipeHelloPayload.TYPE, RecipeHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RecipeQueryPayload.TYPE, RecipeQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RecipeDeletePayload.TYPE, RecipeDeletePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RecipeIndexPayload.TYPE, RecipeIndexPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RecipePagePayload.TYPE, RecipePagePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                RecipeDeleteResultPayload.TYPE, RecipeDeleteResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RecipeChangedPayload.TYPE, RecipeChangedPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(RecipeHelloPayload.TYPE, RecipeNetwork::handleHello);
        ServerPlayNetworking.registerGlobalReceiver(RecipeQueryPayload.TYPE, RecipeNetwork::handleQuery);
        ServerPlayNetworking.registerGlobalReceiver(RecipeDeletePayload.TYPE, RecipeNetwork::handleDelete);

        // 玩家下线就抹掉限流与浏览会话，长期运行的服务器不攒离线玩家的 UUID
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                forget(player.getUUID());
            }
        });
    }

    private static void forget(UUID id) {
        LAST_HELLO_AT.remove(id);
        LAST_QUERY_AT.remove(id);
        LAST_DELETE_AT.remove(id);
        BROWSING_AT.remove(id);
    }

    // ------------------------------------------------------------------ 三个接收端

    /** hello：回索引。规则关着 / 没开仓库界面 ⇒ 静默忽略（不向可疑客户端确认什么） */
    private static void handleHello(RecipeHelloPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseMenu menu = openedMenu(player);
        if (menu == null) {
            return;
        }
        if (throttled(LAST_HELLO_AT, HELLO_INTERVAL_MS, player)) {
            return;
        }
        touchBrowser(player);
        send(player, RecipeService.index(menu.data()));
    }

    /** query：切一页。同样只在「开着仓库界面 + 规则开着」时回应 */
    private static void handleQuery(RecipeQueryPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseMenu menu = openedMenu(player);
        if (menu == null) {
            return;
        }
        if (throttled(LAST_QUERY_AT, QUERY_INTERVAL_MS, player)) {
            return;
        }
        touchBrowser(player);
        send(player, RecipeService.page(menu.data(), payload));
    }

    /**
     * delete：删一批，然后立刻刷新自己和其它浏览者。
     *
     * <p>顺序是刻意的：<b>先重建这一页</b>（{@code refreshAfterRecipesChanged}），
     * 再发回执、再广播。原因见 {@code VoidWarehouseMenu#refreshAfterRecipesChanged}：
     * 钉住的那一页上挂着的 {@code ExchangeOffer} 惰性闭包捕获的是删除前的配方树，
     * 不立刻重建的话，2 秒内点它仍会按旧表出料。</p>
     */
    private static void handleDelete(RecipeDeletePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseMenu menu = openedMenu(player);
        if (menu == null) {
            return;
        }
        VoidWarehouseData data = menu.data();
        if (throttled(LAST_DELETE_AT, DELETE_INTERVAL_MS, player)) {
            send(player, RecipeDeleteResultPayload.refused(
                    RecipeDeleteResultPayload.THROTTLED, data.recipeGeneration(),
                    data.recipeBookUsed(), true));
            return;
        }
        if (payload.recipeIds() == null || payload.recipeIds().size() > RecipeDeletePayload.MAX_IDS) {
            send(player, RecipeDeleteResultPayload.refused(
                    RecipeDeleteResultPayload.TOO_MANY, data.recipeGeneration(),
                    data.recipeBookUsed(), true));
            return;
        }

        RecipeService.DeleteOutcome outcome = RecipeService.delete(data, payload.recipeIds());
        if (!outcome.removed().isEmpty()) {
            // ① 自己的界面立刻重建（不能等 IDLE_TICKS），② 再通知其它浏览者
            menu.refreshAfterRecipesChanged();
            broadcastDeleted(player, data, outcome.removed().size());
        }
        send(player, new RecipeDeleteResultPayload(
                outcome.status(),
                outcome.removed(),
                outcome.failed(),
                data.recipeGeneration(),
                data.recipeBookUsed(),
                true));
    }

    // ------------------------------------------------------------------ 广播

    /**
     * 通知「正在看配方本的人」：版本号变了，重取当前页。
     *
     * <p>只发给浏览会话还有效、且此刻仍开着仓库界面、且装了对应客户端模组的玩家。
     * <b>不发给全服</b>：配方本是全服一份，但「正在浏览」是少数；
     * 学一条配方都全服广播的代价远大于收益（这也是当前不广播「学习」的原因）。</p>
     */
    private static void broadcastDeleted(ServerPlayer actor, VoidWarehouseData data, int removed) {
        MinecraftServer server = actor.level().getServer();
        if (server == null) {
            return;
        }
        String actorName = actor.getScoreboardName();
        int generation = data.recipeGeneration();
        long now = System.currentTimeMillis();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            Long seen = BROWSING_AT.get(viewer.getUUID());
            if (seen == null || now - seen > SESSION_TTL_MS) {
                continue;
            }
            if (!(viewer.containerMenu instanceof VoidWarehouseMenu)) {
                continue;
            }
            send(viewer, new RecipeChangedPayload(
                    generation, RecipeChangedPayload.REASON_DELETED, actorName, removed));
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 三道闸门的前两道：规则开着，且玩家正开着自己的仓库界面。
     *
     * @return 通过时返回那个菜单（省得调用方再 cast 一次），否则 {@code null}
     */
    private static VoidWarehouseMenu openedMenu(ServerPlayer player) {
        if (player == null || !VoidWarehouseData.craftEnabled()) {
            return null;
        }
        return player.containerMenu instanceof VoidWarehouseMenu menu ? menu : null;
    }

    /** 记一次「这个人在看配方本」；登录 / 下线 / 掉线时清掉（见 DISCONNECT 注册） */
    private static void touchBrowser(ServerPlayer player) {
        if (player != null) {
            BROWSING_AT.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    /**
     * @return {@code true} = 发得太快，本次请求不作数（<b>不更新时间戳</b>，
     *         必须先退够时间才可能再成功 —— 与 restock / search 同一个取舍）
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
    private static void send(ServerPlayer player, RecipeIndexPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, RecipeIndexPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, RecipePagePayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, RecipePagePayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, RecipeDeleteResultPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, RecipeDeleteResultPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, RecipeChangedPayload payload) {
        if (player != null && ServerPlayNetworking.canSend(player, RecipeChangedPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
