package neko.voidwarehouse;

/**
 * 漏斗上的「分类物过滤模式」开关（由 {@code HopperBlockEntityMixin} 实现）。
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
 */
public interface VoidWarehouseHopperFilter {
    /** 这个漏斗有没有被玩家声明过分类物（只对入库漏斗有意义） */
    boolean hasClassifierFilter();

    /** 打开 / 关掉分类物过滤模式 */
    void setClassifierFilter(boolean enabled);
}
