package neko.voidwarehouse.search;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 客户端：虚空仓库的「物品目录」（现有全部物品的注册 id 列表）。
 *
 * <p><b>这是 {@code carpet-Neko-Addition} 服务端模组里的类；独立客户端模组
 * {@code voidwarehouse-search} 里有一份包名类名完全相同的镜像。</b>
 * 两份必须逐字节等价（字段、{@code CODEC} 结构、限长常量），改一边务必同步改另一边 ——
 * 同一个 FQCN 在两个 jar 里各一份，Fabric knot 只加载其中一份，
 * 内容不一致就会出现「一边编的包另一边解不开」。</p>
 *
 * <h2>为什么要下发目录，而不是让客户端把搜索词发上来</h2>
 * <p>中文 / 拼音匹配<b>只能在客户端做</b>：专用服务器上没有 {@code zh_cn.json}
 * （原版语言文件只在客户端 jar），服务端无法把「铁锭」解析成中文，拼音更无从谈起。
 * 而客户端又只看得到当前页的 45 格，不知道别的页有什么。
 * 所以由服务端把「仓库里现在有哪些物品」的 id 清单发下来，客户端用
 * 本地语言文件 + 拼音库筛出匹配的 id 集合再回传（{@link SearchApplyPayload}），
 * 服务端只按 {@code Item} 身份做集合求交 —— 不碰语言、不碰拼音。</p>
 *
 * <h2>体量</h2>
 * <p>全物品存档实测 1657 种，id 字符串平均 ~20 字节 ⇒ 目录 ≈ 30 KB，
 * 只在打开界面 / 请求刷新时发一次，完全可以接受。上限 4096 留足余量。</p>
 */
public record SearchCatalogPayload(List<String> itemIds) implements CustomPacketPayload {

    /** 目录通道：{@code carpet_neko_addition:search_catalog}（S2C）。 */
    public static final CustomPacketPayload.Type<SearchCatalogPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "search_catalog"));

    /**
     * 一次最多下发多少种物品。
     *
     * <p>两端必须一致：{@code ByteBufCodecs.list(n)} 在<b>解码</b>时按 {@code n} 限长，
     * 不一致就会读出错误的条数（或者直接抛）。全物品存档实测 1657 种，4096 留足余量。</p>
     */
    public static final int MAX_IDS = 4096;

    /**
     * 物品 id 清单的编解码器。
     *
     * <p>显式写出 {@code <ByteBuf, String>}：{@code ByteBufCodecs.list} 是 CodecOperation，
     * 三个类型参数全靠推断，不写会退化成 {@code Object}。</p>
     */
    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_IDS).apply(ByteBufCodecs.STRING_UTF8);

    public static final StreamCodec<FriendlyByteBuf, SearchCatalogPayload> CODEC = StreamCodec.composite(
            IDS_CODEC, SearchCatalogPayload::itemIds,
            SearchCatalogPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
