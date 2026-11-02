package neko.voidwarehouse.search;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import neko.voidwarehouse.VoidWarehouseData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索通道的服务端业务逻辑：建目录、把客户端回传的 id 集合解析成过滤集合。
 *
 * <p>刻意的<b>双不碰</b>：不碰语言（专用服务器没有中文语言文件，匹配必须在客户端做）、
 * 不碰拼音（同理）。服务端只认 {@code Item} 身份，所以这里唯一的「聪明」是
 * 注册表查表 —— 而查表口径与配方本（{@code VoidWarehouseRecipeBook}）完全一致：
 * {@code Identifier.tryParse} 判格式（空串会解析成 {@code minecraft:}，
 * 所以必须再过一道 {@code containsKey}）、查不到的静默丢弃。</p>
 */
public final class SearchService {

    private SearchService() {
    }

    /**
     * 建一份「仓库现有全部物品」的目录：真实库存格 + 9 合 1 可兑虚拟条目，按展示序去重。
     *
     * <p>可兑条目也要进目录 —— 它们是界面上真实存在的格子，玩家搜「铁锭」时
     * 「铁锭（可兑）」那一格同样应该被搜到。顺序跟展示序走（创造栏顺序），
     * 客户端按什么顺序展示搜索结果都不受影响，这里去重只为包体最小。</p>
     *
     * <p><b>不在热路径上</b>：只在客户端发 hello（打开界面 / 主动刷新）时调用一次，
     * 且有 100ms 限流。走 {@link VoidWarehouseData#displaySlotAt(int)} 逐格解析，
     * 内部全是缓存的数组与列表，全物品存档（1657 格）一次 ≈ 微秒级。</p>
     */
    public static List<String> catalog(VoidWarehouseData data) {
        int count = data.displayCount();
        Set<String> ids = new LinkedHashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            VoidWarehouseData.DisplaySlot slot = data.displaySlotAt(i);
            var template = slot.entry() != null ? slot.entry().template()
                    : slot.offer() != null ? slot.offer().template() : null;
            if (template == null || template.isEmpty()) {
                continue;
            }
            ids.add(BuiltInRegistries.ITEM.getKey(template.getItem()).toString());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 把客户端回传的 id 列表解析成过滤集合；<b>空列表 / 全部无效 ⇒ 空集合 = 清除过滤</b>。
     *
     * <p>查不到的 id 静默忽略：客户端与服务端的注册表理论上一致，
     * 但防御性地不假设（例如服务端装了客户端没有的模组物品时，目录里那一条
     * 客户端匹配不出来自然不会回传；反过来多出来的 id 也绝不能让服务端崩）。</p>
     */
    public static Set<Item> resolveFilter(List<String> itemIds) {
        Set<Item> items = new HashSet<>();
        if (itemIds == null) {
            return items;
        }
        for (String id : itemIds) {
            if (id == null) {
                continue;
            }
            Identifier key = Identifier.tryParse(id);
            if (key != null && BuiltInRegistries.ITEM.containsKey(key)) {
                items.add(BuiltInRegistries.ITEM.getValue(key));
            }
        }
        return items;
    }
}
