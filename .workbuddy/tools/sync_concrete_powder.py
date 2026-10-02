#!/usr/bin/env python3
"""
把 26.3 的「混凝土粉末 → 染色玻璃」规则同步到其余四个版本。

本轮新增的东西（全部是新增，不动已有逻辑）：
  · java  neko/cooking/{ConcretePowderToGlass,CookingSettings,CookingExtension}.java
  · java  neko/cooking/mixin/{RecipeManagerMixin,AbstractFurnaceMenuMixin}.java
  · json  resources/carpet_neko_addition.cooking.mixins.json        （新的 Mixin 配置）
  · 接线  fabric.mod.json 的 mixins 数组 + AutoMendingMod 的 CookingExtension.init()
  · 文案  lang/zh_cn.json、lang/en_us.json 各加 2 条

★ 版本差异（全部 javap 实证，不许猜）
  1) 混凝土粉末 / 染色玻璃的取法
       26.2 / 26.3         Items.CONCRETE_POWDER.pick(color)   （ColorCollection）
       26.1.1 / 26.1.2 /
       1.21.11             16 个独立字段 Items.WHITE_CONCRETE_POWDER …
     实证：26.2 的 Items 里有 `ColorCollection<Item> CONCRETE_POWDER`；
           26.1.1 / 26.1.2 / 1.21.11 有 16 个 `Item WHITE_CONCRETE_POWDER` 字段。
  2) SmeltingRecipe 的构造
       26.1.1 / 26.1.2 / 26.3
             (Recipe$CommonInfo, AbstractCookingRecipe$CookingBookInfo, Ingredient, ItemStackTemplate, float, int)
       1.21.11
             (String group, CookingBookCategory, Ingredient, ItemStack, float, int)
     实证：1.21.11 没有 Recipe$CommonInfo / ItemStackTemplate 这两个类。
  3) mixins.json 的 compatibilityLevel
       26.x 一律 JAVA_25，1.21.11 是 JAVA_21。
  4) 自检里的 Recipe#assemble
       26.x 一参 assemble(input)；1.21.11 两参 assemble(input, HolderLookup.Provider)。

★ 幂等
  patch_once() 在「已经改过」时跳过而不是报错，方便反复跑；
  但真正写下去的那一次一定要求锚点恰好 1 处、且替换后确实变了 ——
  静默失效（改了但没生效）比崩掉更难查。

用法:
    python sync_concrete_powder.py            # 只同步源码 / 接线 / 文案
    python sync_concrete_powder.py selfcheck  # 额外装临时的自检 + onTick 钩子
    python sync_concrete_powder.py selfcheck-remove
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

SRC_JAVA = ROOT / 'modern/26.3/src/main/java/neko'
SRC_RES = ROOT / 'modern/26.3/src/main/resources'
SRC_SELFCHECK = SRC_JAVA / 'cooking/ConcretePowderToGlassSelfCheck.java'

# 版本名 → (java 根, resources 根)
BASES = {
    '26.2': (ROOT / 'modern/26.2/src/main/java/neko', ROOT / 'modern/26.2/src/main/resources'),
    '26.1.1': (ROOT / 'modern/26.1.1/src/main/java/neko', ROOT / 'modern/26.1.1/src/main/resources'),
    '26.1.2': (ROOT / 'modern/26.1.2/src/main/java/neko', ROOT / 'modern/26.1.2/src/main/resources'),
    '1.21.11': (ROOT / 'versions/1.21.11/src/main/java/neko', ROOT / 'versions/1.21.11/src/main/resources'),
}

ALL = list(BASES)
PRE_COLLECTION = ['26.1.1', '26.1.2', '1.21.11']   # 混凝土粉末/染色玻璃还是独立字段
ONLY_121 = ['1.21.11']

# 每个版本用哪个已有文件当「行尾样本」（新增文件要从它推断 EOL）
EOL_SAMPLE = 'voidwarehouse/VoidWarehouseExtension.java'          # java 树
EOL_SAMPLE_RES = 'fabric.mod.json'                                # resources 树


def eol_of(base, res=False):
    raw = (base / (EOL_SAMPLE_RES if res else EOL_SAMPLE)).read_bytes()
    return '\r\n' if b'\r\n' in raw else '\n'


def read_norm(path):
    return Path(path).read_bytes().decode('utf-8').replace('\r\n', '\n')


def load_json_strict(path, v):
    """JSON 必须能解析、且**没有重复键**。

    本轮就是栽在这上面：lang 补丁少写一个逗号 + 重复追加了两次，
    两份 lang 都成了非法 JSON，游戏里表现为「缺翻译键」→ Carpet 抛 NPE 崩服，
    而 `gradlew runServer` 依然是 BUILD SUCCESSFUL。所以在这里把门关上。
    """
    import json

    def no_dup(pairs):
        seen = set()
        for k, _ in pairs:
            assert k not in seen, '%s: JSON 出现重复键 %r（%s）' % (v, k, path.name)
            seen.add(k)
        return dict(pairs)

    try:
        return json.loads(read_norm(path), object_pairs_hook=no_dup)
    except AssertionError:
        raise
    except Exception as e:
        raise AssertionError('%s: %s 不是合法 JSON —— %s' % (v, path.name, e)) from None


def write_text(path, text, eol):
    Path(path).write_bytes(text.replace('\r\n', '\n').replace('\n', eol).encode('utf-8'))


def copy_whole(rel, versions, res=False, src_rel=None):
    """整文件复制；EOL 跟目标版本已有的同族文件保持一致（免得制造整文件假 diff）"""
    src = (SRC_RES if res else SRC_JAVA) / (src_rel or rel)
    text = read_norm(src)
    for v in versions:
        base = BASES[v][1 if res else 0]
        target = base / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        write_text(target, text, eol_of(base, res))
    print('[copy] %-52s → %s' % (rel, ', '.join(versions)))


def patch_once(rel, old, new, what, versions, res=False):
    """改一次。

    已经改过就跳过；没改过则要求**锚点恰好 1 处** + **替换后确实变了**。

    ⚠️ 「已改过」必须靠 `new in text` 判断，不能靠 `old not in text` ——
    因为这里的补丁大多是「在 old 后面追加」，改完之后 old 依然在文件里。
    最初写成「old 在就改」，第二次跑就**又追加了一遍**，把 lang 文件写成非法 JSON
    （少逗号 + 重复键），服务端启动直接 NPE。见 docs/混凝土粉末烧制染色玻璃规则移植.md §6。
    """
    assert old != new, '%s: old == new' % what
    for v in versions:
        base = BASES[v][1 if res else 0]
        p = base / rel
        text = read_norm(p)
        # new == '' 表示「删掉某段」：那时「已改过」= 那段已经不在了
        applied = (old not in text) if new == '' else (new in text)
        if applied:
            print('[skip] %-12s %s' % (v, what))
            continue
        n = text.count(old)
        assert n == 1, '%s / %s: 锚点出现 %d 次，期望 1 次（%s）\n---\n%s\n---' % (v, rel, n, what, old)
        out = text.replace(old, new)
        assert out != text, '%s / %s: %s 替换后没有变化' % (v, rel, what)
        write_text(p, out, eol_of(base, res))
        print('[edit] %-12s %s' % (v, what))


# ================================================================ 补丁片段

# ---- 1) ConcretePowderToGlass：独立字段版的取色（26.1.1 / 26.1.2 / 1.21.11）

POWDER_GLASS_OLD = '''    /** 该颜色的混凝土粉末 */
    public static Item powderOf(DyeColor color) {
        return Items.CONCRETE_POWDER.pick(color);
    }

    /** 该颜色的染色玻璃（也就是配方产出） */
    public static Item glassOf(DyeColor color) {
        return Items.STAINED_GLASS.pick(color);
    }
'''

POWDER_GLASS_NEW = '''    /**
     * 该颜色的混凝土粉末。
     *
     * <p>这个版本里 16 种混凝土粉末还是<b>各自独立的 {@code Items} 字段</b>
     * （{@code Items.WHITE_CONCRETE_POWDER}…），26.2 起才统一收进
     * {@code ColorCollection}（{@code Items.CONCRETE_POWDER.pick(color)}），所以这里枚举一遍。</p>
     */
    public static Item powderOf(DyeColor color) {
        return powderItem(color);
    }

    /**
     * 颜色 → 混凝土粉末物品。
     *
     * <p>{@link DyeColor} 的 16 个常量与 {@code Items} 里的字段一一对应，但字段名是
     * 「颜色名 + {@code _CONCRETE_POWDER}」，只能用 {@code switch} 显式列出来 ——
     * 拿枚举名拼字符串再用反射查字段，拼错了要等运行期才知道，不值得。</p>
     */
    private static Item powderItem(DyeColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_CONCRETE_POWDER;
            case ORANGE -> Items.ORANGE_CONCRETE_POWDER;
            case MAGENTA -> Items.MAGENTA_CONCRETE_POWDER;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_CONCRETE_POWDER;
            case YELLOW -> Items.YELLOW_CONCRETE_POWDER;
            case LIME -> Items.LIME_CONCRETE_POWDER;
            case PINK -> Items.PINK_CONCRETE_POWDER;
            case GRAY -> Items.GRAY_CONCRETE_POWDER;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_CONCRETE_POWDER;
            case CYAN -> Items.CYAN_CONCRETE_POWDER;
            case PURPLE -> Items.PURPLE_CONCRETE_POWDER;
            case BLUE -> Items.BLUE_CONCRETE_POWDER;
            case BROWN -> Items.BROWN_CONCRETE_POWDER;
            case GREEN -> Items.GREEN_CONCRETE_POWDER;
            case RED -> Items.RED_CONCRETE_POWDER;
            case BLACK -> Items.BLACK_CONCRETE_POWDER;
        };
    }

    /** 该颜色的染色玻璃（也就是配方产出） */
    public static Item glassOf(DyeColor color) {
        return glassItem(color);
    }

    /**
     * 颜色 → 染色玻璃物品。
     *
     * <p>同上，这个版本还是 16 个独立字段（{@code Items.WHITE_STAINED_GLASS}…）。</p>
     */
    private static Item glassItem(DyeColor color) {
        return switch (color) {
            case WHITE -> Items.WHITE_STAINED_GLASS;
            case ORANGE -> Items.ORANGE_STAINED_GLASS;
            case MAGENTA -> Items.MAGENTA_STAINED_GLASS;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_STAINED_GLASS;
            case YELLOW -> Items.YELLOW_STAINED_GLASS;
            case LIME -> Items.LIME_STAINED_GLASS;
            case PINK -> Items.PINK_STAINED_GLASS;
            case GRAY -> Items.GRAY_STAINED_GLASS;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_STAINED_GLASS;
            case CYAN -> Items.CYAN_STAINED_GLASS;
            case PURPLE -> Items.PURPLE_STAINED_GLASS;
            case BLUE -> Items.BLUE_STAINED_GLASS;
            case BROWN -> Items.BROWN_STAINED_GLASS;
            case GREEN -> Items.GREEN_STAINED_GLASS;
            case RED -> Items.RED_STAINED_GLASS;
            case BLACK -> Items.BLACK_STAINED_GLASS;
        };
    }
'''

BAG_COMMENT_OLD = '            // 16 条都必须落到表里，少一条就说明 pick() 把颜色取歪了（或者两个颜色指到同一个物品）\n'
BAG_COMMENT_NEW = '            // 16 条都必须落到表里，少一条就说明取色取歪了（或者两个颜色指到同一个物品）\n'

# ---- 2) ConcretePowderToGlass：1.21.11 的 SmeltingRecipe 老构造

RECIPE_OLD = '''            SmeltingRecipe recipe = new SmeltingRecipe(
                    // showNotification = true：烧出来的时候在快捷栏上方弹一下（与原版配方一致）
                    new Recipe.CommonInfo(true),
                    new AbstractCookingRecipe.CookingBookInfo(CookingBookCategory.BLOCKS, ""),
                    Ingredient.of(powder),
                    new ItemStackTemplate(glass),
                    EXPERIENCE,
                    COOKING_TIME);
'''

RECIPE_NEW_121 = '''            // 1.21.11 的 SmeltingRecipe 还是老式构造（String group + ItemStack 产物），
            // 26.x 才换成 Recipe.CommonInfo + ItemStackTemplate。
            SmeltingRecipe recipe = new SmeltingRecipe(
                    "",
                    CookingBookCategory.BLOCKS,
                    Ingredient.of(powder),
                    new ItemStack(glass),
                    EXPERIENCE,
                    COOKING_TIME);
'''

IMPORT_TEMPLATE = 'import net.minecraft.world.item.ItemStackTemplate;\n'
IMPORT_COOKING_RECIPE = 'import net.minecraft.world.item.crafting.AbstractCookingRecipe;\n'

# ---- 3) 自检：1.21.11 的 assemble 是两参

SC_ASSEMBLE_OLD = '''    /** 把配方真的「做」一次拿到产出 —— 比只断言「查到了」硬得多 */
    private static ItemStack assemble(RecipeHolder<?> holder, SingleRecipeInput input) {
        if (!(holder.value() instanceof SmeltingRecipe smelting)) {
            return ItemStack.EMPTY;
        }
        return smelting.assemble(input);
    }
'''

SC_ASSEMBLE_NEW_121 = '''    /**
     * 把配方真的「做」一次拿到产出 —— 比只断言「查到了」硬得多。
     *
     * <p>1.21.11 的 {@code Recipe#assemble} 是<b>两参</b>
     * （{@code assemble(input, HolderLookup.Provider)}），26.x 才收敛成一参
     * —— 所以这个版本的副本多带一个 {@code level} 参数。</p>
     */
    private static ItemStack assemble(RecipeHolder<?> holder, SingleRecipeInput input, ServerLevel level) {
        if (!(holder.value() instanceof SmeltingRecipe smelting)) {
            return ItemStack.EMPTY;
        }
        return smelting.assemble(input, level.registryAccess());
    }
'''

SC_CALL1_OLD = '            ItemStack out = assemble(found.get(), input);\n'
SC_CALL1_NEW_121 = '            ItemStack out = assemble(found.get(), input, level);\n'
SC_CALL2_OLD = '                vanilla.isPresent() && assemble(vanilla.get(), sand).getItem() == Items.GLASS);\n'
SC_CALL2_NEW_121 = '                vanilla.isPresent() && assemble(vanilla.get(), sand, level).getItem() == Items.GLASS);\n'

# ---- 4) 接线

FMJ_OLD = '''  "mixins": [
    "carpet_neko_addition.mixins.json"
  ],
'''
FMJ_NEW = '''  "mixins": [
    "carpet_neko_addition.mixins.json",
    "carpet_neko_addition.cooking.mixins.json"
  ],
'''

MOD_IMPORT_OLD = 'import neko.voidwarehouse.VoidWarehouseExtension;\n'
MOD_IMPORT_NEW = 'import neko.cooking.CookingExtension;\nimport neko.voidwarehouse.VoidWarehouseExtension;\n'

MOD_INIT_OLD = '''        // 虚空仓库是同一个模组里的第二条 Carpet 规则，单独注册一个扩展
        VoidWarehouseExtension.init();
'''
MOD_INIT_NEW = '''        // 虚空仓库是同一个模组里的第二条 Carpet 规则，单独注册一个扩展
        VoidWarehouseExtension.init();
        // 熔炉玩法（混凝土粉末烧制染色玻璃）是第三条，同样一个扩展一条线
        CookingExtension.init();
'''

ZH_LAST = '  "carpet.rule.voidWarehouseRestock.desc": "允许建造时缺少材料的打印机直接从虚空仓库领取物品（每次一组）。注意：开启后任何装了对应打印机的玩家都能动用全服共享的库存"\n'
# ⚠️ 这一行原本是对象的最后一个成员，**没有逗号**；追加新条目时必须自己把逗号补上，
#    否则写出非法 JSON，TranslationLoader 解析失败 → Carpet 抛
#    「No language key provided for carpet.rule.autoMending.desc」直接崩服。
ZH_OLD = ZH_LAST
ZH_NEW = ZH_LAST.replace('\n', ',\n') + '''  "carpet.rule.concretePowderToGlass.name": "混凝土粉末烧制染色玻璃",
  "carpet.rule.concretePowderToGlass.desc": "允许在普通熔炉里把 16 种混凝土粉末烧成对应颜色的染色玻璃（0.1 经验 / 200 tick，与「沙子 → 玻璃」一致）。只影响普通熔炉，高炉与烟熏炉仍不受理混凝土粉末"
'''

EN_LAST = '  "carpet.rule.voidWarehouseRestock.desc": "Lets a printer that ran out of blocks while building take a full stack straight from the Void Warehouse. Warning: while enabled, any player with a compatible printer can draw from the shared stock"\n'
EN_OLD = EN_LAST
EN_NEW = EN_LAST.replace('\n', ',\n') + '''  "carpet.rule.concretePowderToGlass.name": "Smelt Concrete Powder Into Stained Glass",
  "carpet.rule.concretePowderToGlass.desc": "Lets a regular furnace smelt all 16 concrete powder colours into the matching stained glass (0.1 xp / 200 ticks, same as sand into glass). Only regular furnaces are affected; blast furnaces and smokers still reject concrete powder"
'''

# ---- 5) 临时自检钩子

HOOK_OLD = '''    public void onTick(MinecraftServer server) {
        if (VoidWarehouseSettings.voidWarehouse) {
'''
HOOK_NEW = '''    public void onTick(MinecraftServer server) {
        // 【临时自检】混凝土粉末烧制染色玻璃，验证后删除
        neko.cooking.ConcretePowderToGlassSelfCheck.run(server);

        if (VoidWarehouseSettings.voidWarehouse) {
'''

COOKING_PKG = 'cooking'
MIXIN_PKG = 'cooking/mixin'
COOKING_MIXINS_JSON = 'carpet_neko_addition.cooking.mixins.json'
EXTENSION_REL = 'voidwarehouse/VoidWarehouseExtension.java'


def main():
    # ---------------------------------------------------------- java
    copy_whole(f'{COOKING_PKG}/CookingSettings.java', ALL)
    copy_whole(f'{COOKING_PKG}/CookingExtension.java', ALL)
    copy_whole(f'{MIXIN_PKG}/RecipeManagerMixin.java', ALL)
    copy_whole(f'{MIXIN_PKG}/AbstractFurnaceMenuMixin.java', ALL)

    copy_whole(f'{COOKING_PKG}/ConcretePowderToGlass.java', ALL)
    patch_once(f'{COOKING_PKG}/ConcretePowderToGlass.java', POWDER_GLASS_OLD, POWDER_GLASS_NEW,
               'powderOf / glassOf 改用 16 个独立字段', PRE_COLLECTION)
    patch_once(f'{COOKING_PKG}/ConcretePowderToGlass.java', BAG_COMMENT_OLD, BAG_COMMENT_NEW,
               'Bag 里的注释不再点名 pick()', PRE_COLLECTION)
    patch_once(f'{COOKING_PKG}/ConcretePowderToGlass.java', RECIPE_OLD, RECIPE_NEW_121,
               'SmeltingRecipe 换成 1.21.11 的老构造', ONLY_121)
    # 换掉构造之后这两个 import 在 1.21.11 上就没有使用者了（类本身那边也确实没有）
    patch_once(f'{COOKING_PKG}/ConcretePowderToGlass.java', IMPORT_TEMPLATE, '',
               '摘掉 ItemStackTemplate import', ONLY_121)
    patch_once(f'{COOKING_PKG}/ConcretePowderToGlass.java', IMPORT_COOKING_RECIPE, '',
               '摘掉 AbstractCookingRecipe import', ONLY_121)

    # ---------------------------------------------------------- mixin 配置
    copy_whole(COOKING_MIXINS_JSON, ['26.2', '26.1.1', '26.1.2'], res=True)
    copy_whole(COOKING_MIXINS_JSON, ONLY_121, res=True)
    patch_once(COOKING_MIXINS_JSON,
               '  "compatibilityLevel": "JAVA_25",\n',
               '  "compatibilityLevel": "JAVA_21",\n',
               'compatibilityLevel → JAVA_21', ONLY_121, res=True)

    # ---------------------------------------------------------- 接线
    patch_once('fabric.mod.json', FMJ_OLD, FMJ_NEW, 'fabric.mod.json 挂上 cooking 的 mixins', ALL, res=True)
    patch_once('automending/AutoMendingMod.java', MOD_IMPORT_OLD, MOD_IMPORT_NEW,
               'import CookingExtension', ALL)
    patch_once('automending/AutoMendingMod.java', MOD_INIT_OLD, MOD_INIT_NEW,
               'CookingExtension.init()', ALL)
    patch_once('assets/carpet_neko_addition/lang/zh_cn.json', ZH_OLD, ZH_NEW,
               'zh_cn 加 2 条规则文案', ALL, res=True)
    patch_once('assets/carpet_neko_addition/lang/en_us.json', EN_OLD, EN_NEW,
               'en_us 加 2 条规则文案', ALL, res=True)

    # ---------------------------------------------------------- 收尾校验
    print('\n=== 校验 ===')
    # 只看「真正被执行的代码」：本轮补的注释里会提到 ItemStackTemplate / CommonInfo 做对比
    ITEMSTACKTEMPLATE = 'new ItemStackTemplate('
    for v in ALL:
        java_base, res_base = BASES[v]
        cp = read_norm(java_base / f'{COOKING_PKG}/ConcretePowderToGlass.java')
        assert 'concrete_powder_to_glass/' in cp, f'{v}: recipeId 命名不对'
        assert 'CookingSettings.concretePowderToGlass' in cp, f'{v}: 没有规则开关判断'
        assert 'public static final float EXPERIENCE = 0.1F;' in cp, f'{v}: 经验值不是 0.1'
        assert 'public static final int COOKING_TIME = 200;' in cp, f'{v}: 时长不是 200'

        if v in PRE_COLLECTION:
            # 只查「真正被执行的代码」—— javadoc 里会提到另一种写法做对比
            assert 'return Items.CONCRETE_POWDER.pick(color);' not in cp, f'{v}: 这个版本没有 ColorCollection'
            assert 'return Items.STAINED_GLASS.pick(color);' not in cp, f'{v}: 这个版本没有 ColorCollection'
            assert 'case RED -> Items.RED_CONCRETE_POWDER;' in cp, f'{v}: 粉末 switch 不完整'
            assert 'case LIGHT_GRAY -> Items.LIGHT_GRAY_STAINED_GLASS;' in cp, f'{v}: 玻璃 switch 少了浅灰'
        else:
            assert 'return Items.CONCRETE_POWDER.pick(color);' in cp, f'{v}: 应该走 ColorCollection'
            assert 'return Items.STAINED_GLASS.pick(color);' in cp, f'{v}: 应该走 ColorCollection'

        if v in ONLY_121:
            assert ITEMSTACKTEMPLATE not in cp, f'{v}: 1.21.11 没有 ItemStackTemplate'
            assert 'new Recipe.CommonInfo' not in cp, f'{v}: 1.21.11 没有 Recipe$CommonInfo'
            assert 'new SmeltingRecipe(\n                    "",\n' in cp, f'{v}: 构造没换成老式'
            assert IMPORT_TEMPLATE not in cp, f'{v}: ItemStackTemplate 的 import 没摘掉'
            assert IMPORT_COOKING_RECIPE not in cp, f'{v}: AbstractCookingRecipe 的 import 没摘掉'
        else:
            assert 'new Recipe.CommonInfo(true)' in cp, f'{v}: 少了 Recipe.CommonInfo'
            assert 'new ItemStackTemplate(glass)' in cp, f'{v}: 少了 ItemStackTemplate'

        # 混入配置
        mx = read_norm(res_base / COOKING_MIXINS_JSON)
        want_level = 'JAVA_21' if v in ONLY_121 else 'JAVA_25'
        assert f'"compatibilityLevel": "{want_level}"' in mx, f'{v}: compatibilityLevel 不对'

        # 接线
        fmj = read_norm(res_base / 'fabric.mod.json')
        assert COOKING_MIXINS_JSON in fmj, f'{v}: fabric.mod.json 没挂 cooking mixins'
        mod = read_norm(java_base / 'automending/AutoMendingMod.java')
        assert 'CookingExtension.init();' in mod, f'{v}: 没调用 CookingExtension.init()'
        assert 'import neko.cooking.CookingExtension;' in mod, f'{v}: 少了 import'

        # 文案
        for lang, key in (('zh_cn', '混凝土粉末烧制染色玻璃'),
                          ('en_us', 'Smelt Concrete Powder Into Stained Glass')):
            t = read_norm(res_base / f'assets/carpet_neko_addition/lang/{lang}.json')
            assert 'carpet.rule.concretePowderToGlass.name' in t, f'{v}/{lang}: 缺 name'
            assert key in t, f'{v}/{lang}: name 文案不对'
            assert 'carpet.rule.concretePowderToGlass.desc' in t, f'{v}/{lang}: 缺 desc'
            assert t.count('carpet.rule.concretePowderToGlass') == 2, \
                f'{v}/{lang}: 规则文案条数不是 2（重复追加过？）'

        # JSON 一律必须是合法的（含无重复键）—— 崩服的那一类问题在这里拦掉
        for name in ('fabric.mod.json', COOKING_MIXINS_JSON,
                     'assets/carpet_neko_addition/lang/zh_cn.json',
                     'assets/carpet_neko_addition/lang/en_us.json'):
            load_json_strict(res_base / name, v)
        print(f'  {v}: OK')

    # 26.3 是「源」，顺手验收
    src_cp = read_norm(SRC_JAVA / f'{COOKING_PKG}/ConcretePowderToGlass.java')
    assert 'Items.CONCRETE_POWDER.pick(color)' in src_cp, '26.3: 应该走 ColorCollection'
    assert 'new ItemStackTemplate(glass)' in src_cp, '26.3: 少了 ItemStackTemplate'
    assert 'carpet.rule.concretePowderToGlass.name' in read_norm(
        SRC_RES / 'assets/carpet_neko_addition/lang/zh_cn.json'), '26.3: 缺中文文案'
    print('  26.3: OK')
    print('\n全部同步完成。')


# ================================================================ 临时自检

def install_selfcheck():
    """把 26.3 的自检复制到四个版本并挂上 onTick 钩子（只为跑 runServer 自检）"""
    text = read_norm(SRC_SELFCHECK)
    for v in ALL:
        base = BASES[v][0]
        dst = base / f'{COOKING_PKG}/ConcretePowderToGlassSelfCheck.java'
        write_text(dst, text, eol_of(base))
        if v in ONLY_121:
            patch_once(f'{COOKING_PKG}/ConcretePowderToGlassSelfCheck.java',
                       SC_ASSEMBLE_OLD, SC_ASSEMBLE_NEW_121, 'assemble 改两参', ONLY_121)
            patch_once(f'{COOKING_PKG}/ConcretePowderToGlassSelfCheck.java',
                       SC_CALL1_OLD, SC_CALL1_NEW_121, 'assemble 调用点 1', ONLY_121)
            patch_once(f'{COOKING_PKG}/ConcretePowderToGlassSelfCheck.java',
                       SC_CALL2_OLD, SC_CALL2_NEW_121, 'assemble 调用点 2', ONLY_121)
        patch_once(EXTENSION_REL, HOOK_OLD, HOOK_NEW, '挂上临时自检钩子', [v])
    print('\n临时自检已安装（跑完记得 selfcheck-remove）。')


def remove_selfcheck():
    for v in ALL:
        base = BASES[v][0]
        f = base / f'{COOKING_PKG}/ConcretePowderToGlassSelfCheck.java'
        if f.exists():
            f.unlink()
            print(f'[rm]   {v}: 已删除 SelfCheck')
        patch_once(EXTENSION_REL, HOOK_NEW, HOOK_OLD, '摘掉临时自检钩子', [v])
    print('\n临时自检已卸载。')


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'selfcheck':
        install_selfcheck()
    elif cmd == 'selfcheck-remove':
        remove_selfcheck()
    else:
        main()
