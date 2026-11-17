package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：「我要看配方本，先给我一份索引」。
 *
 * <p><b>这是 {@code carpet-Neko-Addition} 服务端模组里的类；客户端模组 {@code neko-client}
 * 里有一份包名类名完全相同的镜像。</b>两份必须逐字节等价（字段、{@code CODEC} 结构、常量），
 * 改一边务必同步改另一边 —— 同一个 FQCN 在两个 jar 里各一份，Fabric knot 只加载其中一份，
 * 内容不一致就会出现「一边编的包另一边解不开」（先例见 {@code search_hello}）。</p>
 *
 * <h2>为什么 payload 是空的</h2>
 * <p>客户端只说「我要看」，语义全由<b>服务端当前上下文</b>决定：服务端看
 * {@code player.containerMenu} 是不是 {@code VoidWarehouseMenu} —— 是就回索引，不是就静默忽略。
 * 纯原版客户端永远不发这条包，所以对服务器零影响。</p>
 *
 * <h2>这条包同时是能力探测</h2>
 * <p>旧服务端没注册这条 C2S 通道 ⇒ 客户端 {@code canSend} 为 false ⇒ 整个配方本入口不显示；
 * 新服务端 + 没装客户端模组 ⇒ 没人发 hello ⇒ 服务端零开销。两边都不需要额外的握手协议。</p>
 */
public record RecipeHelloPayload() implements CustomPacketPayload {

    /** 探测 / 要索引：{@code carpet_neko_addition:recipe_hello}（C2S，空 payload） */
    public static final CustomPacketPayload.Type<RecipeHelloPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_hello"));

    /** 全服务共享的单例：空 payload 没有任何可变状态 */
    public static final RecipeHelloPayload INSTANCE = new RecipeHelloPayload();

    /** 空 payload 的编解码器：读写都不碰缓冲区 */
    public static final StreamCodec<ByteBuf, RecipeHelloPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
