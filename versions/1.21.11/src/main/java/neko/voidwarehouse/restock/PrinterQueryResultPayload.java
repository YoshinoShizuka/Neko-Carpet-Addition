package neko.voidwarehouse.restock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 打印机：「你要的那批物品，仓库里各有多少」。
 *
 * <p><b>同样是 litematica-printer 里那份镜像的服务端原件</b>，两份必须逐字节等价。</p>
 *
 * <h2>为什么把 id 也回显一遍（明明客户端刚发过）</h2>
 * <p>回显让这个包<b>自解释</b>：{@code itemIds[i]} 对应的就是 {@code amounts[i]}，
 * 不依赖「客户端还记得第 i 个问的是谁」。少写一份 id 能省几 KB，但一旦将来出现
 * 「两个请求并发 / 响应乱序」这类问题，省下来的那点带宽完全不值。
 * 更实际的理由：客户端只保留<b>最新一次</b>请求的上下文，回显让「拿旧响应去套新请求」
 * 这种错配根本无从发生。</p>
 *
 * <h2>只回有货的物品</h2>
 * <p>仓库里没有的（或数量为 0 的）不占位置。客户端查不到就是 0 —— 语义完全一样，
 * 而一个几千格的仓库里「投影要的但没有」的那部分往往占大多数。</p>
 *
 * <h2>数量为什么是 {@code long} 不是 {@code int}</h2>
 * <p>仓库的账是 {@link java.math.BigInteger}，可以到天文数字。显示层面用不到那么细，
 * 但也没必要在传输时先砍一刀 —— {@code long} 的 {@code VAR_LONG} 编码对小数几乎不占空间，
 * 对一位数只是 1 个字节。<b>只有超过 {@code Long.MAX_VALUE} 才会饱和</b>，
 * 那种量级已经远超任何真实玩法。</p>
 */
public record PrinterQueryResultPayload(int nonce, int status, List<String> itemIds, List<Long> amounts)
        implements CustomPacketPayload {

    /** 查询回执通道：{@code carpet_neko_addition:printer_query_result}（S2C）。 */
    public static final CustomPacketPayload.Type<PrinterQueryResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query_result"));

    /** 成功：{@code itemIds} / {@code amounts} 有效（可能为空 = 这些物品仓库里一个都没有） */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则没开（{@code voidWarehouse} 或 {@code voidWarehouseRestock}） */
    public static final int RULE_OFF = 1;
    /** 包本身不合法（清单为空 / 全是解析不出来的 id） */
    public static final int BAD_REQUEST = 2;
    /** 撞上限流：客户端问得太快 */
    public static final int THROTTLED = 3;

    /**
     * 回执里最多多少条。
     *
     * <p>必须 ≥ 请求侧的 {@link PrinterQueryPayload#MAX_IDS}，否则「客户端问了个满清单、
     * 服务端刚好全都有」时会在客户端的解码侧被截断报错。</p>
     */
    public static final int MAX_ENTRIES = PrinterQueryPayload.MAX_IDS;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_ENTRIES).apply(ByteBufCodecs.STRING_UTF8);

    private static final StreamCodec<ByteBuf, List<Long>> AMOUNTS_CODEC =
            ByteBufCodecs.<ByteBuf, Long>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_LONG);

    public static final StreamCodec<FriendlyByteBuf, PrinterQueryResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterQueryResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterQueryResultPayload::status,
            IDS_CODEC, PrinterQueryResultPayload::itemIds,
            AMOUNTS_CODEC, PrinterQueryResultPayload::amounts,
            PrinterQueryResultPayload::new
    );

    /** 失败路径的简写：只有状态码，两个清单都是空的。 */
    public static PrinterQueryResultPayload of(int nonce, int status) {
        return new PrinterQueryResultPayload(nonce, status, List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
