package neko.voidwarehouse.recipe;

import neko.voidwarehouse.VoidWarehouseData;
import neko.voidwarehouse.VoidWarehouseRecipes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 配方本浏览 / 删除的服务端业务逻辑：建索引、切一页、删一批。
 *
 * <p>这个类<b>不碰网络</b>（那是 {@link RecipeNetwork} 的事），也<b>不碰语言</b>
 * （中文 / 拼音匹配只能在客户端做 —— 专用服务器没有 {@code zh_cn.json}）。
 * 它只接受「页码 + 排序 + 产物白名单」这种与语言无关的输入。</p>
 *
 * <h2>排序为什么由服务端定死</h2>
 * <p>分页要能「翻第 2 页还接着第 1 页」就必须有一个<b>跨请求稳定</b>的全序。
 * 两个排序键都是内容派生的，不随插入顺序漂移：</p>
 * <ul>
 *   <li>{@code OUTPUT}：产物 id 字典序 → {@code recipeId} 字典序；</li>
 *   <li>{@code RECENT}：学习序倒排（{@code recipeTexts} 是 {@code LinkedHashSet}，
 *       解码出来的 {@code recipes} 天然保序）。</li>
 * </ul>
 *
 * <h2>删除为什么要先整体校验</h2>
 * <p>删除会触发整本重建（解码 + 配方树编译 + 落盘）。逐条校验逐条删 = N 次重建，
 * 2048 条时会把主线程按住好几秒。所以这里有三个动作、每个只做一次：
 * 校验 → {@code forgetRecipes(批量)} → 回执。</p>
 */
public final class RecipeService {

    /**
     * {@code recipeId} 的长度上限。
     *
     * <p>id 是「产物 + 产出数 + 原料清单」编出来的文本，正常几十到一两百字符。
     * 512 足够容纳任何真实配方，同时挡住「发一个 1MB 的字符串让我去哈希」这种玩法。</p>
     */
    public static final int MAX_ID_LENGTH = 512;

    private RecipeService() {
    }

    // ------------------------------------------------------------------ 索引

    /**
     * 建一份索引：产物 id 去重清单 + 两个总条数 + 能不能删。
     *
     * <p>{@code canDelete} 当前恒为 {@code true} —— 按产品决定，「开启虚空仓库规则后
     * 视为所有玩家都有删除权」。字段留着是为了<b>将来收紧权限时不用改协议</b>：
     * 客户端已经在按它灰按钮了。</p>
     */
    public static RecipeIndexPayload index(VoidWarehouseData data) {
        return new RecipeIndexPayload(
                RecipeIndexPayload.PROTOCOL,
                data.recipeGeneration(),
                data.recipeSnapshot().size(),
                data.recipeBookUsed(),
                true,
                data.recipeOutputs());
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 切一页明细。
     *
     * <p>页码越界不报错：夹到合法范围，并把夹后的值放进回执（删除之后总页数变小，
     * 客户端拿着旧页码来问是常态）。{@code pageSize} 同理，超过
     * {@link RecipeQueryPayload#MAX_PAGE_SIZE} 就夹。</p>
     *
     * <p>白名单里的未知产物 id 静默忽略（客户端与服务端的注册表理论上一致，
     * 但防御性地不假设 —— 与服务端搜索通道同一个口径）。</p>
     */
    public static RecipePagePayload page(VoidWarehouseData data, RecipeQueryPayload query) {
        int pageSize = clamp(query.pageSize(), 1, RecipeQueryPayload.MAX_PAGE_SIZE);
        Set<String> filter = query.outputs() == null || query.outputs().isEmpty()
                ? Set.of()
                : new HashSet<>(query.outputs());

        List<VoidWarehouseRecipes.Recipe> hits = new ArrayList<>();
        if (query.sort() == RecipeQueryPayload.SORT_RECENT) {
            List<VoidWarehouseRecipes.Recipe> snapshot = data.recipeSnapshot();
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                keep(snapshot.get(i), filter, hits);
            }
        } else {
            for (VoidWarehouseRecipes.Recipe recipe : data.recipeOrdered()) {
                keep(recipe, filter, hits);
            }
        }

        int total = hits.size();
        int pages = Math.max(1, (total + pageSize - 1) / pageSize);
        int page = clamp(query.page(), 0, pages - 1);
        int from = page * pageSize;
        int to = Math.min(from + pageSize, total);

        List<RecipePagePayload.Entry> entries = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            entries.add(entryOf(hits.get(i)));
        }
        return new RecipePagePayload(
                RecipePagePayload.PROTOCOL,
                data.recipeGeneration(),
                page,
                total,
                entries);
    }

    private static void keep(VoidWarehouseRecipes.Recipe recipe, Set<String> filter,
                             List<VoidWarehouseRecipes.Recipe> out) {
        if (recipe == null) {
            return;
        }
        if (filter.isEmpty() || filter.contains(recipe.output())) {
            out.add(recipe);
        }
    }

    /** 把内部模型翻成线格式（两边故意不共用类型：payload 不能依赖只有服务端有的 {@code common/}） */
    private static RecipePagePayload.Entry entryOf(VoidWarehouseRecipes.Recipe recipe) {
        List<RecipePagePayload.Part> parts = new ArrayList<>(recipe.parts().size());
        for (VoidWarehouseRecipes.Part part : recipe.parts()) {
            parts.add(new RecipePagePayload.Part(List.copyOf(part.candidates()), part.count()));
        }
        return new RecipePagePayload.Entry(
                recipe.recipeId(), recipe.output(), recipe.outputCount(), parts);
    }

    // ------------------------------------------------------------------ 删除

    /** 一批删除的结果：状态码 + 真的删掉的 id + 逐条失败原因 */
    public record DeleteOutcome(int status, List<String> removed, List<RecipeDeleteResultPayload.Failure> failed) {
        public DeleteOutcome {
            removed = List.copyOf(removed);
            failed = List.copyOf(failed);
        }
    }

    /**
     * 删一批。
     *
     * <p>先去重（同一个 id 发两遍不报错），再逐条判存在性，最后<b>一次</b>调用
     * {@link VoidWarehouseData#forgetRecipes(java.util.Collection)} 完成移除 + 重建 + 落盘。</p>
     *
     * <p>「已经不存在」不是错误：另一个玩家可能刚删过同一条。回执里逐条说明即可，
     * 不该整包失败 —— 那会让客户端把真正删掉的那几条也当成失败。</p>
     */
    public static DeleteOutcome delete(VoidWarehouseData data, List<String> recipeIds) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<RecipeDeleteResultPayload.Failure> failed = new ArrayList<>();
        if (recipeIds != null) {
            for (String id : recipeIds) {
                if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
                    failed.add(new RecipeDeleteResultPayload.Failure(
                            id == null ? "" : id, RecipeDeleteResultPayload.BAD_ID));
                    continue;
                }
                unique.add(id);
            }
        }

        List<String> present = new ArrayList<>(unique.size());
        for (String id : unique) {
            if (data.knowsRecipeId(id)) {
                present.add(id);
            } else {
                failed.add(new RecipeDeleteResultPayload.Failure(
                        id, RecipeDeleteResultPayload.NOT_FOUND));
            }
        }

        List<String> removed = present.isEmpty() ? List.of() : data.forgetRecipes(present);
        int status;
        if (removed.isEmpty()) {
            status = RecipeDeleteResultPayload.NOTHING;
        } else if (failed.isEmpty()) {
            status = RecipeDeleteResultPayload.OK;
        } else {
            status = RecipeDeleteResultPayload.PARTIAL;
        }
        return new DeleteOutcome(status, removed, failed);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
