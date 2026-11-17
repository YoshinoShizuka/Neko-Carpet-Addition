package neko.automending;

import neko.NekoConstants;
import neko.cooking.CookingExtension;
import neko.voidwarehouse.VoidWarehouseExtension;
import neko.voidwarehouse.VoidWarehouseRecipePrompt;
import neko.voidwarehouse.fill.FillNetwork;
import neko.voidwarehouse.recipe.RecipeNetwork;
import neko.voidwarehouse.restock.PrinterQueryNetwork;
import neko.voidwarehouse.restock.PrinterRestockNetwork;
import neko.voidwarehouse.restock.PrinterPlaceNetwork;
import neko.voidwarehouse.restock.PrinterReturnNetwork;
import neko.voidwarehouse.search.SearchNetwork;
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
        // Fabric networking：给 litematica-printer 留的两条通道。
        // 必须早于任何玩家连接，所以在 onInitialize 里注册。
        // ① 取货（有副作用，50ms 限流）
        PrinterRestockNetwork.init();
        PrinterPlaceNetwork.init();
        // ② 库存查询（只读，供材料列表显示；200ms 限流）
        PrinterQueryNetwork.init();
        // ③ 返还（有副作用，50ms 限流）：打印机用不完的材料退回仓库。
        // 与 ① 是两个方向、两张限流表 —— 返还紧跟着取货发生，共用一张会互相拖后腿。
        PrinterReturnNetwork.init();
        // ④ 搜索（只读 + 视图过滤）：客户端搜索框的目录下发与匹配集合回传。
        // 三条独立通道（hello / catalog / apply），限流各一张表；纯原版客户端不发不收，零影响。
        SearchNetwork.init();
        // ⑤ 配方本浏览 / 删除（{@code voidWarehouseCraft}）：服务端出索引与分页明细，
        //    客户端（neko-client）做界面与中文匹配。七条通道，删除要重建整本并落盘，
        //    所以单独一张 1 秒的限流表。
        RecipeNetwork.init();
        // ⑤ 一键填满背包（Ctrl+Alt+左键）：客户端识别手势、服务端只收一个 slotId。
        // 有副作用（扣仓库、动背包），50ms 限流；纯原版客户端永远不发这条包。
        FillNetwork.init();
        // ⑥ 配方确认提示的「本局开关」要跟着登录走：不接下线钩子，`[总是记住]` 就变成
        //    「永远不再问」，而玩家没有任何入口能改回来（没有指令、没有按钮）。
        VoidWarehouseRecipePrompt.init();
    }
}
