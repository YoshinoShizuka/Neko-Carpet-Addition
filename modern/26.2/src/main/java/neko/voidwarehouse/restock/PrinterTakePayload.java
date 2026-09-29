package neko.voidwarehouse.restock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 打印机 → 服务端：「我这里缺料了，从虚空仓库给我一组」。
 *
 * <p>这是客户端唯一能拿到仓库库存的途径 —— 仓库数据全在服务端 SavedData 里，
 * 客户端一个字节都读不到，所以只能「发个请求过去、等服务端把东西塞进背包」。</p>
 *
 * <h2>为什么带的是候选清单而不是单个物品</h2>
 * <p>打印机的 {@code MaterialRequest} 本来就允许「这类方块有好几种等价物品」，
 * 逐个发请求要来回好几趟。这里一次把候选都发过来，服务端按顺序找第一个仓库里真有的，
 * 一趟搞定，对打印流畅度影响很大。</p>
 *
 * <h2>{@code nonce} 的用处</h2>
 * <p>回执靠它和请求配对。打印机同一时刻只允许一个请求在飞，所以其实只要一个整数就够；
 * 保留它主要是为了「迟到的回执不会误导当前这一次请求」。</p>
 */
public record PrinterTakePayload(int nonce, List<String> candidates) implements CustomPacketPayload {

    /**
     * 服务端自家那条通道：{@code carpet_neko_addition:printer_take}。
     *
     * <p>保留它是为了向后兼容 —— 还没升级的打印机客户端仍然往这条通道发。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_take"));

    /**
     * 打印机客户端用的专属通道：{@code litematica_printer:printer_take}。
     *
     * <h2>为什么非要分成两条</h2>
     * <p>Fabric 的 {@code PayloadTypeRegistryImpl.register} 实现是
     * {@code if (packetTypes.containsKey(type.id())) throw new IllegalArgumentException(...)} ——
     * <b>只按通道名 Identifier 去重</b>，既不比类也不比 CODEC；而每条通道在 dispatch 表里
     * <b>只绑一个 codec</b>。两边共用同一个名字时：客户端模组与服务端模组抢着注册，
     * 谁先谁赢；输的一方发出去的包会被赢家的 codec 解释，直接
     * {@code ClassCastException: VoidWarehouseTakePayload cannot be cast to PrinterTakePayload}。</p>
     *
     * <p>分成两条通道名之后，两边的注册互不冲突，各走各的 codec。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakePayload> PRINTER_TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("litematica_printer", "printer_take"));

    /**
     * 一次最多带几个候选物品。
     *
     * <p>够用了（打印机常见情况也就一两个），同时也是防御：客户端再怎么出错也撑不大这个包。</p>
     */
    public static final int MAX_CANDIDATES = 8;

    /**
     * 候选物品清单的编解码器。
     *
     * <p>{@code list} 是 {@link net.minecraft.network.codec.StreamCodec.CodecOperation}，
     * 三个类型参数全靠推断 —— 直接写 {@code ByteBufCodecs.list(8)} 推断不出来会退化成 {@code Object}，
     * 所以这里必须显式写出 {@code <ByteBuf, String>}。<br>
     * 结果故意保留为 {@code StreamCodec<ByteBuf, ...>}（而不是 FriendlyByteBuf）：
     * {@code composite} 的形参是 {@code StreamCodec<? super B, T>}，
     * 用更宽的 {@code ByteBuf} 正好能被 26.3 与 1.21.11 两边同时接纳。</p>
     */
    private static final StreamCodec<ByteBuf, List<String>> CANDIDATES_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_CANDIDATES).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, PrinterTakePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterTakePayload::nonce,
            CANDIDATES_CODEC, PrinterTakePayload::candidates,
            PrinterTakePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
