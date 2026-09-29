package neko.voidwarehouse.restock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 打印机：「刚才那次要货的结果」。
 *
 * <p>为什么需要回执，而不是让打印机自己盯着背包看：仓库里<b>根本没这件东西</b>时，
 * 盯背包的人要干等到超时才知道白等了 —— 打印一个大面积投影要缺料几十次，
 * 每次卡上几十 tick，体验会非常糟。有了回执，没货立刻被告知，
 * 责任链可以继续往下走或者干脆标记缺料。</p>
 *
 * <p>反过来，成功时仍然以「背包里真的出现了这个物品」为准
 * （{@code take-OK} 只代表服务端发货了），两边取交集最稳。</p>
 */
public record PrinterTakeResultPayload(int nonce, int status) implements CustomPacketPayload {

    /**
     * 服务端自家那条通道：{@code carpet_neko_addition:printer_take_result}。
     *
     * <p>与 {@link PrinterTakePayload#TYPE} 同理保留，兼容旧客户端。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakeResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_take_result"));

    /**
     * 打印机客户端用的专属通道：{@code litematica_printer:printer_take_result}。
     *
     * <p>回执必须沿「请求走哪条通道、回执就走哪条」的对应关系发回，
     * 否则客户端在自己的接收表里找不到这个类型，包会被当成未知通道丢掉。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakeResultPayload> PRINTER_TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("litematica_printer", "printer_take_result"));

    /** 成功：服务端已经把东西放进玩家背包 */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则 {@code voidWarehouseRestock} 没开 */
    public static final int RULE_OFF = 1;
    /** 仓库里一件都没有（候选全军覆没） */
    public static final int EMPTY = 2;
    /** 背包塞不下，一件都没给（仓库也没扣，不会产生幽灵消耗） */
    public static final int INVENTORY_FULL = 3;
    /** 撞上限流：客户端发太快 */
    public static final int THROTTLED = 4;
    /** 包本身不合法（候选为空 / 物品 id 解析不出来） */
    public static final int BAD_REQUEST = 5;

    public static final StreamCodec<FriendlyByteBuf, PrinterTakeResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterTakeResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterTakeResultPayload::status,
            PrinterTakeResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
