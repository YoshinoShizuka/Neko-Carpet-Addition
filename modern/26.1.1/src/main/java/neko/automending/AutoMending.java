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
 * 原版来自经验球实体，这里改成玩家自己身上已有的经验值。</p>
 *
 * <h2>不变量：这个方法绝不能让 {@code experienceProgress} 变成负数</h2>
 * <p>26.3 的 {@code Player#giveExperiencePoints}（javap -c 实证）在扣经验时是这样收尾的：</p>
 * <pre>
 *   experienceProgress += amount / getXpNeededForNextLevel();
 *   while (experienceProgress &lt; 0.0F) {          // ← 负值"回弹"循环
 *       float f = experienceProgress * getXpNeededForNextLevel();
 *       if (experienceLevel &gt; 0) { giveExperienceLevels(-1);
 *                                   experienceProgress = 1.0F + f / getXpNeededForNextLevel(); }
 *       else                     { giveExperienceLevels(-1); experienceProgress = 0.0F; }
 *   }
 * </pre>
 * <p>也就是说：<b>只要一次性扣掉的经验超过进度条里的存量，等级就会 -1，而进度条会被直接
 * 弹到 {@code 1 + 负值} ≈ 98%</b>。本来这不算错——附魔台花经验也是这个表现——
 * 但自动修补是<b>每 20 tick 无条件跑一次</b>的：鞘翅滑行时耐久持续掉，于是每秒都会触发一次
 * 「扣穿 → 降级 → 进度条弹回 98%」，玩家看到的就是<b>只看得见等级在变、进度条完全反映不出
 * 真实进度</b>的锯齿。</p>
 *
 * <p>结论：<b>预算必须限定为「进度条里现有的经验」，且实际消耗的经验不能超过这个预算。</b>
 * 这样 {@code experienceProgress - spent / needed ≥ (progress*needed - floor(progress*needed)) / needed ≥ 0}
 * 恒成立，负值分支永远进不去，等级也就不会被这个方法改动。</p>
 *
 * <h2>为什么"实际消耗"要按比例反算</h2>
 * <p>{@code modifyDurabilityToRepairFromXp} 是<b>线性</b>变换（mending 的
 * {@code repair_with_xp} 效果 = {@code multiply 2.0}，即 1 经验 → 2 耐久）。原版
 * {@code ExperienceOrb#repairPlayerItems} 在算「用掉了多少经验」时正是用的这个变换的反函数
 * （javap -c 实证：{@code remainder = amount - actual * amount / repair}）。
 * 直接拿修好的耐久点数当经验扣，会扣掉 <b>整整 2 倍</b>——这是本方法此前的真实缺陷。</p>
 *
 * <p>26.3 上用到的这几个原版 API 都已确认存在：</p>
 * <ul>
 *   <li>{@code EnchantmentHelper.getRandomItemWith(DataComponentType, LivingEntity, Predicate)}</li>
 *   <li>{@code EnchantmentHelper.modifyDurabilityToRepairFromXp(ServerLevel, ItemStack, int)}</li>
 *   <li>{@code EnchantmentEffectComponents.REPAIR_WITH_XP}</li>
 * </ul>
 */
public final class AutoMending {
    private AutoMending() {
    }

    /**
     * 尝试为玩家自动进行一次经验修补。没有可修补的物品、或没有经验可花时什么都不做。
     *
     * @param player 目标玩家（只在服务端调用）
     */
    public static void mending(ServerPlayer player) {
        // 1) 预算 = 「当前这一个等级内已经攒下的经验」= 经验条进度 × 升到下一级所需经验。
        //
        //    这里刻意【不】去看 experienceLevel / totalExperience：
        //    - 用 totalExperience 当预算会让单次扣减轻易超过进度条存量，直接踩进上面说的负值回弹；
        //    - 曾经有过一个「进度条空了就给 1 点试探等级」的兜底，那是越界的必然来源
        //      （扣 1 点也会让进度条变负 → 降级 → 弹回 98%），已删除。
        int budget = (int) Math.floor(player.experienceProgress * (float) player.getXpNeededForNextLevel());
        if (budget <= 0) {
            // 进度条里一点经验都没有 —— 没有可花的经验，安静退出
            return;
        }

        // 2) 在玩家身上（主手/副手/盔甲/背包）随机找一件「带经验修补且耐久不满」的物品
        Optional<EnchantedItemInUse> optional = EnchantmentHelper.getRandomItemWith(
                EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (optional.isEmpty()) {
            return;
        }

        // 3) 原版换算：budget 点经验能修多少耐久（经验修补 I 是 1 经验 = 2 耐久）
        ItemStack stack = optional.get().itemStack();
        int repair = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), stack, budget);
        if (repair <= 0) {
            // 换算不出耐久（理论上不会发生）—— 绝不能拿它当除数
            return;
        }

        // 不能修超过物品当前的损坏值
        int actual = Math.min(repair, stack.getDamageValue());
        if (actual <= 0) {
            return;
        }
        stack.setDamageValue(stack.getDamageValue() - actual);

        // 4) 真正消耗的经验 = budget × (实际修的耐久 / 全部预算能修的耐久)。
        //    这是 repair 变换的线性反函数，与原版 ExperienceOrb#repairPlayerItems 完全一致；
        //    因为 actual ≤ repair，所以 spent ≤ budget —— 进度条永远不会被扣成负数，
        //    也就不会触发降级回弹，等级恒定不变。
        int spent = actual * budget / repair;
        if (spent > 0) {
            player.giveExperiencePoints(-spent);
        }
    }
}
