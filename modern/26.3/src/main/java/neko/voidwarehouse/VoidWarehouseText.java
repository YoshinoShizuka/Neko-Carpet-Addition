package neko.voidwarehouse;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

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
 */
final class VoidWarehouseText {
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

    private VoidWarehouseText() {
    }

    /** 按语义造一行 lore */
    static Component line(String text, ChatFormatting style) {
        return Component.literal(text).withStyle(style);
    }

    /** 物品名 / 图标名：{@code on} 决定用「开」还是「关」的颜色（null = 中性青色） */
    static Component name(String text, Boolean on) {
        ChatFormatting style = on == null ? FACT : (on ? ON : OFF);
        return Component.literal(text).withStyle(style);
    }

    /**
     * 聊天栏提示：{@code [信息] } 前缀（加粗金色）+ 金色正文。
     *
     * <p>统一带前缀是为了让玩家一眼分清「这是虚空仓库在跟我说话」，
     * 而不是自己或别人的聊天消息。</p>
     */
    static Component info(String body) {
        return Component.literal("[信息] ")
                .withStyle(ChatFormatting.GOLD)
                .withStyle(ChatFormatting.BOLD)
                .append(Component.literal(body).withStyle(ChatFormatting.GOLD));
    }

    /** 千分位：一盒上千个、库存上亿，不分隔符没法一眼读出位数 */
    static String count(BigInteger amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** {@link #count(BigInteger)} 的 int 版（容量、堆叠上限这类小数字） */
    static String count(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }
}
