package neko.voidwarehouse;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 「这条配方要不要记住」的待确认状态机：<b>问一句，玩家点一下，才真的记。</b>
 *
 * <h2>为什么要一个待确认态</h2>
 * <p>1.0.17 之前是「合成了就记」—— 玩家没有选择权。现在改成问一句，于是
 * 「记住」这一步必须从 {@code ResultSlot#onTake} 里拆出来，中间挂一个 PENDING：
 * 合成那一刻<b>只发问、不写配方本</b>，等玩家点了才写。</p>
 *
 * <h2>🔴 一条指令都不加：走原版的「自定义点击」通道</h2>
 * <p>聊天里的可点击组件，过去只有 {@code RunCommand} 一条路，而那条路必须注册指令。
 * 1.21.6 起原版提供了另一条：{@link ClickEvent.Custom}（自定义 id + NBT 负载）——
 * 客户端点下去会发 {@code ServerboundCustomClickActionPacket}，服务端按 id 分派。
 * <b>原版客户端自己就会发</b>（已 javap 核实：{@code Screen} 里构造这条包），
 * 所以既不用改客户端，也不用注册任何指令。</p>
 * <p>代价是服务端要自己接一下：原版 {@code MinecraftServer#handleCustomClickAction}
 * 目前只是把 id 和负载写进 debug 日志，什么也不做。所以本模组挂了一个 mixin
 * （{@code CustomClickMixin}）把它截下来。</p>
 *
 * <h2>三个按钮，同一个 id，靠负载里的 {@code d} 区分</h2>
 * <pre>
 *   { t: "令牌", d: "y" }  记住这一条
 *   { t: "令牌", d: "n" }  不要这一条
 *   { t: "令牌", d: "a" }  总是记住（记住这一条 + 本局不再问）
 * </pre>
 * <p>令牌是每位玩家每次提问随机生成的，<b>只能本人用、只能用一次</b>：
 * 点击带来的是「谁」由服务端从连接里取（{@code ServerGamePacketListenerImpl#player}），
 * 载荷里只有令牌，<b>没有任何身份信息可伪造</b>。</p>
 *
 * <h2>为什么「不要」和「总是记住」只活在本局</h2>
 * <p>玩家明确要求<b>不新增任何指令</b>，所以没有「改回来」的命令可用。落盘则意味着
 * 误点一次永久生效、且再没有撤销入口 —— 那是最糟的组合。折中：两个开关都只在内存里，
 * <b>重新登录即恢复「一条条问」</b>，并且切换时把这件事明写出来（{@link #autoOnLine}）。
 * 把状态说清楚，比让玩家去记一条命令可靠。</p>
 *
 * <h2>节流</h2>
 * <ul>
 *   <li>同一条配方<b>只有一个</b>待确认（shift 连合不会刷屏）；</li>
 *   <li>每人同时最多 {@link #MAX_PENDING} 条，超了丢最旧的（旧按钮点下去 = 「已处理过」）；</li>
 *   <li>{@link #TTL_TICKS} 之后过期，点击时惰性判断 —— 不需要每 tick 扫一遍。</li>
 * </ul>
 */
public final class VoidWarehouseRecipePrompt {

    /**
     * 点击事件的 id：{@code carpet_neko_addition:recipe_confirm}。
     *
     * <p>用本模组的命名空间，和搜索通道、打印机通道同一个前缀风格。id 必须<b>全局唯一</b> ——
     * 别的模组撞上同一个 id 时，两边都会收到对方的点击（服务端按 id 分派，没有"认领"机制）。</p>
     */
    public static final Identifier CLICK_ID =
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_confirm");

    /** 一条待确认挂多久（tick）：60 秒。过期点下去只说一句「已处理过」，不会静默无事发生 */
    public static final int TTL_TICKS = 1200;

    /** 每人同时最多挂几条；超了丢最旧的 */
    public static final int MAX_PENDING = 3;

    /** 最多跟踪多少个玩家的状态；超了按 LRU 淘汰（代价只是重登一次似的再问一遍） */
    private static final int MAX_TRACKED = 512;

    private static final Map<UUID, State> STATE =
            new LinkedHashMap<UUID, State>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, State> eldest) {
                    return size() > MAX_TRACKED;
                }
            };

    private VoidWarehouseRecipePrompt() {
    }

    /** 一条待确认 */
    private static final class Pending {
        final VoidWarehouseRecipes.Recipe recipe;
        final List<VoidWarehouseRecipes.Recipe> othersBefore;
        final boolean en;
        final long expireTick;

        Pending(VoidWarehouseRecipes.Recipe recipe, List<VoidWarehouseRecipes.Recipe> othersBefore,
                boolean en, long expireTick) {
            this.recipe = recipe;
            this.othersBefore = othersBefore;
            this.en = en;
            this.expireTick = expireTick;
        }
    }

    /** 每玩家的开关与待确认 */
    private static final class State {
        /** 令牌 → 待确认（插入序 = 先后序，丢最旧时取第一个） */
        final Map<String, Pending> byToken = new LinkedHashMap<>();
        /** recipeId → 令牌：同一条配方不重复提问 */
        final Map<String, String> tokenByRecipe = new HashMap<>();
        /** 本局点过「不要」的配方 */
        final Set<String> refused = new HashSet<>();
        /** 本局「总是记住」 */
        boolean auto;
        /** 令牌计数器（只是为了让令牌每次不同，不做安全用途） */
        long serial;
    }

    private static State state(UUID who) {
        return STATE.computeIfAbsent(who, key -> new State());
    }

    /**
     * 造一个点击事件。
     *
     * @param decision {@code y} 记住 / {@code n} 不要 / {@code a} 总是记住
     */
    public static ClickEvent click(String token, String decision) {
        CompoundTag tag = new CompoundTag();
        tag.putString("t", token);
        tag.putString("d", decision);
        return new ClickEvent.Custom(CLICK_ID, Optional.of(tag));
    }

    /**
     * 这次合成要不要<b>问一句</b>（{@code false} = 走老路，立即记住）。
     *
     * <p>四种情况不问，直接按老路走：</p>
     * <ol>
     *   <li>这条已经在配方本里了 → 老路会给出「早就记过」那一条（每玩家一次）；</li>
     *   <li>配方本满了 → 老路会给出「已满」；此时问也没用，玩家点「记住」照样记不进去；</li>
     *   <li>玩家本局选过「总是记住」；</li>
     *   <li>玩家本局对这条点过「不要」。</li>
     * </ol>
     * <p>另外「已经挂着同一条」时也返回 {@code false}，但<b>不发任何消息</b> ——
     * 见 {@link #isPending} 与 {@link #ask} 的分工：那种情况既不该问，也不该报「已记住」。</p>
     */
    public static boolean shouldAsk(ServerPlayer player, VoidWarehouseData data,
                                    VoidWarehouseRecipes.Recipe recipe, int max) {
        if (player == null || data == null || recipe == null) {
            return false;
        }
        if (data.knows(recipe)) {
            return false;
        }
        if (data.recipeBookUsed() >= max) {
            return false;
        }
        State st = state(player.getUUID());
        return !st.auto && !st.refused.contains(recipe.recipeId());
    }

    /** 这条配方是否已经挂着一条待确认（shift 连合时用来判断「别再问一遍」） */
    public static boolean isPending(ServerPlayer player, VoidWarehouseRecipes.Recipe recipe) {
        if (player == null || recipe == null) {
            return false;
        }
        return state(player.getUUID()).tokenByRecipe.containsKey(recipe.recipeId());
    }

    /**
     * 挂一条待确认，返回<b>要发出去的那一行</b>（三个按钮）。
     *
     * <p>调用前必须已经问过 {@link #shouldAsk} 与 {@link #isPending}。为防误用，
     * 这里再判一次：已经挂着就返回 {@code null}（不发第二遍）。</p>
     */
    public static Component ask(ServerPlayer player, boolean en, VoidWarehouseData data,
                                VoidWarehouseRecipes.Recipe recipe, long nowTick) {
        if (player == null || data == null || recipe == null) {
            return null;
        }
        State st = state(player.getUUID());
        if (st.tokenByRecipe.containsKey(recipe.recipeId())) {
            return null;
        }
        // 超上限：先丢最旧的那一条（它的令牌随即作废，点下去就是「已处理过」）
        while (st.byToken.size() >= MAX_PENDING) {
            String oldest = st.byToken.keySet().iterator().next();
            Pending dropped = st.byToken.remove(oldest);
            if (dropped != null) {
                st.tokenByRecipe.remove(dropped.recipe.recipeId());
            }
        }
        String token = Long.toString(++st.serial, 36) + Integer.toString(player.getUUID().hashCode(), 36);
        List<VoidWarehouseRecipes.Recipe> others = data.recipesFor(recipe.output());
        st.byToken.put(token, new Pending(recipe, others, en, nowTick + TTL_TICKS));
        st.tokenByRecipe.put(recipe.recipeId(), token);
        return VoidWarehouseRecipeNotice.askLine(en, recipe, token);
    }

    /**
     * 处理一次点击（由 {@code CustomClickMixin} 调）。
     *
     * @return 这条点击事件是不是我们的（{@code false} 时调用方原样放行给原版）
     */
    public static boolean handleClick(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        if (player == null || id == null || !CLICK_ID.equals(id)) {
            return false;
        }
        boolean en = VoidWarehouseText.isEnglish(player);
        String token = "";
        String decision = "";
        if (payload.isPresent() && payload.get() instanceof CompoundTag tag) {
            token = tag.getStringOr("t", "");
            decision = tag.getStringOr("d", "");
        }
        State st = state(player.getUUID());
        Pending pending = token.isEmpty() ? null : st.byToken.remove(token);
        if (pending != null) {
            st.tokenByRecipe.remove(pending.recipe.recipeId());
        }
        long now = serverTickOf(player);
        // 令牌认不出来 / 已经用掉 / 已经过期 —— 三种都归到同一句话。
        // 不静默：玩家点了却什么都不发生，会以为界面坏了（「什么都没说 = 坏掉」的老教训）。
        if (pending == null || now > pending.expireTick) {
            player.sendSystemMessage(VoidWarehouseRecipeNotice.alreadyHandledLine(en));
            return true;
        }
        if ("n".equals(decision)) {
            st.refused.add(pending.recipe.recipeId());
            player.sendSystemMessage(VoidWarehouseRecipeNotice.refusedLine(en));
            return true;
        }
        boolean always = "a".equals(decision);
        if (always) {
            // 先切开关再记：万一下面 learn 抛异常，玩家的「别再问了」也照样生效
            st.auto = true;
        }
        VoidWarehouseData data = VoidWarehouse.data(player.level().getServer());
        if (data == null) {
            player.sendSystemMessage(VoidWarehouseRecipeNotice.alreadyHandledLine(en));
            return true;
        }
        // 重新学一次并按<b>真实结局</b>出文案：这 60 秒里可能别人也记了同一条（DUPLICATE）、
        // 也可能配方本刚好被填满（FULL）。文案仍然只有 VoidWarehouseRecipeNotice 一份。
        List<Component> out = new ArrayList<>(4);
        VoidWarehouseData.LearnOutcome outcome = data.learnCapturingOthers(pending.recipe);
        out.addAll(VoidWarehouseRecipeNotice.onCrafted(
                player.getUUID(), pending.en, outcome.result(), pending.recipe,
                pending.othersBefore, data.recipeBookUsed(),
                VoidWarehouseRecipeBook.MAX_RECIPES, now));
        if (always) {
            out.add(VoidWarehouseRecipeNotice.autoOnLine(pending.en));
        }
        for (Component line : out) {
            player.sendSystemMessage(line);
        }
        return true;
    }

    /** 本局是否已切到「总是记住」（自检用；产品侧不需要读它） */
    public static boolean isAuto(UUID who) {
        return who != null && state(who).auto;
    }

    /** 本局是否对这条点过「不要」（自检用） */
    public static boolean isRefused(UUID who, VoidWarehouseRecipes.Recipe recipe) {
        return who != null && recipe != null && state(who).refused.contains(recipe.recipeId());
    }

    /**
     * 这位玩家此刻挂着几条待确认（自检用；产品侧不需要读它）。
     *
     * <p>自检要咬「shift 连合不会刷屏」这条，而刷屏的<b>真实形态</b>就是这里变大 ——
     * 数「发了几条消息」需要拦 {@code sendSystemMessage}（假连接下更脆），
     * 数待确认条数才是直接量。</p>
     */
    public static int pendingCount(UUID who) {
        return who == null ? 0 : state(who).byToken.size();
    }

    /** 清掉一位玩家的全部状态（玩家退出时调，避免 LRU 里挂着已经下线的 UUID） */
    public static void forget(UUID who) {
        if (who != null) {
            STATE.remove(who);
        }
    }

    /**
     * 在模组初始化时调一次：<b>玩家一下线就清掉他的本局开关与待确认</b>。
     *
     * <p>🔴 这不只是「省点内存」。{@code [不要]} / {@code [总是记住]} 的契约是
     * <b>「本次登录内」</b>（见类注释「为什么只活在本局」）—— 不在这条路上清，
     * 玩家退出再进来，{@code auto} 还在，就变成「永远不再问」，
     * 而玩家没有任何界面入口能把这件事改回来（没有指令、没有按钮）。
     * 换句话说：不接这个钩子，那句「重新登录后恢复」就是假的。</p>
     *
     * <p>顺带把待确认一起丢掉：令牌本来就只活 60 秒，重登后旧按钮点下去会回
     * 「已经处理过了」，而不是莫名其妙记进一条配方。</p>
     */
    public static void init() {
        INIT_CALLED = true;
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            if (player != null) {
                forget(player.getUUID());
            }
        });
    }

    /**
     * {@link #init()} 到底被调过没有（自检用）。
     *
     * <p>为什么要这么一个字段：这条钩子是<b>接线</b>，没有任何玩家能直接看见它 ——
     * 漏了它，「重新登录恢复」会静默失效，而所有别的断言照样全绿
     * （效果要等到「退出再进来」才显形，自检跑不到那一步）。
     * 存一个布尔，自检就能咬住「模组初始化时真的调过」。</p>
     */
    public static boolean isInitCalled() {
        return INIT_CALLED;
    }

    private static volatile boolean INIT_CALLED = false;

    /** 自检专用：把状态清空，免得同一次开服里第二套断言被上一套的开关污染 */
    public static void resetForTest() {
        STATE.clear();
    }

    private static long serverTickOf(ServerPlayer player) {
        var server = player.level().getServer();
        return server == null ? 0L : server.getTickCount();
    }
}
