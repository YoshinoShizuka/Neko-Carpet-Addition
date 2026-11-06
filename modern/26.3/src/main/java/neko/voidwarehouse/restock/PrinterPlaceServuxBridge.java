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

/** Optional linkage to Servux's existing placement validator; no Servux classes are bundled or required. */
public final class PrinterPlaceServuxBridge {
    private static final Binding API = bind();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private PrinterPlaceServuxBridge() { }

    public static boolean available() { return API != null; }

    public static boolean hasPermission(ServerPlayer player) {
        if (API == null || player == null) return false;
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
                            LivingEntity.class, InteractionHand.class, BlockPlaceContext.class),
                    (Set<?>) handler.getField("WHITELISTED_PROPERTIES").get(null),
                    (Map<?, ?>) handler.getField("BLACKLISTED_PROPERTIES").get(null));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException failure) {
            LoggerFactory.getLogger("carpet_neko_addition/printer_place").warn(
                    "Servux placement API is unavailable; structured warehouse placement is disabled", failure);
            return null;
        }
    }

    /**
     * Validate semantic input against the actual item, then adapt it to the installed Servux V3 API.
     * Servux's internal legacy vector is never used for permissions, reach or block selection.
     */
    public static BlockState placementState(BlockItem item, BlockPlaceContext physical, PrinterPlaceStatePayload payload) {
        if (API == null) return null;
        Map<String, String> requested = payload.properties();
        if (payload.blockId() == null || payload.blockId().length() > PrinterPlaceStatePayload.MAX_BLOCK_ID_LENGTH
                || !BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString().equals(payload.blockId())
                || requested == null || requested.size() > PrinterPlaceStatePayload.MAX_PROPERTIES) {
            throw new IllegalArgumentException("Invalid structured direct-place block");
        }
        BlockState normal = item.getBlock().getStateForPlacement(physical);
        if (normal == null) return null;
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
            if (property == null || property.getValue(value).isEmpty()) {
                throw new IllegalArgumentException("Unknown structured direct-place property or value");
            }
            boolean directional = property == firstDirection && property != BlockStateProperties.VERTICAL_DIRECTION;
            if (!API.blacklist.containsKey(property) && (directional || API.whitelist.contains(property))) {
                desired = with(desired, property, property.getValue(value).orElseThrow());
            }
        }
        Vec3 protocolHit = protocolHit(normal, desired, physical.getClickedPos(), firstDirection);
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
            if (property == firstDirection || !API.whitelist.contains(property) || API.blacklist.containsKey(property)) continue;
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

    private record Binding(Object config, Method permission, Method decode, Constructor<?> context,
                           Set<?> whitelist, Map<?, ?> blacklist) { }

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
