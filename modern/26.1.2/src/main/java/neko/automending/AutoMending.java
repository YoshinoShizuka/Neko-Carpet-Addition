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
 * <p>更要命的是：这个「扣穿」<b>不需要真的算错经验</b>。浮点误差自己就能触发它——
 * 见下面 {@link #mending} 里第 1 步的注释。所以本方法有<b>两道</b>防线：
 * 预算用 double 精确算出来（保证 {@code spent ≤ budget ≤ progress × needed}），
 * 写回时再用原版 {@code setExperiencePoints}（内部带 clamp）兜底。</p>
 *
 * <h2>为什么"实际消耗"要按比例反算</h2>
 * <p>{@code modifyDurabilityToRepairFromXp} 是<b>线性</b>变换（mending 的
 * {@code repair_with_xp} 效果 = {@code multiply 2.0}，即 1 经验 → 2 耐久）。原版
 * {@code ExperienceOrb#repairPlayerItems} 在算「用掉了多少经验」时正是用的这个变换的反函数
 * （javap -c 实证：{@code remainder = amount - actual * amount / repair}）。
 * 直接拿修好的耐久点数当经验扣，会扣掉 <b>整整 2 倍</b>——这是本方法更早的一个真实缺陷。</p>
 *
 * <p>26.3 上用到的这几个原版 API 都已确认存在：</p>
 * <ul>
 *   <li>{@code EnchantmentHelper.getRandomItemWith(DataComponentType, LivingEntity, Predicate)}</li>
 *   <li>{@code EnchantmentHelper.modifyDurabilityToRepairFromXp(ServerLevel, ItemStack, int)}</li>
 *   <li>{@code EnchantmentEffectComponents.REPAIR_WITH_XP}</li>
 *   <li>{@code ServerPlayer#setExperiencePoints(int)} —— 26.x 与 1.21.11 均存在（已逐版本 javap）</li>
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
        // 1) 预算 = 「当前这一个等级内已经攒下的经验」= 进度条进度 × 升到下一级所需经验。
        //
        //    ★ 必须用 double 算，绝不能写成 (float) progress * (float) needed。
        //      experienceProgress 是 float32（24 位有效数字），needed 最大可达数千。
        //      玩家经验是一点一点加出来的，所以 progress 几乎总是 k/needed 的形态 ——
        //      于是 progress*needed 恰好贴着整数。float32 乘法会把「差一点点到整数」的
        //      乘积直接舍入到那个整数，floor 就取到比真实值大 1 的预算；扣完 progress
        //      变成 -3e-8 这种极小负数，照样踩进负值回弹。
        //      实测：在 k/needed ±1ulp 的边界点上，float32 预算有 2.58% 会越界。
        //      转成 double 后 progress 与 needed 都无损，乘积 < 2^37 能被 double 精确
        //      表示，floor 结果恒 ≤ 真实值 —— 同一批边界点越界数降为 0。
        //
        //    这里刻意【不】去看 experienceLevel / totalExperience：
        //    - 用 totalExperience 当预算会让单次扣减轻易超过进度条存量，直接踩进负值回弹；
        //    - 曾经有过一个「进度条空了就给 1 点试探等级」的兜底，那是越界的必然来源
        //      （扣 1 点也会让进度条变负 → 降级 → 弹回 98%），已删除。
        int needed = player.getXpNeededForNextLevel();
        int budget = (int) Math.floor((double) player.experienceProgress * (double) needed);
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

        // 4) 真正消耗的经验 = budget × (实际修的耐久 / 全部预算能修的耐久)。
        //    这是 repair 变换的线性反函数，与原版 ExperienceOrb#repairPlayerItems 完全一致；
        //    因为 actual ≤ repair，所以 spent ≤ budget。
        int spent = actual * budget / repair;
        if (spent <= 0) {
            // 摊下来连 1 点经验都不到 —— 不修，避免「修了耐久却不扣经验」的白嫖
            return;
        }
        stack.setDamageValue(stack.getDamageValue() - actual);

        // 5) 写回经验。
        //
        //    ★ 这里【绝不能】用 giveExperiencePoints(-spent)。
        //      它内部的负值回弹循环见类注释：只要 progress 掉进 -1e-8 这种极小负数，
        //      等级就 -1、进度条被弹到 ~100%。而 float32 的 progress 做
        //      「p - spent/needed」根本无法从浮点层面保证结果非负 —— 上一版就是栽在这里：
        //      预算明明 ≤ 进度条存量，仍然被舍入误差扣穿。
        //
        //    改用原版 ServerPlayer#setExperiencePoints（26.x 与 1.21.11 均存在，已 javap）：
        //        experienceProgress = Mth.clamp((float)amount / f, 0.0F, (f - 1.0F) / f);
        //        if (变了) { experienceProgress = 新值; lastSentExp = -1; }
        //    它物理上不可能写出负数，而且顺手置 lastSentExp = -1，doTick 里会发包同步客户端。
        //    参数是「这一级内已经攒下的经验点数」，正好就是 budget - spent。
        player.setExperiencePoints(budget - spent);
        // setExperiencePoints 只管进度条、不动总经验；这里手动补齐，否则会留下"幽灵总经验"
        player.totalExperience = Math.max(0, player.totalExperience - spent);
    }
}
