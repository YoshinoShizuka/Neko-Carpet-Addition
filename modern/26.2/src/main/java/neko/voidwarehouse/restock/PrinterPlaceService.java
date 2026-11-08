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

    /**
     * 一次直接放置的完整结果：状态码 + <b>真正被扣掉的那一种物品</b> + <b>扣了几个</b>。
     *
     * <h2>为什么不是直接返回状态码</h2>
     * <p>{@link #place} 的返回值原本只是一个 int，够判断成败，但没法支撑「扣料提示」——
     * 提示要说清扣的是<b>哪一种、几个</b>。把这两个数当成结果的一部分交出去，
     * service 就仍然只做纯逻辑（可单独跑自检、不依赖真玩家），
     * 「要不要发消息、怎么合并着发」全部留给网络层，与
     * {@code PrinterTakeService.TakeOutcome} 的分工完全一致。</p>
     *
     * <h2>{@code consumed} 是净扣减，不是预扣量</h2>
     * <p>放置流程是「先从仓库预扣 1 个 → 塞进手上临时格 → 走原版放置 → 没用掉的原样还回仓库」
     * （{@code Withdrawal#returnUnplaced}）。所以只有<b>放置真的发生</b>时这里才是 1；
     * 中途被拒（{@code REJECTED} / {@code STALE}）一律还回去，{@code consumed} 为 0，
     * 调用方据此不发提示 —— 报「消耗了 0 个」纯属噪音。</p>
     *
     * @param status   结果码，语义见 {@link PrinterPlaceResultPayload}
     * @param sample   这一轮命中的物品样板（数量恒 1）；一件都没命中时为 {@link ItemStack#EMPTY}
     * @param consumed 本次<b>净</b>从仓库扣掉的数量（失败 / 回滚时为 0）
     */
    public record PlaceOutcome(int status, ItemStack sample, int consumed) {

        /** 只有状态、没有数量的简写（失败路径用） */
        static PlaceOutcome of(int status) {
            return new PlaceOutcome(status, ItemStack.EMPTY, 0);
        }

        /** 真的放下了一个方块，并且真的从仓库扣了料 */
        public boolean deducted() {
            return this.status == PrinterPlaceResultPayload.OK && this.consumed > 0 && !this.sample.isEmpty();
        }
    }

    public static int place(MinecraftServer server, ServerPlayer player, PrinterPlacePayload payload) {
        return placeDetailed(server, player, payload).status();
    }

    public static int place(MinecraftServer server, ServerPlayer player, PrinterPlaceStatePayload structured) {
        return placeDetailed(server, player, structured).status();
    }

    /** 与 {@link #place(MinecraftServer, ServerPlayer, PrinterPlacePayload)} 同一条路径，但带出扣料明细 */
    public static PlaceOutcome placeDetailed(MinecraftServer server, ServerPlayer player, PrinterPlacePayload payload) {
        return placeDetailed(server, player, payload, null);
    }

    /** 与 {@link #place(MinecraftServer, ServerPlayer, PrinterPlaceStatePayload)} 同一条路径，但带出扣料明细 */
    public static PlaceOutcome placeDetailed(MinecraftServer server, ServerPlayer player,
                                             PrinterPlaceStatePayload structured) {
        return structured == null ? PlaceOutcome.of(PrinterPlaceResultPayload.BAD_REQUEST)
                : placeDetailed(server, player, structured.placement(), structured);
    }

    private static PlaceOutcome placeDetailed(MinecraftServer server, ServerPlayer player, PrinterPlacePayload payload,
                                              PrinterPlaceStatePayload structured) {
        if (server == null || player == null || payload == null) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.BAD_REQUEST);
        }
        if (!enabled()) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.RULE_OFF);
        }
        if (structured != null && !PrinterPlaceServuxBridge.hasPermission(player)) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.REJECTED);
        }
        List<String> candidates = payload.candidates();
        if (payload.nonce() <= 0 || candidates == null || candidates.isEmpty()
                || candidates.size() > PrinterPlacePayload.MAX_CANDIDATES || !validHit(payload)) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.BAD_REQUEST);
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().identifier().toString().equals(payload.dimensionId())) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.STALE);
        }
        BlockPos target = payload.target();
        BlockPos clicked = payload.hit().getBlockPos();
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !player.isAlive()
                || !player.mayBuild() || player.isUsingItem() || player.containerMenu != player.inventoryMenu
                || !allowedPosition(level, player, clicked) || !allowedPosition(level, player, target)) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.REJECTED);
        }
        if (Block.getId(level.getBlockState(target)) != payload.expectedStateId()) {
            return PlaceOutcome.of(PrinterPlaceResultPayload.STALE);
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
                return PlaceOutcome.of(PrinterPlaceResultPayload.REJECTED);
            }
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, sample, payload.hit());
            if (!context.getClickedPos().equals(target) || !context.canPlace()) {
                return PlaceOutcome.of(PrinterPlaceResultPayload.REJECTED);
            }
            if (structured != null) {
                try {
                    BlockState state = PrinterPlaceServuxBridge.placementState(blockItem, context, structured);
                    if (state == null || !state.canSurvive(level, target)) {
                        return PlaceOutcome.of(PrinterPlaceResultPayload.REJECTED);
                    }
                } catch (IllegalArgumentException malformedProtocol) {
                    return PlaceOutcome.of(PrinterPlaceResultPayload.BAD_REQUEST);
                }
            }
            VoidWarehouseData.Withdrawal withdrawal = VoidWarehouse.data(server).withdraw(sample, BigInteger.ONE);
            if (withdrawal.isEmpty()) {
                continue;
            }
            // 净扣减由 placeReserved 的 finally 算出来（它才知道原版放置到底用掉了几个），
            // 这里先摆一个出口容器。用 int[] 而不是成员字段：这条路径是全服共享的热路径，
            // 不能为了带一个数出来引入任何可变状态。
            int[] consumed = new int[1];
            int status = placeReserved(player, level, blockItem, payload, structured, withdrawal, consumed);
            return new PlaceOutcome(status, sample, consumed[0]);
        }
        return PlaceOutcome.of(!validItemId ? PrinterPlaceResultPayload.BAD_REQUEST
                : !supportedItem ? PrinterPlaceResultPayload.REJECTED : PrinterPlaceResultPayload.EMPTY);
    }

    /**
     * 真正动世界的那一段：预扣的物品临时塞进手上、走原版放置、再把没用掉的还回去。
     *
     * @param consumedOut 出口容器：净扣减数量。必须由 {@code finally} 写 —— 只有那里
     *                    才同时知道「预扣了多少」和「放置用掉了多少」。
     */
    private static int placeReserved(ServerPlayer player, ServerLevel level, BlockItem item,
                                     PrinterPlacePayload payload, PrinterPlaceStatePayload structured,
                                     VoidWarehouseData.Withdrawal withdrawal, int[] consumedOut) {
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
            // 出口容器放在「还回未用掉的那部分」之后写：真正记账的只有净扣减。
            if (consumedOut != null) {
                consumedOut[0] = consumed;
            }
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
