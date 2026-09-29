package neko.automending;

import carpet.api.settings.Rule;

import static carpet.api.settings.RuleCategory.SURVIVAL;

/**
 * Carpet 规则声明。
 * 字段名 autoMending 就是规则名，对应 /carpet autoMending true|false，
 * 翻译键为 carpet.rule.autoMending.name / .desc。
 */
public class AutoMendingSettings {
    /** 自定义分类名，会出现在 /carpet 的分类列表里 */
    public static final String NEKO = "neko";

    @Rule(categories = {NEKO, SURVIVAL})
    public static boolean autoMending = false;
}
