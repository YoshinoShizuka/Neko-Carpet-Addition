package neko.voidwarehouse;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * 漏斗上的「分类物过滤模式」开关 + <b>玩家亲手指定的分类物清单</b>
 * （实现在 {@code HopperBlockEntityMixin}）。
 *
 * <p>这是一个<b>普通接口</b>，不是 Mixin：Mixin 只能把实现写在目标类自己身上，
 * 而外部代码想读一个「加在 {@code HopperBlockEntity} 上的字段」时，
 * 唯一稳妥的办法就是让 Mixin 顺便给目标类加一个接口（duck interface），
 * 外部只认接口、不认那些只有运行时才存在的字段。</p>
 *
 * <h2>这个开关是什么</h2>
 * <p>它是<b>玩家手动放东西进漏斗</b>这个动作留下的痕迹：</p>
 * <ul>
 *   <li>玩家手动往漏斗里放物品 → 打开（<b>你放进去的这些就是分类物</b>）；</li>
 *   <li>玩家把漏斗掏空 → 自动关掉（分类物都没了，回到原规则）；</li>
 *   <li>它<b>随方块实体一起存进存档</b>（{@code HopperBlockEntity#saveAdditional}），
 *       所以服务器重启、区块卸载重载都不会把「我已经声明过分类物」这件事忘掉。</li>
 * </ul>
 *
 * <p><b>开关只对「口朝向仓库终端」的入库漏斗生效</b>（见
 * {@link VoidWarehouseHopper#isIntake}）。普通漏斗即使被标记了这个开关，
 * 行为也一个字都不会变 —— 这是「不影响原版漏斗属性」的硬要求。</p>
 *
 * <h2>为什么光有开关不够（实测事故）</h2>
 * <p>早先只有那个布尔，而「哪些物品算分类物」是靠<b>反推漏斗当前格子里有什么</b>
 * 得出来的。于是只要玩家动手之前漏斗里还残留着别的东西（比如上方容器流下来的石头），
 * 那件东西就被<b>一并认成分类物</b>：{@code drain} 给它留 {@code KEEP} 个，
 * 它从此永久占着一个格子，还摇身一变成了合法的分类物 ——
 * 玩家看到的就是「我只摆了橡木，漏斗却变成 {@code 橡木 石头 空 空 空}」。</p>
 * <p>所以分类物必须<b>有一份独立于漏斗内容的清单</b>：写清单的唯一途径是
 * {@code Slot#setByPlayer}（玩家的手），机器搬运永远写不进来。
 * 这正是需求里那句「漏斗里的填充物只能由玩家来指定」。</p>
 */
public interface VoidWarehouseHopperFilter {
    /** 这个漏斗有没有被玩家声明过分类物（只对入库漏斗有意义） */
    boolean hasClassifierFilter();

    /** 打开 / 关掉分类物过滤模式 */
    void setClassifierFilter(boolean enabled);

    /**
     * 玩家亲手在第 {@code slot} 格指定的分类物；这一格没指定过就返回 {@code EMPTY}。
     *
     * <p><b>只认玩家的手</b>：写这份清单的唯一入口是 {@code Slot#setByPlayer}，
     * 机器搬运（上方容器吸料、邻居漏斗推料、掉落物被吸入）一个字都写不进来。</p>
     */
    ItemStack getClassifierSeed(int slot);

    /**
     * 记下 / 抹掉玩家在第 {@code slot} 格指定的分类物（传空 = 抹掉）。
     *
     * <p>只存「种类」（数量归一），因为分类物回答的是「这是什么」，
     * 与格子里此刻有几个无关。</p>
     */
    void setClassifierSeed(int slot, ItemStack stack);

    /**
     * 把原版搬运冷却（{@code cooldownTime}）清零。
     *
     * <p>原版漏斗「每 8 gt 才动一次」的节流全靠这个字段：{@code tryMoveItems} 开头检查
     * {@code isOnCooldown()}（>0 就直接出队），结尾只要搬成了就 {@code setCooldown(8)}。
     * 入库漏斗要无速率上限，就必须在<b>整条 tick 走完之后</b>把它清掉。</p>
     *
     * <p><b>位置很关键</b>：早先在 {@code suckInItems} 中途清零是<b>无效的</b> ——
     * 紧接着 {@code tryMoveItems} 收尾就把 8 写回去了。现在改到
     * {@code pushItemsTick} 的 TAIL 才真的解掉节流。</p>
     */
    void resetCooldown();
}
