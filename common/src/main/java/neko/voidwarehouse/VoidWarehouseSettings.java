package neko.voidwarehouse;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.SURVIVAL;

/**
 * 虚空仓库的 Carpet 规则声明。
 *
 * <p>字段名 {@code voidWarehouse} 就是规则名，对应 {@code /carpet voidWarehouse true|false}，
 * 翻译键为 {@code carpet.rule.voidWarehouse.name} / {@code .desc}。</p>
 *
 * <p><b>默认关</b>：本模组的全部规则都默认 {@code false}，由服主显式开启，
 * 避免装上就悄悄改变原版行为。op-only 限制由其它模组负责，本扩展不做额外权限判定。</p>
 */
public class VoidWarehouseSettings {
    /** 自定义分类名，与 AutoMending 共用同一个分类，会出现在 /carpet 的分类列表里 */
    public static final String NEKO = "neko";

    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean voidWarehouse = false;
}
