package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import neko.voidwarehouse.VoidWarehouseText;

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
     * <h2>注册的总原则：一条通道只有一个主人</h2>
     * <p>本模组的 {@code environment} 是 {@code "*"}，也就是说它<b>也会在客户端进程里跑</b>
     * （单人存档、局域网主机、以及任何把 Carpet 模组丢进客户端 mods 的玩法都是这种情形）。
     * 而 {@code PayloadTypeRegistryImpl} 是<b>全局静态单例</b>，
     * {@code register} 的实现是 {@code if (packetTypes.containsKey(id)) throw ...}
     * （<b>严格去重，不是 putIfAbsent</b>）。所以「谁注册哪个 key」必须提前分死，
     * 两边都注册同一个 key 一定炸。</p>
     *
     * <table border="1">
     *   <tr><th>通道</th><th>方向</th><th>注册方</th></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_take}</td><td>C2S</td><td>服务端（本模组）</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_take_result}</td><td>S2C</td><td>服务端（本模组），客户端幂等补位</td></tr>
     * </table>
     *
     * <h2>客户端为什么可以不注册 C2S</h2>
     * <p>{@code ClientPlayNetworking.send} 只做「构造包 + 交给连接发出」，<b>发送本身不查本地注册表</b>；
     * 但<b>编码阶段会查</b> —— {@code writeCap} 按 {@code type.id()} 去
     * {@code PayloadTypeRegistryImpl.SERVERBOUND_PLAY} 找 codec。同进程时这张表
     * 就是服务端刚填好的那张，里面已经有 {@code printer_take} → 客户端直接受益，
     * 无需自己再注册（自己注册反而会撞名字）。<b>前提是两端用的是同一个
     * {@code PrinterTakePayload} 类</b>，否则查到的 codec 会在内部 checkcast 时炸掉。</p>
     *
     * <h2>回执为什么两边都注册</h2>
     * <p>回执是 S2C：服务端 {@code send} 时需要 {type, codec} 去构造包，客户端需要
     * 同一个 {type, codec} 去解包。同进程下这是同一张 {@code CLIENTBOUND_PLAY} 表，
     * 谁先注册谁占坑。本模组主体在服务端、初始化更早，所以由这里注册；
     * 客户端侧的注册写成「表里已有就跳过」的幂等形式，双保险。</p>
     */
    public static void init() {
        // C2S：打印机 → 服务端，取货请求。整张表里这有且只有这一个 key，
        // 客户端复用这一条（它自己不注册），因此两端解析到的永远是同一个类。
        PayloadTypeRegistry.serverboundPlay().register(PrinterTakePayload.TYPE, PrinterTakePayload.CODEC);
        // S2C：服务端 → 打印机，取货回执
        PayloadTypeRegistry.clientboundPlay().register(PrinterTakeResultPayload.TYPE, PrinterTakeResultPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(PrinterTakePayload.TYPE, PrinterRestockNetwork::handle);

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
     * <p>只做三件事：过限流 → 转交 {@link PrinterTakeService} → 回包 + 发提示。
     * 具体判断都写在 service 里，这里保持薄，便于单独验证。</p>
     */
    private static void handle(PrinterTakePayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        int nonce = payload.nonce();

        if (isThrottled(player)) {
            reply(player, nonce, PrinterTakeResultPayload.THROTTLED);
            return;
        }

        PrinterTakeService.TakeOutcome outcome =
                PrinterTakeService.take(context.server(), player, payload.candidates());
        reply(player, nonce, outcome.status());
        notifyTaken(player, outcome);
    }

    /**
     * 取货成功后，在聊天栏告诉玩家「拿走了多少、仓库还剩多少」。
     *
     * <h2>为什么只在成功且真的放了东西时才发</h2>
     * <p>打印机的取货请求是<b>自动、高频</b>的：一次大面积建造会连发几十次。
     * 如果每种结果都发一条，聊天栏会被刷屏，玩家自己的聊天记录全被顶掉。
     * 所以这里只覆盖「你确实拿到了东西」这一种情形 —— 剩下的失败态
     * （没货 / 背包满 / 限流 / 规则关）本来就该由打印机自己在 HUD 上体现，
     * 聊天栏塞这些等于噪音。</p>
     *
     * <h2>为什么放在网络层而不是 service 里</h2>
     * <p>{@code PrinterTakeService} 的定位是纯逻辑、可单独跑自检，
     * 一旦它自己去 {@code sendSystemMessage}，就绑死了「必须有真玩家」这个前提。
     * 发消息是「怎么用这个结果」，属于网络层的事。</p>
     */
    private static void notifyTaken(ServerPlayer player, PrinterTakeService.TakeOutcome outcome) {
        if (player == null || !outcome.delivered()) {
            return;
        }
        String name = outcome.sample().getHoverName().getString();
        String body = "取走 " + VoidWarehouseText.count(outcome.takenCount()) + " 个 " + name
                + "，仓库剩余 " + VoidWarehouseText.count(outcome.remaining()) + " 个";
        player.sendSystemMessage(VoidWarehouseText.info(body));
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
     * 把结果回执出去。
     *
     * <p>{@code ServerPlayNetworking.send} 只做「构造包 + 交给连接发出」，
     * 不需要对端先注册；真正决定「发不发得出去」的是 {@code canSend} ——
     * 它查客户端在通道协商阶段上报的可接收集合（也就是对面到底登记了哪个类型）。
     * 通道现在只有一条，发之前问一句，对端没装模组就干脆别发。</p>
     */
    private static void reply(ServerPlayer player, int nonce, int status) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterTakeResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new PrinterTakeResultPayload(nonce, status));
    }
}
