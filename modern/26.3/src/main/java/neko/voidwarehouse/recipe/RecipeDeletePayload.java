package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 客户端 → 服务端：删掉这几条配方。
 *
 * <h2>为什么是「一次一批」而不是一条一包</h2>
 * <p>删除要重建整本（解码 + 配方树编译）并落盘。一条一包会让「删 20 条」变成 20 次重建，
 * 而一次批处理只付一次代价。批上限 {@link #MAX_IDS} = 64：
 * 再多就该考虑「按产物整体删」这种更明确的动作，而不是让一包无限长。</p>
 *
 * <h2>为什么不带确认令牌</h2>
 * <p>服务端做「二次确认」是<B>假防护</B>：令牌由服务端签发，恶意客户端照样能拿到并立刻回传，
 * 挡不住任何攻击，只多一份会话状态。确认放在客户端 UI 上（人看得见的那一层），
 * 服务端只做<b>存在性校验 + 限流 + 权限闸门</b>这三件真正有效的事。</p>
 */
public record RecipeDeletePayload(List<String> recipeIds) implements CustomPacketPayload {

    /** 删除通道：{@code carpet_neko_addition:recipe_delete}（C2S） */
    public static final CustomPacketPayload.Type<RecipeDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_delete"));

    /**
     * 一包最多删几条。
     *
     * <p>超限<b>整包拒</b>（回 {@link RecipeDeleteResultPayload#TOO_MANY}），<b>不截断</b> ——
     * 截断会让客户端以为「发出去的都删了」，而实际上只删了前 64 条。</p>
     */
    public static final int MAX_IDS = 64;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_IDS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, RecipeDeletePayload> CODEC = StreamCodec.composite(
            IDS_CODEC, RecipeDeletePayload::recipeIds,
            RecipeDeletePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
