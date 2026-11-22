package neko.voidwarehouse.open;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseMenu;
import neko.voidwarehouse.VoidWarehouseSettings;

/**
 * 「按键打开虚空仓库」的纯业务判断：只决定这一次请求该回什么状态、要不要真开界面。
 *
 * <p>与 {@code PrinterTakeService} 同一定位 —— <b>只算、不管收发</b>。
 * 这样它既能在网络线程里被调用，也能被自检直接拿一个假玩家调用而不需要真连接。</p>
 *
 * <h2>它复用的入口</h2>
 * <p>真正开界面还是走 {@link VoidWarehouse#open(ServerPlayer)} —— 与右键方块、右键终端、
 * 右键接了漏斗的工作台三条入口<b>同一个方法</b>。这里不另写一套，否则「标题怎么取、
 * 菜单怎么建、规则怎么判」会分裂成两份口径。</p>
 *
 * <h2>🔴 必须接住空壳闸门的异常</h2>
 * <p>{@code VoidWarehouse#open} 内部会调 {@code VoidWarehouse#data(server)}，它在
 * 「档案里明明有条目、却一条都没读出来」时会抛 {@link IllegalStateException} 拒绝服务。
 * 右键入口那条路上它抛在玩家点方块那一刻、玩家看得见；而这条通道是在<b>包处理器</b>里跑，
 * 不接住就是每次按键在包处理器里抛 —— <b>那个位置会直接踢掉玩家连接</b>。
 * 所以这里 {@code try/catch} 掉，回一个 {@code UNAVAILABLE}，让客户端自己提示。</p>
 *
 * <h2>幂等</h2>
 * <p>玩家已经在虚空仓库界面里时不再重开：重开会让服务端重新建菜单，客户端的页码、
 * 位置钉住、盒模式开关都会被重置 —— 这正是「连点两下结果跳回第一页」的成因。
 * 所以先判 {@code containerMenu instanceof VoidWarehouseMenu}，在就直接回
 * {@code ALREADY_OPEN}。</p>
 */
public final class OpenWarehouseService {

    private OpenWarehouseService() {
    }

    /**
     * 处理一次「打开虚空仓库」请求。
     *
     * @return 要回给客户端的状态码，见 {@link OpenWarehouseResultPayload} 的常量
     */
    public static int open(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return OpenWarehouseResultPayload.BAD_REQUEST;
        }
        // 规则闸门与右键入口同口径：规则关着时右键都是 PASS，这条通道自然也不该开
        if (!VoidWarehouseSettings.voidWarehouse) {
            return OpenWarehouseResultPayload.RULE_OFF;
        }
        // 已经在这个界面里 —— 不重开，避免把页码 / 钉位 / 盒模式重置掉
        if (player.containerMenu instanceof VoidWarehouseMenu) {
            return OpenWarehouseResultPayload.ALREADY_OPEN;
        }
        try {
            if (!VoidWarehouse.open(player)) {
                return OpenWarehouseResultPayload.UNAVAILABLE;
            }
        } catch (RuntimeException ex) {
            // 空壳闸门的 IllegalStateException 会走到这里。绝不往外抛：
            // 这里是包处理器，抛出去等于踢掉玩家连接。
            return OpenWarehouseResultPayload.UNAVAILABLE;
        }
        return OpenWarehouseResultPayload.OK;
    }
}
