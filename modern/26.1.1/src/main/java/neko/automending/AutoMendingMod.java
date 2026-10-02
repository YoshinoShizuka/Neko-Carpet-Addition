package neko.automending;

import neko.NekoConstants;
import neko.cooking.CookingExtension;
import neko.cooking.CookingExtension;
import neko.cooking.CookingExtension;
import neko.voidwarehouse.VoidWarehouseExtension;
import neko.voidwarehouse.restock.PrinterRestockNetwork;
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
        // 熔炉玩法（混凝土粉末烧制染色玻璃）是第三条，同样一个扩展一条线
        CookingExtension.init();
        // 熔炉玩法（混凝土粉末烧制染色玻璃）是第三条，同样一个扩展一条线
        CookingExtension.init();
        // 熔炉玩法（混凝土粉末烧制染色玻璃）是第三条，同样一个扩展一条线
        CookingExtension.init();
        // Fabric networking：给 litematica-printer 留的取货通道。
        // 必须早于任何玩家连接，所以在 onInitialize 里注册。
        PrinterRestockNetwork.init();
    }
}
