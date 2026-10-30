import neko.voidwarehouse.VoidWarehouseRecipes;
import neko.voidwarehouse.VoidWarehouseRecipes.Part;
import neko.voidwarehouse.VoidWarehouseRecipes.Plan;
import neko.voidwarehouse.VoidWarehouseRecipes.Recipe;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑复核：两条「木板可互换」的活塞配方在配方本里如何共存、扣料时选哪一条。
 * 不依赖 Minecraft，只编译 common 里的 VoidWarehouseRecipes。
 */
public final class CraftCheck {

    static final BigInteger B64 = BigInteger.valueOf(64);

    static Recipe piston(String plankId) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(List.of(plankId), 3));                          // 木板 ×3
        parts.add(new Part(List.of("minecraft:cobblestone"), 4));
        parts.add(new Part(List.of("minecraft:iron_ingot"), 1));
        parts.add(new Part(List.of("minecraft:redstone"), 1));
        return new Recipe(null, "minecraft:piston", 1, parts);
    }

    static Map<String, BigInteger> stock(Object... kv) {
        Map<String, BigInteger> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], BigInteger.valueOf(((Number) kv[i + 1]).longValue()));
        }
        return m;
    }

    /** 复刻 VoidWarehouseData#planCraft 的候选循环：按学会顺序逐条试，第一条成功就用 */
    static Plan planCraft(List<Recipe> learnedCandidates, BigInteger need, boolean allowPartial,
                          Map<String, BigInteger> stock, Map<String, List<Recipe>> byOutput) {
        for (Recipe recipe : learnedCandidates) {
            BigInteger wantOut = need;
            if (allowPartial) {
                BigInteger maxOut = VoidWarehouseRecipes.maxCraftable(recipe, stock, byOutput);
                if (maxOut.signum() <= 0) {
                    continue;
                }
                wantOut = need.min(maxOut);
            }
            Plan plan = VoidWarehouseRecipes.plan(recipe, wantOut, stock, byOutput);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        Recipe oak = piston("minecraft:oak_planks");
        Recipe birch = piston("minecraft:birch_planks");
        System.out.println("配方本里的两条记录（recipeId 由内容生成）：");
        System.out.println("  A = " + oak.recipeId());
        System.out.println("  B = " + birch.recipeId());
        System.out.println("  两条 recipeId 相同吗? " + oak.recipeId().equals(birch.recipeId()));
        System.out.println();

        Map<String, BigInteger> rich = stock(
                "minecraft:oak_planks", 192, "minecraft:birch_planks", 192,
                "minecraft:cobblestone", 256, "minecraft:iron_ingot", 64, "minecraft:redstone", 64);

        // ---- 1) 先学橡木、后学白桦，两种木板都充足 ----
        Map<String, List<Recipe>> byOutput1 = VoidWarehouseRecipes.byOutput(List.of(oak, birch));
        Plan p1 = planCraft(byOutput1.get("minecraft:piston"), BigInteger.ONE, false, rich, byOutput1);
        System.out.println("[1] 学序 oak→birch，两种木板各 192，取 1 个活塞 → 扣了 " + p1.consumed());

        // ---- 2) 先学白桦、后学橡木，同样两种都充足 ----
        Map<String, List<Recipe>> byOutput2 = VoidWarehouseRecipes.byOutput(List.of(birch, oak));
        Plan p2 = planCraft(byOutput2.get("minecraft:piston"), BigInteger.ONE, false, rich, byOutput2);
        System.out.println("[2] 学序 birch→oak，同样库存，取 1 个活塞 → 扣了 " + p2.consumed());

        // ---- 3) 只学过橡木版，仓库只有白桦木板 ----
        Map<String, List<Recipe>> byOutput3 = VoidWarehouseRecipes.byOutput(List.of(oak));
        Map<String, BigInteger> onlyBirch = stock(
                "minecraft:birch_planks", 192,
                "minecraft:cobblestone", 256, "minecraft:iron_ingot", 64, "minecraft:redstone", 64);
        Plan p3 = planCraft(byOutput3.get("minecraft:piston"), BigInteger.ONE, false, onlyBirch, byOutput3);
        System.out.println("[3] 只学 oak，仓库只有 birch 木板 → 结果 = " + p3 + "（null = 做不出来）");

        // ---- 4) 学序 oak→birch，但 oak 只剩 1 块（不够 3 块），birch 充足 ----
        Map<String, BigInteger> oakShort = stock(
                "minecraft:oak_planks", 1, "minecraft:birch_planks", 192,
                "minecraft:cobblestone", 256, "minecraft:iron_ingot", 64, "minecraft:redstone", 64);
        Plan p4 = planCraft(byOutput1.get("minecraft:piston"), BigInteger.ONE, false, oakShort, byOutput1);
        System.out.println("[4] 学序 oak→birch，oak 只有 1 块 → 自动落到 birch，扣了 " + p4.consumed());

        // ---- 5) 界面上的「可合成 N 个」取两条的最大值 ----
        Map<String, BigInteger> scarce = stock(
                "minecraft:oak_planks", 192, "minecraft:birch_planks", 3,
                "minecraft:cobblestone", 256, "minecraft:iron_ingot", 64, "minecraft:redstone", 64);
        BigInteger maxOak = VoidWarehouseRecipes.maxCraftable(oak, scarce, byOutput1);
        BigInteger maxBirch = VoidWarehouseRecipes.maxCraftable(birch, scarce, byOutput1);
        BigInteger maxOffer = maxOak.max(maxBirch);
        System.out.println("[5] 库存 oak=192(64 个活塞)、birch=3(1 个活塞)");
        System.out.println("    界面口径 maxCraftable: oak=" + maxOak + " birch=" + maxBirch
                + " → 显示的「可合成」= " + maxOffer);
        Plan p5 = planCraft(byOutput1.get("minecraft:piston"), B64, true, scarce, byOutput1);
        System.out.println("    右键「取一组」实际给到 = " + (p5 == null ? "null" : p5.crafts())
                + " 个活塞，扣料 " + (p5 == null ? "-" : p5.consumed()));
        Plan p5rev = planCraft(byOutput2.get("minecraft:piston"), B64, true, scarce, byOutput2);
        System.out.println("    若学序反过来（birch 先）实际给到 = " + (p5rev == null ? "null" : p5rev.crafts())
                + " 个活塞，扣料 " + (p5rev == null ? "-" : p5rev.consumed()));
    }

    private CraftCheck() {
    }
}
