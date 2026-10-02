package neko.cooking;

import carpet.api.settings.Rule;

import neko.voidwarehouse.VoidWarehouseSettings;

import static carpet.api.settings.RuleCategory.SURVIVAL;

/**
 * 熔炉玩法规则的声明。
 *
 * <p>字段名 {@code concretePowderToGlass} 就是规则名，对应
 * {@code /carpet concretePowderToGlass true|false}，
 * 翻译键为 {@code carpet.rule.concretePowderToGlass.name} / {@code .desc}。</p>
 *
 * <h2>这条规则是「移植」来的，但<b>故意不叫原名</b></h2>
 * <p>同一件事在 {@code Carpet DDS Addition} 里叫 {@code smeltConcretePowderToGlass}，
 * 而 Carpet 的 {@code SettingsManager#addCarpetRule} 在遇到重名时是</p>
 * <pre>if (rules.containsKey(rule.name())) throw new UnsupportedOperationException(...)</pre>
 * <p>—— <b>直接抛异常、没有商量余地</b>（javap 实证）。所以只要哪天玩家在同一个版本里
 * 同时装上两个模组，用同名就意味着<b>服务端起不来</b>。改成一个不同的名字之后，
 * 两个模组即使在同一个实例里共存，也各自是一条独立的规则，最坏情况是「两条开关做同一件事」，
 * 不会崩。</p>
 *
 * <p>顺带一提：玩家的存档里那条 {@code smeltConcretePowderToGlass true} 早就随着
 * DDS 从 26.3 移除而被 Carpet 从 {@code carpet.conf} 里删掉了，所以<b>沿用原名也捞不回旧配置</b>
 * —— 改名在这件事上没有代价。</p>
 *
 * <h2>默认值</h2>
 * <p><b>默认关</b>，与本模组其它规则一致（见 {@link VoidWarehouseSettings}）：
 * 装上就悄悄多出 16 条熔炉配方属于「改变原版行为」，必须由服主显式开启。</p>
 */
public class CookingSettings {

    /**
     * 允许在普通熔炉里把 16 种混凝土粉末烧成对应颜色的染色玻璃。
     *
     * <p>只影响<b>普通熔炉</b>：高炉走的是 {@code RecipeType.BLASTING}、烟熏炉走
     * {@code SMOKING}，两者都不在覆盖范围内，与原版「混凝土粉末不可烧」保持一致。</p>
     */
    @Rule(categories = {VoidWarehouseSettings.NEKO, SURVIVAL})
    public static boolean concretePowderToGlass = false;
}
