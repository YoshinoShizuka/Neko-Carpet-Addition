package neko.voidwarehouse.restock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseSettings;

/**
 * 打印机返还的<b>纯逻辑</b>：把「背包里这几格」原样收进仓库，并把槽位清空。
 *
 * <p>与 {@link PrinterTakeService} 对称 —— 那边是「仓库 → 背包」，这边是「背包 → 仓库」。
 * 之所以单独一个类而不是塞进 take 那边：取货有取货的坑（找候选、兑换、放不下要回滚），
 * 返还只有一个坑（别让客户端决定物品内容），分开写两边都好读，也好各自验证。</p>
 *
 * <h2>为什么整条路径不需要「回滚」</h2>
 * <p>{@link VoidWarehouseData#insert} 只有「收下」和「拒收」两种结果，而且
 * <b>收下时会把传入的堆叠清零、拒收时原样不动</b>。所以每一格的顺序只能是
 * 「先入库（拿到 true 才动背包）」—— 入库成功才清空槽位，入库失败就把格子留着。
 * 中途不可能出现「仓库收到了、背包也还留着」或两者都不认的中间态，
 * 因此不需要像取货那样准备一条撤销路径。</p>
 *
 * <h2>潜影盒为什么直接跳过</h2>
 * <p>{@code insert} 遇到非空潜影盒是<b>拆开</b>入库（变成壳 + 箱子 + 内容物），
 * 这是漏斗与手动存盒该有的行为，但放在「返还」这条自动路径上就是灾难：
 * 客户端只要发一个盒子所在槽位，玩家的盒子就被不可逆地拆了。所以这里
 * <b>与取货的拒绝清单保持一致</b>，整类挡掉 —— 何况仓库本来就不会把盒子发给打印机
 * （见 {@link PrinterTakeService} 的拒绝清单），正常流程里根本不该出现盒子。</p>
 */
public final class PrinterReturnService {

    /**
     * 主背包 + 快捷栏的槽位数。
     *
     * <p>与 {@link PrinterTakeService} 里那份、以及打印机 {@code PlayerInventoryProvider}
     * 的定义必须一致，否则会出现「客户端数第 30 格、服务端却只看前 27 格」的错位。</p>
     */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private PrinterReturnService() {
    }

    /**
     * 一次返还的结果。
     *
     * @param status        结果码，语义见 {@link PrinterReturnResultPayload}
     * @param returnedCount 真正收进仓库的物品总数（失败时为 0）
     * @param clearedSlots  被清空的背包槽数（用于「退了几堆」这种口径）
     * @param sample        这批里最先退回去的物品样板（数量恒 1）；一格都没退时是 {@link ItemStack#EMPTY}
     * @param moved         逐物品明细（见 {@link Moved}）；失败路径为空清单
     */
    public record ReturnOutcome(int status, int returnedCount, int clearedSlots, ItemStack sample,
                                List<Moved> moved) {

        static ReturnOutcome of(int status) {
            return new ReturnOutcome(status, 0, 0, ItemStack.EMPTY, List.of());
        }

        /** 真的往仓库里放进了东西 */
        public boolean delivered() {
            return status == PrinterReturnResultPayload.OK && this.returnedCount > 0;
        }
    }

    /**
     * 一种物品在这一批里退了多少、入完之后仓库里还剩多少。
     *
     * @param itemId    注册名（如 {@code minecraft:dirt}）；客户端拿它还原物品与译名
     * @param count     本批退回去的件数
     * @param remaining 本批入库<b>做完之后</b>仓库里这种物品的总数（口径 = 物品 + 数据组件）
     */
    public record Moved(String itemId, int count, long remaining) {
    }

    /**
     * 执行一次返还。
     *
     * @param server 服务端实例（取仓库用）
     * @param player 交出物品的玩家
     * @param slots  主背包 / 快捷栏的槽位号（0–35），越界与空槽会被逐个跳过
     * @return 结果（状态码 + 实收数量 + 清空的槽数 + 逐物品明细）
     */
    public static ReturnOutcome returnSlots(MinecraftServer server, ServerPlayer player, List<Integer> slots) {
        if (server == null || player == null) {
            return ReturnOutcome.of(PrinterReturnResultPayload.BAD_REQUEST);
        }
        // 与取货同一道门：主开关关掉时仓库 UI 会被强制关闭，此时「规则看起来开着却退不进去」
        // 会非常难排查，索性明确回报 RULE_OFF。返还复用补货那条规则 ——
        // 它们本来就是同一套「打印机自动化」能力的两个方向。
        if (!VoidWarehouseSettings.voidWarehouse || !VoidWarehouseRestockSettings.voidWarehouseRestock) {
            return ReturnOutcome.of(PrinterReturnResultPayload.RULE_OFF);
        }
        if (slots == null || slots.isEmpty()) {
            return ReturnOutcome.of(PrinterReturnResultPayload.BAD_REQUEST);
        }

        VoidWarehouseData data = VoidWarehouse.data(server);
        Inventory inventory = player.getInventory();
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());

        int returned = 0;
        int cleared = 0;
        ItemStack sample = ItemStack.EMPTY;
        // 逐物品记账：样板（数量恒 1，带数据组件）→ 件数。用 List 而不是 Map 是为了保住
        // 「先退的排前面」这个顺序 —— 提示里的次序跟玩家背包里被拿走的次序对得上。
        List<ItemStack> templates = new ArrayList<>();
        List<Integer> tallies = new ArrayList<>();

        for (Integer raw : slots) {
            if (raw == null) {
                continue;
            }
            int slot = raw;
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (current.isEmpty() || isBlacklisted(current)) {
                continue;
            }
            // 数量与样板都必须在 insert 之前取：insert 成功时会把传进去的那份清零
            int count = current.getCount();
            ItemStack template = current.copyWithCount(1);
            ItemStack moving = current.copy();
            if (!data.insert(moving)) {
                // 拒收（理论上只剩空堆叠这一种）→ 格子原样留着，绝不清空
                continue;
            }
            inventory.setItem(slot, ItemStack.EMPTY);
            returned += count;
            cleared++;
            if (sample.isEmpty()) {
                sample = template;
            }
            tally(templates, tallies, template, count);
        }

        if (returned <= 0) {
            return ReturnOutcome.of(PrinterReturnResultPayload.NOTHING);
        }
        // 与 /give 同理：改动了服务端背包就必须推一次差分，否则客户端那一格看起来还有东西
        player.containerMenu.broadcastChanges();
        if (player.inventoryMenu != player.containerMenu) {
            player.inventoryMenu.broadcastChanges();
        }
        return new ReturnOutcome(PrinterReturnResultPayload.OK, returned, cleared, sample,
                describe(data, templates, tallies));
    }

    /** 把一份「样板 + 件数」累加进记账单（物品 + 数据组件相同就合并）。 */
    private static void tally(List<ItemStack> templates, List<Integer> tallies, ItemStack template, int count) {
        for (int i = 0; i < templates.size(); i++) {
            if (ItemStack.isSameItemSameComponents(templates.get(i), template)) {
                tallies.set(i, tallies.get(i) + count);
                return;
            }
        }
        templates.add(template);
        tallies.add(count);
    }

    /**
     * 把记账单变成回执明细：补上注册名与<b>入库做完之后</b>的仓库剩余量。
     *
     * <p>剩余量必须在<b>全部 insert 之后</b>统一读 —— 一批里同一种物品可能横跨好几格
     * （快捷栏一份、主背包一份），边退边读会报出「退完第一格时的中间值」，
     * 那不是玩家想看的数。口径用 {@link VoidWarehouseData#amountExact}（物品 + 数据组件），
     * 与入库归并、与客户端那本账完全一致。</p>
     */
    private static List<Moved> describe(VoidWarehouseData data, List<ItemStack> templates, List<Integer> tallies) {
        List<Moved> moved = new ArrayList<>(templates.size());
        for (int i = 0; i < templates.size(); i++) {
            ItemStack template = templates.get(i);
            long remaining;
            try {
                remaining = data.amountExact(template).longValue();
            } catch (RuntimeException ex) {
                // 读剩余量出问题只丢这个数字，绝不让整批返还（已经进仓库了）判成失败
                remaining = -1L;
            }
            moved.add(new Moved(BuiltInRegistries.ITEM.getKey(template.getItem()).toString(),
                    tallies.get(i), remaining));
        }
        return moved;
    }

    /**
     * 明确不接受的格子内容：潜影盒。
     *
     * <p>判断方式与 {@link PrinterTakeService} 完全一致 —— 看物品背后的方块类型，
     * 而不是看 {@code CONTAINER} 组件（空盒的组件就是 {@code EMPTY}，用组件判断等于睁眼瞎）。
     * 理由见本类注释「潜影盒为什么直接跳过」。</p>
     */
    private static boolean isBlacklisted(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }
}
