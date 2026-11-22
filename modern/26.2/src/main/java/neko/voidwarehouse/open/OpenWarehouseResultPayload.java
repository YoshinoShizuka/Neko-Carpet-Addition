package neko.voidwarehouse.open;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → neko-client：开界面请求的结果回执。
 *
 * <p>通道 {@code carpet_neko_addition:open_warehouse_result}（S2C）。</p>
 *
 * <h2>为什么成功也要回执</h2>
 * <p>成功时客户端本来就会收到原版的 {@code ClientboundOpenScreenPacket}，看似多余。
 * 但「收到开屏包」和「这条请求是我发的那一次」不是一回事：玩家连点两次、或前一个包丢了，
 * 客户端需要知道自己最后那次请求到底落到了哪个状态，才能决定要不要继续重试 / 给提示。
 * 统一都回一条，逻辑最简单，也不会有「成功静默、失败才说话」造成的状态机分叉。</p>
 */
public record OpenWarehouseResultPayload(int nonce, int status) implements CustomPacketPayload {

    /** 服务端已下发界面（客户端随后会收到 ClientboundOpenScreenPacket） */
    public static final int OK = 0;
    /** Carpet 规则 {@code voidWarehouse} 没开 */
    public static final int RULE_OFF = 1;
    /** 撞上限流：客户端发得太快 */
    public static final int THROTTLED = 2;
    /** 玩家已经在这个界面里，本次请求不重复开（防止连点把页码 / 钉位重置） */
    public static final int ALREADY_OPEN = 3;
    /** 仓库读不出来（空壳闸门拦截，或服务端暂时不可用） */
    public static final int UNAVAILABLE = 4;
    /** 包本身不合法（例如没有玩家上下文） */
    public static final int BAD_REQUEST = 5;
    /**
     * 本地状态：客户端还没收到任何回执。
     *
     * <p>服务端用不到，纯粹为了让本类与 neko-client 的镜像副本逐字节一致。</p>
     */
    public static final int NONE = -1;

    public static final CustomPacketPayload.Type<OpenWarehouseResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "open_warehouse_result"));

    public static final StreamCodec<FriendlyByteBuf, OpenWarehouseResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenWarehouseResultPayload::nonce,
            ByteBufCodecs.VAR_INT, OpenWarehouseResultPayload::status,
            OpenWarehouseResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
