package neko.voidwarehouse.restock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.math.BigInteger;
import java.util.List;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseSettings;

/**
 * 打印机取货的<b>纯逻辑</b>：给定「候选物品清单」，挑一个真的有货的、取出一组、塞进背包。
 *
 * <p>和网络层分开是为了干净 —— 网络层只管收包与回包，真实的仓库读写和背包操作都在这里，
 * 既便于看，也便于在真实服务端里单独验证。</p>
 *
 * <h2>为什么「取一组」而不是「取 N 个」</h2>
 * <p>需求定的就是取满一组（按物品的原版堆叠上限）。取一组最划算：一次往返能撑很多次放置，
 * 请求次数最少、等待最少 —— 打印机最怕的就是每次缺料都要往返一趟。</p>
 *
 * <h2>一件都没放下时必须原样还回去</h2>
 * <p>背包塞满时如果只是「扣了仓库但不给货」，玩家的仓库就被凭空吃掉了，
 * 而且是很难查的那种。所以这里判定 placed ≤ 0 时把扣掉的整份塞回仓库，
 * 一次都不吭声也不行，要回 {@code INVENTORY_FULL} 让客户端知道。</p>
 *
 * <h2>为什么返回一个 {@link TakeOutcome} 而不是 {@code int}</h2>
 * <p>原先只回一个状态码，够用是因为取货方（打印机）只关心「成没成」。
 * 但要在聊天栏告诉玩家「取走多少 / 还剩多少」就得多带两个数出来 ——
 * 这两个数在 {@link #giveOrRollback} 里本来就算过一遍（{@code placed}），
 * 丢掉再让调用方重算一遍纯属浪费，而且调用方还拿不到「实际放进了几个」这个
 * 只在下发过程中才确定的值。</p>
 */
public final class PrinterTakeService {

    /**
     * 主背包 + 快捷栏的槽位数。
     *
     * <p>对面打印机 {@code PlayerInventoryProvider} 也是用 36 来数背包的，
     * 两边定义必须一致，否则会出现「服务端放在了第 37 格、客户端压根不看」的错位。</p>
     */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private PrinterTakeService() {
    }

    /**
     * 一次取货的完整结果。
     *
     * <p>是个纯数据载体，不含任何 Minecraft 行为 —— service 只负责算，怎么用（回包 / 发消息）
     * 由网络层决定。</p>
     *
     * @param status       结果码，语义见 {@link PrinterTakeResultPayload}
     * @param takenCount   这次<b>真正放进背包</b>的数量（失败 / 回滚时为 0）
     * @param remaining    本次操作<b>之后</b>该物品在仓库里的剩余数量
     * @param sample       这轮命中的物品样板（数量恒 1）；一次都没命中时为 {@link ItemStack#EMPTY}
     */
    public record TakeOutcome(int status, int takenCount, BigInteger remaining, ItemStack sample) {

        /** 只有状态、没有数量的简写（失败路径用；剩余数量按「没动过仓库」置 0） */
        static TakeOutcome of(int status) {
            return new TakeOutcome(status, 0, BigInteger.ZERO, ItemStack.EMPTY);
        }

        /** 成功取货时才有意义：确实往背包里放了东西 */
        public boolean delivered() {
            return status == PrinterTakeResultPayload.OK && takenCount > 0;
        }
    }

    /**
     * 执行一次取货。
     *
     * @param server  服务端实例（取仓库用）
     * @param player  收货的玩家
     * @param candidates 候选物品的注册表 id 字符串，按顺序尝试
     * @return 结果（状态码 + 实际取走数量 + 取后剩余量）
     */
    public static TakeOutcome take(MinecraftServer server, ServerPlayer player, List<String> candidates) {
        if (server == null || player == null) {
            return TakeOutcome.of(PrinterTakeResultPayload.BAD_REQUEST);
        }
        // 主开关也一并检查：voidWarehouse 关掉时仓库 UI 会被强制关闭，
        // 此时「补货规则看起来是开的但取不出东西」会很难排查，索性明确回报 RULE_OFF。
        if (!VoidWarehouseSettings.voidWarehouse || !VoidWarehouseRestockSettings.voidWarehouseRestock) {
            return TakeOutcome.of(PrinterTakeResultPayload.RULE_OFF);
        }
        if (candidates == null || candidates.isEmpty()) {
            return TakeOutcome.of(PrinterTakeResultPayload.BAD_REQUEST);
        }

        VoidWarehouseData data = VoidWarehouse.data(server);

        for (String raw : candidates) {
            Item item = resolveItem(raw);
            if (item == null) {
                continue;
            }
            if (isBlacklisted(item)) {
                continue;
            }
            ItemStack sample = new ItemStack(item);
            // 「有货就直接扣成品、成品一件都没有时（规则允许）改用 9 合 1 兑换」这条判断
            // 封装在 VoidWarehouseData#withdraw 里，这里不需要自己先查一遍库存。
            VoidWarehouseData.Withdrawal withdrawal =
                    data.withdraw(sample, BigInteger.valueOf(Integer.MAX_VALUE));
            if (withdrawal.isEmpty()) {
                // 这件既没货、也兑不出来 → 换下一个候选，不算失败
                continue;
            }
            return giveOrRollback(data, player, sample, withdrawal);
        }
        return TakeOutcome.of(PrinterTakeResultPayload.EMPTY);
    }

    /**
     * 把 registry id 字符串解析成物品。
     *
     * <p>用 {@code tryParse} 而不是 {@code parse}：客户端传什么都可能的，
     * 一个畸形字符串不该在服务端的网络线程里抛异常。</p>
     *
     * @return 解析失败或不是有效物品时返回 {@code null}
     */
    private static Item resolveItem(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 256) {
            return null;
        }
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == Items.AIR) {
            return null;
        }
        return item;
    }

    /**
     * 明确不接受的东西：目前只有潜影盒。
     *
     * <p>为什么挡：仓库里存的是散件原料，任由客户端无限取盒既不合理、
     * 也可能被当成刷盒机（生产一个盒子还额外需要潜影壳与箱子，放开了等于漏成本）。</p>
     *
     * <h2>为什么拿 block 类型判断，而不是看 {@code CONTAINER} 组件</h2>
     * <p>先看可行的坑：<b>空</b>潜影盒的容器组件取出来就是 {@code ItemContainerContents.EMPTY}，
     * 用 {@code getOrDefault(CONTAINER, EMPTY) != EMPTY} 判断等于「没装东西的盒子不算盒子」，
     * 完全睁眼瞎 —— 而仓库里最常见的恰好就是空盒。<br>
     * 所以直接看物品背后的方块类型：26.x 根本没有 {@code ShulkerBoxItem}，
     * 潜影盒就是 {@link net.minecraft.world.level.block.ShulkerBoxBlock} 的
     * {@link net.minecraft.world.item.BlockItem}，这样判断最准，也不依赖组件是否缺省。</p>
     */
    private static boolean isBlacklisted(Item item) {
        return item instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * 真的取并且真的放；放不下就把扣掉的还回去。
     *
     * <p>撤销走 {@link VoidWarehouseData.Withdrawal#returnUnplaced(int)} ——
     * 它同时覆盖两条路：直接扣成品那条「原样退回同一种物品」，
     * 以及 9 合 1 兑换那条「整笔账反向执行、再按实际用掉的量重做」。</p>
     *
     * @param sample     样板（数量无所谓，内部只看 Item + 数据组件）
     * @param withdrawal 已经扣好账的取货结果（还没放进背包）
     */
    private static TakeOutcome giveOrRollback(VoidWarehouseData data, ServerPlayer player,
                                              ItemStack sample, VoidWarehouseData.Withdrawal withdrawal) {
        ItemStack taken = withdrawal.stack();
        Inventory inventory = player.getInventory();
        int remaining = tryGive(inventory, taken);
        int placed = taken.getCount() - remaining;

        if (placed <= 0) {
            // 一件都没放进去 → 整笔撤销，仓库零消耗
            withdrawal.returnUnplaced(0);
            return new TakeOutcome(PrinterTakeResultPayload.INVENTORY_FULL, 0,
                    data.amountExact(sample), sample);
        }
        if (remaining > 0) {
            // 放了一部分 → 只退还没放下的那部分
            withdrawal.returnUnplaced(placed);
        }
        // 与 /give 一致：改动了服务端背包就必须推一次差分，否则客户端看不到货
        player.containerMenu.broadcastChanges();
        if (player.inventoryMenu != player.containerMenu) {
            player.inventoryMenu.broadcastChanges();
        }
        // 注意：remaining 是「退还未放下部分之后」的真实剩余量，不是取货前的 stock - placed。
        // 两者在「背包只塞得下一部分」时不同（没放下的会退回去），所以必须重新查一次。
        return new TakeOutcome(PrinterTakeResultPayload.OK, placed,
                data.amountExact(sample), sample);
    }

    /**
     * 往主背包里塞东西，返回<b>没塞下的数量</b>。
     *
     * <p>自己写而不是用 {@code Inventory#placeItemBackInInventory}：那个方法在 26.3 和
     * 1.21.11 的签名不一样（26.3 多了 {@code Prediction} 参数），而这套逻辑要五个
     * 版本共用，写得直白一点省得每个版本都要 A/B。</p>
     *
     * <p>顺序是「先合并到已有槽，再放空格」—— 与原版 /give 的行为一致，
     * 玩家背包里已经有 30 个圆石时会先补到 64，不会平白占一个新格子。</p>
     */
    private static int tryGive(Inventory inventory, ItemStack stack) {
        int remaining = stack.getCount();
        ItemStack unit = stack.copyWithCount(1);
        int max = Math.max(1, unit.getMaxStackSize());
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());

        // ① 先填已经装了同类物品的槽
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, unit)) {
                continue;
            }
            int canAbsorb = max - current.getCount();
            if (canAbsorb <= 0) {
                continue;
            }
            int move = Math.min(canAbsorb, remaining);
            inventory.setItem(slot, current.copyWithCount(current.getCount() + move));
            remaining -= move;
        }

        // ② 再放空格
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            if (!inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int move = Math.min(max, remaining);
            inventory.setItem(slot, unit.copyWithCount(move));
            remaining -= move;
        }
        return remaining;
    }
}
