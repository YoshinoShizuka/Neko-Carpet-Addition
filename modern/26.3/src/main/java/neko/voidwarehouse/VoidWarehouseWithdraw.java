package neko.voidwarehouse;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 *       记一条待处理请求，并提示玩家「在聊天栏输入要取走的数量」；</li>
 *   <li>玩家在聊天栏输入一个数字 → 聊天 Mixin 调 {@link #onChat(ServerPlayer, String)}，
 *       解析数量并执行取货；输入非数字 → 取消请求、提示已取消。</li>
 * </ol>
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
 * <h2>合成材料的扣除（优先用空盒）</h2>
 * <p>每个满盒都需要一个「盒子」：</p>
 * <ul>
 *   <li><b>仓库里有空潜影盒库存</b> → 优先用空盒装（每盒扣 1 个空盒，不耗壳 / 箱）；</li>
 *   <li><b>空盒不够</b> → 剩下的盒子每个扣 {@link VoidWarehouseShulker#SHELL_COST} 个潜影壳 +
 *       {@link VoidWarehouseShulker#CHEST_COST} 个箱子（原版配方）。</li>
 * </ul>
 * <p>散货不需要盒子，直接给散件。</p>
 *
 * <h2>All-or-nothing</h2>
 * <p>整次操作要么全成、要么全不成：先校验「物品库存 / 空盒 / 壳 / 箱 / 背包空间」全部够，
 * 才真正放物品 + 扣账；任何一项不够都只发一条提示、仓库一个字节都不动。</p>
 */
public final class VoidWarehouseWithdraw {
    /** 单次可输入的数量上限，防止手滑输入天文数字 */
    private static final long MAX_REQUEST = 2_000_000L;

    private static final long REQUEST_TTL_MS = 30_000L;

    private static final Component MSG_PROMPT =
            info("请在聊天栏输入要取走的数量（输入其他内容取消）");
    private static final Component MSG_CANCELLED = info("已取消本次取货");
    private static final Component MSG_INVALID =
            info("输入的不是有效数量，已取消本次取货");
    private static final Component MSG_TOO_LARGE =
            info("数量过大（最多 " + MAX_REQUEST + "），已取消本次取货");
    private static final Component MSG_NO_STOCK =
            info("仓库里这种物品的数量不足，本次取货已取消");
    private static final Component MSG_NO_PARTS =
            info("空潜影盒 / 潜影壳 / 箱子不足，无法装盒，本次取货已取消");
    private static final Component MSG_INV_FULL =
            info("背包放不下这么多物品，本次取货已取消");

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
        player.sendSystemMessage(MSG_PROMPT);
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
        // 过期（玩家磨蹭太久）：直接作废，消息交还原版聊天
        if (System.currentTimeMillis() - pending.createdMs() > REQUEST_TTL_MS) {
            return false;
        }
        long requested;
        try {
            requested = Long.parseLong(message.trim());
        } catch (NumberFormatException e) {
            player.sendSystemMessage(MSG_INVALID);
            // 非数字：取消请求，但让这条消息照常发出去（玩家可能只是想说话）
            return false;
        }
        if (requested <= 0 || requested > MAX_REQUEST) {
            player.sendSystemMessage(MSG_TOO_LARGE);
            return true;
        }
        execute(player, pending.sample(), requested);
        return true;
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行取货：按 {@code requested} 拆成满盒 + 散货，放背包、扣账。
     *
     * <p>顺序与 {@code withdrawShulkerBox} 一致：先校验 → 生成产物 → 检查背包 → 放成功才扣账。</p>
     */
    private static void execute(ServerPlayer player, ItemStack sample, long requested) {
        VoidWarehouseData data = VoidWarehouse.data(player.level().getServer());
        if (data == null) {
            return;
        }
        long capacity = VoidWarehouseShulker.capacity(sample);
        long boxes = requested / capacity;
        long remainder = requested % capacity;

        // ① 校验物品库存（精确口径：同物品 + 同数据组件，与 takeExact 一致）
        BigInteger stock = data.amountExact(sample);
        if (stock.compareTo(BigInteger.valueOf(requested)) < 0) {
            player.sendSystemMessage(MSG_NO_STOCK);
            return;
        }

        // ② 校验盒子材料：优先空盒，不够的用壳 + 箱
        long emptyBoxes = data.emptyBoxCount().min(BigInteger.valueOf(boxes)).longValue();
        long needCraft = boxes - emptyBoxes;
        BigInteger needShells = BigInteger.valueOf(needCraft * VoidWarehouseShulker.SHELL_COST);
        BigInteger needChests = BigInteger.valueOf(needCraft * VoidWarehouseShulker.CHEST_COST);
        if (data.amountOf(Items.SHULKER_SHELL).compareTo(needShells) < 0
                || data.amountOf(Items.CHEST).compareTo(needChests) < 0) {
            player.sendSystemMessage(MSG_NO_PARTS);
            return;
        }

        // ③ 生成产物：boxes 个满盒 + 散货（散货按 maxStackSize 拆成若干满堆叠 + 1 个余数堆叠）
        List<ItemStack> outputs = new ArrayList<>();
        for (long i = 0; i < boxes; i++) {
            outputs.add(VoidWarehouseShulker.pack(sample, capacity));
        }
        appendLoose(outputs, sample, remainder);
        if (outputs.isEmpty()) {
            return;
        }

        // ④ 检查背包能否全部放下（纯模拟，不污染真实背包）
        if (!canFitAll(player, outputs)) {
            player.sendSystemMessage(MSG_INV_FULL);
            return;
        }

        // ⑤ 全部放得下：先放，放成功（已确认能放）才扣账
        for (ItemStack out : outputs) {
            moveIntoInventory(player, out);
        }

        // ⑥ 扣账：物品 + 空盒 + 壳 + 箱
        data.takeExact(sample, (int) requested);
        data.takeEmptyBoxes((int) emptyBoxes);
        if (needShells.signum() > 0) {
            data.takeItem(Items.SHULKER_SHELL, needShells.intValue());
        }
        if (needChests.signum() > 0) {
            data.takeItem(Items.CHEST, needChests.intValue());
        }
        player.sendSystemMessage(info("已取出 " + requested + " 个（"
                + (boxes > 0 ? boxes + " 满盒" : "")
                + (remainder > 0 ? (boxes > 0 ? " + " : "") + remainder + " 散货" : "")
                + "）"));
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
        int size = inv.getContainerSize();
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
        // 先合并进同类格
        int size = inv.getContainerSize();
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

    // ------------------------------------------------------------------ 提示

    /** 金色 + [信息] 前缀的聊天栏提示 */
    private static Component info(String body) {
        return Component.literal("[信息] ")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(ChatFormatting.BOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.GOLD));
    }
}
