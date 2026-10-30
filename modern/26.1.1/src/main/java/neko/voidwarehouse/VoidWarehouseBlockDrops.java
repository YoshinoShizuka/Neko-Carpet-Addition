package neko.voidwarehouse;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 「方块掉落物直接进虚空仓库」（{@link VoidWarehouseSettings#voidWarehouseBlockDrops}）的上下文与收料口。
 *
 * <h2>为什么要一个「当前正在挖方块的人」</h2>
 * <p>掉落物本身不知道自己是谁挖出来的 —— {@code Block#popResource} 造出来的
 * {@link net.minecraft.world.entity.item.ItemEntity} 只是「世界里多了一个物品实体」，
 * 挖方块、爆掉、怪物死亡、玩家丢弃，走的是<b>同一个</b> {@code ServerLevel#addFreshEntity}。
 * 想把范围严格限制在「玩家挖掉的方块」上，唯一可靠的办法是在
 * {@code ServerPlayerGameMode#destroyBlock} 这一段里挂一个上下文，
 * 让这期间的 {@code addFreshEntity} 知道自己正处在「某人挖方块」之中。</p>
 *
 * <p>所以这里存的是<b>一个 static</b>：服务端的方块破坏全在主线程上跑，
 * 不存在并发；{@code destroyBlock} 也不会自我嵌套（破坏一个方块只跑一次）。
 * 用 static 而不引 ThreadLocal / ScopedValue，是因为后者在 Java 21 上还是预览特性，
 * 而本模组的 1.21.11 那一轨正好锁在 Java 21。</p>
 *
 * <h2>泄漏兜底：tick 戳</h2>
 * <p>{@link #begin} 与 {@link #end} 成对挂在 {@code destroyBlock} 的 HEAD / RETURN 上。
 * 万一 {@code destroyBlock} 抛了异常（模组冲突、方块自己炸了），RETURN 那份不会执行、
 * 上下文就留在了 static 上 —— 那会让<b>之后世界里每一个物品实体</b>都被吸进仓库，
 * 属于最不能接受的一类故障。所以 {@link #begin} 同时记下当时的服务端 tick，
 * {@link #collect} 只接受<b>同一 tick</b> 的掉落物：即使真漏掉了 {@code end}，
 * 最多也就影响本次 tick 的剩余部分，下一 tick 自动失效。
 * 「挖方块产生掉落」本来就全在这一次调用里同步完成，同 tick 这个条件不会漏收。</p>
 */
public final class VoidWarehouseBlockDrops {

    /** 正在挖方块的玩家；{@code null} = 当前不在「玩家挖方块」之中 */
    private static ServerPlayer breaker;

    /** {@link #breaker} 生效的那个服务端 tick（泄漏兜底用，见类注释） */
    private static int breakerTick;

    private VoidWarehouseBlockDrops() {
    }

    /** 规则开着吗：主开关 + 本条规则都要开（与 {@code craftEnabled} 同一形状） */
    public static boolean enabled() {
        return VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseBlockDrops;
    }

    /** 进入「某个玩家开始挖一个方块」，{@code destroyBlock} 的 HEAD 调用 */
    public static void begin(ServerPlayer player) {
        if (player == null || !enabled()) {
            // 关着的时候连上下文都不立 —— 平时 addFreshEntity 的代价就只剩一次 null 读
            breaker = null;
            return;
        }
        breaker = player;
        breakerTick = tickOf(player.level() instanceof ServerLevel level ? level.getServer() : null);
    }

    /** 离开「挖方块」，{@code destroyBlock} 的 RETURN 调用（无论成功失败都清） */
    public static void end() {
        breaker = null;
    }

    /**
     * 把一份掉落物收进虚空仓库。
     *
     * @param level 掉落物要进入的那个服务端世界（同 tick 校验用）
     * @param stack 掉落物的堆叠。<b>成功时 {@code insert} 会把它就地清零</b>，
     *              调用方（mixin）据此拦下这个实体，所以世界上不会同时出现两份
     * @return {@code true} = 已经收进仓库（调用方必须拦下这个物品实体）；
     *         {@code false} = 不收，照原样掉在地上
     */
    public static boolean collect(ServerLevel level, ItemStack stack) {
        ServerPlayer player = breaker;
        if (player == null || level == null) {
            return false;
        }
        if (!enabled()) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || server.getTickCount() != breakerTick) {
            // 上下文是上一次挖方块漏下来的（见类注释的「tick 戳」），当它不存在
            breaker = null;
            return false;
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            VoidWarehouseData data = VoidWarehouse.data(server);
            if (data == null) {
                return false;
            }
            // insert 的语义就是「入库」：潜影盒按与漏斗一致的方式拆开、其余按物品 + 数据组件归并，
            // 成功时会把 stack 清零。它返回 false（空物品）时 stack 原样不动，正好交给原版继续掉。
            return data.insert(stack);
        } catch (RuntimeException ex) {
            // 仓库读不出来（存档闸门）或任何意外：这条规则绝不能把玩家的掉落物弄没
            return false;
        }
    }

    private static int tickOf(MinecraftServer server) {
        return server == null ? Integer.MIN_VALUE : server.getTickCount();
    }
}
