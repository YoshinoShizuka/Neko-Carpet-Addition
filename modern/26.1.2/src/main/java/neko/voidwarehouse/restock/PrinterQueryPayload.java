package neko.voidwarehouse.restock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 打印机 → 服务端：「这些物品在虚空仓库里各有多少？」。
 *
 * <p><b>这是 {@code carpet-Neko-Addition} 服务端模组里的类；litematica-printer 里有一份
 * 包名类名完全相同的镜像。</b>两份必须逐字节等价（字段、{@code CODEC} 结构、限长常量），
 * 改一边务必同步改另一边 —— 理由见 {@link PrinterTakePayload} 的类注释。</p>
 *
 * <h2>它和取货通道（{@link PrinterTakePayload}）的分工</h2>
 * <table border="1">
 *   <tr><th>通道</th><th>用途</th><th>会不会改仓库</th></tr>
 *   <tr><td>{@code printer_take}</td><td>缺料时领一组材料</td><td><b>会</b>（扣库存、改背包）</td></tr>
 *   <tr><td>{@code printer_query}</td><td>材料列表要显示库存</td><td><b>不会</b>（纯只读）</td></tr>
 * </table>
 * <p>分开的理由很实在：材料列表每 5 秒问一次，而取货是有真实副作用、带 50ms 限流的操作。
 * 把它们塞进一条通道的话，要么查询被限流卡住，要么取货的限流形同虚设。</p>
 *
 * <h2>为什么由客户端给出物品清单，而不是「把整个仓库发过去」</h2>
 * <p>仓库可能有上千种物品，而一个投影的材料清单通常只有几十到几百种。让客户端只问它真正
 * 要显示的那些，包小了、服务端也省了。代价是「客户端换投影就要重问一次」——
 * 那正好，因为它本来就要重新拉一次数。</p>
 *
 * <h2>为什么通道归服务端命名空间</h2>
 * <p>语义上这是<b>服务端提供的能力</b>（数据在服务端的 SavedData 里），所以用
 * {@code carpet_neko_addition} 命名空间；客户端与服务端解析出的 {@code Identifier} 相等，
 * 查表才能互相命中。</p>
 */
public record PrinterQueryPayload(int nonce, List<String> itemIds) implements CustomPacketPayload {

    /** 唯一的查询通道：{@code carpet_neko_addition:printer_query}（C2S）。 */
    public static final CustomPacketPayload.Type<PrinterQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_query"));

    /**
     * 一次最多问多少种物品。
     *
     * <p>两端必须一致：{@code ByteBufCodecs.list(n)} 在<b>解码</b>时按 {@code n} 限长，
     * 不一致就会读出错误的条数（或者直接抛）。1024 足够覆盖任何投影的材料清单；
     * 真超过的话客户端分批问，也不会丢数据。</p>
     */
    public static final int MAX_IDS = 1024;

    /**
     * 物品 id 清单的编解码器。
     *
     * <p>显式写出 {@code <ByteBuf, String>}：{@code ByteBufCodecs.list} 是 CodecOperation，
     * 三个类型参数全靠推断，不写会退化成 {@code Object}。</p>
     */
    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_IDS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, PrinterQueryPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterQueryPayload::nonce,
            IDS_CODEC, PrinterQueryPayload::itemIds,
            PrinterQueryPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
