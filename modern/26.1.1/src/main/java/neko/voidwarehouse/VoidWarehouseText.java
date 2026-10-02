package neko.voidwarehouse;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.math.BigInteger;
import java.util.Locale;

/**
 * 虚空仓库<b>所有面向玩家文字</b>的统一出处：配色语义 + 行构造 + 聊天提示。
 *
 * <h2>为什么要有这么一个类</h2>
 * <p>描述文字原先散落在 {@link VoidWarehouseData}（物品格）、{@link VoidWarehousePageContainer}
 * （潜影盒模式追加行）、{@link VoidWarehouseNav}（导航图标）、{@link VoidWarehouseMenu} 与
 * {@link VoidWarehouseWithdraw}（聊天提示）五处，各写各的颜色和措辞，结果：</p>
 * <ul>
 *   <li>同一种信息在不同地方颜色不同（同样是「说明」，有的黄、有的青、有的灰）；</li>
 *   <li>潜影盒模式在普通说明后面<b>追加</b>三行，越堆越长，还留着「左键：取出 1 个」
 *       这种在盒模式下<b>根本不成立</b>的旧说明；</li>
 *   <li>「左键单击切换」这种玩家最该看见的操作提示反而用了最暗的深灰。</li>
 * </ul>
 * <p>现在颜色只在这里定义一次，各处只说「这句话是哪种语义」，不再自己挑颜色。</p>
 *
 * <h2>配色语义（按信息的主次排序）</h2>
 * <ol>
 *   <li>{@link #VALUE} 金色 —— <b>核心数值</b>：数量、盒 / 组 / 个换算。
 *       玩家打开仓库第一眼找的就是「有多少」，给最抓眼的颜色；</li>
 *   <li>{@link #ACTION} 绿色 —— <b>能做的事</b>：左键 / 右键。绿色在 MC 里一贯表示
 *       「可执行、放行」，和「代价」形成对比；</li>
 *   <li>{@link #FACT} 青色 —— <b>客观事实</b>：容量、排序规则、当前模式。
 *       需要知道但不需行动的冷信息；</li>
 *   <li>{@link #COST} 红色 —— <b>代价与拒绝</b>：消耗的潜影壳 / 箱子、会被整次拒绝的情形。
 *       红色天然读作「要付出 / 有风险」，一眼能警示；</li>
 *   <li>{@link #NOTE} 深灰 —— <b>次要补充</b>：操作方式、脚注。存在但不抢视线。</li>
 * </ol>
 *
 * <h2>为什么不再用「≈」</h2>
 * <p>「X 盒 X 组 X 个」是<b>精确换算</b>而不是估算（口径与 {@link VoidWarehouseShulker}
 * 的装盒逻辑完全一致），加约等于号反而让人以为数字不准。</p>
 *
 * <h2>全局：不加粗、不倾斜</h2>
 * <p>玩家明确要求<b>所有提示文本都不要加粗和斜体</b>。这两个属性都得<b>显式摁成 false</b>
 * 才作数，光「不设置」是没用的 —— 原版会在渲染前再合并一次样式：</p>
 * <ul>
 *   <li><b>lore</b>：{@code ItemLore.LORE_STYLE = DARK_PURPLE + italic(true)}；</li>
 *   <li><b>物品名</b>：{@code CUSTOM_NAME_STYLE = italic(true)}。</li>
 * </ul>
 * <p>合并走 {@code ComponentUtils.mergeStyles} → {@code Style.applyTo}，
 * 规则是「<b>自己设过（非 null）的字段赢，没设的就沿用对方的</b>」。
 * 所以颜色只要我们自己设了就能保住，而斜体必须显式写 {@code false} 才不会被
 * 原版的 {@code true} 顶掉。所有造文本的方法都统一加了这一句。</p>
 */
public final class VoidWarehouseText {
    /** 核心数值：数量、盒 / 组 / 个换算 */
    static final ChatFormatting VALUE = ChatFormatting.GOLD;
    /** 可执行操作：左键 / 右键 */
    static final ChatFormatting ACTION = ChatFormatting.GREEN;
    /** 客观事实：容量、规则、当前模式 */
    static final ChatFormatting FACT = ChatFormatting.AQUA;
    /** 代价与拒绝：消耗的材料、会失败的情形 */
    static final ChatFormatting COST = ChatFormatting.RED;
    /** 次要补充：操作方式、脚注 */
    static final ChatFormatting NOTE = ChatFormatting.DARK_GRAY;

    /** 开关「开」时的名字颜色（与 {@link #OFF} 配对，一眼看出状态） */
    static final ChatFormatting ON = ChatFormatting.GREEN;
    /** 开关「关」时的名字颜色 */
    static final ChatFormatting OFF = ChatFormatting.GRAY;

    // ---- 物品格提示用的 RGB 色（原版 16 色枚举里没有这几个颜色，只能用十六进制）----
    // 玩家反馈「原来的配色不方便读」，改成「浅灰标签 + 亮金数值 + 青色操作」这一套：
    // 标签退到背景里、数值最抓眼、操作行整行统一，扫一眼就能分清哪行是数字、哪行是能做的事。

    /** 数量行的标签色：浅灰，退到背景里不抢视线（原版 16 色里 GRAY 偏暗，用 #AAAAAA） */
    static final int LABEL_RGB = 0xAAAAAA;
    /** 数量数值的亮金色：比 {@link #VALUE}（GOLD #FFAA00）更亮、更偏金，一眼能读出位数 */
    static final int VALUE_RGB = 0xFFD700;
    /** 操作行（左键 / 右键）的青色：整行同色，和「数量」行形成明确的上下分层 */
    static final int ACTION_RGB = 0x55FFFF;

    // ---- 聊天提示用的 RGB ----
    // 玩家反馈「金色正文太炸、斜体不好读」，改成三段式：前缀抓眼 → 正文退后 → 关键数字跳出。

    /** 聊天提示前缀：亮金，一眼认出「这是虚空仓库在跟我说话」 */
    static final int INFO_PREFIX_RGB = 0xFFD700;
    /** 聊天提示正文：浅灰。前缀抓眼、正文退后，读起来是「谁在说 → 说了什么」 */
    static final int INFO_BODY_RGB = 0xAAAAAA;
    /** 聊天提示里的关键数字：亮绿。整条消息里只有这类数字允许跳出来 */
    static final int INFO_KEY_RGB = 0x55FF55;

    /**
     * 聊天提示的<b>统一前缀</b>：{@code [虚空仓库] }。
     *
     * <p>玩家指定：<b>所有</b>聊天提示开头都写「[虚空仓库]」。这个名字本身就是
     * 「谁在说话」，方括号再把它和后面的正文隔开 —— 比泛泛的「[信息]」有用得多：
     * 玩家扫一眼就知道这条消息来自虚空仓库，而不是自己或别人的聊天。</p>
     *
     * <p>前缀只有这一个来源（{@link #info(MutableComponent)}），不再有「某个功能
     * 用另一套前缀」的分支 —— 各写各的前缀迟早会分叉。</p>
     */
    public static final String INFO_PREFIX = "[虚空仓库] ";

    private VoidWarehouseText() {
    }

    /**
     * 任意 RGB 造一行 lore（原版 16 色枚举装不下全部需求）。
     *
     * <p><b>必须显式 {@code withItalic(false)}</b>：原版 {@code ItemLore.LORE_STYLE} 是
     * {@code DARK_PURPLE + italic(true)}，经 {@code ComponentUtils.mergeStyles} 合并时
     * 「自己没设的字段就用它的」—— 我们只设了颜色，斜体就漏成了 {@code true}。
     * 想要正体只能自己摁死。</p>
     */
    static Component line(String text, int rgb) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(rgb).withItalic(false));
    }

    /**
     * 两段行：标签一种颜色、数值另一种颜色。
     *
     * <p>用来做「{@code 数量：} 灰 + {@code 1,234,567} 亮金」这种效果 ——
     * 一行里两个 Component 拼起来，各带各的颜色。分段比整行一个颜色好读：
     * 玩家找的是数字，标签只需要存在、不需要抢眼。</p>
     */
    static Component pair(String label, int labelRgb, String value, int valueRgb) {
        return Component.literal(label).withStyle(Style.EMPTY.withColor(labelRgb).withItalic(false))
                .append(Component.literal(value)
                        .withStyle(Style.EMPTY.withColor(valueRgb).withItalic(false)));
    }

    /** 按语义造一行 lore（同样去斜体，理由见 {@link #line(String, int)}） */
    static Component line(String text, ChatFormatting style) {
        return Component.literal(text).withStyle(style)
                .withStyle(Style.EMPTY.withItalic(false));
    }

    /**
     * 物品名 / 图标名：{@code on} 决定用「开」还是「关」的颜色（null = 中性青色）。
     *
     * <p>去斜体在这里尤其重要：这些文本会被设成 {@code CUSTOM_NAME} 组件，而原版
     * {@link net.minecraft.world.item.ItemStack#getStyledHoverName} 会拿
     * {@code CUSTOM_NAME_STYLE}（{@code italic(true)}）去合并 —— 不摁死就会显示斜体。</p>
     */
    static Component name(String text, Boolean on) {
        ChatFormatting style = on == null ? FACT : (on ? ON : OFF);
        return Component.literal(text).withStyle(style)
                .withStyle(Style.EMPTY.withItalic(false));
    }

    /**
     * 聊天栏提示：亮金色 {@code [虚空仓库] } 前缀 + 灰色正文。<b>不加粗、不倾斜</b>。
     *
     * <p>统一带前缀是为了让玩家一眼分清「这是虚空仓库在跟我说话」，
     * 而不是自己或别人的聊天消息。</p>
     *
     * <p>配色是「前缀抓眼 → 正文退后」：前缀亮金负责吸引注意，正文浅灰不喧宾夺主。
     * 正文里如果有关键数字（数量、上限…），用 {@link #body}/{@link #key} 拼好再传进来。</p>
     *
     * <p><b>为什么去掉加粗</b>：加粗会让整条消息糊成一坨，长句尤其难读；
     * 而且前缀本身已经靠颜色区分开了，不需要再加一层强调。</p>
     */
    public static Component info(MutableComponent body) {
        return Component.literal(INFO_PREFIX)
                .withStyle(Style.EMPTY
                        .withColor(INFO_PREFIX_RGB)
                        .withItalic(false)
                        .withBold(false))
                .append(body);
    }

    /** 正文里没有关键数字的纯文字提示（「仓库已满」「已取消」这类） */
    public static Component info(String body) {
        return info(body(body));
    }

    /** 提示正文（非关键信息）：浅灰、正体 */
    public static MutableComponent body(String text) {
        return Component.literal(text)
                .withStyle(Style.EMPTY.withColor(INFO_BODY_RGB).withItalic(false));
    }

    /**
     * 提示正文里夹一段<b>不能提前解析成字符串</b>的内容（目前只有物品名）。
     *
     * <p><b>为什么非要留成组件</b>：{@code ItemStack#getHoverName()} 拿到的是
     * 「可翻译组件」（{@code {"translate":"block.minecraft.dirt"}}），它<b>必须由客户端
     * 按自己的语言渲染</b>。一旦在服务端调 {@code getString()}，就会用<b>服务端的语言表</b>
     * 当场解析：</p>
     * <ul>
     *   <li>单人存档：客户端和服务端在同一个 JVM 里，客户端启动时把语言表
     *       「注入」进静态单例（{@code LanguageManager} → {@code Language.inject}），
     *       所以服务端代码也拿到中文 → 显示「泥土」，看起来一切正常；</li>
     *   <li><b>专用服务器</b>：没有客户端来注入，静态单例一直是启动时那份
     *       {@code Language.loadDefault()}（读 {@code /assets/minecraft/lang/en_us.json}）
     *       → 同一个 {@code getString()} 解析成「dirt」，玩家在中文客户端上也只能看到英文。</li>
     * </ul>
     * <p>所以这里只做「套上正文颜色」，内容原样保留为组件，让本地化发生在客户端。</p>
     *
     * <p>{@code copy()} 一定会返回可变的 {@link MutableComponent}（内容、子片段、样式都带过来），
     * 然后把样式<b>整个覆盖</b>成「正文灰 + 正体」—— 物品名自身可能带的样式（改过名的物品）
     * 在这条提示里不该冒出来，统一成正文的样子最好读。</p>
     */
    public static MutableComponent body(Component text) {
        return text.copy().setStyle(Style.EMPTY.withColor(INFO_BODY_RGB).withItalic(false));
    }

    /**
     * 提示里的关键数字：亮绿、正体。
     *
     * <p>整条消息里只有「玩家真正要读的那个数字」用这个 —— 比如取走的数量、
     * 数量上限。滥用就失去了「一眼定位关键值」的作用。</p>
     */
    public static MutableComponent key(String text) {
        return Component.literal(text)
                .withStyle(Style.EMPTY.withColor(INFO_KEY_RGB).withItalic(false));
    }

    /** 千分位：一盒上千个、库存上亿，不分隔符没法一眼读出位数 */
    public static String count(BigInteger amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** {@link #count(BigInteger)} 的 int 版（容量、堆叠上限这类小数字） */
    public static String count(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
