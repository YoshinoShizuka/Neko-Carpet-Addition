package neko.voidwarehouse.restock;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import neko.voidwarehouse.VoidWarehouse;
import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseSettings;

/**
 * 材料列表查询的<b>纯逻辑</b>：给定一批物品 id，回一份「各有多少」。
 *
 * <p>与 {@link PrinterTakeService} 的定位一样 —— 只算，不管收发。这样它既能在
 * 真实服务端里单独跑自检，也不会把「必须有网络连接」这件事绑死在业务逻辑上。</p>
 *
 * <h2>为什么先建索引再回答，而不是对着请求逐个扫仓库</h2>
 * <p>请求里可能有几百个 id，仓库里可能有上千格。逐个扫就是「几百 × 上千」的乘积；
 * 先把仓库摊成一张 {@code id → 数量} 的表（一遍上千次），再拿请求去查表（几百次），
 * 总和是加法不是乘法。这点在小仓库上无所谓，但它决定了这个功能能不能被
 * 「开着材料列表边建造边刷」——那是每 5 秒一次的常态。</p>
 *
 * <h2>只读</h2>
 * <p>这个类<b>只</b>调 {@code VoidWarehouse.data(server)} 的读方法，
 * 一个字节都不会写回存档，也不会碰玩家背包。所以它没有取货那边的
 * 「背包放不下要原样退货」这类回滚问题。</p>
 */
public final class PrinterQueryService {

    /**
     * 一次查询的结果。
     *
     * @param available  仓库能力是否可用（false = 规则没开，客户端应当停用本功能）
     * @param itemIds    有货的物品 id，与 {@code amounts} 一一对应、顺序一致
     * @param amounts    对应的数量，任意精度、已饱和到 {@code long}
     */
    public record Snapshot(boolean available, List<String> itemIds, List<Long> amounts) {

        static Snapshot unavailable() {
            return new Snapshot(false, List.of(), List.of());
        }
    }

    private PrinterQueryService() {
    }

    /**
     * 回答一次查询。
     *
     * @param server  服务端实例（取仓库用）
     * @param itemIds 客户端要问的物品注册表 id，允许含重复 / 畸形项（会被跳过）
     */
    public static Snapshot query(MinecraftServer server, List<String> itemIds) {
        if (server == null) {
            return Snapshot.unavailable();
        }
        // 与取货同一个口径：主开关关了就当没这回事。查询虽然只读，但「仓库界面都被强制关了
        // 却还能看到库存」同样会让人困惑，不如一致地回 unavailable。
        if (!VoidWarehouseSettings.voidWarehouse || !VoidWarehouseRestockSettings.voidWarehouseRestock) {
            return Snapshot.unavailable();
        }
        if (itemIds == null || itemIds.isEmpty()) {
            return new Snapshot(true, List.of(), List.of());
        }

        Map<String, BigInteger> stock = index(server);

        List<String> ids = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        // 同一个 id 被问两次只回一次：客户端拼清单时可能因为「同一个物品既是方块也是物品」
        // 之类的来源重复，多回一条会让两边数组对不齐（它们靠下标对应）。
        // 用 Set 去重而不是 List#contains —— 清单上限 1024，线性查会退化成平方级。
        Set<String> seen = new HashSet<>();
        for (String raw : itemIds) {
            String id = canonicalId(raw);
            if (id == null || !seen.add(id)) {
                continue;
            }
            BigInteger amount = stock.get(id);
            if (amount == null || amount.signum() <= 0) {
                continue;
            }
            ids.add(id);
            amounts.add(saturate(amount));
        }
        return new Snapshot(true, List.copyOf(ids), List.copyOf(amounts));
    }

    /**
     * 把整个仓库摊成 {@code 物品注册表 id → 数量}。
     *
     * <p>用注册表 id（{@code minecraft:stone}）而不是 {@link Item} 对象当键：
     * 它正是网络上传的那种形式，建表和查表都不需要来回转换。</p>
     *
     * <h2>为什么同一 id 是累加而不是覆盖</h2>
     * <p>仓库的格子是「物品 + 数据组件」，理论上同一个物品的两种形态（比如附魔不同的书）
     * 会各占一格。而材料列表是<b>按物品</b>统计的，所以这里必须把同名 id 的几格加起来，
     * 否则「仓库里明明有 200 本附魔书却显示 0」。</p>
     */
    private static Map<String, BigInteger> index(MinecraftServer server) {
        VoidWarehouseData data = VoidWarehouse.data(server);
        int count = data.entryCount();
        Map<String, BigInteger> stock = new HashMap<>(Math.max(16, count * 2));

        for (int i = 0; i < count; i++) {
            VoidWarehouseData.Entry entry = data.entryAt(i);
            if (entry == null) {
                continue;
            }
            String id = idOf(entry.template().getItem());
            if (id == null) {
                continue;
            }
            BigInteger amount = entry.amount();
            if (amount.signum() <= 0) {
                continue;
            }
            stock.merge(id, amount, BigInteger::add);
        }
        return stock;
    }

    /**
     * 把客户端传来的字符串规整成「注册表 id」。
     *
     * <p>用 {@code tryParse} + {@code getOptional} 而不是 {@code parse}/{@code get}：
     * 客户端传什么都可能，一个畸形字符串不该在服务端主线程上抛异常。</p>
     *
     * @return 规整后的 id；解析失败或不是有效物品时返回 {@code null}
     */
    private static String canonicalId(String raw) {
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
        return idOf(item);
    }

    private static String idOf(Item item) {
        if (item == null || item == Items.AIR) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? null : key.toString();
    }

    /**
     * 把任意精度的数量压进 {@code long}。
     *
     * <p>仓库的账没有上限，而网络上传的是 {@code long}。超过上限时<b>饱和</b>而不是环绕 ——
     * 环绕会得到一个负数，在界面上就是「仓库里有 -9223372036854775808 个」，比数字大一点糟糕得多。</p>
     */
    private static long saturate(BigInteger amount) {
        return amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : amount.longValue();
    }
}
