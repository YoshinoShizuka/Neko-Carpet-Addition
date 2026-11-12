package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「按数量批量取货」的流程（潜影盒模式下右键物品触发）。
 *
 * <h2>整体流程</h2>
 * <ol>
 *   <li>玩家在<b>潜影盒模式</b>下右键某个仓库格 → {@link #request(ServerPlayer, ItemStack)}
 *       记一条待处理请求、<b>主动关掉仓库界面</b>，并提示玩家「在聊天栏输入要取走的数量」；</li>
 *   <li>玩家在聊天栏输入一个数字 → 聊天 Mixin 调 {@link #onChat(ServerPlayer, String)}，
 *       解析数量并执行取货；</li>
 *   <li><b>无论取货成功、失败、取消还是超时，最后都把仓库界面开回来</b>
 *       （见 {@link #reopenWarehouse(ServerPlayer)}）。</li>
 * </ol>
 *
 * <h2>为什么要先关界面再开回来</h2>
 * <p>数量只能在聊天栏里输入，而仓库界面会盖住聊天栏、输入时看不见自己打了什么。
 * 所以发起请求时先 {@code closeContainer()} 让聊天栏露出来，取完货再自动开回来 ——
 * 页码 / 潜影盒模式 / 数量排序都在玩家自己的 {@link VoidWarehouseViews.View} 里，
 * <b>重新打开时和离开时一模一样</b>，玩家感觉不到被打断过。</p>
 *
 * <h2>取货换算（满盒 + 散货）</h2>
 * <p>一盒容量 {@code capacity = 27 × 该物品堆叠上限}。请求数量 {@code N} 拆成：</p>
 * <pre>
 *   满盒数 boxes    = N / capacity
 *   散货数 remainder = N % capacity
 * </pre>
 * <p>例如圆石（堆叠 64，capacity 1728）：输入 1729 → 1 满盒 + 1 散货；
 * 输入 3457 → 2 满盒 + 1 散货（3456 + 1）。</p>
 *
 * <h2>散货够多就顺手装盒（省背包格子）</h2>
 * <p>散件是按「一组一份」铺进背包的，散货一多就会占掉一大片格子。所以当散货超过
 * {@link #LOOSE_PACK_STACKS} 组时，<b>把它也装进一个盒子</b>（原本那些满盒之外再多一个盒子）：</p>
 * <pre>
 *   输入 2728（圆石） = 1 满盒(1728) + 散货 1000
 *   1000 &gt; 9 组(576)  → 散货装盒 → 产物 = 1 满盒 + 1 个装 1000 的盒（只占 2 格）
 * </pre>
 * <p>散货不足这个数就原样给散件 —— 一个 30 个的散货只占 1 格，为它花一个盒子不划算。</p>
 *
 * <h2>合成材料的扣除（优先用空盒）</h2>
 * <p>每个盒子（含「散货装出来的那个」）都需要一个「盒子」：</p>
 * <ul>
 *   <li><b>仓库里有空潜影盒库存</b> → 优先用空盒装（每盒扣 1 个空盒，不耗壳 / 箱）；</li>
 *   <li><b>空盒不够</b> → 剩下的盒子每个扣 {@link VoidWarehouseShulker#SHELL_COST} 个潜影壳 +
 *       {@link VoidWarehouseShulker#CHEST_COST} 个箱子（原版配方）。</li>
 * </ul>
 *
 * <h2>All-or-nothing</h2>
 * <p>整次操作要么全成、要么全不成：先校验「物品库存 / 空盒 / 壳 / 箱 / 背包空间」全部够，
 * 才真正放物品 + 扣账；任何一项不够都只发一条提示、仓库一个字节都不动。</p>
 */
public final class VoidWarehouseWithdraw {
    /** 单次可输入的数量上限，防止手滑输入天文数字 */
    private static final long MAX_REQUEST = 2_000_000L;

    private static final long REQUEST_TTL_MS = 30_000L;

    /**
     * 散货超过这么多「组」就顺手打包成一个盒子。
     *
     * <p>9 组 = 9 个背包格子。散货到这个量级再用散件给，背包会被铺掉一大片；
     * 装成盒只占 1 格。不足这么多就原样给散件 —— 为 30 个散货花一个盒子不划算。</p>
     */
    private static final int LOOSE_PACK_STACKS = 9;

    // 聊天提示双语版（方案 B）：不再是静态常量，而是按接收玩家语言构造的实例消息 ——
    // 每个发送点用 VoidWarehouseText.isEnglish(player) 现取现发。

    private static Component msgPrompt(boolean en) {
        return VoidWarehouseText.info(en, "请输入要取走的数量（其他内容 = 取消）",
                "Type the amount to withdraw (anything else = cancel)");
    }

    private static Component msgInvalid(boolean en) {
        return VoidWarehouseText.info(en, "不是有效数量，已取消本次取货",
                "Not a valid amount — withdrawal cancelled");
    }

    private static Component msgTooLarge(boolean en) {
        return VoidWarehouseText.info(en,
                VoidWarehouseText.body("数量过大（最多 ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(MAX_REQUEST)))
                        .append(VoidWarehouseText.body("），已取消本次取货")),
                VoidWarehouseText.body("Amount too large (max ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(MAX_REQUEST)))
                        .append(VoidWarehouseText.body(") — withdrawal cancelled")));
    }

    private static Component msgNoStock(boolean en) {
        return VoidWarehouseText.info(en, "仓库里这种物品不够，本次取消",
                "Not enough of that item in the warehouse — cancelled");
    }

    private static Component msgNoParts(boolean en) {
        return VoidWarehouseText.info(en, "空盒 / 潜影壳 / 箱子不足，无法装盒，本次取消",
                "Not enough empty boxes / shells / chests to pack — cancelled");
    }

    private static Component msgInvFull(boolean en) {
        return VoidWarehouseText.info(en, "背包放不下这么多，本次取消",
                "Not enough inventory space — cancelled");
    }

    /** 一条待处理请求：记录要取的是哪种物品（样板，数量恒 1）+ 发起时间 */
    private record Pending(ItemStack sample, long createdMs) {
    }

    /** 玩家 UUID → 待处理请求（瞬时状态，服务器重启即失效，无需持久化） */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private VoidWarehouseWithdraw() {
    }

    // ------------------------------------------------------------------ 发起

    /**
     * 玩家右键仓库格时发起一次「按数量取货」请求。
     *
     * <p>{@code sample} 是该格物品的样板（数量会被归一到 1，数据组件完整保留），
     * 执行时用 {@link VoidWarehouseData#takeExact} 按「物品 + 数据组件」精确扣减。</p>
     */
    public static void request(ServerPlayer player, ItemStack sample) {
        if (sample == null || sample.isEmpty()) {
            return;
        }
        PENDING.put(player.getUUID(), new Pending(sample.copyWithCount(1), System.currentTimeMillis()));
        // 先关掉仓库界面：聊天栏被界面盖着，输入时看不见自己打了什么。
        // 取完货（或取消）会由 onChat 统一开回来，见 reopenWarehouse。
        player.closeContainer();
        player.sendSystemMessage(msgPrompt(VoidWarehouseText.isEnglish(player)));
    }

    /**
     * 把仓库界面开回来（无论本次取货是成功、失败、取消还是超时）。
     *
     * <p>页码 / 潜影盒模式 / 数量排序都存在<b>按玩家持久化</b>的
     * {@link VoidWarehouseViews.View} 里，所以重新打开的界面和关掉之前<b>完全一样</b>，
     * 玩家不会丢失自己翻到的那一页。</p>
     */
    private static void reopenWarehouse(ServerPlayer player) {
        // 标题跟随玩家语言：英文玩家重开的界面也该是英文标题（与触发名对应）
        boolean en = VoidWarehouseText.isEnglish(player);
        VoidWarehouse.open(player, Component.literal(
                VoidWarehouseText.tr(en,
                        VoidWarehouseProvider.TITLE_TEXT, VoidWarehouseText.TITLE_TEXT_EN)));
    }

    /** 玩家有没有一条待处理请求 */
    public static boolean hasPending(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    // ------------------------------------------------------------------ 聊天输入

    /**
     * 玩家聊天输入。由聊天 Mixin 调用。
     *
     * @return {@code true} = 这条消息被当成取货指令消费了（调用方应取消原版聊天）；
     *         {@code false} = 不是取货指令（调用方应放行原版聊天）。
     */
    public static boolean onChat(ServerPlayer player, String message) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null) {
            return false;
        }
        boolean consumed;
        if (System.currentTimeMillis() - pending.createdMs() > REQUEST_TTL_MS) {
            // 过期（玩家磨蹭太久）：直接作废，消息交还原版聊天
            consumed = false;
        } else {
            boolean en = VoidWarehouseText.isEnglish(player);
            long requested;
            try {
                requested = Long.parseLong(message.trim());
            } catch (NumberFormatException e) {
                // 非数字：取消请求，但让这条消息照常发出去（玩家可能只是想说话）
                player.sendSystemMessage(msgInvalid(en));
                requested = -1;
            }
            if (requested < 0) {
                consumed = false;
            } else if (requested == 0 || requested > MAX_REQUEST) {
                player.sendSystemMessage(msgTooLarge(en));
                consumed = true;
            } else {
                execute(player, pending.sample(), requested);
                consumed = true;
            }
        }
        // 界面是发起请求时被我们关掉的，所以无论成不成都要开回来
        reopenWarehouse(player);
        return consumed;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行取货：按 {@code requested} 拆成满盒 + 散货，放背包、扣账。
     *
     * <p>顺序与 {@code withdrawShulkerBox} 一致：先校验 → 生成产物 → 检查背包 → 放成功才扣账。</p>
     */
    private static void execute(ServerPlayer player, ItemStack sample, long requested) {
        executeCore(player, sample, requested, true);
    }

    /**
     * 取货的核心，{@code allowBox} 决定「允不允许顺手装盒」。
     *
     * <h2>为什么要这个开关</h2>
     * <p>「按数量取货」（右键输入数字）希望散货多了就装盒 —— 那是省背包格子。
     * 但「Ctrl+Alt+左键 填满背包」在<b>常规模式</b>下的语义是「把格子铺满」，
     * 玩家要的就是一片能顺手拿的散货；这时若悄悄给成盒子，点一下背包里冒出个
     * 装着 1728 个圆石的潜影盒，跟玩家的预期完全对不上。
     * 所以填满这条路明确传 {@code false}，<b>一个盒子都不装</b>。</p>
     *
     * @param allowBox {@code false} = 绝不装盒，请求多少就给多少散货
     */
    private static void executeCore(ServerPlayer player, ItemStack sample, long requested,
                                    boolean allowBox) {
        boolean en = VoidWarehouseText.isEnglish(player);
        VoidWarehouseData data = VoidWarehouse.data(player.level().getServer());
        if (data == null) {
            return;
        }
        int max = Math.max(1, sample.getMaxStackSize());
        long capacity = VoidWarehouseShulker.capacity(sample);
        // 这一格本身就是潜影盒 → <b>不装盒</b>：按「散盒」原样取出 requested 个。
        //   再装一道会造出原版禁止的「盒子套盒子」，而且外盒的材料账恰好也从这一格算
        //   （planBoxMaterials 空盒优先），takeExact 先把它扣空之后材料就扣不到了 ——
        //   于是「取 5 个盒子出来 6 个」、送回仓库后壳和箱无限增长（2026-11 事故）。
        //   盒子本来就是盒子，直接给。见 VoidWarehouseShulker#isBoxable。
        // allowBox = false 与「目标本身是盒子」是同一件事：都不许再包一层。
        boolean noPacking = !allowBox || !VoidWarehouseShulker.isBoxable(sample);
        long fullBoxes = noPacking ? 0 : requested / capacity;
        long loose = noPacking ? requested : requested % capacity;
        // 散货超过 LOOSE_PACK_STACKS 组：它单独要占掉一大片背包格子，顺手装进一个盒子
        // （这个盒子同样要算材料，所以盒子总数是 fullBoxes + 1）
        boolean packLoose = !noPacking && loose > (long) LOOSE_PACK_STACKS * max;
        long totalBoxes = packLoose ? fullBoxes + 1 : fullBoxes;

        // ① 校验物品库存（精确口径：同物品 + 同数据组件，与 takeExact 一致）。
        //    成品不够时，规则允许就用 9 合 1 兑换补齐 —— 规划是「先扣成品、再动别的」，
        //    所以有成品一定先用成品，玩家不会遇到「明明有铁锭却被扣了铁粒」。
        BigInteger want = BigInteger.valueOf(requested);
        VoidWarehouseExchange.Plan exchange = null;
        if (data.amountExact(sample).compareTo(want) < 0) {
            exchange = data.planExchange(sample, want);
            if (exchange == null) {
                player.sendSystemMessage(msgNoStock(en));
                return;
            }
        }

        // ② 校验盒子材料：优先空盒，不够的用壳 + 箱（按 totalBoxes 算，含散货装出来的那个）。
        //    与界面里「点击取一盒」共用同一份算法，两边行为必须一致。
        VoidWarehouseData.BoxMaterials plan = data.planBoxMaterials(totalBoxes);
        if (!data.hasBoxMaterials(plan)) {
            player.sendSystemMessage(msgNoParts(en));
            return;
        }

        // ③ 生成产物：若干满盒 + （散货要么装盒、要么按组拆成散件）
        List<ItemStack> outputs = new ArrayList<>();
        for (long i = 0; i < fullBoxes; i++) {
            outputs.add(VoidWarehouseShulker.pack(sample, capacity));
        }
        if (packLoose) {
            // 散货装盒：这一盒没装满（loose < capacity），但只占 1 个背包格
            outputs.add(VoidWarehouseShulker.pack(sample, loose));
        } else {
            appendLoose(outputs, sample, loose);
        }
        if (outputs.isEmpty()) {
            return;
        }

        // ④ 检查背包能否全部放下（纯模拟，不污染真实背包）
        if (!canFitAll(player, outputs)) {
            player.sendSystemMessage(msgInvFull(en));
            return;
        }

        // ⑤ 全部放得下：先放，放成功（已确认能放）才扣账
        for (ItemStack out : outputs) {
            moveIntoInventory(player, out);
        }

        // ⑥ 扣账：物品 + 盒子材料（空盒优先，不够的才用壳 + 箱；材料在 ② 里验过）。
        //    物品那一步在 ① 里已经定好了走哪条路：兑换计划一次性提交（原子），否则直接按量扣。
        if (exchange != null) {
            data.applyExchange(exchange);
        } else {
            data.takeExact(sample, (int) requested);
        }
        data.consumeBoxMaterials(plan);
        player.sendSystemMessage(successMessage(en, sample, requested, fullBoxes, loose, packLoose));
    }

    // ------------------------------------------------------------------ 填满背包

    /**
     * Ctrl + Alt + 左键：<b>把这一格的物品取出，装到背包放不下为止</b>。
     *
     * <h2>两种模式的语义不同，必须分开算</h2>
     * <table border="1">
     *   <tr><th>模式</th><th>给什么</th><th>「装得下多少」怎么算</th></tr>
     *   <tr><td>常规</td><td><b>纯散货</b>，一个盒子都不装</td>
     *       <td>每个格子还能塞多少（同类格算余量、空格算一整叠）之和</td></tr>
     *   <tr><td>潜影盒</td><td><b>满盒</b>，一格一个</td>
     *       <td>空格数 × 一盒容量</td></tr>
     * </table>
     * <p>常规模式特意<b>不许</b>装盒：玩家要的是「顺手能拿的一片散货」。
     * 悄悄给成盒子会跟预期对不上（见 {@link #executeCore} 的 {@code allowBox}）。</p>
     *
     * <h2>三个上限取最小</h2>
     * <ol>
     *   <li><b>背包放得下多少</b> —— 主判据，这个功能的名字就叫「填满背包」；</li>
     *   <li><b>仓库有多少</b> —— 只取现有的，<b>绝不走 9 合 1 兑换补齐</b>
     *       （与「按数量取货」不同：那是玩家明确要 N 个，这里是「有多少给我多少」）；</li>
     *   <li>盒模式下还有<b>材料够做几个盒子</b>（空盒优先，其次壳 + 箱）。</li>
     * </ol>
     *
     * <h2>为什么这里不用 All-or-nothing 的取消语义</h2>
     * <p>「按数量取货」是玩家点了名要 N 个，给不够就该整单取消。
     * 填满背包没有「要多少个」这个前提 —— 能装多少就装多少，取到的数量由上面三个上限
     * 里最小的那个决定，所以它天然是<b>「按上限裁剪」</b>而不是「不够就取消」。
     * 唯一会全盘拒绝的情形是「一个都装不下 / 一个都没有」，那时发一句提示就结束。</p>
     */
    public static void fill(ServerPlayer player, ItemStack sample, boolean boxMode) {
        if (player == null || sample == null || sample.isEmpty()) {
            return;
        }
        boolean en = VoidWarehouseText.isEnglish(player);
        VoidWarehouseData data = VoidWarehouse.data(player.level().getServer());
        if (data == null) {
            return;
        }
        BigInteger stock = data.amountExact(sample);
        if (stock.signum() <= 0) {
            player.sendSystemMessage(msgFillNoStock(en));
            return;
        }
        long have = stock.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();

        if (boxMode && VoidWarehouseShulker.isBoxable(sample)) {
            long capacity = VoidWarehouseShulker.capacity(sample);
            int freeSlots = emptySlots(player);
            if (freeSlots <= 0) {
                player.sendSystemMessage(msgInvFull(en));
                return;
            }
            long byMaterial = data.maxBoxesUpTo(freeSlots);
            long byStock = have / capacity;
            long boxes = Math.min(Math.min((long) freeSlots, byMaterial), byStock);
            if (boxes <= 0) {
                // 三种「一个盒子都给不出」的原因分开说清楚，别让玩家去猜
                if (byStock <= 0) {
                    player.sendSystemMessage(msgFillNotEnoughForBox(en, capacity, have));
                } else {
                    player.sendSystemMessage(msgNoParts(en));
                }
                return;
            }
            executeCore(player, sample, boxes * capacity, true);
            return;
        }

        long room = looseRoom(player, sample);
        if (room <= 0) {
            player.sendSystemMessage(msgInvFull(en));
            return;
        }
        executeCore(player, sample, Math.min(room, have), false);
    }

    /**
     * 背包里还能吸收多少个 {@code sample}（同类格算余量、空格算一整叠）。
     *
     * <p>🔴 只数<b>那 36 格</b>（{@link Inventory#INVENTORY_SIZE}），不是
     * {@code getContainerSize()} —— 后者在 26.3 是 <b>43</b>（36 格 + 副手 + 身体盔甲 + 鞍），
     * 拿它当「背包容量」会把副手和盔甲槽也算成能塞圆石的地方，
     * 于是「填满背包」实际会往副手里塞一组石头（实测踩过：算出 2752 = 43×64）。</p>
     */
    static long looseRoom(Player player, ItemStack sample) {
        Inventory inv = player.getInventory();
        int max = Math.max(1, sample.getMaxStackSize());
        long room = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameComponents(slot, sample)) {
                room += Math.max(0, Math.max(1, slot.getMaxStackSize()) - slot.getCount());
            }
        }
        return room;
    }

    /** 背包里有几个完全空着的格子（潜影盒一格一个，装不了盒就只能算空格）；只数那 36 格 */
    static int emptySlots(Player player) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static Component msgFillNoStock(boolean en) {
        return VoidWarehouseText.info(en, "仓库里没有这种物品了",
                "The warehouse has none of that item left");
    }

    /** 仓库里不够装满一整盒 —— 把「还差多少」说出来，玩家才知道要不要再攒 */
    private static Component msgFillNotEnoughForBox(boolean en, long capacity, long have) {
        return VoidWarehouseText.info(en,
                VoidWarehouseText.body("仓库里不够装满一整盒（一盒 ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(capacity)))
                        .append(VoidWarehouseText.body("，现有 "))
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(have)))
                        .append(VoidWarehouseText.body("）")),
                VoidWarehouseText.body("Not enough for even one full box (needs ")
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(capacity)))
                        .append(VoidWarehouseText.body(", have "))
                        .append(VoidWarehouseText.key(VoidWarehouseText.count(have)))
                        .append(VoidWarehouseText.body(")")));
    }

    /**
     * 取货成功的聊天提示：<b>一整条</b>（含 {@code [虚空仓库] } 前缀），只拼装、不发消息。
     *
     * <p>从 {@link #execute} 里抽出来是为了<b>能脱离世界单测</b>：这条消息的分支不少
     * （有满盒 / 全散货 / 散货装盒），每改一次措辞就跑一局游戏去肉眼验，成本太高。
     * 抽成纯函数后可以在真实服务端里直接构造各种组合断言。</p>
     *
     * <p>文案口径（玩家定）：</p>
     * <ul>
     *   <li>数量后面要跟<b>物品名</b> —— 只写「已取出 200 个」等于没说是取什么，还得回头看背包；</li>
     *   <li>全散货时<b>不写</b>「（200 散货）」—— 括号里的数字和「已取出 200 个」完全一样，
     *       只有真的多出来一个盒子（满盒 / 散货装盒）括号才有新信息，否则纯属啰嗦；</li>
     *   <li>「取了多少个」是这条消息里唯一的关键信息 → 亮绿；物品名与装盒明细是补充 → 灰。</li>
     * </ul>
     *
     * @param en        接收玩家的客户端语言是不是英语（双语版新增；自检默认按中文传 {@code false}）
     * @param fullBoxes 装出来的满盒数
     * @param loose     余下的散货数
     * @param packLoose 散货是否已顺手装成一个盒子（那一盒没装满）
     */
    static Component successMessage(boolean en, ItemStack sample, long requested,
                                    long fullBoxes, long loose, boolean packLoose) {
        StringBuilder detail = new StringBuilder();
        if (fullBoxes > 0) {
            detail.append(VoidWarehouseText.count(fullBoxes))
                    .append(en ? " full box(es)" : " 满盒");
        }
        if (packLoose) {
            if (detail.length() > 0) {
                detail.append(" + ");
            }
            detail.append(en ? "loose " : "散货 ").append(VoidWarehouseText.count(loose))
                    .append(en ? " boxed up" : " 已装盒");
        } else if (fullBoxes > 0 && loose > 0) {
            // 有满盒时「散货数」不等于总数，括号里的数字是真信息，保留
            detail.append(en ? " + loose " : " + 散货 ").append(VoidWarehouseText.count(loose));
        }
        MutableComponent message = VoidWarehouseText.body(en ? "Withdrew " : "已取出 ")
                .append(VoidWarehouseText.key(VoidWarehouseText.count(requested)))
                .append(VoidWarehouseText.body(en ? " " : " 个 "))
                // 物品名保持「可翻译组件」原样，交给客户端本地化（理由见 PrinterRestockNetwork）
                .append(VoidWarehouseText.body(sample.getHoverName()));
        if (detail.length() > 0) {
            message.append(VoidWarehouseText.body(en ? " (" + detail + ")" : "（" + detail + "）"));
        }
        // 前缀在这里就套上：调用方只需「发出去」，不用记得再包一层 info()
        // （本轮重构时就漏过一次 —— 自检里那条「以 [虚空仓库] 开头」把它抓了出来）
        return VoidWarehouseText.info(en, message);
    }

    // ------------------------------------------------------------------ 背包

    /**
     * 把 {@code count} 个散货按该物品的 {@code maxStackSize} 拆成若干<b>满堆叠</b> +
     * 至多一个<b>余数堆叠</b>，追加进 {@code outputs}。
     *
     * <p>这是「按数量取货」散货部分的关键：散货不能是一个超大的单堆叠
     * （例如 1544 个圆石如果直接 {@code copyWithCount(1544)} 会变成一格，
     * 既超过堆叠上限、又没法塞进背包的空格合并逻辑），必须拆成
     * {@code 1544 = 24 × 64 + 8} → 24 个 64 的满堆叠 + 1 个 8 的堆叠。</p>
     */
    private static void appendLoose(List<ItemStack> outputs, ItemStack sample, long count) {
        if (count <= 0) {
            return;
        }
        int max = Math.max(1, sample.getMaxStackSize());
        long remaining = count;
        while (remaining > 0) {
            int n = (int) Math.min(max, remaining);
            outputs.add(sample.copyWithCount(n));
            remaining -= n;
        }
    }

    /**
     * 模拟检查：这堆产物能不能全部塞进玩家背包。
     *
     * <p>在<b>内存里的背包副本</b>上逐个模拟放入（复用原版「优先合并同类、再找空格」的规则），
     * 一个都不剩才算放得下。因为只是副本，真实的背包一个字节都不会被改 ——
     * 这也是「先检查、后放、再扣账」三步里最不容易复制物品的写法。</p>
     */
    private static boolean canFitAll(Player player, List<ItemStack> outputs) {
        Inventory inv = player.getInventory();
        // 🔴 36，不是 getContainerSize()（26.3 那是 43，含副手 / 身体盔甲 / 鞍）。
        // 用 43 的话「放得下」会把副手格也算进去，取货就会往副手里塞一组东西。
        int size = Inventory.INVENTORY_SIZE;
        List<ItemStack> sim = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            sim.add(inv.getItem(i).copy());
        }
        for (ItemStack out : outputs) {
            ItemStack remaining = out.copy();
            // 先合并进同类格
            for (int i = 0; i < sim.size() && !remaining.isEmpty(); i++) {
                ItemStack slot = sim.get(i);
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, remaining)) {
                    continue;
                }
                int room = Math.max(1, slot.getMaxStackSize()) - slot.getCount();
                if (room <= 0) {
                    continue;
                }
                int move = Math.min(room, remaining.getCount());
                slot.grow(move);
                remaining.shrink(move);
            }
            // 再找空格
            for (int i = 0; i < sim.size() && !remaining.isEmpty(); i++) {
                if (sim.get(i).isEmpty()) {
                    sim.set(i, remaining.copy());
                    remaining.shrink(remaining.getCount());
                }
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 把产物真正放进背包（调用方已用 {@link #canFitAll} 确认能全部放下） */
    private static void moveIntoInventory(Player player, ItemStack stack) {
        Inventory inv = player.getInventory();
        // 先合并进同类格（同样只认那 36 格，理由见 canFitAll）
        int size = Inventory.INVENTORY_SIZE;
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) {
                continue;
            }
            int room = Math.max(1, slot.getMaxStackSize()) - slot.getCount();
            if (room <= 0) {
                continue;
            }
            int move = Math.min(room, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
        }
        // 再放空格
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, stack.copy());
                stack.shrink(stack.getCount());
            }
        }
        inv.setChanged();
    }

}
