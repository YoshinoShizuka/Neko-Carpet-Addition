package neko.voidwarehouse.open;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * neko-client → 服务端：「按下了打开虚空仓库的键，请给我开界面」。
 *
 * <p>通道 {@code carpet_neko_addition:open_warehouse}（C2S）。整包<b>只有一个 nonce</b>：
 * 开界面这件事服务端拿不出「数量」之类的结果，客户端也不需要传任何参数 ——
 * 标题由服务端按玩家语言取默认值，因为这条入口<b>没有手持物</b>可跟随。</p>
 *
 * <h2>为什么要有 nonce</h2>
 * <p>虽然「开界面」成功时客户端会直接收到原版的 {@code ClientboundOpenScreenPacket}，
 * 但失败（规则关 / 空壳 / 限流）时客户端什么都不知道，只能干等着按键没反应。
 * 所以配一个 S2C 回执 {@link OpenWarehouseResultPayload}，nonce 用来把回执与请求配对，
 * 让客户端能在 HUD / 动作栏上给一句「虚空仓库没开：规则未启用」这类提示。
 * 迟到的回执不会误导当前这一次按键 —— 客户端只认自己最后一次发出的 nonce。</p>
 *
 * <h2>⚠ 这个类被<b>两端共用</b></h2>
 * <p>与 {@code PrinterTakePayload} 同一条规矩：neko-client 里要有一份<b>同包名同类名、
 * CODEC 结构逐字节等价</b>的源码副本。单人 / 局域网里客户端与服务端同进程、共用
 * {@code SERVERBOUND_PLAY} 那一张静态表，按通道名查到 codec 后会 {@code checkcast} 成
 * 服务端的类；两份不一致就是 {@code ClassCastException} + 连接当场断开。</p>
 */
public record OpenWarehousePayload(int nonce) implements CustomPacketPayload {

    /** 唯一的开界面通道：{@code carpet_neko_addition:open_warehouse}（C2S） */
    public static final CustomPacketPayload.Type<OpenWarehousePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "open_warehouse"));

    public static final StreamCodec<FriendlyByteBuf, OpenWarehousePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenWarehousePayload::nonce,
            OpenWarehousePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
