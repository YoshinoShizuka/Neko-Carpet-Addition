package neko.voidwarehouse.recipe;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 客户端：删除的回执。
 *
 * <h2>为什么逐条回执，而不是只回一个成功标志</h2>
 * <p>一批里可能「删掉 3 条、2 条早就不在了」（另一个玩家刚删过）。客户端如果乐观删除后
 * 只收一个 {@code ok}，界面就会和真相分叉；逐条 {@link Failure} 才能让它把失败的挂回去。
 * 与打印机那条 {@code printer_take_result} 同一个思路：<b>回执要能自解释</b>。</p>
 *
 * <h2>为什么还要回 {@link #remaining}</h2>
 * <p>界面顶部通常写着「已用 N / 2048」。删完不回这个数，客户端就得再发一次索引才知道 ——
 * 多一个来回，还多一个状态不一致的窗口。</p>
 */
public record RecipeDeleteResultPayload(int status, List<String> removed, List<Failure> failed,
                                        int generation, int remaining, boolean canDelete)
        implements CustomPacketPayload {

    /** 回执通道：{@code carpet_neko_addition:recipe_delete_result}（S2C） */
    public static final CustomPacketPayload.Type<RecipeDeleteResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "recipe_delete_result"));

    /** 全部成功 */
    public static final int OK = 0;
    /** 部分成功：成功的在 {@code removed}，失败的在 {@code failed} */
    public static final int PARTIAL = 1;
    /** 一条都没删掉（全部 NOT_FOUND） */
    public static final int NOTHING = 2;
    /** 一包里超过 {@link RecipeDeletePayload#MAX_IDS} 条，整包没有执行 */
    public static final int TOO_MANY = 3;
    /** 撞上限流，本次请求不作数 */
    public static final int THROTTLED = 4;
    /** 规则没开（正常情况下服务端会直接丢弃，不会回这条） */
    public static final int RULE_OFF = 5;

    /** 失败原因：本子里没有这个 id（可能刚被别人删掉，也可能从来不存在） */
    public static final int NOT_FOUND = 0;
    /** 失败原因：id 是空串 / 长得不像一个 id（长度超上限） */
    public static final int BAD_ID = 1;

    /** 回执里最多列多少条删除成功 / 失败（与请求侧的批上限一致） */
    public static final int MAX_LIST = RecipeDeletePayload.MAX_IDS;

    /** 一条失败记录：哪个 id、为什么 */
    public record Failure(String id, int reason) {
        private static final StreamCodec<FriendlyByteBuf, Failure> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Failure::id,
                ByteBufCodecs.VAR_INT, Failure::reason,
                Failure::new
        );
    }

    private static final StreamCodec<ByteBuf, List<String>> REMOVED_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_LIST).apply(ByteBufCodecs.STRING_UTF8);

    private static final StreamCodec<FriendlyByteBuf, List<Failure>> FAILED_CODEC =
            ByteBufCodecs.<FriendlyByteBuf, Failure>list(MAX_LIST).apply(Failure.CODEC);

    public static final StreamCodec<FriendlyByteBuf, RecipeDeleteResultPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RecipeDeleteResultPayload::status,
                    REMOVED_CODEC, RecipeDeleteResultPayload::removed,
                    FAILED_CODEC, RecipeDeleteResultPayload::failed,
                    ByteBufCodecs.VAR_INT, RecipeDeleteResultPayload::generation,
                    ByteBufCodecs.VAR_INT, RecipeDeleteResultPayload::remaining,
                    ByteBufCodecs.BOOL, RecipeDeleteResultPayload::canDelete,
                    RecipeDeleteResultPayload::new
            );

    /** 整包失败（限流 / 超限 / 规则关）：没有删任何东西 */
    public static RecipeDeleteResultPayload refused(int status, int generation, int remaining,
                                                   boolean canDelete) {
        return new RecipeDeleteResultPayload(status, List.of(), List.of(), generation, remaining, canDelete);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
