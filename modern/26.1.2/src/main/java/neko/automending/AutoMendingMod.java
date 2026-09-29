package neko.automending;

import neko.NekoConstants;
import neko.voidwarehouse.VoidWarehouseExtension;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class AutoMendingMod implements ModInitializer {
    public static final String MOD_ID = NekoConstants.MOD_ID;

    public static String MOD_VERSION = "dev";

    @Override
    public void onInitialize() {
        FabricLoader.getInstance().getModContainer(MOD_ID)
                .ifPresent(container -> MOD_VERSION = container.getMetadata().getVersion().getFriendlyString());

        AutoMendingExtension.init();
        // 虚空仓库是同一个模组里的第二条 Carpet 规则，单独注册一个扩展
        VoidWarehouseExtension.init();
    }
}
