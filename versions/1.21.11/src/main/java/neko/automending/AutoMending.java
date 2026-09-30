package neko.automending;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Optional;

/**
 * 自动经验修补的核心逻辑。
 *
 * <p>思路和原版 {@code ExperienceOrb#repairPlayerItems} 一致——都是「找到一件带 REPAIR_WITH_XP
 * 效果且已受损的物品 → 换算成要修的耐久 → 扣经验」这一套，唯一的区别是经验来源：
 * 原版来自经验球实体，这里改成玩家自己已有的经验值。</p>
 *
 * <h2>口径：一次触发就把一件修满；进度条不够时可以向下动用等级</h2>
 * <p>原版 Carpet-GUGU-Addition 的 {@code autoMending_new}（以及本项目 1.0.2）的行为都是
 * 「每秒把一件修满」，玩家感受到的速度是恒定的。它之所以能一直满速，是因为
 * {@code giveExperiencePoints(-actual)} 在经验条不够时会<b>扣穿到等级里</b>：等级 -1 的同时
 * 进度条被弹回 ~98%，于是下一轮又有一整条经验可用。</p>
 *
 * <p>1.0.3 一度把预算严格限制成「进度条里现有的经验」。结果是进度条只减不增——
 * 一旦 {@code 2 × 进度条存量 < 物品损坏值}，一次就修不满了；再往后预算越用越少，
 * 直到 {@code budget <= 0} 彻底停机。玩家看到的就是「修补越来越慢 / 忽然不修了」。</p>
 *
 * <p>现在改成：</p>
 * <ul>
 *   <li><b>预算</b> = 进度条存量，必要时再借「至多一级」的等级经验，且只借到
 *       「刚好能修满这一件」为止 —— 既不被进度条存量卡住，也不平白多花；</li>
 *   <li><b>支付</b> 走 {@link #spendExact}：先进度条、不够再逐级下降，
 *       每一级都写成精确的整数刻度。</li>
 * </ul>
 *
 * <h2>为什么支付不用 {@code giveExperiencePoints(-spent)}</h2>
 * <p>它的收尾是一段 float32 的「负值回弹」循环（javap -c 实证）：</p>
 * <pre>
 *   experienceProgress += amount / getXpNeededForNextLevel();
 *   while (experienceProgress &lt; 0.0F) {
 *       float f = experienceProgress * getXpNeededForNextLevel();
 *       if (experienceLevel &gt; 0) { giveExperienceLevels(-1);
 *                                   experienceProgress = 1.0F + f / getXpNeededForNextLevel(); }
 *       else                     { giveExperienceLevels(-1); experienceProgress = 0.0F; }
 *   }
 * </pre>
 * <p>两个问题：① 进度条被弹到 {@code 1 + 负值} ≈ 98%，视觉上就是「经验条突然满了」；
 * ② 它是 float32 的减法，每轮都把进度推离 {@code k/needed} 网格一点，累积漂移之后迟早在
 * 「差一点点到整数」的 ulp 邻域上扣穿。这里改成「先把要花多少算清楚，再用带 clamp 的
 * {@code setExperiencePoints} 写整数刻度」，既没有回弹也没有漂移。</p>
 *
 * <h2>26.3 / 1.21.11 上已 javap 确认的原版 API</h2>
 * <ul>
 *   <li>{@code EnchantmentHelper.getRandomItemWith(DataComponentType, LivingEntity, Predicate)}
 *       —— 只遍历 {@code EquipmentSlot.VALUES}（装备槽），<b>不扫背包</b></li>
 *   <li>{@code EnchantmentHelper.modifyDurabilityToRepairFromXp(ServerLevel, ItemStack, int)}</li>
 *   <li>{@code EnchantmentEffectComponents.REPAIR_WITH_XP}；mending 的
 *       {@code repair_with_xp} = {@code multiply 2.0}（1 经验 = 2 耐久）</li>
 *   <li>{@code ServerPlayer#setExperiencePoints(int)} —— 内部 {@code Mth.clamp} + 置
 *       {@code lastSentExp = -1}</li>
 *   <li>{@code ServerPlayer#giveExperienceLevels(int)} —— {@code levels == 0} 直接 return，
 *       负数生效；{@code Player} 那层用 {@code IntMath.saturatedAdd}，只在 level &lt; 0 时清零</li>
 * </ul>
 */
public final class AutoMending {
    private AutoMending() {
    }

    /**
     * 单次触发最多向下动用多少级经验。
     *
     * <p>修满一件物品最多也就需要 {@code 最大耐久 / 2} 点经验（原版最高耐久 2031 ⇒ 约 1016 点），
     * 对 30 级以上（每级 ≥ 139 点）连 8 级都用不到。这个上限只是给「低等级 + 满损物品」
     * 这种极端组合兜底，防止一次把等级吃空。</p>
     */
    private static final int MAX_LEVELS_DRAWN = 8;

    /**
     * 尝试为玩家自动进行一次经验修补。没有可修补的物品、或一点经验都拿不出来时什么都不做。
     *
     * @param player 目标玩家（只在服务端调用）
     */
    public static void mending(ServerPlayer player) {
        // 1) 先挑物品 —— 没有可修的东西就什么都不做，省掉后面的算术
        Optional<EnchantedItemInUse> optional = EnchantmentHelper.getRandomItemWith(
                EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (optional.isEmpty()) {
            return;
        }
        ItemStack stack = optional.get().itemStack();
        int damage = stack.getDamageValue();
        if (damage <= 0) {
            return;
        }

        // 2) 进度条里已经攒下的经验（整数刻度）。
        //
        //    ★ 必须用 double 算，绝不能写成 (float) progress * (float) needed。
        //      experienceProgress 是 float32（24 位有效数字），而玩家经验是一点一点加出来的，
        //      所以它几乎总是 k/needed 的形态 —— progress*needed 恰好贴着整数。
        //      float32 乘法会把「差一点点到整数」的乘积直接舍入到那个整数，预算就多出 1 点。
        int needed = player.getXpNeededForNextLevel();
        int bar = (int) Math.floor((double) player.experienceProgress * (double) needed);

        // 进度条空、又一点等级都没有 —— 拿不出经验，安静退出
        if (bar <= 0 && player.experienceLevel <= 0) {
            return;
        }

        // 3) 预算 = max(进度条存量, 刚好修满这一件所需的经验)。
        //    取 max 是为了「一次就把这件修满」——这正是旧版（以及 GUGU 原版）的手感；
        //    上限只到「刚好修满」为止（修满需要 ceil(damage / 2) 点，1 经验 = 2 耐久），
        //    所以不会平白多花。进度条不够的部分，由第 6 步向下动用等级来付。
        int needXp = (damage + 1) / 2;
        int budget = Math.max(bar, needXp);
        if (budget <= 0) {
            return;
        }

        // 4) 原版换算：budget 点经验能修多少耐久（经验修补 I 是 1 经验 = 2 耐久）
        int repair = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), stack, budget);
        if (repair <= 0) {
            // 换算不出耐久（理论上不会发生）—— 绝不能拿它当除数
            return;
        }
        int actual = Math.min(repair, damage);
        if (actual <= 0) {
            return;
        }

        // 5) 实际消耗 = 这个线性变换的反函数，与原版 ExperienceOrb#repairPlayerItems 完全一致：
        //      损坏值 ≤ 预算能修的量时，actual = damage ⇒ spent = damage / 2；
        //      预算不够修满时，actual = repair ⇒ spent = budget（把预算用光，不留尾巴）。
        int spent = (int) ((long) actual * budget / repair);
        if (spent <= 0) {
            // 只掉了 1 点耐久时 damage/2 向下取整会得到 0 —— 那会让整轮白白空转、什么都修不了。
            // 既然真的修了东西，就至少扣 1 点。
            spent = 1;
        }

        stack.setDamageValue(damage - actual);

        // 6) 支付。经验不够付清时（等级见底），按实付额度重新折算该修多少耐久，
        //    把多修出来的部分还回去 —— 账目必须守恒，绝不能出现「修了却不扣经验」。
        int paid = spendExact(player, bar, spent);
        if (paid < spent) {
            int affordable = (int) ((long) paid * repair / budget);
            if (affordable < actual) {
                stack.setDamageValue(damage - Math.max(0, affordable));
            }
        }
        // setExperiencePoints / giveExperienceLevels 只管进度条与等级，不动总经验，这里手动补齐
        player.totalExperience = Math.max(0, player.totalExperience - paid);
    }

    /**
     * 从玩家身上精确扣掉 {@code spent} 点经验：先用进度条里的存量，不够再逐级下降。
     *
     * <p>每一级都通过带 clamp 的 {@link ServerPlayer#setExperiencePoints(int)} 写成精确的整数
     * 刻度，所以 {@code experienceProgress} 永远不会变成负数，也就进不去原版那段「负值回弹」，
     * 进度条更不会突然跳到 98%。</p>
     *
     * @param bar   调用方算好的「进度条存量（整数经验）」，必须是最新值
     * @param spent 想扣掉的点数，必须 &gt; 0
     * @return 实际扣到的点数（≤ spent；等级见底时会更少）
     */
    private static int spendExact(ServerPlayer player, int bar, int spent) {
        if (spent <= bar) {
            player.setExperiencePoints(bar - spent);
            return spent;
        }
        // 进度条全花光，还差 remain 点要从等级里出
        int remain = spent - bar;
        int paid = bar;
        player.setExperiencePoints(0);
        int levels = 0;
        while (remain > 0 && player.experienceLevel > 0 && levels < MAX_LEVELS_DRAWN) {
            player.giveExperienceLevels(-1);
            levels++;
            // 掉一级之后，「攒满」这一级所需的经验（getXpNeededForNextLevel 就是按当前等级算的）
            int n = player.getXpNeededForNextLevel();
            if (remain >= n) {
                // 这一级整条都被吃掉
                remain -= n;
                paid += n;
                player.setExperiencePoints(0);
            } else {
                // 只吃掉一部分，其余留在进度条上（精确到整数刻度）
                player.setExperiencePoints(n - remain);
                paid += remain;
                remain = 0;
            }
        }
        return paid;
    }
}
