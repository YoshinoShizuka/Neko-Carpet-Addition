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
     * 建一份「这个仓库现在能给我看的东西」的目录：<b>真实库存格 + 合成页的全部条目</b>
     * （9 合 1 可兑 + 已学配方可合成），按物品去重。
     *
     * <p>两边都要进目录 —— 它们是界面上真实存在的格子。玩家搜「铁锭」时，
     * 「铁锭（可兑）」和「铁锭（可合成）」那两格同样应该被搜到。</p>
     *
     * <p>🔴 <b>2026-11-23 之前这里只有库存</b>（{@link VoidWarehouseData#displaySlotAt(int)}
     * 在页面分离之后<b>永远</b>只返回真实条目，{@code offer} 那一支是死代码）。后果是
     * 「合成页搜不到已学配方」：客户端只拿目录里的 id 去匹配，目录里没有的东西就<b>不会</b>
     * 被回传成过滤集合 —— 而只要搜索命中了任何一件<b>在库存里</b>的东西，服务端就会
     * 真的挂上过滤集合，于是所有「不在库存、只在合成页」的格子当场消失。玩家看到的是
     * 「配方明明存了，搜一下就没了」。合成页专用的条目必须和库存一样进目录。</p>
     *
     * <p><b>不在热路径上</b>：只在客户端发 hello（打开界面 / 主动刷新）时调用一次，
     * 且有 100ms 限流；两个来源都是缓存好的序列（{@code displaySlotAt} 走缓存数组，
     * {@code exchangeOffers()} 按 tick 合并），全物品存档（1657 格 + 满本 746 条）
     * 一次 ≈ 微秒级。</p>
     */
    public static List<String> catalog(VoidWarehouseData data) {
        int count = data.displayCount();
        Set<String> ids = new LinkedHashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            VoidWarehouseData.DisplaySlot slot = data.displaySlotAt(i);
            var template = slot.entry() != null ? slot.entry().template() : null;
            if (template == null || template.isEmpty()) {
                continue;
            }
            ids.add(BuiltInRegistries.ITEM.getKey(template.getItem()).toString());
        }
        // 合成页（第二张）的条目：9 合 1「可兑」与配方「可合成」。
        // 少了这一段，搜索就只认库存 —— 详见方法注释。
        for (VoidWarehouseData.ExchangeOffer offer : data.exchangeOffers()) {
            var template = offer.template();
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
