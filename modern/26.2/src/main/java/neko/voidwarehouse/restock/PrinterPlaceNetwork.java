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
                try {
                    status = structured == null ? PrinterPlaceService.place(context.server(), player, payload)
                            : PrinterPlaceService.place(context.server(), player, structured);
                } catch (RuntimeException failure) {
                    status = PrinterPlaceResultPayload.REJECTED;
                    if (now - lastErrorAt > 5_000_000_000L) {
                        lastErrorAt = now;
                        LOGGER.warn("Printer direct placement failed", failure);
                    }
                }
                session.complete(payload.nonce(), status);
                correct(player, payload);
            } else if (session.allowCorrection(now)) {
                // Replays and flooded requests get bounded authoritative correction traffic.
                correct(player, payload);
            }
        }
        ServerPlayNetworking.send(player, new PrinterPlaceResultPayload(payload.nonce(), status));
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
