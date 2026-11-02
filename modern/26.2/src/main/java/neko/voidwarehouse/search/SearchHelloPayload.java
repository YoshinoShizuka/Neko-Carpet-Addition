package neko.voidwarehouse.search;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：「我现在打开的这个界面，是你的虚空仓库吗？顺手把目录发我」。
 *
 * <p><b>这是 {@code carpet-Neko-Addition} 服务端模组里的类；独立客户端模组
 * {@code voidwarehouse-search} 里有一份包名类名完全相同的镜像。</b>
 * 两份必须逐字节等价（字段、{@code CODEC} 结构、常量），改一边务必同步改另一边 ——
 * 同一个 FQCN 在两个 jar 里各一份，Fabric knot 只加载其中一份，
 * 内容不一致就会出现「一边编的包另一边解不开」（先例：2026-10-31 restock 通道
 * 双 jar 版本错配的 {@code NoSuchMethodError} 崩溃）。</p>
 *
 * <h2>为什么 payload 是空的</h2>
 * <p>客户端只说「问一下」，语义全部由<b>服务端当前的上下文</b>决定：
 * 服务端看 {@code player.containerMenu} 是不是 {@code VoidWarehouseMenu} ——
 * 是就回一份目录，不是就静默忽略。<b>纯原版客户端永远不发这条包</b>，
 * 所以它对服务器零影响；而改过的客户端即使疯狂发，也只会拿到
 * 「自己正开着的仓库」的目录，问不到别人的（目录跟随菜单走）。</p>
 *
 * <h2>版本兼容</h2>
 * <p>旧服务端没注册这条 C2S 通道 ⇒ 客户端 {@code canSend} 为 false ⇒ 搜索框不显示；
 * 新服务端 + 没装客户端模组 ⇒ 没人会发 hello ⇒ 服务端零开销。
 * 两边都不需要任何握手协议。</p>
 */
public record SearchHelloPayload() implements CustomPacketPayload {

    /** 探测通道：{@code carpet_neko_addition:search_hello}（C2S，空 payload）。 */
    public static final CustomPacketPayload.Type<SearchHelloPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "search_hello"));

    /** 全服务共享的单例：空 payload 没有任何可变状态 */
    public static final SearchHelloPayload INSTANCE = new SearchHelloPayload();

    /** 空 payload 的编解码器：读写都不碰缓冲区 */
    public static final StreamCodec<ByteBuf, SearchHelloPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
