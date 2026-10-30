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
     *
     * <h2>顺带做 9 合 1 兑换表的惰性绑定</h2>
     * <p>配方是<b>跟着数据包重载</b>的，服务器构造完成的那一刻还不一定读好，
     * 所以不能挂在 {@code onGameStarted} 上。第一次 tick 时按真实配方复核一遍静态表
     * （见 {@link VoidWarehouseExchangeBootstrap}），之后每个 tick 只是一次引用比较。</p>
     */
    @Override
    public void onTick(MinecraftServer server) {
        // 「可兑表」的重算按 tick 合并，需要一个 tick 戳（见 VoidWarehouseData#serverTick）。
        // 写在这里、且在任何提前返回之前：规则关着时也要推进，否则重新打开时会拿到很旧的名单。
        // 是 static 调用，不碰 VoidWarehouse.data()，所以不会牵动「空壳闸门」那条异常路径。
        VoidWarehouseData.noteServerTick(server.getTickCount());

        // 「方块掉落物直接进虚空仓库」的聊天提示：按玩家汇总、每秒最多一条（见 VoidWarehouseBlockDrops#tick）。
        // 必须在「规则关着就提前 return」之前 —— 规则刚被关掉时，攒下的那几条也要发出去。
        VoidWarehouseBlockDrops.tick(server);

        if (VoidWarehouseSettings.voidWarehouse && VoidWarehouseSettings.voidWarehouseExchange) {
            VoidWarehouseExchangeBootstrap.tick(server);
        }
        if (VoidWarehouseSettings.voidWarehouse) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof VoidWarehouseMenu) {
                player.closeContainer();
            }
        }
    }

    /**
     * 数据包重载（{@code /reload}）会换掉整张配方表，上一次的复核结论必须作废重做。
     *
     * <p>不作废的后果很隐蔽：管理员用数据包删掉某条 9 合 1 配方，玩家那边却还能兑 ——
     * 而「执行兑换」是不走 {@code RecipeManager} 的（我们自己按静态表扣账），
     * 所以这件事只有启动期这一道校验能拦住。</p>
     */
    @Override
    public void onReload(MinecraftServer server) {
        VoidWarehouseExchangeBootstrap.invalidate();
    }
}
