package neko.automending;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Carpet 扩展本体。
 *
 * <p>相比原版 Carpet GUGU Addition 用 Mixin 注入 {@code ServerPlayer#tick} 的做法，
 * 这里改用 Carpet 官方提供的扩展事件 {@link CarpetExtension#onTick(MinecraftServer)}
 * （每个服务端 tick 开头调用一次），于是整个模组不需要任何 Mixin，
 * 也就没有 mixin 配置文件、conditional-mixin、Mixin 兼容插件这一堆东西。</p>
 */
public class AutoMendingExtension implements CarpetExtension {
    private static final AutoMendingExtension INSTANCE = new AutoMendingExtension();

    /** 检查间隔，单位 tick。20 tick = 1 秒，与原版实现保持一致 */
    private static final int INTERVAL_TICKS = 20;

    private int tickCounter;

    private AutoMendingExtension() {
    }

    public static void init() {
        CarpetServer.manageExtension(INSTANCE);
    }

    @Override
    public String version() {
        return AutoMendingMod.MOD_VERSION;
    }

    /**
     * 把规则注册进 Carpet 的主设置管理器。
     * 这样规则和 Carpet 自带规则一样用 /carpet autoMending true 设置，并写进 carpet.conf，
     * 与原 Carpet GUGU Addition 的行为保持一致。
     *
     * <p>如果想让它单独占一条命令（例如 /carpet_neko_addition autoMending true），
     * 改成覆写 {@code extensionSettingsManager()} 即可。</p>
     */
    @Override
    public void onGameStarted() {
        CarpetServer.settingsManager.parseSettingsClass(AutoMendingSettings.class);
    }

    @Override
    public void onTick(MinecraftServer server) {
        if (!AutoMendingSettings.autoMending) {
            return;
        }
        // 每 20 tick（1 秒）检查一次
        if (++this.tickCounter % INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AutoMending.mending(player);
        }
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return TranslationLoader.load(lang);
    }
}
