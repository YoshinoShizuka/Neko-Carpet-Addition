package neko.voidwarehouse;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 配方本在存档里的表示：<b>一条配方一个字符串元素</b>，装进一个 {@code ListTag}。
 *
 * <h2>为什么不直接用 {@code Codec.STRING.listOf()}</h2>
 * <p>它的语义是「全成或全不成」—— 实测只要有一个元素读不出来（例如存档里那一格是
 * {@code IntTag}），整份 {@code List} 就解码失败（{@code Not a string}），
 * 进而整个 {@code VoidWarehouseData} 解码失败，上层把它当成「文件不存在」，
 * <b>静默造一个空仓库并覆盖旧档</b>。这是本模组历史上踩过的坑。</p>
 *
 * <p>所以这里逐元素独立解析：读得出来的收下，读不出来的<b>跳过它自己</b>，
 * 整体永远成功。坏的只可能是一行，牵连不到整表。</p>
 *
 * <h2>为什么不再担心 65535</h2>
 * <p>{@code StringTag} 走 {@code DataOutputStream#writeUTF}，单个字段上限 65535 字节。
 * 以前是「全部配方拼成一个字符串」，条数一多就撑爆（975 条顶格，超了被
 * {@code NbtIo$StringFallbackDataOutput} 悄悄替换成空串）。现在一条一个元素，
 * 单个元素只有几十字节，<b>这个上限再也碰不到</b>；而 {@code ListTag} 的元素计数是 int。</p>
 *
 * <h2>顶层类型错时的态度</h2>
 * <p>顶层压根不是列表（比如被手工改成了字符串）时，这里<b>不</b>宽容 —— 让解码失败，
 * 交给 {@code VoidWarehouse.data} 的空壳闸门拒绝服务。宁可让服主看见一个明确的错误，
 * 也不能把「整本配方凭空消失」伪装成一次正常读档。</p>
 */
public final class RecipeLinesCodec {

    /**
     * 单个元素允许的最大 UTF-8 字节数，{@code writeUTF} 的硬上限是 65535。
     *
     * <p>正常配方行不可能到这个量级（最深 4 层、最多 9 种原料、id 再长也就几百字节），
     * 这条只是防止手工改档塞进来一条超长行 —— 那种行写出去会被
     * {@code StringFallbackDataOutput} 悄悄换成空串，等于在存档里留一颗哑弹。
     * 与其留哑弹，不如在写盘时就不要它。</p>
     */
    private static final int MAX_LINE_BYTES = 60_000;

    private RecipeLinesCodec() {
    }

    public static final Codec<List<String>> CODEC = new Codec<>() {

        @Override
        public <T> DataResult<Pair<List<String>, T>> decode(DynamicOps<T> ops, T input) {
            // getList 失败 = 顶层不是列表 -> 整个 decode 失败（有意为之，见类注释）
            return ops.getList(input).map(consumer -> {
                List<String> lines = new ArrayList<>();
                consumer.accept(element ->
                        ops.getStringValue(element).result().ifPresent(lines::add));
                return Pair.of(List.copyOf(lines), input);
            });
        }

        @Override
        public <T> DataResult<T> encode(List<String> input, DynamicOps<T> ops, T prefix) {
            ListBuilder<T> builder = ops.listBuilder();
            for (String line : input) {
                if (line == null) {
                    continue;
                }
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                    // 写出去也会被静默换成空串，不如不带（见 MAX_LINE_BYTES）
                    continue;
                }
                builder.add(ops.createString(line));
            }
            return builder.build(prefix);
        }
    };
}
