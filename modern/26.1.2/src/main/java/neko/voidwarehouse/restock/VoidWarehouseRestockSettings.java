package neko.voidwarehouse.restock;

import carpet.api.settings.Rule;

import neko.voidwarehouse.VoidWarehouseSettings;

import static carpet.api.settings.RuleCategory.SURVIVAL;

/**
 * 打印机补货的 Carpet 规则。
 *
 * <p>字段名 {@code voidWarehouseRestock} 就是规则名，对应
 * {@code /carpet voidWarehouseRestock true|false}，
 * 翻译键为 {@code carpet.rule.voidWarehouseRestock.name} / {@code .desc}。</p>
 *
 * <h2>为什么单独一个规则类，不塞进 {@link VoidWarehouseSettings}</h2>
 * <p>{@code VoidWarehouseSettings} 在 {@code common} 里 —— 那里的一行代码会出现在
 * <b>所有</b> MC 版本的产物中。而这套补货设计跑在 Fabric networking 上，
 * 只在能加载 Fabric API 的版本里才有意义（当前是 26.x 与 1.21.11 五个版本）。
 * 放在 {@code restock} 包里就能保证「有这个包才有这条规则」，
 * 将来要支持更老的版本时，不复制这个包即可，不会留下一条点了没反应的假开关。</p>
 *
 * <h2>与客户端开关的关系</h2>
 * <p>需求定的是<b>双开关</b>：这里是<b>总闸</b>，关了之后服务端一律回 {@code RULE_OFF}，
 * 客户端再怎么勾也没用；loretica-printer 自己的配置界面还有一个开关，
 * 那是给单个玩家用的（比如同一台服务器里只有某个人在用仓库）。</p>
 *
 * <h2>默认值的取舍</h2>
 * <p><b>默认关</b>。理由：开着的规则意味着<b>任何装了对应打印机客户端的玩家都能从全服共享的
 * 仓库里领取物品</b>——这是服务器主需要主动知情的授权，不该由模组替它默认打开。
 * 要用就 {@code /carpet voidWarehouseRestock true}，并接受上面那条代价
 * （该代价也写在 {@code .desc} 里，{@code /carpet} 列表时能看到）。</p>
 */
public class VoidWarehouseRestockSettings {

    /**
     * 打开后才能响应 litematica-printer 的取货请求。
     *
     * <p>注意它<b>依赖</b> {@link VoidWarehouseSettings#voidWarehouse}：主开关关了的话，
     * 仓库 UI 会强制关闭，这里也一并视为关闭（回 {@code RULE_OFF}），
     * 免得出现「规则看着是开的、实际取不出东西」的莫名其妙状态。</p>
     */
    @Rule(categories = {VoidWarehouseSettings.NEKO, SURVIVAL})
    public static boolean voidWarehouseRestock = false;
}
