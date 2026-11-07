package neko.voidwarehouse.restock;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseSettings;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.math.BigInteger;
import java.util.List;

/** Ordinary block placement using one reserved warehouse item and a temporary hand copy. */
public final class PrinterPlaceService {
    private PrinterPlaceService() {
    }

    public static boolean enabled() {
        return VoidWarehouseSettings.voidWarehouse && VoidWarehouseRestockSettings.voidWarehouseRestock
                && VoidWarehouseRestockSettings.voidWarehouseDirectPlace;
    }

    public static int place(MinecraftServer server, ServerPlayer player, PrinterPlacePayload payload) {
        return place(server, player, payload, null);
    }

    public static int place(MinecraftServer server, ServerPlayer player, PrinterPlaceStatePayload structured) {
        return structured == null ? PrinterPlaceResultPayload.BAD_REQUEST
                : place(server, player, structured.placement(), structured);
    }

    private static int place(MinecraftServer server, ServerPlayer player, PrinterPlacePayload payload,
                             PrinterPlaceStatePayload structured) {
        if (server == null || player == null || payload == null) {
            return PrinterPlaceResultPayload.BAD_REQUEST;
        }
        if (!enabled()) {
            return PrinterPlaceResultPayload.RULE_OFF;
        }
        if (structured != null && !PrinterPlaceServuxBridge.hasPermission(player)) {
            return PrinterPlaceResultPayload.REJECTED;
        }
        List<String> candidates = payload.candidates();
        if (payload.nonce() <= 0 || candidates == null || candidates.isEmpty()
                || candidates.size() > PrinterPlacePayload.MAX_CANDIDATES || !validHit(payload)) {
            return PrinterPlaceResultPayload.BAD_REQUEST;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().identifier().toString().equals(payload.dimensionId())) {
            return PrinterPlaceResultPayload.STALE;
        }
        BlockPos target = payload.target();
        BlockPos clicked = payload.hit().getBlockPos();
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !player.isAlive()
                || !player.mayBuild() || player.isUsingItem() || player.containerMenu != player.inventoryMenu
                || !allowedPosition(level, player, clicked) || !allowedPosition(level, player, target)
                || player.getEyePosition().distanceToSqr(payload.hit().getLocation())
                    > Math.pow(player.blockInteractionRange() + 1.0, 2)) {
            return PrinterPlaceResultPayload.REJECTED;
        }
        if (Block.getId(level.getBlockState(target)) != payload.expectedStateId()) {
            return PrinterPlaceResultPayload.STALE;
        }

        boolean validItemId = false;
        boolean supportedItem = false;
        for (String raw : candidates) {
            if (raw == null || raw.isEmpty() || raw.length() > PrinterPlacePayload.MAX_ITEM_ID_LENGTH) {
                continue;
            }
            Identifier id = Identifier.tryParse(raw);
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item == null) {
                continue;
            }
            validItemId = true;
            // Dedicated subclasses can produce buckets, open interfaces or place multiple blocks.
            if (item.getClass() != BlockItem.class) {
                continue;
            }
            BlockItem blockItem = (BlockItem) item;
            Block block = blockItem.getBlock();
            if (block instanceof ShulkerBoxBlock || block instanceof BedBlock || block instanceof DoorBlock
                    || block instanceof DoublePlantBlock || !item.isEnabled(level.enabledFeatures())) {
                continue;
            }
            supportedItem = true;
            ItemStack sample = new ItemStack(item);
            if (player.getCooldowns().isOnCooldown(sample) || !player.mayUseItemAt(target, payload.hit().getDirection(), sample)) {
                return PrinterPlaceResultPayload.REJECTED;
            }
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, sample, payload.hit());
            if (!context.getClickedPos().equals(target) || !context.canPlace()) {
                return PrinterPlaceResultPayload.REJECTED;
            }
            if (structured != null) {
                try {
                    BlockState state = PrinterPlaceServuxBridge.placementState(blockItem, context, structured);
                    if (state == null || !state.canSurvive(level, target)) return PrinterPlaceResultPayload.REJECTED;
                } catch (IllegalArgumentException malformedProtocol) {
                    return PrinterPlaceResultPayload.BAD_REQUEST;
                }
            }
            VoidWarehouseData.Withdrawal withdrawal = VoidWarehouse.data(server).withdraw(sample, BigInteger.ONE);
            if (withdrawal.isEmpty()) {
                continue;
            }
            return placeReserved(player, level, blockItem, payload, structured, withdrawal);
        }
        return !validItemId ? PrinterPlaceResultPayload.BAD_REQUEST
                : !supportedItem ? PrinterPlaceResultPayload.REJECTED : PrinterPlaceResultPayload.EMPTY;
    }

    private static int placeReserved(ServerPlayer player, ServerLevel level, BlockItem item,
                                     PrinterPlacePayload payload, PrinterPlaceStatePayload structured,
                                     VoidWarehouseData.Withdrawal withdrawal) {
        // Withdrawal owns its original count for rollback, including exchange/crafting change.
        ItemStack temporary = withdrawal.stack().copy();
        int selected = player.getInventory().getSelectedSlot();
        ItemStack original = player.getInventory().getItem(selected);
        BlockState before = level.getBlockState(payload.target());
        boolean enteredPlacement = false;
        boolean placementReturned = false;
        try {
            player.getInventory().setItem(selected, temporary);
            InteractionResult permission = UseBlockCallback.EVENT.invoker().interact(
                    player, level, InteractionHand.MAIN_HAND, payload.hit());
            if (permission != InteractionResult.PASS) {
                return PrinterPlaceResultPayload.REJECTED;
            }
            // A callback may have changed the world or the held item; never place on an old snapshot.
            if (Block.getId(level.getBlockState(payload.target())) != payload.expectedStateId()
                    || player.level() != level || player.getMainHandItem() != temporary
                    || player.containerMenu != player.inventoryMenu) {
                return PrinterPlaceResultPayload.STALE;
            }
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, temporary, payload.hit());
            if (!context.getClickedPos().equals(payload.target()) || !context.canPlace()) {
                return PrinterPlaceResultPayload.REJECTED;
            }
            // Invoke the ordinary placement primitive, never the clicked block's interaction.
            // This keeps chests/composters/cauldrons from receiving the reserved item as an input.
            enteredPlacement = true;
            InteractionResult result;
            if (structured == null) {
                result = item.place(context);
            } else {
                try (PrinterPlaceServuxBridge.Scope ignored = PrinterPlaceServuxBridge.enter(item, context, structured)) {
                    result = item.place(context);
                }
            }
            placementReturned = true;
            BlockState after = level.getBlockState(payload.target());
            return result.consumesAction() && !before.equals(after) && temporary.getCount() < withdrawal.taken()
                    ? PrinterPlaceResultPayload.OK : PrinterPlaceResultPayload.REJECTED;
        } finally {
            int consumed = Math.max(0, Math.min(withdrawal.taken(), withdrawal.taken() - temporary.getCount()));
            player.getInventory().setItem(selected, original);
            player.getInventory().setSelectedSlot(selected);
            if (enteredPlacement && (!placementReturned || !before.equals(level.getBlockState(payload.target())))) {
                // Vanilla writes the block before sound/advancement/game-event hooks and consumption.
                // A throwing hook has an unknown partial outcome: never refund a possibly placed item.
                consumed = Math.max(consumed, 1);
            }
            withdrawal.returnUnplaced(consumed);
        }
    }

    private static boolean validHit(PrinterPlacePayload payload) {
        BlockPos target = payload.target();
        BlockHitResult hit = payload.hit();
        if (target == null || hit == null || hit.getBlockPos() == null || hit.getDirection() == null
                || payload.expectedStateId() < 0 || hit.isWorldBorderHit()) {
            return false;
        }
        BlockPos clicked = hit.getBlockPos();
        if (!target.equals(clicked) && !target.equals(clicked.relative(hit.getDirection()))) {
            return false;
        }
        Vec3 location = hit.getLocation();
        // This hit is always physical; schematic state uses a separate semantic property map.
        return Double.isFinite(location.x) && Double.isFinite(location.y) && Double.isFinite(location.z)
                && location.x >= clicked.getX() - 0.0001 && location.x <= clicked.getX() + 1.0001
                && location.y >= clicked.getY() - 0.0001 && location.y <= clicked.getY() + 1.0001
                && location.z >= clicked.getZ() - 0.0001 && location.z <= clicked.getZ() + 1.0001;
    }

    private static boolean allowedPosition(ServerLevel level, ServerPlayer player, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.hasChunkAt(pos) && player.isWithinBlockInteractionRange(pos, 1.0)
                && player.mayInteract(level, pos);
    }
}
