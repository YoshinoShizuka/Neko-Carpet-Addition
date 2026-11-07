package neko.voidwarehouse.restock;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the exact block state a structured direct-place request asked for.
 *
 * <p>Servux ships a decoder for Litematica's easy-place protocol, but its metadata declares
 * {@code "environment": "server"}: Fabric never loads it on a client, so a single-player
 * integrated server can never offer that decoder. Structured placement therefore computes the
 * state itself and only borrows Servux when the mod really is present on a dedicated server.
 */
public final class PrinterPlaceServuxBridge {
    /**
     * The properties easy place is allowed to dictate, mirroring Servux's own table so a request
     * resolves to the same state whether or not that mod is installed. Every other property is
     * left at whatever the world and the player's aim produced.
     */
    private static final Set<Property<?>> WHITELISTED = Set.<Property<?>>of(
            BlockStateProperties.INVERTED, BlockStateProperties.OPEN, BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS, BlockStateProperties.HALF, BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE, BlockStateProperties.MODE_COMPARATOR, BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING, BlockStateProperties.FACING_HOPPER, BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION, BlockStateProperties.RAIL_SHAPE, BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.STAIRS_SHAPE, BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BITES, BlockStateProperties.DELAY, BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16);

    /** Properties easy place must never dictate; the value the world produced always wins. */
    private static final Map<Property<?>, Comparable<?>> BLACKLISTED = Map.<Property<?>, Comparable<?>>of(
            BlockStateProperties.WATERLOGGED, Boolean.FALSE,
            BlockStateProperties.POWERED, Boolean.FALSE);

    private static final Binding API = bind();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private PrinterPlaceServuxBridge() { }

    /** Whether Servux's decoder was found. Diagnostics only; placement never depends on it. */
    public static boolean servuxDecoderPresent() { return API != null; }

    /**
     * Servux gates easy place behind its own permission setting. Without it there is nothing to
     * ask: the warehouse rules plus the ordinary may-build checks are the only authority, exactly
     * as they are for the restock channel.
     */
    public static boolean hasPermission(ServerPlayer player) {
        if (player == null) return false;
        if (API == null) return true;
        try { return (boolean) API.permission.invoke(API.config, player); }
        catch (ReflectiveOperationException failure) { throw apiFailure(failure); }
    }

    private static Binding bind() {
        if (!FabricLoader.getInstance().isModLoaded("servux")) return null;
        try {
            Class<?> handler = Class.forName("fi.dy.masa.servux.util.PlacementHandler");
            Class<?> useContext = Class.forName("fi.dy.masa.servux.util.PlacementHandler$UseContext");
            Class<?> config = Class.forName("fi.dy.masa.servux.dataproviders.ServuxConfigProvider");
            return new Binding(config.getField("INSTANCE").get(null),
                    config.getMethod("hasPermission_EasyPlace", ServerPlayer.class),
                    handler.getMethod("applyPlacementProtocolV3", BlockState.class, useContext),
                    useContext.getConstructor(Level.class, BlockPos.class, Direction.class, Vec3.class,
                            LivingEntity.class, InteractionHand.class, BlockPlaceContext.class));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            LoggerFactory.getLogger("carpet_neko_addition/printer_place").warn(
                    "Servux placement API is unavailable; using the built-in structured decoder", failure);
            return null;
        }
    }

    /**
     * Validate semantic input against the actual item, then resolve it into the state to place.
     * The requested map is applied here; Servux only reproduces that same state when present.
     */
    public static BlockState placementState(BlockItem item, BlockPlaceContext physical, PrinterPlaceStatePayload payload) {
        Map<String, String> requested = payload.properties();
        if (payload.blockId() == null || payload.blockId().length() > PrinterPlaceStatePayload.MAX_BLOCK_ID_LENGTH
                || !BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString().equals(payload.blockId())
                || requested == null || requested.size() > PrinterPlaceStatePayload.MAX_PROPERTIES) {
            throw new IllegalArgumentException("Invalid structured direct-place block");
        }
        BlockState normal = item.getBlock().getStateForPlacement(physical);
        if (normal == null) return null;
        BlockState desired = withRequested(normal, requested);
        if (API == null) return desired;
        Vec3 protocolHit = protocolHit(normal, desired, physical.getClickedPos(), firstDirection(normal));
        try {
            // The original physical context remains the item context for Servux's placement checks.
            Object context = API.context.newInstance(physical.getLevel(), physical.getClickedPos(), physical.getClickedFace(),
                    protocolHit, physical.getPlayer(), physical.getHand(), physical);
            Object result = API.decode.invoke(null, normal, context);
            // Servux only decodes the bit field packed above for it. If the state it hands back does
            // not carry the properties the schematic asked for, that packed layout is out of sync
            // with the installed Servux. Placing it anyway would look like "the printer rotated the
            // block wrong" -- silent, and impossible to trace back to this line. Refuse instead.
            return result instanceof BlockState state && requestedSurvived(normal, desired, state) ? state : null;
        } catch (ReflectiveOperationException failure) { throw apiFailure(failure); }
    }

    /** Apply the requested properties the way easy place does, keeping every world-driven one. */
    private static BlockState withRequested(BlockState normal, Map<String, String> requested) {
        BlockState desired = normal;
        Property<?> firstDirection = firstDirection(normal);
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.isBlank() || name.length() > PrinterPlaceStatePayload.MAX_PROPERTY_NAME_LENGTH
                    || value == null || value.length() > PrinterPlaceStatePayload.MAX_PROPERTY_VALUE_LENGTH) {
                throw new IllegalArgumentException("Invalid structured direct-place property");
            }
            Property<?> property = normal.getBlock().getStateDefinition().getProperty(name);
            if (property == null) {
                throw new IllegalArgumentException("Unknown structured direct-place property");
            }
            Comparable<?> parsed = property.getValue(value).orElse(null);
            if (parsed == null) {
                throw new IllegalArgumentException("Unknown structured direct-place property value");
            }
            boolean directional = property == firstDirection && property != BlockStateProperties.VERTICAL_DIRECTION;
            if (BLACKLISTED.containsKey(property) || (!directional && !WHITELISTED.contains(property))) continue;
            // One reserved item cannot become two slabs. Easy place leaves that to a second pass,
            // where the ordinary place-on-slab rule turns the pair into the double slab asked for.
            if (SlabType.DOUBLE.equals(parsed)) continue;
            desired = with(desired, property, parsed);
        }
        return desired;
    }

    /** Pack semantic fields solely for the installed Servux public decoder, following its V3 wire field order. */
    private static Vec3 protocolHit(BlockState normal, BlockState desired, BlockPos target, Property<?> firstDirection) {
        long fields = 0;
        int shift = 1;
        int count = 0;
        if (firstDirection != null && firstDirection != BlockStateProperties.VERTICAL_DIRECTION) {
            fields |= (long) ((Direction) desired.getValue(firstDirection)).get3DDataValue() << shift;
            shift += 3;
            count++;
        }
        List<Property<?>> properties = new ArrayList<>(normal.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        for (Property<?> property : properties) {
            if (property == firstDirection || !WHITELISTED.contains(property) || BLACKLISTED.containsKey(property)) continue;
            List<Comparable<?>> values = values(property);
            int width = 32 - Integer.numberOfLeadingZeros(values.size() - 1);
            if (shift + width > 30) throw new IllegalArgumentException("Unsupported Servux placement property layout");
            int index = values.indexOf(desired.getValue(property));
            if (index < 0) throw new IllegalArgumentException("Unsupported Servux placement property value");
            fields |= (long) index << shift;
            shift += width;
            count++;
        }
        return count == 0 ? Vec3.atLowerCornerOf(target) : new Vec3(target.getX() + 2.0 + fields, target.getY(), target.getZ());
    }

    /**
     * Read-back check on the round trip through Servux: every property this bridge asked to change
     * must come back exactly as asked. Properties nobody touched are deliberately not compared --
     * Servux decodes from the same {@code normal} state, so only the encoded bits can drift.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean requestedSurvived(BlockState normal, BlockState desired, BlockState actual) {
        if (actual.getBlock() != desired.getBlock()) {
            return false;
        }
        for (Property property : desired.getProperties()) {
            Object asked = desired.getValue(property);
            if (!asked.equals(normal.getValue(property)) && !asked.equals(actual.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    private static Property<?> firstDirection(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?> && property.getValueClass() == Direction.class) return property;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Comparable<?>> values(Property<?> property) {
        List values = new ArrayList(property.getPossibleValues());
        values.sort(Comparator.naturalOrder());
        return values;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState with(BlockState state, Property property, Comparable value) { return state.setValue(property, value); }

    private static IllegalStateException apiFailure(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
        return new IllegalStateException("Servux placement API failed", cause);
    }

    public static Scope enter(BlockItem item, BlockPlaceContext physical, PrinterPlaceStatePayload payload) {
        Scope scope = new Scope(item, physical, payload, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static boolean matches(BlockItem item, BlockPlaceContext context) {
        Scope scope = CURRENT.get();
        return scope != null && scope.item == item && scope.context == context && context.getLevel() == scope.level
                && context.getPlayer() == scope.player && context.getItemInHand() == scope.stack
                && context.getClickedPos().equals(scope.payload.placement().target());
    }

    public static BlockState scopedState(BlockItem item, BlockPlaceContext context) {
        Scope scope = CURRENT.get();
        return matches(item, context) && scope.player instanceof ServerPlayer player && hasPermission(player)
                ? placementState(item, context, scope.payload) : null;
    }

    private record Binding(Object config, Method permission, Method decode, Constructor<?> context) { }

    public static final class Scope implements AutoCloseable {
        private final BlockItem item;
        private final BlockPlaceContext context;
        private final PrinterPlaceStatePayload payload;
        private final Level level;
        private final net.minecraft.world.entity.player.Player player;
        private final ItemStack stack;
        private final Scope previous;

        private Scope(BlockItem item, BlockPlaceContext context, PrinterPlaceStatePayload payload, Scope previous) {
            this.item = item;
            this.context = context;
            this.payload = payload;
            this.level = context.getLevel();
            this.player = context.getPlayer();
            this.stack = context.getItemInHand();
            this.previous = previous;
        }

        @Override public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
