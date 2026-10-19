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
 *
 * <h2>⚠ 这个类被<b>两端共用</b>，改动前先读完这一段</h2>
 * <p>同一个类在 {@code carpet-Neko-Addition}（服务端）与 {@code litematica-printer}
 * （客户端）里各有一份<b>源码副本</b>，包名、类名、字段、{@code CODEC} 的结构必须
 * <b>逐字节等价</b>：</p>
 * <ul>
 *   <li>客户端那份是 {@code litematica-printer/src/main/java/neko/voidwarehouse/restock/}
 *       下的<b>同包名同类名</b>副本，只是注释不同；</li>
 *   <li>为什么必须共用：单人 / 局域网里客户端与服务端<b>同进程</b>，共用
 *       {@code PayloadTypeRegistryImpl.SERVERBOUND_PLAY} 这一个静态 map。编码时
 *       {@code CustomPacketPayload.writeCap} 先 {@code buf.writeIdentifier(type.id())}，
 *       再 {@code findCodec(id)} 按<b>通道名</b>查表，最后拿查到的 codec 去 encode
 *       客户端那个 payload 对象。</li>
 *   <li>如果两端各用各的类（历史上就是这样），查到的 codec 内部会
 *       {@code checkcast} 服务端的类 → 客户端 payload 直接
 *       {@code ClassCastException: VoidWarehouseTakePayload cannot be cast to
 *       neko.voidwarehouse.restock.PrinterTakePayload}，连接当场断开。</li>
 *   <li>共用之后「谁赢」不再重要：Fabric 的类加载按 classpath 顺序取第一个命中的 jar，
 *       但两份字节码等价，用哪份行为都一样。</li>
 * </ul>
 *
 * <h2>为什么只有一条通道名</h2>
 * <p>早先版本给打印机单独开过 {@code litematica_printer:printer_take}，想法是
 * 「名字不同就不会撞」。<b>这个方向是错的</b>：撞不撞看的是类，不是名字；
 * 名字分开只会让两端各注册一个 key，而客户端发自家 key 时服务端的 handler
 * 仍然只会 cast 成自己的类。现在收敛回服务端命名空间这一条，
 * 语义上也更对 —— 这是<b>服务端的能力</b>，通道自然归服务端命名空间。</p>
 */
public record PrinterTakePayload(int nonce, List<String> candidates) implements CustomPacketPayload {

    /**
     * 唯一的取货通道：{@code carpet_neko_addition:printer_take}（C2S）。
     *
     * <p>客户端与服务端<b>指向同一个常量语义</b>，两边各自的 {@code TYPE} 实例
     * （但 {@code Identifier} 相等）都能查中注册表里那条记录。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_take"));

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
