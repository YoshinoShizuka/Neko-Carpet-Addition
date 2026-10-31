package neko.voidwarehouse.restock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 打印机：「刚才那批返还的结果」。
 *
 * <h2>回执拿来干什么</h2>
 * <p>客户端并不需要靠它判断「东西还在不在手上」—— 那件事背包同步会告诉它。
 * 回执真正的用处有三个：</p>
 * <ol>
 *   <li><b>知道自己那笔账该销多少</b>：{@code returned} 是这次真正收进仓库的<b>总数量</b>，
 *       客户端据此把「仓库欠我的」那本账减掉，避免把自己的库存也当成仓库给的退回去；</li>
 *   <li><b>告诉玩家退了什么、仓库还剩多少</b>：{@code itemIds / counts / remainings} 是
 *       逐物品明细，客户端拿它拼聊天提示；</li>
 *   <li><b>失败时立刻收手</b>：规则没开 / 撞限流 / 包不合法，等下去毫无意义，
 *       客户端应当退避而不是每 tick 重发。</li>
 * </ol>
 *
 * <h2>为什么给了明细还留着 {@code returned}</h2>
 * <p>两个数<b>不是一个来源</b>：{@code returned} 是「这次背包一共少了几件」（含服务端跳过、
 * 只收了一部分的情形），客户端用它销账；明细里的 {@code counts} 是<b>按物品</b>分开的同一批数，
 * 加起来等于 {@code returned}。销账那条路径已经在现网跑过，不去动它 —— 明细是<b>新增</b>的旁挂信息，
 * 就算客户端不认（老版本）也读得到自己需要的三个数。</p>
 *
 * <h2>剩余数量为什么必须由服务端给</h2>
 * <p>「仓库里这一种现在还有多少」是<b>只有服务端知道</b>的事实：客户端根本看不到仓库，
 * 靠自己去猜（比如背包件数加减）永远对不上。所以这里的 {@code remainings} 是服务端在
 * <b>这一批入库做完之后</b>现读的值，口径是「物品 + 数据组件」（与入库归并同一把尺子）。</p>
 *
 * <h2>三个清单等长、按插入顺序</h2>
 * <p>与 {@link PrinterQueryResultPayload} 一样用平行清单而不是嵌套结构：编码简单，
 * 也不需要为一次回执额外定义一个 codec。三份清单<b>必须等长</b>，
 * 客户端解码后要按 {@code min(size)} 兜底（坏包不该让客户端崩）。</p>
 *
 * <h2>⚠ 两端共用</h2>
 * <p>客户端镜像在 {@code litematica-printer/src/main/java/neko/voidwarehouse/restock/}，
 * 同包名、同类名，状态码常量与 {@code CODEC} 结构必须与这里逐字节等价。
 * 注册方向的规矩与 {@link PrinterTakeResultPayload} 完全一致。</p>
 */
public record PrinterReturnResultPayload(
        int nonce,
        int status,
        int returned,
        List<String> itemIds,
        List<Integer> counts,
        List<Long> remainings
) implements CustomPacketPayload {

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

    /**
     * 明细最多几条。
     *
     * <p>一批最多覆盖主背包 + 快捷栏 36 格，所以「最多 36 种」是这一批的硬上限 ——
     * 与 {@code PrinterReturnService} 的 {@code MAIN_INVENTORY_SLOTS} 一致。
     * 给到 36 而不是更小，是为了让「36 格全是不一样的方块」这种极端批次也能整批描述清楚。</p>
     */
    public static final int MAX_ENTRIES = 36;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_ENTRIES).apply(ByteBufCodecs.STRING_UTF8);

    private static final StreamCodec<ByteBuf, List<Integer>> COUNTS_CODEC =
            ByteBufCodecs.<ByteBuf, Integer>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_INT);

    private static final StreamCodec<ByteBuf, List<Long>> REMAININGS_CODEC =
            ByteBufCodecs.<ByteBuf, Long>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_LONG);

    public static final StreamCodec<FriendlyByteBuf, PrinterReturnResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::status,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::returned,
            IDS_CODEC, PrinterReturnResultPayload::itemIds,
            COUNTS_CODEC, PrinterReturnResultPayload::counts,
            REMAININGS_CODEC, PrinterReturnResultPayload::remainings,
            PrinterReturnResultPayload::new
    );

    /** 失败路径的简写：只有状态码，三个清单都是空的。 */
    public static PrinterReturnResultPayload of(int nonce, int status) {
        return new PrinterReturnResultPayload(nonce, status, 0, List.of(), List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
