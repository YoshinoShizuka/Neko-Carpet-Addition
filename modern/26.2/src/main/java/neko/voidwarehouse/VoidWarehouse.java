package neko.voidwarehouse;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 虚空仓库的门面：负责「拿到那份全服唯一的数据」和「给玩家打开界面」。
 */
public final class VoidWarehouse {
    private static final Logger LOGGER = LoggerFactory.getLogger("carpet_neko_addition/void_warehouse");

    /** 仓库文件相对 {@code <维度目录>/data/} 的路径（必须与 {@link VoidWarehouseData#TYPE} 的 id 一致） */
    private static final String DATA_FILE = "carpet_neko_addition/void_warehouse.dat";

    /**
     * 本次进程里是否已经报过「空壳」错误。
     *
     * <p>这个错误一旦出现就会每次 {@link #data} 都命中，每 tick 刷一次日志会淹掉整个
     * 日志文件；只在第一次喊，之后静默拒绝。</p>
     */
    private static boolean shellReported;

    /** 写盘前快照是否已经做过（每进程一次） */
    private static boolean backupDone;

    private VoidWarehouse() {
    }

    /**
     * 取全服唯一的仓库数据。
     *
     * <p>固定存进<b>主世界</b>的 {@code SavedDataStorage}，所以是「全存档唯一、跨维度共享」。
     * 若要改成「每个维度各一份」，把 {@code server.overworld()} 换成 {@code player.serverLevel()} 即可。</p>
     *
     * <p><b>读存档之前先把排列顺序准备好</b>（{@link VoidWarehouseSort}）：这一句保证了
     * 第一次载入进来的条目就是按创造栏顺序摆的，不存在「先按注册顺序显示、之后才纠正」的中间态。
     * 构建本身是幂等的，而且失败也只会退回注册表顺序，不会影响仓库能不能用。</p>
     *
     * <h2>空壳闸门（防「静默清空」的关键）</h2>
     * <p>26.3 的 {@code SavedDataStorage.computeIfAbsent} 有这么个行为：只要
     * {@code readSavedData} 返回 null（文件不存在、或整份解码失败），它会<b>静默</b>
     * 调构造函数造一个<b>全新的空实例</b>并 {@code setDirty()}；退出时这个空仓库
     * 会正常写盘，把原来的存档整个盖掉。这是「进一次存档，东西全没了」的唯一成因。</p>
     *
     * <p>所以这里在拿到实例后立刻校验：磁盘上<b>确实有</b>这个文件、而拿到的实例
     * 却「读进来 N 条、一格子都没落下」（{@link VoidWarehouseData#isSuspiciouslyEmpty()}），
     * 那就是空壳，直接抛异常拒绝服务 —— <b>宁可这一局用不了仓库，也绝不让空壳写盘</b>。
     * 数据层本身已做到「单条坏不牵连整表」，走到这一步说明问题比单条坏数据严重得多。</p>
     */
    public static VoidWarehouseData data(MinecraftServer server) {
        ServerLevel overworld = server.overworld();

        // ---- 写盘前快照：本局第一次拿到仓库时，先把磁盘上的上一版留一份 .bak ----
        backupOnce(server, overworld);

        VoidWarehouseSort.prepare(overworld.registryAccess());
        VoidWarehouseData data = overworld.getDataStorage().computeIfAbsent(VoidWarehouseData.TYPE);

        // 空壳判定分两路，两条都必须拦：
        //   ① 读到了记录、却一条都没还原成物品 → 解码整片失败（见 VoidWarehouseData）。
        //   ② 磁盘上明明有条目，实例却是个全新空对象（loadingCount 还是 0）→
        //      computeIfAbsent 走了「新建」分支，也就是 readSavedData 返回了 null。
        // ② 是关键：这条路上的实例由无参构造造出来，光看它自己什么都看不出来，
        // 必须回磁盘点一遍条目数才知道「本该有多少」。
        int onDisk = countItemsOnDisk(server, overworld);
        if (data.isSuspiciouslyEmpty() || (onDisk > 0 && data.loadedCount() == 0 && data.isEmpty())) {
            if (!shellReported) {
                shellReported = true;
                LOGGER.error("虚空仓库读到了空壳：文件 {} 里实际有 {} 条记录，"
                                + "载入后却是 0 格（读取计数 {}，其中 {} 条读不懂）。"
                                + "已拒绝本次访问，避免空仓库写盘覆盖存档。"
                                + "请立即停止游戏并检查该文件（同目录下有 .bak 快照）。",
                        dataFile(server, overworld), onDisk, data.loadedCount(), data.loadedBrokenCount());
            }
            throw new IllegalStateException(
                    "虚空仓库数据异常（磁盘上有 " + onDisk + " 条，载入后却为 0 格），已拒绝访问以保护存档；"
                            + "详情见日志与 " + DATA_FILE + ".bak");
        }

        return data;
    }

    /** 给玩家打开虚空仓库界面，成功返回 true */
    public static boolean open(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        player.openMenu(new VoidWarehouseProvider(data(server)));
        return true;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 仓库文件在磁盘上的实际位置。
     *
     * <p>路径拼法必须与 {@code ServerChunkCache} 建 {@code SavedDataStorage} 时一致：
     * {@code DimensionType.getStorageFolder(维度, 存档根).resolve("data").resolve("ns/path.dat")}。
     * 这里直接复用 {@link DimensionType#getStorageFolder} —— 它是游戏内部同一个方法，
     * 所以永远不会和真实路径跑偏（自己拼字符串就有跑偏的风险）。</p>
     */
    private static Path dataFile(MinecraftServer server, ServerLevel level) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path dimension = DimensionType.getStorageFolder(level.dimension(), root);
        return dimension.resolve("data").resolve(DATA_FILE);
    }

    /**
     * 直接读磁盘文件，数出 {@code data.items} 里一共有多少条记录。
     *
     * <p>这是<b>独立于 {@code SavedData} 加载流程</b>的第二意见：{@code getDataStorage()}
     * 那边有可能「静默地」把文件当成不存在、或把内容整片丢掉，只有自己点一遍才知道
     * 磁盘上本该有多少。读 4KB 的小文件，且只在拿到实例时做一次（进程级缓存），开销可忽略。</p>
     *
     * <p>文件不存在 / 读不出来一律返回 0 —— 那时「空」是正常的，不该拦。</p>
     */
    private static int countItemsOnDisk(MinecraftServer server, ServerLevel overworld) {
        if (diskCountChecked) {
            return diskItemCount;
        }
        diskCountChecked = true;
        try {
            Path file = dataFile(server, overworld);
            if (!Files.isRegularFile(file)) {
                return 0;
            }
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            // 文件结构：{DataVersion:int, data:{items:[...]}}，条目在 root.data.items 下
            diskItemCount = root.getCompound(DATA_KEY).map(inner -> inner.getList(ITEMS_KEY))
                    .flatMap(java.util.function.Function.identity())
                    .map(ListTag::size)
                    .orElse(0);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("虚空仓库读盘自检失败（按 0 处理，不影响正常使用）", e);
        }
        return diskItemCount;
    }

    /** 与 {@link VoidWarehouseData#CODEC} 里 {@code items} 字段同名，这里只用来数条数 */
    private static final String ITEMS_KEY = "items";
    /** 存档文件的固定外层字段：{@code {DataVersion:int, data:{...}}} */
    private static final String DATA_KEY = "data";

    private static boolean diskCountChecked;
    private static int diskItemCount;

    /**
     * 写盘前快照：把当前磁盘上的仓库文件复制成 {@code .bak}。
     *
     * <p>只在<b>本次进程第一次</b>触碰仓库时做一次 —— 每 tick 都复制一遍既浪费，
     * 又会让快照本身变成「刚才那份已经写坏的」。一局一次，语义正好是「进入存档时的
     * 上一版」，出问题用它就能回滚。</p>
     *
     * <p>整个动作纯 IO、失败也只记日志：<b>绝不能因为备份失败就让仓库用不了</b>。</p>
     */
    private static void backupOnce(MinecraftServer server, ServerLevel overworld) {
        if (backupDone) {
            return;
        }
        backupDone = true;
        try {
            Path source = dataFile(server, overworld);
            if (!Files.isRegularFile(source)) {
                return;
            }
            Path target = source.resolveSibling(source.getFileName() + ".bak");
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            LOGGER.info("虚空仓库已在写盘前留出快照：{}", target.getFileName());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("虚空仓库写盘前快照失败（不影响正常使用）", e);
        }
    }
}
