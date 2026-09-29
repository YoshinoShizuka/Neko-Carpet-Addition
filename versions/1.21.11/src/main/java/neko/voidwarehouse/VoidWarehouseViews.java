package neko.voidwarehouse;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>每个玩家各自的视图状态</b>：当前页码、潜影盒模式、数量排序。
 *
 * <h2>为什么必须单独存一份</h2>
 * <p>这三样都是「我怎么看这个仓库」，而不是「仓库里有什么」，所以它们不能写进
 * {@link VoidWarehouseData}（那会变成「A 翻页 B 也跟着翻」）。它们也<b>不能</b>只存在
 * {@link VoidWarehousePageContainer} 里 —— 那个对象是<b>跟着菜单一起创建、一起销毁</b>的，
 * 于是「开一下数量排序 → 关掉界面 → 再打开」会把它连同状态一起丢掉，
 * 玩家看到的就是「排序开关被复位了」（这是一个真实修过的 bug）。</p>
 *
 * <p>所以这里按玩家 UUID 存一份，跟着存档一起持久化：
 * <b>关界面、重连、甚至重启服务器，回来还是他离开时的样子</b>，
 * 而 A 的状态永远不影响 B。</p>
 *
 * <h2>为什么挂在存档上而不是玩家身上</h2>
 * <p>26.3 的 {@code Entity} 已经<b>没有</b> {@code getPersistentData()} 这类通用附加数据接口了
 * （已用 {@code javap} 核对过），而本项目又不依赖 fabric-api 的 attachment，
 * 所以走和 {@link VoidWarehouseTerminals} 完全一样的路子：一份 {@link SavedData}，
 * 固定挂在主世界的存档储存上。代价只是每个开过界面的玩家多几行 JSON。</p>
 *
 * <h2>写入即标脏</h2>
 * <p>{@link View} 持有创建它的那份 {@code SavedData} 的引用（{@link View#owner}），
 * 任何一个 setter 都会顺手 {@code setDirty()}，所以不需要调用方记得标脏。
 * 反序列化出来的 View 由构造函数统一补上引用。</p>
 */
public final class VoidWarehouseViews extends SavedData {
    private static final String VIEWS_KEY = "views";
    private static final String PAGE_FIELD = "page";
    private static final String SHULKER_FIELD = "shulker";
    private static final String SORT_FIELD = "sort";

    /**
     * 一个玩家的视图状态（可变，界面直接改这一份）。
     *
     * <p>{@link #owner} 只用于「改了就标脏」，不参与序列化（{@code Codec} 只认那三个字段）。</p>
     */
    public static final class View {
        static final Codec<View> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf(PAGE_FIELD, 0).forGetter(View::page),
                Codec.BOOL.optionalFieldOf(SHULKER_FIELD, false).forGetter(View::shulkerMode),
                Codec.BOOL.optionalFieldOf(SORT_FIELD, false).forGetter(View::amountSort)
        ).apply(instance, View::new));

        /** 创建它的那份存档；{@code null} = 兜底用的临时对象（不持久化，但绝不会 NPE） */
        private VoidWarehouseViews owner;

        private int page;
        private boolean shulkerMode;
        private boolean amountSort;

        View() {
        }

        View(int page, boolean shulkerMode, boolean amountSort) {
            this.page = Math.max(0, page);
            this.shulkerMode = shulkerMode;
            this.amountSort = amountSort;
        }

        /** 存档里的原始页码（还没按实际页数夹取） */
        public int page() {
            return this.page;
        }

        public void setPage(int page) {
            this.page = Math.max(0, page);
            this.touch();
        }

        public boolean shulkerMode() {
            return this.shulkerMode;
        }

        public void setShulkerMode(boolean enabled) {
            this.shulkerMode = enabled;
            this.touch();
        }

        public boolean amountSort() {
            return this.amountSort;
        }

        public void setAmountSort(boolean enabled) {
            this.amountSort = enabled;
            this.touch();
        }

        /** 改一下就标脏：调用方不需要（也不应该）自己记得这件事 */
        private void touch() {
            if (this.owner != null) {
                this.owner.setDirty();
            }
        }
    }

    public static final Codec<VoidWarehouseViews> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, View.CODEC)
                    .optionalFieldOf(VIEWS_KEY, Map.of())
                    .forGetter(VoidWarehouseViews::rawViews)
    ).apply(instance, VoidWarehouseViews::new));

    public static final SavedDataType<VoidWarehouseViews> TYPE = new SavedDataType<>(
            "carpet_neko_addition_void_warehouse_views",
            VoidWarehouseViews::new,
            CODEC,
            DataFixTypes.LEVEL);

    /** 玩家 UUID（字符串形式）→ 他自己那份视图状态 */
    private final Map<String, View> views = new HashMap<>();

    public VoidWarehouseViews() {
    }

    VoidWarehouseViews(Map<String, View> loaded) {
        this.views.putAll(loaded);
        // 反序列化出来的 View 还没有 owner，这里统一补上（否则改了不会存盘）
        for (View view : this.views.values()) {
            view.owner = this;
        }
    }

    private Map<String, View> rawViews() {
        return this.views;
    }

    /**
     * 取某个玩家自己的视图状态：<b>关掉界面再打开也还是这一份</b>。
     *
     * <p>拿不到服务端（例如客户端侧或自检里手工造的玩家）时返回一个临时的空白 View ——
     * 状态不持久化，但界面绝对不会因为 NPE 打不开。</p>
     */
    public static View viewFor(Player player) {
        if (player == null) {
            return new View();
        }
        MinecraftServer server = player.level().getServer();
        UUID id = player.getUUID();
        if (server == null || id == null) {
            return new View();
        }
        return get(server).viewOf(id);
    }

    private View viewOf(UUID playerId) {
        String key = playerId.toString();
        View view = this.views.get(key);
        if (view == null) {
            view = new View();
            view.owner = this;
            this.views.put(key, view);
            this.setDirty();
        }
        return view;
    }

    /** 现在记了多少个玩家的视图状态（诊断 / 自检用） */
    public static int size(MinecraftServer server) {
        return get(server).views.size();
    }

    /** 忘掉某个玩家的视图状态（自检用；正式逻辑不调用） */
    public static void forget(MinecraftServer server, UUID playerId) {
        VoidWarehouseViews data = get(server);
        if (data.views.remove(playerId.toString()) != null) {
            data.setDirty();
        }
    }

    /**
     * 取那份「全存档唯一」的视图表：固定挂在主世界的存档储存上，
     * 与仓库内容、终端登记表同一处（和 {@link VoidWarehouseTerminals} 一致）。
     */
    private static VoidWarehouseViews get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
