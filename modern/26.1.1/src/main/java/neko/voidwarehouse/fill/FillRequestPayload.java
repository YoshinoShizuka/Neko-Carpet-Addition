package neko.voidwarehouse.fill;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：<b>把这一格的物品取出，装到背包放不下为止</b>
 * （Ctrl + Alt + 左键）。
 *
 * <h2>为什么要有这条通道</h2>
 * <p>原版容器点击只有 7 种输入（{@code PICKUP / QUICK_MOVE / SWAP / CLONE / THROW /
 * QUICK_CRAFT / PICKUP_ALL}），<b>没有任何一个能带修饰键</b> —— 服务端收到的
 * {@code ServerboundContainerClickPacket} 里根本没有 Ctrl / Alt 的信息
 * （26.3 与 1.21.11 的枚举完全相同，均已核对）。所以「按住 Ctrl+Alt 点一下」
 * 这件事必须在客户端识别，然后走一条自己的通道告诉服务端「点的是这一格、意图是填满」。</p>
 *
 * <h2>载荷只有一个 {@code slotId}</h2>
 * <p>不带物品、不带数量、不带玩家：</p>
 * <ul>
 *   <li><b>是哪一格</b>由客户端说（就是它点的那一格，0..44）；</li>
 *   <li><b>是哪件物品</b>由服务端自己从那一格里解析 —— 客户端说的物品 id 一概不信；</li>
 *   <li><b>能装多少</b>由服务端按玩家<b>真实背包</b>现算 —— 客户端报的数字一概不信；</li>
 *   <li><b>是谁</b>由连接里取（{@code context.player()}）。</li>
 * </ul>
 * <p>于是伪造这条包最多只能让自己多拿货 —— 而「拿货」本来就要扣仓库的账，
 * 扣的是自己仓库里的东西。它造不出物，也碰不到别人的仓库。</p>
 *
 * <h2>服务端的四道校验</h2>
 * <ol>
 *   <li>玩家此刻开的菜单<b>必须是</b> {@code VoidWarehouseMenu}（否则整包丢弃）；</li>
 *   <li>{@code slotId} 必须落在<b>内容格</b>范围内（导航行 / 玩家背包格一律不认）；</li>
 *   <li>那一格必须真的解析得出物品（空格子 / 已消失的「可兑」条目 = 丢弃）；</li>
 *   <li>限流（与取货同档 50ms）。</li>
 * </ol>
 *
 * <h2>与客户端模组的契约</h2>
 * <p>通道 id 固定为 {@code carpet_neko_addition:fill_request}，载荷是一个
 * <b>{@code VarInt} 的 {@code slotId}</b>。客户端只要定义一条同 id、
 * 同字段的同名 payload 就能发过来（{@code CustomPacketPayload} 按 id 匹配，
 * 类名不必相同，但字段类型与顺序必须一致）。</p>
 */
public record FillRequestPayload(int slotId) implements CustomPacketPayload {

    /** 通道：{@code carpet_neko_addition:fill_request}（C2S）。 */
    public static final CustomPacketPayload.Type<FillRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "fill_request"));

    public static final StreamCodec<FriendlyByteBuf, FillRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, FillRequestPayload::slotId,
                    FillRequestPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 只用来自检/日志的可读形式（不要放进热路径） */
    @Override
    public String toString() {
        return "FillRequestPayload[slot=" + this.slotId + "]";
    }
}
