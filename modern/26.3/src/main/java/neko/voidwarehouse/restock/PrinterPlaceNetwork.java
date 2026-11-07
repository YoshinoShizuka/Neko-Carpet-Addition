package neko.voidwarehouse.restock;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/** Negotiation and replay protection are scoped to live connections, never reusable UUIDs. */
public final class PrinterPlaceNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger("carpet_neko_addition/printer_place");
    private static final Map<ServerGamePacketListenerImpl, PrinterPlaceSession> SESSIONS = new IdentityHashMap<>();
    private static long lastErrorAt;

    private PrinterPlaceNetwork() {
    }

    public static void init() {
        register(() -> PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceHelloPayload.TYPE, PrinterPlaceHelloPayload.CODEC));
        register(() -> PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceHelloResultPayload.TYPE, PrinterPlaceHelloResultPayload.CODEC));
        register(() -> PayloadTypeRegistry.serverboundPlay().register(PrinterPlacePayload.TYPE, PrinterPlacePayload.CODEC));
        register(() -> PayloadTypeRegistry.clientboundPlay().register(PrinterPlaceResultPayload.TYPE, PrinterPlaceResultPayload.CODEC));
        ServerPlayNetworking.registerGlobalReceiver(PrinterPlaceHelloPayload.TYPE, PrinterPlaceNetwork::hello);
        ServerPlayNetworking.registerGlobalReceiver(PrinterPlacePayload.TYPE, PrinterPlaceNetwork::handle);
        register(() -> PayloadTypeRegistry.serverboundPlay().register(PrinterPlaceStatePayload.TYPE, PrinterPlaceStatePayload.CODEC));
        ServerPlayNetworking.registerGlobalReceiver(PrinterPlaceStatePayload.TYPE, PrinterPlaceNetwork::handleState);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler));
    }

    private static void register(Runnable registration) {
        try {
            registration.run();
        } catch (IllegalArgumentException alreadyRegisteredByClientMod) {
            // Integrated servers share the registry and the identical payload class with the printer.
        }
    }

    private static void hello(PrinterPlaceHelloPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!ServerPlayNetworking.canSend(player, PrinterPlaceHelloResultPayload.TYPE)
                || !ServerPlayNetworking.canSend(player, PrinterPlaceResultPayload.TYPE)) {
            return;
        }
        PrinterPlaceSession session = SESSIONS.computeIfAbsent(player.connection, ignored -> new PrinterPlaceSession());
        if (session.allowHello(System.nanoTime())) {
            ServerPlayNetworking.send(player, new PrinterPlaceHelloResultPayload(payload.nonce(), PrinterPlaceService.enabled()));
        }
    }

    private static void handle(PrinterPlacePayload payload, ServerPlayNetworking.Context context) {
        handle(payload, null, context);
    }

    private static void handleState(PrinterPlaceStatePayload payload, ServerPlayNetworking.Context context) {
        handle(payload.placement(), payload, context);
    }

    private static void handle(PrinterPlacePayload payload, PrinterPlaceStatePayload structured,
                               ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!ServerPlayNetworking.canSend(player, PrinterPlaceResultPayload.TYPE)) {
            return;
        }
        PrinterPlaceSession session = SESSIONS.get(player.connection);
        long now = System.nanoTime();
        int status;
        if (session == null || !session.negotiated()) {
            status = PrinterPlaceResultPayload.REJECTED;
        } else {
            status = session.begin(payload.nonce(), now, context.server().getTickCount());
            if (status == -1) {
                // 直放的扣料提示要用「扣了哪一种、几个」，所以走 placeDetailed；
                // 状态码仍是同一套，place(...) 只是它的 .status() 简写。
                PrinterPlaceService.PlaceOutcome outcome = null;
                try {
                    outcome = structured == null
                            ? PrinterPlaceService.placeDetailed(context.server(), player, payload)
                            : PrinterPlaceService.placeDetailed(context.server(), player, structured);
                    status = outcome.status();
                } catch (RuntimeException failure) {
                    status = PrinterPlaceResultPayload.REJECTED;
                    if (now - lastErrorAt > 5_000_000_000L) {
                        lastErrorAt = now;
                        LOGGER.warn("Printer direct placement failed", failure);
                    }
                }
                session.complete(payload.nonce(), status);
                correct(player, payload);
                notifyDeducted(player, outcome);
            } else if (session.allowCorrection(now)) {
                // Replays and flooded requests get bounded authoritative correction traffic.
                correct(player, payload);
            }
        }
        ServerPlayNetworking.send(player, new PrinterPlaceResultPayload(payload.nonce(), status));
    }

    /**
     * 放置成功后记一笔「从仓库扣掉了什么」，具体合并与发送见 {@link PrinterPlaceNotifier}。
     *
     * <h2>为什么不在这里直接发消息</h2>
     * <p>直放是<b>全模组最高频</b>的动作：实机日志单个 10 秒窗口就有 334 次。
     * 一次一条会把聊天栏刷穿（每秒三十多行），所以记账与发送必须分开：
     * 这里只负责往汇总表里加一笔，真正的发送由 {@link PrinterPlaceNotifier#tick}
     * 按玩家每秒最多一条合并发出。</p>
     *
     * <h2>为什么整个包在 try 里</h2>
     * <p>提示是<b>附带</b>的。它若抛异常，绝不能反过来把「方块已经放好、料已经扣掉」
     * 判成失败 —— 那样客户端会重试，等于白扣一次料。所以这里吞掉一切异常，
     * 最坏的结果只是少一条提示。</p>
     */
    private static void notifyDeducted(ServerPlayer player, PrinterPlaceService.PlaceOutcome outcome) {
        try {
            PrinterPlaceNotifier.record(player, outcome);
        } catch (RuntimeException ignored) {
            // 记账失败只丢这一条提示，不影响放置结果
        }
    }

    private static void correct(ServerPlayer player, PrinterPlacePayload payload) {
        if (player.connection == null || payload.hit() == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        correctPosition(player, level, payload.target());
        correctPosition(player, level, payload.hit().getBlockPos());
        correctPosition(player, level, payload.hit().getBlockPos().relative(payload.hit().getDirection()));
    }

    private static void correctPosition(ServerPlayer player, ServerLevel level, BlockPos pos) {
        // Invalid requests must not load arbitrary chunks just to send their rejection.
        if (pos != null && !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos)
                && player.isWithinBlockInteractionRange(pos, 1.0)) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }
    }
}
