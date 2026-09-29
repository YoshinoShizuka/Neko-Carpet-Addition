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
 * <p>思路和原版 {@code ExperienceOrb#repairPlayerItems} 完全一致——都是用
 * 「找到一件带 REPAIR_WITH_XP 效果且已受损的物品 → 换算成要修的耐久 → 扣经验」这一套原版流程，
 * 唯一的区别是经验来源：原版来自经验球实体，这里改成玩家自己身上已有的经验值。</p>
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
        // 1) 取「当前这一个等级内已经攒下的经验」= 经验条进度 × 升到下一级所需经验
        int exp = (int) Math.floor(player.experienceProgress * (float) player.getXpNeededForNextLevel());

        // 经验条正好是空的、但还有等级时兜底给 1 点：
        // 这样下面的扣减会自然地吃掉等级里的存量经验（原版 giveExperiencePoints 支持负数降级）。
        int amount = exp == 0 ? (player.experienceLevel > 0 ? 1 : 0) : exp;

        // 既没有进度也没有等级 —— 没经验可花，直接退出
        if (amount == 0) {
            return;
        }

        // 2) 在玩家身上（主手/副手/盔甲/背包）随机找一件「带经验修补且耐久不满」的物品
        Optional<EnchantedItemInUse> optional = EnchantmentHelper.getRandomItemWith(
                EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (optional.isEmpty()) {
            return;
        }

        // 3) 原版换算：amount 点经验能修多少耐久（经验修补 I 是 1 经验 = 2 耐久）
        ItemStack stack = optional.get().itemStack();
        int repair = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), stack, amount);

        // 不能修超过物品当前的损坏值
        int actual = Math.min(repair, stack.getDamageValue());
        stack.setDamageValue(stack.getDamageValue() - actual);

        // 4) 把消耗掉的经验从玩家身上扣除。
        //    传负数时原版会先进经验条、不够再降等级，所以这里不需要自己处理升级/降级。
        if (actual > 0) {
            player.giveExperiencePoints(-actual);
        }
    }
}
