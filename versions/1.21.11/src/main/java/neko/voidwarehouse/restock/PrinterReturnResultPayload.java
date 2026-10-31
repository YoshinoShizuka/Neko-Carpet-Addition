package neko.voidwarehouse.restock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 打印机：「刚才那批返还的结果」。
 *
 * <h2>回执拿来干什么</h2>
 * <p>客户端并不需要靠它判断「东西还在不在手上」—— 那件事背包同步会告诉它。
 * 回执真正的用处只有两个：</p>
 * <ol>
 *   <li><b>知道自己那笔账该销多少</b>：{@code returned} 是这次真正收进仓库的<b>总数量</b>，
 *       客户端据此把「仓库欠我的」那本账减掉，避免把自己的库存也当成仓库给的退回去；</li>
 *   <li><b>失败时立刻收手</b>：规则没开 / 撞限流 / 包不合法，等下去毫无意义，
 *       客户端应当退避而不是每 tick 重发。</li>
 * </ol>
 *
 * <h2>{@code returned} 为什么是总量而不是逐物品明细</h2>
 * <p>客户端发出去的本来就是「一批槽位」，它在发的时候已经知道每个槽位里是什么；
 * 服务端只要告诉它「实收 N 个」，剩下来的差额自然由下一 tick 的背包对账抹平。
 * 按物品列明细会让包体随物品种类线性膨胀，而这批账本来就只是一次近似。</p>
 *
 * <h2>⚠ 两端共用</h2>
 * <p>客户端镜像在 {@code litematica-printer/src/main/java/neko/voidwarehouse/restock/}，
 * 同包名、同类名，状态码常量与 {@code CODEC} 结构必须与这里逐字节等价。
 * 注册方向的规矩与 {@link PrinterTakeResultPayload} 完全一致。</p>
 */
public record PrinterReturnResultPayload(int nonce, int status, int returned) implements CustomPacketPayload {

    /** 唯一的返还回执通道：{@code carpet_neko_addition:printer_return_result}（S2C）。 */
    public static final CustomPacketPayload.Type<PrinterReturnResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_return_result"));

    /** 成功：至少有一格真的进了仓库 */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则（{@code voidWarehouse} / {@code voidWarehouseRestock}）没开 */
    public static final int RULE_OFF = 1;
    /** 一格都没退成：槽位全空 / 全是潜影盒 / 全被跳过 */
    public static final int NOTHING = 2;
    /** 撞上限流：客户端发太快 */
    public static final int THROTTLED = 3;
    /** 包本身不合法（槽位清单为空） */
    public static final int BAD_REQUEST = 4;
    /** 本地状态：还没收到任何回执（服务端从 0 开始，所以用 -1 不冲突） */
    public static final int NONE = -1;

    public static final StreamCodec<FriendlyByteBuf, PrinterReturnResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::status,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::returned,
            PrinterReturnResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
