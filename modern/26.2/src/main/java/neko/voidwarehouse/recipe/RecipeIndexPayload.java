package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 客户端：配方本的<b>索引</b>（有哪些产物、共有多少条、能不能删）。
 *
 * <p>和 {@code search_catalog} 同一个角色：只下发「有什么」，<b>不下发明细</b>。
 * 客户端拿这份产物清单去本地做中文 / 拼音匹配（专用服务器没有 {@code zh_cn.json}，
 * 匹配只能在客户端），筛出来的产物 id 再随 {@link RecipeQueryPayload} 回传。</p>
 *
 * <h2>为什么不干脆把 2048 条明细全发下来</h2>
 * <p>明细里每条都带原料清单（最多 9 项），全量约 200~400 KB，而且每次增删都要整份重发。
 * 索引只有去重后的产物 id（实测量级与仓库物品目录相当，几十 KB 以内），
 * 明细按页取、只取玩家正在看的那 32 条。</p>
 *
 * <h2>两个「总条数」的区别</h2>
 * <p>{@link #total} 是<b>可浏览</b>的条数（解码成功的），{@link #used} 是本子实际占用的
 * 条数（含极少数解码失败的残行）。两者通常相等；不等时 {@code used} 才是「已用 N / 2048」
 * 该显示的数字。</p>
 */
public record RecipeIndexPayload(int protocol, int generation, int total, int used,
                                 boolean canDelete, List<String> outputs) implements CustomPacketPayload {

    /** 索引通道：{@code carpet_neko_addition:recipe_index}（S2C） */
    public static final CustomPacketPayload.Type<RecipeIndexPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_index"));

    /**
     * 协议版本。
     *
     * <p>加字段不升（客户端按 {@code protocol} 决定怎么解读，多余字段由编解码器吃掉）；
     * <b>改语义</b>才升。首版 = 1。</p>
     */
    public static final int PROTOCOL = 1;

    /**
     * 一次最多下发多少种产物。
     *
     * <p>两端必须一致：{@code ByteBufCodecs.list(n)} 在<b>解码</b>时按 {@code n} 限长，
     * 不一致就会读出错误的条数（或者直接抛）。配方本上限 2048 条，产物种类必然 ≤ 2048，
     * 这里取 4096 与搜索通道同档。</p>
     */
    public static final int MAX_OUTPUTS = 4096;

    private static final StreamCodec<ByteBuf, List<String>> OUTPUTS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_OUTPUTS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, RecipeIndexPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecipeIndexPayload::protocol,
            ByteBufCodecs.VAR_INT, RecipeIndexPayload::generation,
            ByteBufCodecs.VAR_INT, RecipeIndexPayload::total,
            ByteBufCodecs.VAR_INT, RecipeIndexPayload::used,
            ByteBufCodecs.BOOL, RecipeIndexPayload::canDelete,
            OUTPUTS_CODEC, RecipeIndexPayload::outputs,
            RecipeIndexPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
