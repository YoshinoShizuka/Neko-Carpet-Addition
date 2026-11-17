package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 客户端 → 服务端：要我正在看的那一页配方明细。
 *
 * <p>字段刻意少：页码、页大小、排序、产物白名单。**没有搜索词** ——
 * 名字匹配在客户端做完，回传的是产物 id 白名单（与 {@code search_apply} 同一条理由：
 * 专用服务器没有中文语言表，服务端根本没法把「铁锭」对上号）。</p>
 *
 * <h2>白名单为空 = 不过滤</h2>
 * <p>与 {@code search_apply}「空集合 = 清除过滤」同义。客户端想让列表为空时自己拦，
 * 不需要一条「什么都不显示」的特殊协议。</p>
 *
 * <h2>页码越界怎么办</h2>
 * <p>不报错：服务端夹到合法范围，并在 {@link RecipePagePayload#page()} 里回夹后的值。
 * 删除之后总页数会变小，客户端拿着旧页码来问是常态，不值得为此设计一个错误码。</p>
 */
public record RecipeQueryPayload(int page, int pageSize, int sort, List<String> outputs)
        implements CustomPacketPayload {

    /** 查询通道：{@code carpet_neko_addition:recipe_query}（C2S） */
    public static final CustomPacketPayload.Type<RecipeQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_query"));

    /** 排序：按产物 id 字典序 → recipeId 字典序（稳定，跨查询一致） */
    public static final int SORT_OUTPUT = 0;

    /** 排序：按学习时间倒序（最近学的在最前） */
    public static final int SORT_RECENT = 1;

    /** 白名单上限（与本子上限同档：产物种类不可能更多） */
    public static final int MAX_OUTPUTS = 4096;

    /**
     * 服务端认可的页大小上限。
     *
     * <p>客户端可以建议更小，超过就夹到 32：一页 32 条明细 ≈ 几 KB，
     * 足够铺满任何合理尺寸的列表，而不会让「翻一页」变成一次大包。</p>
     */
    public static final int MAX_PAGE_SIZE = 32;

    private static final StreamCodec<ByteBuf, List<String>> OUTPUTS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_OUTPUTS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, RecipeQueryPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecipeQueryPayload::page,
            ByteBufCodecs.VAR_INT, RecipeQueryPayload::pageSize,
            ByteBufCodecs.VAR_INT, RecipeQueryPayload::sort,
            OUTPUTS_CODEC, RecipeQueryPayload::outputs,
            RecipeQueryPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
