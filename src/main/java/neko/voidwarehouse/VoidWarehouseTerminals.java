package neko.voidwarehouse;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「仓库终端」的登记表：世界上哪些坐标上的工作台是虚空仓库的入口。
 *
 * <h2>为什么要单独记一份坐标</h2>
 * <p>原版工作台<b>没有方块实体</b>，改名只是物品上的一个数据组件，放下之后那个名字就没了 ——
 * 所以「这个工作台是不是虚空仓库」不可能从方块本身读出来。加上一条硬约束：
 * 要让<b>纯原版客户端</b>也能用（方案 A），就不能给工作台加自定义方块状态 / 方块实体
 * （会改变方块状态数量与客户端注册表，原版客户端直接报错）。</p>
 *
 * <p>于是改用最轻的做法：<b>把位置记在存档里</b>。放下时登记、被破坏 / 被活塞推走时注销，
 * 查询时再顺手校验「这个坐标现在还是不是工作台」双保险。</p>
 *
 * <h2>登记与注销</h2>
 * <ul>
 *   <li>登记：{@code Block.setPlacedBy} —— 用「虚空仓库」物品放下一个工作台时（潜行 + 右键方块）；</li>
 *   <li>注销：{@code BlockBehaviour.affectNeighborsAfterRemoval} —— 方块被破坏 / 被替换 / 被活塞推走；</li>
 *   <li>兜底：同一个位置又放下一个<b>普通</b>工作台时也注销一次，
 *       万一上面两条因为外部编辑存档而漏了，也不会出现「普通工作台被当成仓库」的假终端。</li>
 * </ul>
 *
 * <p>存档固定写在主世界的 {@code SavedDataStorage} 上（与仓库内容同一份存档），
 * 记录用 {@link GlobalPos} 带上维度，所以跨维度的终端不会互相串。</p>
 */
public final class VoidWarehouseTerminals extends SavedData {
    private static final String TERMINALS_KEY = "terminals";

    public static final Codec<VoidWarehouseTerminals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GlobalPos.CODEC.listOf()
                    .optionalFieldOf(TERMINALS_KEY, List.of())
                    .forGetter(VoidWarehouseTerminals::rawList)
    ).apply(instance, VoidWarehouseTerminals::new));

    public static final SavedDataType<VoidWarehouseTerminals> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "void_warehouse_terminals"),
            VoidWarehouseTerminals::new,
            CODEC,
            DataFixTypes.LEVEL);

    private final Set<GlobalPos> terminals = new HashSet<>();

    public VoidWarehouseTerminals() {
    }

    VoidWarehouseTerminals(List<GlobalPos> loaded) {
        this.terminals.addAll(loaded);
    }

    private List<GlobalPos> rawList() {
        return new ArrayList<>(this.terminals);
    }

    // ------------------------------------------------------------------ 查

    /**
     * 这个坐标上的方块是不是一个「仓库终端」。
     *
     * <p>三重判定：坐标必须是工作台（快筛，绝大多数调用在这里就返回了）、
     * 维度 + 坐标在登记表里、且规则是开着的。</p>
     */
    public static boolean isTerminal(ServerLevel level, BlockPos pos) {
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return false;
        }
        return get(level).terminals.contains(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    /** 登记表里现在有多少个终端（诊断用） */
    public static int size(ServerLevel level) {
        return get(level).terminals.size();
    }

    // ------------------------------------------------------------------ 写

    /** 把一个工作台登记成仓库终端 */
    public static void add(ServerLevel level, BlockPos pos) {
        VoidWarehouseTerminals data = get(level);
        if (data.terminals.add(GlobalPos.of(level.dimension(), pos.immutable()))) {
            data.setDirty();
        }
    }

    /** 注销一个仓库终端（没有登记过就什么都不做） */
    public static void remove(ServerLevel level, BlockPos pos) {
        VoidWarehouseTerminals data = get(level);
        if (data.terminals.remove(GlobalPos.of(level.dimension(), pos.immutable()))) {
            data.setDirty();
        }
    }

    /**
     * 取那份「全存档唯一」的登记表：固定挂在主世界的存档储存上，
     * 所以任何维度都能查到同一份登记（与 {@link VoidWarehouse#data} 同一处）。
     */
    private static VoidWarehouseTerminals get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
