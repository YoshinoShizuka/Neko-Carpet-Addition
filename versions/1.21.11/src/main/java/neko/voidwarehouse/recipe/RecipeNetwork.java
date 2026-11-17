package neko.voidwarehouse.recipe;

import neko.voidwarehouse.VoidWarehouse;
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
 * <h2>两道闸门</h2>
 * <ol>
 *   <li><b>规则</b>：{@code voidWarehouse} + {@code voidWarehouseCraft} 都开
 *       （{@link VoidWarehouseData#craftEnabled()}）—— 规则关着时连读都不给，
 *       保持「关着的规则不碰存档」这条不变式；</li>
 *   <li><b>权限</b>：按产品决定，规则开启后<b>视为所有玩家都有删除权</b>，
 *       所以这里没有额外的 op 判断；{@code canDelete} 字段仍然回 {@code true}，
 *       留着是为了将来收紧时不用改协议。</li>
 * </ol>
 *
 * <h2>不要求开着仓库界面（2026-11-17 改）</h2>
 * <p>原先照抄搜索通道的边界：能问的前提是 {@code player.containerMenu} 必须是
 * {@link VoidWarehouseMenu}。但配方本本来就不属于「某个容器会话」—— 它是全服唯一的一份
 * （{@link VoidWarehouse#data(MinecraftServer)} 取到的就是那个单例），而它的浏览界面
 * 由客户端模组自己提供、不在仓库界面里。要求先开仓库，等于强迫玩家先点开一个与配方本
 * 无关的界面，删一条还得专门跑回仓库前再开一次。
 *
 * <p>所以现在只剩规则这一道闸门。开着仓库界面时多做一件事：删完顺手把<b>自己</b>那个菜单
 * 全量重发（{@code VoidWarehouseMenu#refreshAfterRecipesChanged}），否则他手里那页
 * 还留着删之前的格子。
 *
 * <p>⚠️ 这次放宽<b>不动权限口径</b>：「规则开着 = 所有人可删」是拍板结果，原来那道
 * 「开着界面」从来不是权限检查，只是一条会话边界。
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

    /** hello：回索引。规则关着 ⇒ 静默忽略（不向可疑客户端确认什么）；不需要开着仓库界面 */
    private static void handleHello(RecipeHelloPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseData data = dataOf(player);
        if (data == null) {
            return;
        }
        if (throttled(LAST_HELLO_AT, HELLO_INTERVAL_MS, player)) {
            return;
        }
        touchBrowser(player);
        send(player, RecipeService.index(data));
    }

    /** query：切一页。同样只看规则开着，不需要开着仓库界面 */
    private static void handleQuery(RecipeQueryPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseData data = dataOf(player);
        if (data == null) {
            return;
        }
        if (throttled(LAST_QUERY_AT, QUERY_INTERVAL_MS, player)) {
            return;
        }
        touchBrowser(player);
        send(player, RecipeService.page(data, payload));
    }

    /**
     * delete：删一批，然后刷新自己（如果他正开着仓库）并通知其它浏览者。
     *
     * <p>顺序是刻意的：<b>先重建自己那页</b>（{@code refreshAfterRecipesChanged}），
     * 再发回执、再广播 —— 客户端拿到回执后会重取当前页，指望的就是「列表已经是新的」。</p>
     *
     * <p>至于「删完立刻不可得」这条正确性，<b>不靠</b>这里的刷新：删除推进
     * {@code recipeGeneration}，而 {@code exchangeOffers()} 取表前先比
     * {@code virtualEntrySignature()}（内含 {@code recipeGeneration << 2}）⇒ 整张可兑表
     * 同一 tick 内就作废。详见 {@code VoidWarehouseData#forgetRecipes} 的注释。</p>
     *
     * <p>没开仓库界面时没有任何页要重建 —— 那也不影响删除本身。</p>
     */
    private static void handleDelete(RecipeDeletePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        VoidWarehouseData data = dataOf(player);
        if (data == null) {
            return;
        }
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
            // ① 自己开着仓库才需要重建那一页（没开就没有页可言），② 再通知其它浏览者
            if (player.containerMenu instanceof VoidWarehouseMenu menu) {
                menu.refreshAfterRecipesChanged();
            }
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
     * <p>只发给浏览会话还有效、且装了对应客户端模组的玩家 —— <b>不要求他此刻开着仓库界面</b>
     * （自从「不开仓库也能看」之后，浏览会话就是唯一的判据）。
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
            send(viewer, new RecipeChangedPayload(
                    generation, RecipeChangedPayload.REASON_DELETED, actorName, removed));
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 规则这一道闸门 + 取数据：{@code null} = 规则关着 / 拿不到服务端，本次请求不作数。
     *
     * <p>不要求玩家开着仓库界面（见类注释「不要求开着仓库界面」）。取到的实例与
     * {@code player.containerMenu} 那个菜单拿到的是<b>同一个单例</b>（全存档唯一），
     * 所以两种入口的数据天然一致。</p>
     *
     * <p>{@link VoidWarehouse#data(MinecraftServer)} 的稳态开销只有<b>一次 map 查表</b>：
     * 读盘自检（{@code diskCountChecked}）与写盘前快照（{@code backupDone}）都是进程级一次性。</p>
     *
     * <p>🔴 <b>必须接住它的异常</b>：{@code VoidWarehouse.data} 在「读到空壳」时会抛
     * {@link IllegalStateException} 拒绝服务（防空仓库写盘覆盖存档）。老路径上这个异常在
     * <b>开界面</b>那一刻就抛了，玩家看得见；现在浏览不需要开界面，若不接住，就是每次翻页
     * 在包处理器里抛一次 —— 那个位置会把玩家连接踢掉，而玩家只是在翻配方本。
     * 所以这里吞掉并返回 {@code null}（本次请求不作数）：空壳的告警
     * {@code VoidWarehouse} 已经打过一次，不必每包再刷一遍日志。</p>
     */
    private static VoidWarehouseData dataOf(ServerPlayer player) {
        if (player == null || !VoidWarehouseData.craftEnabled()) {
            return null;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return null;
        }
        try {
            return VoidWarehouse.data(server);
        } catch (RuntimeException e) {
            return null;
        }
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
