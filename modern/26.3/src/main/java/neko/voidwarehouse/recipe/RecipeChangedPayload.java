package neko.voidwarehouse.recipe;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：配方本变了，把你正在看的那一页重取一遍。
 *
 * <h2>只发给「正在看的人」</h2>
 * <p>配方本是全服共用的一份，但<b>不该全服广播</b>：学一条配方是高频动作，
 * 全服每人一条等于给所有人加流量。所以只发给<b>最近问过页</b>的玩家
 * （服务端记住一个浏览会话，{@code RecipeNetwork} 里 60 秒没有新请求就算离开）。</p>
 *
 * <h2>为什么不带被删的 id 列表</h2>
 * <p>带不了：一次批量删除最多 64 条，塞进聊天通道级别的广播包没有意义。
 * 客户端拿到 {@code generation} 就够了 —— 它必须整页重取，逐条差量反而容易和
 * 「页码夹取」「过滤集变化」这些情况打架。</p>
 *
 * <h2>{@code actor} 要不要显示</h2>
 * <p>由客户端决定。服务端给的是玩家名（离线解析不到时给空串），
 * 显示成「XX 删除了一条配方」还是只刷新页面，属于 UI 取舍。</p>
 */
public record RecipeChangedPayload(int generation, int reason, String actor, int removed)
        implements CustomPacketPayload {

    /** 变更通知通道：{@code carpet_neko_addition:recipe_changed}（S2C） */
    public static final CustomPacketPayload.Type<RecipeChangedPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_changed"));

    /** 原因：学会了新配方（当前版本不广播学习，保留常量给将来用） */
    public static final int REASON_LEARNED = 0;

    /** 原因：删除了配方（当前唯一会广播的原因） */
    public static final int REASON_DELETED = 1;

    public static final StreamCodec<FriendlyByteBuf, RecipeChangedPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RecipeChangedPayload::generation,
                    ByteBufCodecs.VAR_INT, RecipeChangedPayload::reason,
                    ByteBufCodecs.STRING_UTF8, RecipeChangedPayload::actor,
                    ByteBufCodecs.VAR_INT, RecipeChangedPayload::removed,
                    RecipeChangedPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
