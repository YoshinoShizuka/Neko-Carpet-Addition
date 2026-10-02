#!/usr/bin/env python3
"""
把 26.3 的本轮改动同步到其余四个版本。

本轮两处改动：
  1. VoidWarehouseNav        —— 潜影盒模式开关去掉「1 盒 = 27 格 × 堆叠上限（圆石 1,728 个）」
                                整行（玩家要求：盒子本来就是 27 格，算例摆在这里既占行又误导）
  2. VoidWarehouseShulker / VoidWarehouseData / VoidWarehouseHopper
                            —— 染色潜影盒拆包时归还「对应颜色的 1 个染料」
                                （原版：染色盒 = 潜影盒 + 1 染料；原先只吐 2 壳 + 1 箱，等于吞掉玩家一个染料）

★ 版本差异（本轮最关键的一条，javap 实证）
  染料物品的取法随版本不同：
    26.2 / 26.3       染色物品统一收进 ColorCollection → Items.DYE.pick(color)
    26.1.1 / 26.1.2 / 1.21.11
                      仍是 16 个独立字段 → Items.WHITE_DYE … Items.BLACK_DYE
  （实证：26.1.1 / 26.1.2 的 Items 里有 16 个 *_DYE 字段、没有 ColorCollection；
    26.2 起才有。26.1.x 的红色染色玻璃片同理还是独立字段，Nav 的 javadoc 差异也是这个原因。）

同步策略：
  · Nav      26.2 一致 → 整文件复制；26.1.1 / 26.1.2 / 1.21.11 打补丁（有染色玻璃片的字段差异）
  · Shulker  26.2 一致 → 整文件复制；26.1.1 / 26.1.2 / 1.21.11 复制后把 dyeOf 换成独立字段版
  · Data     26.x 四版一致 → 整文件复制；1.21.11 有真差异（Identifier vs 字符串 id）→ 打补丁
  · Hopper   五版本一致 → 整文件复制

行尾：整文件复制时**保留目标原本的行尾**（26.3 是 LF，其余多为 CRLF），
      否则会制造整文件假 diff（前几轮踩过）。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'modern/26.3/src/main/java/neko/voidwarehouse'

ALL = {
    '26.2': ROOT / 'modern/26.2/src/main/java/neko/voidwarehouse',
    '26.1.1': ROOT / 'modern/26.1.1/src/main/java/neko/voidwarehouse',
    '26.1.2': ROOT / 'modern/26.1.2/src/main/java/neko/voidwarehouse',
    '1.21.11': ROOT / 'versions/1.21.11/src/main/java/neko/voidwarehouse',
}
MODERN = {k: v for k, v in ALL.items() if k != '1.21.11'}
PRE_COLLECTION = {k: v for k, v in ALL.items() if k != '26.2'}   # 染料还是独立字段的版本


def read_norm(path):
    return Path(path).read_bytes().decode('utf-8').replace('\r\n', '\n')


def write_keep_eol(path, text):
    """按目标文件原本的行尾写回"""
    raw = Path(path).read_bytes()
    eol = '\r\n' if b'\r\n' in raw else '\n'
    Path(path).write_bytes(text.replace('\r\n', '\n').replace('\n', eol).encode('utf-8'))


def copy_whole(rel, targets):
    text = read_norm(SRC / rel)
    for name, base in targets.items():
        write_keep_eol(base / rel, text)
    print(f'[copy] {rel} → {", ".join(targets)}')


def patch(rel, old, new, what, targets=ALL):
    assert old != new, f'{what}: old == new'
    for name, base in targets.items():
        p = base / rel
        text = read_norm(p)
        n = text.count(old)
        assert n == 1, f'{name}: 锚点出现 {n} 次，期望 1 次（{what}）\n---\n{old}\n---'
        out = text.replace(old, new)
        assert out != text, f'{name}: {what} 替换后没有变化'
        write_keep_eol(p, out)
        print(f'[patch] {name}: {what}')


# ---------------------------------------------------------------- Nav 的补丁片段

NAV_JAVADOC_OLD = (
    '     * <p>图标固定是潜影盒，开 / 关只体现在名字颜色和说明文字里，所以位置和图形完全不跳。\n'
    '     * 说明只留「现在是什么模式 / 一盒多大 / 要点什么代价 / 什么情况会被拒」这四件事，\n'
    '     * 每件事一行，不再把边界说明和「怎么切换」重复写两遍。</p>\n'
    '     */\n'
)
NAV_JAVADOC_NEW = (
    '     * <p>图标固定是潜影盒，开 / 关只体现在名字颜色和说明文字里，所以位置和图形完全不跳。\n'
    '     * 说明只留「现在是什么模式 / 要点什么代价 / 什么情况会被拒」这三件事，\n'
    '     * 每件事一行，不再把边界说明和「怎么切换」重复写两遍。</p>\n'
    '     *\n'
    '     * <p><b>不再写「1 盒 = 27 格 × 堆叠上限」那条换算</b>（玩家要求去掉）：潜影盒本来\n'
    '     * 就是 27 格，玩家自己数得出来；把「圆石 1,728」这种算例摆在开关上，既占一行，\n'
    '     * 又容易让人以为「这个数字是这个模式特有的规矩」。想知道具体能装多少，看物品格\n'
    '     * 上那行换算（{@code VoidWarehouseData#buildDisplay} 的 {@code 「N 盒 M 组 K 个」}）就够。</p>\n'
    '     */\n'
)

NAV_LINE_OLD = (
    '        // 一盒多大（圆石 1728 是玩家最熟悉的参照，写出来比只给公式好读）\n'
    '        lore.add(VoidWarehouseText.line("1 盒 = 27 格 × 堆叠上限（圆石 1,728 个）", VoidWarehouseText.FACT));\n'
    '        // 代价\n'
)
NAV_LINE_NEW = (
    '        // 一盒多大的换算行按玩家要求去掉了（见方法 javadoc）\n'
    '        // 代价\n'
)

# ---------------------------------------------------------------- Data 的补丁片段

DATA_CLASS_OLD = (
    ' *   <li><b>入库</b>：非空盒子被<b>拆开</b> —— 盒里的每种物品各按真实数量入库，盒子本身变成\n'
    ' *       {@code 2 个潜影壳 + 1 个箱子}（原版配方）。<b>空盒子不拆</b>，直接整盒入库，\n'
    ' *       占一格记 1 个「潜影盒」；</li>\n'
)
DATA_CLASS_NEW = (
    ' *   <li><b>入库</b>：非空盒子被<b>拆开</b> —— 盒里的每种物品各按真实数量入库，盒子本身变成\n'
    ' *       {@code 2 个潜影壳 + 1 个箱子}（原版配方），染色盒再额外吐出 {@code 1 个对应颜色的染料}。\n'
    ' *       <b>空盒子不拆</b>，直接整盒入库，\n'
    ' *       占一格记 1 个「潜影盒」；</li>\n'
)

DATA_JAVADOC_OLD = (
    '     * 把潜影盒<b>拆开</b>入库：盒内物品按各自数量入库，盒子本身变成\n'
    '     * {@code 2 个潜影壳 + 1 个箱子}（原版配方）。\n'
    '     *\n'
    '     * <p><b>空盒子不拆</b>：一个东西都没装的潜影盒，直接当成普通物品整盒入库\n'
    '     * （占一格，记 1 个「潜影盒」）。这样玩家手里那个空盒子不会被偷偷换成壳和箱子，\n'
    '     * 语义也最直观 —— 空盒子就是空盒子。</p>\n'
)
DATA_JAVADOC_NEW = (
    '     * 把潜影盒<b>拆开</b>入库：盒内物品按各自数量入库，盒子本身变成\n'
    '     * {@code 2 个潜影壳 + 1 个箱子}（原版配方）；<b>染色盒再额外吐出 1 个对应颜色的染料</b>\n'
    '     * （原版：染色盒 = 潜影盒 + 1 染料）。见 {@link VoidWarehouseShulker#dyeOf(ItemStack)}。\n'
    '     *\n'
    '     * <p><b>空盒子不拆</b>：一个东西都没装的潜影盒，直接当成普通物品整盒入库\n'
    '     * （占一格，记 1 个「潜影盒」）。这样玩家手里那个空盒子不会被偷偷换成壳和箱子，\n'
    '     * 语义也最直观 —— 空盒子就是空盒子。染色空盒同理，整盒入库、颜色原样保留。</p>\n'
)

DATA_BODY_OLD = (
    '        this.put(new ItemStack(Items.CHEST, VoidWarehouseShulker.CHEST_COST));\n'
    '        // 盒子本身已被「拆解」消耗掉\n'
)
DATA_BODY_NEW = (
    '        this.put(new ItemStack(Items.CHEST, VoidWarehouseShulker.CHEST_COST));\n'
    '        // 染色盒：原版是「潜影盒 + 1 染料」合成的，拆开时染料必须一起还回来。\n'
    '        // 无色盒这里拿到的是 EMPTY，直接跳过（不要 put 一个空堆叠）。\n'
    '        ItemStack dye = VoidWarehouseShulker.dyeOf(box);\n'
    '        if (!dye.isEmpty()) {\n'
    '            this.put(dye);\n'
    '        }\n'
    '        // 盒子本身已被「拆解」消耗掉\n'
)

# ---------------------------------------------------------------- Shulker 的补丁片段（独立染料字段版）

SHULKER_IMPORT_OLD = (
    'import net.minecraft.world.item.DyeColor;\n'
    'import net.minecraft.world.item.ItemStack;\n'
)
SHULKER_IMPORT_NEW = (
    'import net.minecraft.world.item.DyeColor;\n'
    'import net.minecraft.world.item.Item;\n'
    'import net.minecraft.world.item.ItemStack;\n'
)

SHULKER_DYE_OLD = (
    '     * <p>26.3 里 16 种染料收在一个 {@code ColorCollection}（{@code Items.DYE}）里，\n'
    '     * 用 {@code pick(DyeColor)} 取色，不必枚举 16 个常量。</p>\n'
    '     */\n'
    '    public static ItemStack dyeOf(ItemStack box) {\n'
    '        DyeColor color = colorOf(box);\n'
    '        return color == null ? ItemStack.EMPTY : new ItemStack(Items.DYE.pick(color), DYE_COST);\n'
    '    }\n'
)
SHULKER_DYE_NEW = (
    '     * <p>这个版本里 16 种染料还是<b>各自独立的 {@code Items} 字段</b>（{@code Items.RED_DYE}…），\n'
    '     * 26.2 起才统一收进 {@code ColorCollection}（{@code Items.DYE.pick(color)}），\n'
    '     * 所以这里枚举一遍。</p>\n'
    '     */\n'
    '    public static ItemStack dyeOf(ItemStack box) {\n'
    '        DyeColor color = colorOf(box);\n'
    '        return color == null ? ItemStack.EMPTY : new ItemStack(dyeItem(color), DYE_COST);\n'
    '    }\n'
    '\n'
    '    /**\n'
    '     * 颜色 → 染料物品。\n'
    '     *\n'
    '     * <p>{@link DyeColor} 的 16 个常量与 {@code Items} 里的 16 个染料字段一一对应，\n'
    '     * 但字段名是「颜色名 + _DYE」，只能用 {@code switch} 显式列出来 —— 拿枚举名拼字符串\n'
    '     * 再用反射查字段，拼错了要等运行期才知道，不值得。</p>\n'
    '     */\n'
    '    private static Item dyeItem(DyeColor color) {\n'
    '        return switch (color) {\n'
    '            case WHITE -> Items.WHITE_DYE;\n'
    '            case ORANGE -> Items.ORANGE_DYE;\n'
    '            case MAGENTA -> Items.MAGENTA_DYE;\n'
    '            case LIGHT_BLUE -> Items.LIGHT_BLUE_DYE;\n'
    '            case YELLOW -> Items.YELLOW_DYE;\n'
    '            case LIME -> Items.LIME_DYE;\n'
    '            case PINK -> Items.PINK_DYE;\n'
    '            case GRAY -> Items.GRAY_DYE;\n'
    '            case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;\n'
    '            case CYAN -> Items.CYAN_DYE;\n'
    '            case PURPLE -> Items.PURPLE_DYE;\n'
    '            case BLUE -> Items.BLUE_DYE;\n'
    '            case BROWN -> Items.BROWN_DYE;\n'
    '            case GREEN -> Items.GREEN_DYE;\n'
    '            case RED -> Items.RED_DYE;\n'
    '            case BLACK -> Items.BLACK_DYE;\n'
    '        };\n'
    '    }\n'
)


def main():
    # ---------------------------------------------------------- 1. Hopper（五版本整文件）
    copy_whole('VoidWarehouseHopper.java', ALL)

    # ---------------------------------------------------------- 2. Nav
    copy_whole('VoidWarehouseNav.java', {'26.2': ALL['26.2']})
    patch('VoidWarehouseNav.java', NAV_JAVADOC_OLD, NAV_JAVADOC_NEW,
          '潜影盒开关 javadoc：四件事 → 三件事', targets=PRE_COLLECTION)
    patch('VoidWarehouseNav.java', NAV_LINE_OLD, NAV_LINE_NEW,
          '删掉「1 盒 = 27 格 × 堆叠上限」那一行', targets=PRE_COLLECTION)
    patch('VoidWarehouseNav.java',
          '        List<Component> lore = new ArrayList<>(5);\n',
          '        List<Component> lore = new ArrayList<>(4);\n',
          'lore 容量 5 → 4', targets=PRE_COLLECTION)

    # ---------------------------------------------------------- 3. Data
    copy_whole('VoidWarehouseData.java', MODERN)
    patch('VoidWarehouseData.java', DATA_CLASS_OLD, DATA_CLASS_NEW,
          '类注释补染料', targets={'1.21.11': ALL['1.21.11']})
    patch('VoidWarehouseData.java', DATA_JAVADOC_OLD, DATA_JAVADOC_NEW,
          '拆包 javadoc 补染料', targets={'1.21.11': ALL['1.21.11']})
    patch('VoidWarehouseData.java', DATA_BODY_OLD, DATA_BODY_NEW,
          '拆包归还染料', targets={'1.21.11': ALL['1.21.11']})

    # ---------------------------------------------------------- 4. Shulker
    copy_whole('VoidWarehouseShulker.java', {'26.2': ALL['26.2']})
    # 先整文件复制，再把 ColorCollection 版换成 16 个独立字段版
    copy_whole('VoidWarehouseShulker.java', PRE_COLLECTION)
    patch('VoidWarehouseShulker.java', SHULKER_IMPORT_OLD, SHULKER_IMPORT_NEW,
          'import Item', targets=PRE_COLLECTION)
    patch('VoidWarehouseShulker.java', SHULKER_DYE_OLD, SHULKER_DYE_NEW,
          'dyeOf 改用 16 个独立染料字段', targets=PRE_COLLECTION)
    # 1.21.11 的 ItemContainerContents 取内容的方法名还没改名（26.x 是 nonEmptyItemCopyStream）
    patch('VoidWarehouseShulker.java',
          '        contents.nonEmptyItemCopyStream().forEach(out::add);\n',
          '        contents.nonEmptyItemsCopy().forEach(out::add);\n',
          'ItemContainerContents 取内容的方法名（1.21.11）',
          targets={'1.21.11': ALL['1.21.11']})

    # ---------------------------------------------------------- 收尾校验
    print('\n=== 校验 ===')
    for name, base in ALL.items():
        nav = read_norm(base / 'VoidWarehouseNav.java')
        # 注意：javadoc 里会提到「不再写「1 盒 = 27 格 …」」这句话本身，
        # 所以这里只能查「还在被 lore.add 出去的字符串字面量」，不能查整份文件。
        assert '"1 盒 = ' not in nav, f'{name}: Nav 还在往 lore 里塞「1 盒 = …」'
        assert 'lore.add(VoidWarehouseText.line("1 盒' not in nav, f'{name}: Nav 的换算行还在'
        # 「一盒多大」这个词只在方法 javadoc 的那句「说明只留…」里出现过，
        # 所以按整句查（行内的「一盒多大」是「已删除」的说明，是允许的）。
        assert '说明只留「现在是什么模式 / 一盒多大' not in nav, \
            f'{name}: Nav 的 javadoc 还写着「一盒多大」'
        assert 'new ArrayList<>(5)' not in nav, f'{name}: Nav 的 lore 容量还是 5'

        data = read_norm(base / 'VoidWarehouseData.java')
        assert 'VoidWarehouseShulker.dyeOf(box)' in data, f'{name}: Data 没调用 dyeOf'

        shulker = read_norm(base / 'VoidWarehouseShulker.java')
        assert 'public static ItemStack dyeOf(ItemStack box)' in shulker, f'{name}: Shulker 缺 dyeOf'
        # 只查「真正被执行的取色代码」，不查注释（javadoc 里会提到另一种写法做对比）
        CC_CALL = 'new ItemStack(Items.DYE.pick(color), DYE_COST)'
        if name == '26.2':
            assert CC_CALL in shulker, f'{name}: 应该走 ColorCollection'
        else:
            assert CC_CALL not in shulker, f'{name}: 这个版本没有 ColorCollection'
            assert 'new ItemStack(dyeItem(color), DYE_COST)' in shulker, f'{name}: dyeOf 没换实现'
            assert 'case RED -> Items.RED_DYE;' in shulker, f'{name}: 独立字段 switch 不完整'
            assert 'case LIGHT_GRAY -> Items.LIGHT_GRAY_DYE;' in shulker, f'{name}: switch 少了浅灰'
        if name == '1.21.11':
            assert 'nonEmptyItemsCopy()' in shulker, f'{name}: ItemContainerContents 方法名没回退'
            assert 'nonEmptyItemCopyStream()' not in shulker, f'{name}: 用了 26.x 才有的方法名'

        hopper = read_norm(base / 'VoidWarehouseHopper.java')
        assert '染色盒再多吐 1 个对应染料' in hopper, f'{name}: Hopper 注释没跟上'
        print(f'  {name}: OK')

    # 26.3 自己也要过一遍（它是「源」，顺手验收）
    src_shulker = read_norm(SRC / 'VoidWarehouseShulker.java')
    assert 'new ItemStack(Items.DYE.pick(color), DYE_COST)' in src_shulker, '26.3: 应该走 ColorCollection'
    print('  26.3: OK')

    print('\n全部同步完成。')


if __name__ == '__main__':
    main()
