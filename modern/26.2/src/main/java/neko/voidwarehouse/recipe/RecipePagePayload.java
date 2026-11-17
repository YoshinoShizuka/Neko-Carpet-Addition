package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 客户端：一页配方明细。
 *
 * <h2>{@code recipeId} 是 opaque 的</h2>
 * <p>它由配方内容算出（同一条配方学两次得到同一个 id），客户端<b>只能拿它当键</b>：
 * 不解析、不拼接、不比较大小、不假设长度上限以外的东西。删除时原样回传。</p>
 *
 * <h2>{@code parts} 里为什么是候选集合</h2>
 * <p>配方模型的原料是「任意一种候选物品各 {@code count} 个」（例如「任意木板」）。
 * 当前版本从合成格记录，候选恒为单元素，但结构留着多候选 —— 客户端按
 * 「candidates 里任意一个」渲染即可，将来填多候选不用改协议。</p>
 *
 * <h2>{@code generation} 的用法</h2>
 * <p>客户端拿它比自己缓存的版本：不一致就丢弃这一页重新请求。
 * 删除 / 学习都会让它自增，所以「正在看的页被别人改过」一定能被发现。</p>
 */
public record RecipePagePayload(int protocol, int generation, int page, int total, List<Entry> entries)
        implements CustomPacketPayload {

    /** 明细通道：{@code carpet_neko_addition:recipe_page}（S2C） */
    public static final CustomPacketPayload.Type<RecipePagePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_page"));

    /** 协议版本，与 {@link RecipeIndexPayload#PROTOCOL} 必须一致 */
    public static final int PROTOCOL = RecipeIndexPayload.PROTOCOL;

    /** 一页最多几条（与 {@link RecipeQueryPayload#MAX_PAGE_SIZE} 必须一致，否则解码侧会截断） */
    public static final int MAX_ENTRIES = RecipeQueryPayload.MAX_PAGE_SIZE;

    /** 一条配方最多几项原料（合成格是 3×3，理论上 9 项；留到 16 给将来的大配方） */
    public static final int MAX_PARTS = 16;

    /** 一项原料最多几个候选（当前恒为 1） */
    public static final int MAX_CANDIDATES = 16;

    /**
     * 一项原料：任意一种候选物品各 {@code count} 个。
     *
     * <p>顺序有意义：客户端展示时按这个顺序列，服务端不重排 ——
     * 同一份存档、同一条配方，两次看到的原料顺序永远一样。</p>
     */
    public record Part(List<String> candidates, int count) {
        static final StreamCodec<ByteBuf, List<String>> CANDIDATES_CODEC =
                ByteBufCodecs.<ByteBuf, String>list(MAX_CANDIDATES).apply(ByteBufCodecs.STRING_UTF8);

        static final StreamCodec<FriendlyByteBuf, Part> CODEC = StreamCodec.composite(
                CANDIDATES_CODEC, Part::candidates,
                ByteBufCodecs.VAR_INT, Part::count,
                Part::new
        );
    }

    /** 一条配方：产物 + 一次产出几个 + 原料清单 */
    public record Entry(String recipeId, String output, int outputCount, List<Part> parts) {
        private static final StreamCodec<FriendlyByteBuf, List<Part>> PARTS_CODEC =
                ByteBufCodecs.<FriendlyByteBuf, Part>list(MAX_PARTS).apply(Part.CODEC);

        static final StreamCodec<FriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Entry::recipeId,
                ByteBufCodecs.STRING_UTF8, Entry::output,
                ByteBufCodecs.VAR_INT, Entry::outputCount,
                PARTS_CODEC, Entry::parts,
                Entry::new
        );
    }

    private static final StreamCodec<FriendlyByteBuf, List<Entry>> ENTRIES_CODEC =
            ByteBufCodecs.<FriendlyByteBuf, Entry>list(MAX_ENTRIES).apply(Entry.CODEC);

    public static final StreamCodec<FriendlyByteBuf, RecipePagePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecipePagePayload::protocol,
            ByteBufCodecs.VAR_INT, RecipePagePayload::generation,
            ByteBufCodecs.VAR_INT, RecipePagePayload::page,
            ByteBufCodecs.VAR_INT, RecipePagePayload::total,
            ENTRIES_CODEC, RecipePagePayload::entries,
            RecipePagePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
