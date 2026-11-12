package neko.voidwarehouse;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配方本的三种聊天栏提示：<b>记住了 / 快满了 / 已满</b>。
 *
 * <h2>为什么要单独一个类</h2>
 * <p>提示是「怎么用这个结果」，不是「结果本身」。数据层（{@link VoidWarehouseData}）的
 * 定位是纯状态，一旦它自己 {@code sendSystemMessage}，就绑死了「必须有真玩家」这个前提 ——
 * 自检没法跑、打印机那种无人路径也没法复用。所以 {@link VoidWarehouseData#learnWithResult}
 * 只回答「记住了没有、为什么没记住」，发不发消息由这里决定、由调用方发。
 * 这个分法与 {@code PrinterRestockNetwork} 那边已经定下的规矩是同一条。</p>
 *
 * <h2>这里只管「说清楚」，不管「发给谁」</h2>
 * <p>{@link #onCrafted} 返回<b>要发出去的那几行</b>，一行都没轮到时返回空列表。
 * 调用方拿到之后逐行 {@code sendSystemMessage}。这样自检可以只断言「返回了什么」，
 * 不需要去拦 {@code ServerPlayer} 的发送方法，也不需要造一个真的连接。</p>
 *
 * <h2>物品名为什么全部走 {@code getHoverName()}</h2>
 * <p>{@code ItemStack#getHoverName()} 拿到的是「可翻译组件」，必须由<b>客户端</b>按自己的
 * 语言渲染。在服务端 {@code getString()} 会拿服务端语言表现场解析 —— 单人存档里恰好正常
 * （客户端把语言表注入进同一个 JVM），<b>专用服务器上则永远是英文</b>。这个坑
 * {@code PrinterRestockNetwork#notifyTaken} 已经踩过并写了整段注释，这里不再重复踩。</p>
 */
public final class VoidWarehouseRecipeNotice {

    private VoidWarehouseRecipeNotice() {
    }

    /**
     * 一次提示最多列几条「同产物的其它配方」，其余折算成「另有 N 条」。
     *
     * <p>木棍这类配方两侧各能堆到十几条（每种木板一条），全列出来会把聊天栏刷满，
     * 而玩家真正需要知道的只是「这个产物还有别的走法」，不是每一条走法的全文。</p>
     */
    public static final int LIST_LIMIT = 5;

    /** 满本提示的最小间隔（tick）：30 秒。边沿那一次不受它限制 */
    public static final int FULL_REPEAT_TICKS = 600;

    /**
     * 用量达到上限的百分之多少时预警一次。
     *
     * <p>放在这里而不是 {@code VoidWarehouseRecipeBook}：这是<b>展示策略</b>，
     * 改它不该牵动配方本本身的落盘与容量语义。上限本身仍然从
     * {@link VoidWarehouseRecipeBook#MAX_RECIPES} 读，不在这里抄第二份。</p>
     */
    public static final int NEAR_FULL_PERCENT = 90;

    /** 最多跟踪多少个玩家的节流状态；超了按 LRU 淘汰，代价只是可能多提示一次 */
    private static final int MAX_TRACKED = 512;

    /**
     * 每玩家的节流状态。
     *
     * <p>用 {@link LinkedHashMap} 的 LRU 而不是 {@code WeakHashMap}：键是 {@code UUID}
     * 这种不可变值，弱引用在「{@code getUUID()} 每次返回新对象」的写法下会把状态随手丢掉，
     * 节流就形同不存在。显式封顶更可靠 —— 丢状态的代价只是重新提示一次。</p>
     */
    private static final Map<UUID, State> STATE =
            new LinkedHashMap<UUID, State>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, State> eldest) {
                    return size() > MAX_TRACKED;
                }
            };

    private static final class State {
        /** 已经就「满本」提示过一次（边沿标记） */
        boolean fullNotified;
        /** 已经就「快满」提示过一次 */
        boolean nearFullNotified;
        /** 上次满本提示的 tick；初值让「第一次一定放行」 */
        long lastFullTick = -FULL_REPEAT_TICKS;
    }

    /**
     * 一次合成之后调，返回要发出去的消息（空列表 = 什么都不发）。
     *
     * <p><b>为什么用量是入参而不是直接从 {@code data} 里读</b>：自检要验「1843 条不吭声 /
     * 1844 条预警 / 满了边沿说一次、之后 30 秒一次」，真把配方本填到 2048 条要跑几十秒
     * 而且每条都触发一次全量重建。把 {@code used} / {@code max} 拆出来之后，这套边界
     * 判断就是一个纯函数，造数字零成本。产品侧照旧从
     * {@link VoidWarehouseData#recipeBookUsed()} 与
     * {@link VoidWarehouseRecipeBook#MAX_RECIPES} 取，口径不变。</p>
     *
     * @param who           收信玩家；只当节流键用，不持有玩家对象
     * @param en            该玩家是不是英文客户端
     * @param result        {@link VoidWarehouseData#learnWithResult} 的结局
     * @param recipe        刚做出来的那条配方
     * @param othersBefore  {@code learn} <b>之前</b>取的同产物配方，见
     *                      {@link VoidWarehouseData#recipesFor}；传空列表也可以
     * @param used          learn 之后配方本里有多少条
     * @param max           上限（{@link VoidWarehouseRecipeBook#MAX_RECIPES}）
     * @param nowTick       当前 tick（由调用方给，便于自检造时间）
     */
    public static List<Component> onCrafted(UUID who, boolean en,
                                            VoidWarehouseData.LearnResult result,
                                            VoidWarehouseRecipes.Recipe recipe,
                                            List<VoidWarehouseRecipes.Recipe> othersBefore,
                                            int used, int max, long nowTick) {
        if (who == null || result == null) {
            return List.of();
        }
        // 这两种结局一个字都不该说：重复合成是常态（同一件东西合十次要说十次？），
        // 规则没开更是完全不该打扰 —— 玩家压根没打算用这个功能。
        if (result == VoidWarehouseData.LearnResult.DUPLICATE
                || result == VoidWarehouseData.LearnResult.DISABLED) {
            return List.of();
        }
        State state = STATE.computeIfAbsent(who, key -> new State());
        boolean full = used >= max;

        // 先按当前状态复位边沿标记。配方本被清空、或用量掉回阈值以下之后，
        // 下一次再撞上同一个坎应该重新提示一次；不复位的话那个标记会一辈子卡住。
        if (!full) {
            state.fullNotified = false;
        }
        if (!isNearFull(used, max)) {
            state.nearFullNotified = false;
        }

        List<Component> out = new ArrayList<>(3);
        if (result == VoidWarehouseData.LearnResult.LEARNED) {
            out.add(learnedLine(en, recipe));
            Component others = othersLine(en, othersBefore);
            if (others != null) {
                out.add(others);
            }
            // 快满只在「刚刚又记住一条」时说：这时候用量才真的往上动了一格。
            if (isNearFull(used, max) && !state.nearFullNotified) {
                state.nearFullNotified = true;
                out.addAll(nearFullLines(en, used, max));
            }
        } else if (result == VoidWarehouseData.LearnResult.FULL) {
            // 边沿必说一次；之后每 30 秒最多再说一次。满本之后玩家每合成一次都会走到这里，
            // 不节流就是每合一次刷两行。
            boolean edge = !state.fullNotified;
            boolean repeatDue = nowTick - state.lastFullTick >= FULL_REPEAT_TICKS;
            if (edge || repeatDue) {
                state.fullNotified = true;
                state.lastFullTick = nowTick;
                out.addAll(fullLines(en, used, max));
            }
        }
        return out;
    }

    /** 用量是否已进入「快满」区间（边界含等于，见 {@link #NEAR_FULL_PERCENT}） */
    public static boolean isNearFull(int used, int max) {
        return used > 0 && max > 0 && (long) used * 100L >= (long) max * NEAR_FULL_PERCENT;
    }

    // ------------------------------------------------------------------ 文案

    /** 「已记住配方：2 桦木木板 → 4 木棍」 */
    private static Component learnedLine(boolean en, VoidWarehouseRecipes.Recipe recipe) {
        MutableComponent body = VoidWarehouseText.body(en ? "Learned recipe: " : "已记住配方：");
        body.append(describe(recipe, en));
        return VoidWarehouseText.info(en, body);
    }

    /**
     * 「└ 木棍还能这样合：2 橡木木板 → 4 木棍；…（另有 3 条）」；没有别的配方时返回 {@code null}。
     *
     * <p>不加 {@code [虚空仓库]} 前缀：它是上一条的补充，不是独立消息。用 {@code └}
     * 做视觉缩进，一眼能看出属于谁。</p>
     */
    private static Component othersLine(boolean en, List<VoidWarehouseRecipes.Recipe> others) {
        if (others == null || others.isEmpty()) {
            return null;
        }
        List<VoidWarehouseRecipes.Recipe> shown = others.size() > LIST_LIMIT
                ? others.subList(0, LIST_LIMIT)
                : others;
        MutableComponent out = VoidWarehouseText.body("└ ");
        out.append(VoidWarehouseText.body(en ? "also craftable: " : "还能这样合："));
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                out.append(VoidWarehouseText.body(en ? "; " : "；"));
            }
            out.append(describe(shown.get(i), en));
        }
        int rest = others.size() - shown.size();
        if (rest > 0) {
            out.append(VoidWarehouseText.body(en ? " (" : "（"));
            out.append(VoidWarehouseText.body(en ? rest + " more" : "另有 " + rest + " 条"));
            out.append(VoidWarehouseText.body(en ? ")" : "）"));
        }
        return out;
    }

    /** 满本：两行 */
    private static List<Component> fullLines(boolean en, int used, int max) {
        MutableComponent head = VoidWarehouseText.body(en
                        ? "Recipe book is full ("
                        : "配方本已满（")
                .append(VoidWarehouseText.key(VoidWarehouseText.count(used)
                        + " / " + VoidWarehouseText.count(max)))
                .append(VoidWarehouseText.body(en
                        ? "), this recipe was not recorded."
                        : " 条），这条配方没记住。"));
        Component tail = VoidWarehouseText.body(en
                ? "└ Recorded recipes still work; free up space before new ones can be learned."
                : "└ 已记的配方仍能正常扣料；要记新的得先腾出空位。");
        return List.<Component>of(VoidWarehouseText.info(en, head), tail);
    }

    /** 快满：两行 */
    private static List<Component> nearFullLines(boolean en, int used, int max) {
        MutableComponent head = VoidWarehouseText.body(en
                        ? "Recipe book almost full: "
                        : "配方本快满了：")
                .append(VoidWarehouseText.key(VoidWarehouseText.count(used)
                        + " / " + VoidWarehouseText.count(max)))
                .append(VoidWarehouseText.body(en ? " used." : " 条已用。"));
        Component tail = VoidWarehouseText.body(en
                ? "└ Once it is full, new recipes will no longer be recorded."
                : "└ 满了之后，新配方不会再被记住。");
        return List.<Component>of(VoidWarehouseText.info(en, head), tail);
    }

    // ------------------------------------------------------------------ 一条配方 → 人话

    /**
     * 「2 橡木木板 → 4 木棍」。
     *
     * <p>产物数量一定要写：同产物的不同配方 {@code outputCount} 可能不一样（同一种木板
     * 走不同形状能出 4 个也能出 6 个），只写原料会让人误以为产物一样。
     * 原料数量同理 —— 「2 木板」和「3 木板」是两条不同的路。</p>
     */
    public static MutableComponent describe(VoidWarehouseRecipes.Recipe recipe, boolean en) {
        MutableComponent line = Component.empty();
        List<VoidWarehouseRecipes.Part> parts = recipe.parts();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                line.append(VoidWarehouseText.body(" + "));
            }
            line.append(partText(parts.get(i), en));
        }
        line.append(VoidWarehouseText.body(" → "));
        line.append(VoidWarehouseText.key(VoidWarehouseText.count(recipe.outputCount())));
        line.append(VoidWarehouseText.body(" "));
        line.append(nameOf(recipe.output()));
        return line;
    }

    private static MutableComponent partText(VoidWarehouseRecipes.Part part, boolean en) {
        MutableComponent out = VoidWarehouseText.key(VoidWarehouseText.count(part.count()));
        out.append(VoidWarehouseText.body(" "));
        List<String> candidates = part.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                // 从合成格记下来的配方恒为单候选（见 VoidWarehouseRecipeBook#fromGrid），
                // 但模型支持多候选，这里不假设只有一种。
                out.append(VoidWarehouseText.body(en ? " or " : " 或 "));
            }
            out.append(nameOf(candidates.get(i)));
        }
        return out;
    }

    /**
     * 物品 id → 可翻译组件。
     *
     * <p>解析不出来（这个版本没有这件物品，比如语料是按 26.3 导出、跑在 26.1.2 上）时
     * <b>退回裸 id</b>：宁可显示得难看，也不能让整条提示消失 —— 提示是说给玩家听的，
     * 缺一件物品的名字不该把它整条吞掉。</p>
     */
    private static Component nameOf(String itemId) {
        Item item = VoidWarehouseRecipeBook.itemOf(itemId);
        if (item == null || item == Items.AIR) {
            return VoidWarehouseText.body(itemId);
        }
        return VoidWarehouseText.body(new ItemStack(item).getHoverName());
    }
}
