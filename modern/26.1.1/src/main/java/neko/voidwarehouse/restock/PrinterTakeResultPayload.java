package neko.voidwarehouse.restock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 打印机：「刚才那次要货的结果」。
 *
 * <p>为什么需要回执，而不是让打印机自己盯着背包看：仓库里<b>根本没这件东西</b>时，
 * 盯背包的人要干等到超时才知道白等了 —— 打印一个大面积投影要缺料几十次，
 * 每次卡上几十 tick，体验会非常糟。有了回执，没货立刻被告知，
 * 责任链可以继续往下走或者干脆标记缺料。</p>
 *
 * <p>反过来，成功时仍然以「背包里真的出现了这个物品」为准
 * （{@code take-OK} 只代表服务端发货了），两边取交集最稳。</p>
 *
 * <h2>⚠ 与 {@link PrinterTakePayload} 同理，这个类也是<b>两端共用</b>的</h2>
 * <p>客户端那份镜像在 {@code litematica-printer/src/main/java/neko/voidwarehouse/restock/}，
 * <b>同包名、同类名</b>，状态码常量与 {@code CODEC} 结构必须与这里逐字节等价
 * （验收方式：两端 {@code javap -p -c} 输出 {@code diff} 为空）。</p>
 *
 * <p>与取货请求的区别在于<b>注册方向不同</b>：这条是 S2C，走
 * {@code CLIENTBOUND_PLAY} 表。客户端为了能<b>解</b>包，必须自己
 * {@code clientboundPlay().register(...)}；服务端<b>同时</b>也要注册一份 ——
 * 因为回执是服务端 {@code send} 出去的，而 {@code createClientboundPacket}
 * 要用到 {type, codec}。<b>同进程时两边会撞名字</b>，所以服务端这份注册
 * 由 {@code PrinterRestockNetwork.init()} 承担，客户端那份改用
 * {@code putIfAbsent} 语义（先查 {canSend} / 先看表里有没有），
 * 具体见客户端侧的注释。</p>
 */
public record PrinterTakeResultPayload(int nonce, int status) implements CustomPacketPayload {

    /**
     * 唯一的回执通道：{@code carpet_neko_addition:printer_take_result}（S2C）。
     *
     * <p>与 {@link PrinterTakePayload#TYPE} 同理，收敛回服务端命名空间。
     * 回执必须沿「请求走哪条通道、回执就走哪条」的对应关系发回，
     * 两端共用一个名字才不会出现「客户端注册的类型没人给它发」。</p>
     */
    public static final CustomPacketPayload.Type<PrinterTakeResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_take_result"));

    /** 成功：服务端已经把东西放进玩家背包 */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则 {@code voidWarehouseRestock} 没开 */
    public static final int RULE_OFF = 1;
    /** 仓库里一件都没有（候选全军覆没） */
    public static final int EMPTY = 2;
    /** 背包塞不下，一件都没给（仓库也没扣，不会产生幽灵消耗） */
    public static final int INVENTORY_FULL = 3;
    /** 撞上限流：客户端发太快 */
    public static final int THROTTLED = 4;
    /** 包本身不合法（候选为空 / 物品 id 解析不出来） */
    public static final int BAD_REQUEST = 5;
    /**
     * 本地状态：还没收到任何回执（与服务端的状态码不冲突，服务端从 0 开始）。
     *
     * <p>服务端本身用不到它，纯粹是为了让这个类与客户端镜像<b>逐字节一致</b> ——
     * 常量池里多一个少一个不影响序列化，但保持两边完全相同就没有「哪份是最新」
     * 的歧义。</p>
     */
    public static final int NONE = -1;

    public static final StreamCodec<FriendlyByteBuf, PrinterTakeResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterTakeResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterTakeResultPayload::status,
            PrinterTakeResultPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
