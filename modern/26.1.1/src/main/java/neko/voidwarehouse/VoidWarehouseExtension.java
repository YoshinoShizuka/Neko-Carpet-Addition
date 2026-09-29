package neko.voidwarehouse;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import neko.voidwarehouse.restock.VoidWarehouseRestockSettings;

/**
 * 虚空仓库的 Carpet 扩展。
 *
 * <p>与 {@code AutoMendingExtension} 并存，各自注册自己的规则类；
 * 这样不需要改动 AutoMending 的任何逻辑。</p>
 */
public class VoidWarehouseExtension implements CarpetExtension {
    private static final VoidWarehouseExtension INSTANCE = new VoidWarehouseExtension();

    private VoidWarehouseExtension() {
    }

    public static void init() {
        CarpetServer.manageExtension(INSTANCE);
    }

    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(VoidWarehouseSettings.class);
        // 补货规则单独成类：它只存在于能用 Fabric networking 的那几个版本
        CarpetServer.settingsManager.parseSettingsClass(VoidWarehouseRestockSettings.class);
    }

    /**
     * 规则被关掉时，已打开的界面必须安全关闭。
     *
     * <p>{@code closeContainer()} 会让原版调用 {@code removed()}，
     * 而 {@code removed()} 会把光标上的物品放回背包（塞不下就存进仓库），
     * 所以仓库内容会原地留在存档里，不会掉落也不会清空。</p>
     */
    @Override
    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof VoidWarehouseMenu) {
                player.closeContainer();
            }
        }
    }
}
