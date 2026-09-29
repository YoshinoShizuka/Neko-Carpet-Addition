package neko.voidwarehouse;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 虚空仓库的门面：负责「拿到那份全服唯一的数据」和「给玩家打开界面」。
 */
public final class VoidWarehouse {
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
     */
    public static VoidWarehouseData data(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        VoidWarehouseSort.prepare(overworld.registryAccess());
        return overworld.getDataStorage().computeIfAbsent(VoidWarehouseData.TYPE);
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
}
