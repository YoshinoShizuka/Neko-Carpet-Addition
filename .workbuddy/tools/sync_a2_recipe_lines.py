# -*- coding: utf-8 -*-
"""把 A2（配方本逐条拆分）同步到其余四个版本。

26.3 已经手改完并跑过真服务端验收；这里是同一批改动，锚点取自 26.2 的原文
（26.2 / 26.1.2 / 26.1.1 三个文件 md5 完全相同，1.21.11 只差 Identifier 那两行，
不在本次任何锚点范围内）。

用法：python sync_a2_recipe_lines.py  [--check]
"""
import io, os, sys, shutil

ROOT = "C:/Users/12286/WorkBuddy/MC/carpet-Neko-Addition"
VERSIONS = [
    "modern/26.3",
    "modern/26.2",
    "modern/26.1.2",
    "modern/26.1.1",
    "versions/1.21.11",
]
REL = "src/main/java/neko/voidwarehouse"
CODEC_SRC = "C:/tmp/a2test/neko/voidwarehouse/RecipeLinesCodec.java"

# ---------------------------------------------------------------- 锚点：常量块
OLD_CONST = """    /**
     * 配方本在存档里的字段名。内容是一整条用 {@code '\\n'} 拼起来的文本，一条配方一行。
     *
     * <p><b>为什么是一个字符串而不是字符串列表</b>：这里必须留神
     * {@link #ITEMS_CODEC} 那段注释讲的同一个坑 —— {@code Codec.listOf()} 的语义是
     * 「全成或全不成」，只要有一条读不懂，整个 {@code VoidWarehouseData} 就解码失败，
     * 上层会把它当成「文件不存在」，<b>静默造一个空仓库</b>并把旧文件覆盖掉。
     * 而一个 {@code Codec.STRING} 字段永远不会失败，坏掉的只可能是里面某一<b>行</b>，
     * 由 {@link VoidWarehouseRecipes#decodeAll} 逐行静默跳过。
     * 用字段失败换行失败 —— 这个交换在仓库这件事上是唯一可接受的方向。</p>
     */
    private static final String RECIPES_KEY = "learned_recipes";

    /** 配方行的分隔符（配方文本本身不会含换行） */
    private static final char RECIPE_SEPARATOR = '\\n';
"""

NEW_CONST = """    /**
     * 配方本的<b>当前</b>字段名：一个字符串列表，一条配方一个元素。
     *
     * <p>历史上这里是「全部配方拼成一个字符串」，卡在 {@code StringTag} 的
     * {@code writeUTF} 硬上限 65,535 字节上 —— 实测 975 条顶格，976 条开始就写不出去，
     * 而且失败是静默的（{@code NbtIo$StringFallbackDataOutput} 把内容换成空串，键还在）。
     * 改成一条一个元素之后，单个元素只有几十字节，这个上限再也碰不到；
     * 列表本身的元素计数是 int，不存在上限。</p>
     *
     * <p>为什么不能直接用 {@code Codec.STRING.listOf()}：它「全成或全不成」，实测只要有一个
     * 元素读不出来就整体失败，进而整个 {@code VoidWarehouseData} 解码失败，上层当成
     * 「文件不存在」<b>静默造空仓库并覆盖旧档</b>。这里用的是
     * {@link RecipeLinesCodec#CODEC}，逐元素独立解析、坏元素只丢它自己。</p>
     */
    private static final String RECIPES_LINES_KEY = "learned_recipes_lines";

    /**
     * 配方本的<b>旧</b>字段名（单字符串、{@code '\\n'} 分隔）。<b>只读，不再写出。</b>
     *
     * <p>留着它只为一件事：老存档还能读。读完第一次写盘就自动升级成
     * {@link #RECIPES_LINES_KEY}，不需要任何迁移命令，也不需要服主做任何事。</p>
     */
    private static final String RECIPES_KEY = "learned_recipes";

    /** 旧格式里配方行的分隔符（配方文本本身不含换行） */
    private static final char RECIPE_SEPARATOR = '\\n';
"""

# ---------------------------------------------------------------- 锚点：CODEC
OLD_CODEC = """    public static final Codec<VoidWarehouseData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ITEMS_CODEC
                    .optionalFieldOf(ITEMS_KEY, List.of())
                    .forGetter(VoidWarehouseData::decodedEntries),
            // 见 RECIPES_KEY 的注释：这里刻意用「一个字符串」而不是「字符串列表」，
            // 让坏数据只影响它自己那一行，不至于把整个仓库拖成空壳。
            Codec.STRING
                    .optionalFieldOf(RECIPES_KEY, "")
                    .forGetter(VoidWarehouseData::recipeTextsBlob)
    ).apply(instance, VoidWarehouseData::new));
"""

NEW_CODEC = """    public static final Codec<VoidWarehouseData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ITEMS_CODEC
                    .optionalFieldOf(ITEMS_KEY, List.of())
                    .forGetter(VoidWarehouseData::decodedEntries),
            // 新格式：一条配方一个元素。列表本身不设上限，单个元素也远不到 65535
            RecipeLinesCodec.CODEC
                    .optionalFieldOf(RECIPES_LINES_KEY)
                    .forGetter(VoidWarehouseData::recipeTextsLines),
            // 旧格式：只读不写（getter 恒 empty ⇒ OptionalFieldCodec 一个字节都不写这个键）。
            // 见 RECIPES_KEY 的注释：留着它只是为了让老存档还能读进来。
            Codec.STRING
                    .optionalFieldOf(RECIPES_KEY)
                    .forGetter(data -> Optional.<String>empty())
    ).apply(instance, VoidWarehouseData::fromCodec));

    /**
     * 两种格式合流的地方：新格式优先，没有新字段才回落到老的单字符串。
     *
     * <p>「两者并存」是升级过程中的正常中间态（写了一半崩了、或者用备份回滚过），
     * 此时以新格式为准 —— 新格式能装下的条数严格更多，信任它。</p>
     */
    private static VoidWarehouseData fromCodec(List<Decoded> items,
                                               Optional<List<String>> recipeLines,
                                               Optional<String> legacyRecipeBlob) {
        if (recipeLines.isPresent()) {
            return new VoidWarehouseData(items, recipeLines.get());
        }
        return new VoidWarehouseData(items, legacyRecipeBlob.orElse(""));
    }
"""

# ---------------------------------------------------------------- 锚点：构造器
OLD_CTOR_JAVADOC_2X = """    /**
     * 从解码结果建仓库。
     *
     * <p>好条目进仓库（同物品合并）；坏条目原文留在 {@link #brokenEntries} 里，
     * 下次写盘时原样写回。<b>注意这里绝不能因为「一条都读不出来」就抛异常</b> ——
     * 抛异常会让上层把整个仓库当成读取失败，那才是真正会丢数据的路径。
     * 「读到空」这种情况由 {@link VoidWarehouse#data} 那一层的指纹校验负责拦截。</p>
     */
    /**
     * 从解码结果建仓库。
"""

NEW_CTOR_JAVADOC_2X = """    /**
     * 从解码结果建仓库（<b>当前格式</b>：配方一条一个元素）。
"""

OLD_CTOR = """    VoidWarehouseData(List<Decoded> loaded, String recipeBlob) {
"""

NEW_CTOR = """    VoidWarehouseData(List<Decoded> loaded, List<String> recipeLines) {
"""

OLD_CTOR_BODY = """        // 配方本逐行解码：坏行静默跳过（只可能来自手工改档），绝不牵连仓库本身。
        // 回填时用的是<b>解码后的规范文本</b>而不是原始行 —— 于是坏行当场丢掉，
        // 不会再出现「数得出 5 条、实际只有 4 条能用」这种幽灵计数。
        if (recipeBlob != null && !recipeBlob.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (String line : recipeBlob.split(String.valueOf(RECIPE_SEPARATOR))) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            this.recipes = List.copyOf(VoidWarehouseRecipes.decodeAll(lines));
"""

NEW_CTOR_BODY = """        // 配方逐条解码：坏行静默跳过（只可能来自手工改档），绝不牵连仓库本身。
        // 回填时用的是<b>解码后的规范文本</b>而不是原始行 —— 于是坏行当场丢掉，
        // 不会再出现「数得出 5 条、实际只有 4 条能用」这种幽灵计数。
        if (recipeLines != null && !recipeLines.isEmpty()) {
            this.recipes = List.copyOf(VoidWarehouseRecipes.decodeAll(recipeLines));
"""

# 构造器收尾：原 ctor 的 } 之后补上旧的 String 构造器 + 拆分方法
OLD_AFTER_CTOR = """        // 这里不排序：载入进来的顺序可能是旧文件的乱序，但第一次读之前
        // ensureSorted() 一定会把它整成创造栏顺序（见 sortedGeneration 的初值 -1）。
    }

    /** 把 {@link #recipeTexts} 重新解成对象与分组（学一条、读档各调一次；条数很少，代价可忽略） */"""

NEW_AFTER_CTOR = """        // 这里不排序：载入进来的顺序可能是旧文件的乱序，但第一次读之前
        // ensureSorted() 一定会把它整成创造栏顺序（见 sortedGeneration 的初值 -1）。
    }

    /**
     * 从解码结果建仓库（<b>旧格式</b>：全部配方拼成一个 {@code '\\n'} 分隔的字符串）。
     *
     * <p>只用于读老存档。写盘永远走新格式（见 {@link #RECIPES_KEY}）。</p>
     */
    VoidWarehouseData(List<Decoded> loaded, String legacyRecipeBlob) {
        this(loaded, splitLegacyBlob(legacyRecipeBlob));
    }

    /** 把旧格式的单字符串拆成一行一条（空行丢掉）。 */
    private static List<String> splitLegacyBlob(String legacyRecipeBlob) {
        if (legacyRecipeBlob == null || legacyRecipeBlob.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : legacyRecipeBlob.split(String.valueOf(RECIPE_SEPARATOR))) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 把 {@link #recipeTexts} 重新解成对象与分组（学一条、读档各调一次；条数很少，代价可忽略） */"""

# ---------------------------------------------------------------- 锚点：getter
OLD_GETTER = """    /** 配方本在存档里怎么写的（{@link #CODEC} 的 getter）：一条一行 */
    private String recipeTextsBlob() {
        if (this.recipeTexts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : this.recipeTexts) {
            if (sb.length() > 0) {
                sb.append(RECIPE_SEPARATOR);
            }
            sb.append(line);
        }
        return sb.toString();
    }
"""

NEW_GETTER = """    /**
     * 配方本在存档里怎么写的（{@link #CODEC} 的 getter）：<b>一条一个元素</b>。
     *
     * <p>空配方本返回 {@link Optional#empty()} 而不是空列表 —— 这样新档里压根不会有这个键，
     * 存档更干净，也省得区分「从没学过」和「学了又清空」。</p>
     */
    private Optional<List<String>> recipeTextsLines() {
        if (this.recipeTexts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(this.recipeTexts));
    }
"""

# ---------------------------------------------------------------- 锚点：撞顶告警
OLD_CAP_FIELD = """    private int recipeGeneration;
"""

NEW_CAP_FIELD = """    private int recipeGeneration;

    /**
     * 「配方本满了」的告警打过了吗。
     *
     * <p>满本是一个<b>持续状态</b>，不是一次事件：玩家之后每合成一次都会再撞到一次上限。
     * 每撞一次打一条日志会把 latest.log 刷爆，所以只报第一次。
     * 这一条告警本身也是补的旧漏 —— 以前撞上限是彻底静默的，玩家只会觉得
     * 「怎么合成过它还是不能从仓库取」。</p>
     */
    private boolean capWarned;
"""

OLD_CAP = """        if (this.recipeTexts.size() >= VoidWarehouseRecipeBook.MAX_RECIPES) {
            return false;
        }
"""

NEW_CAP = """        if (this.recipeTexts.size() >= VoidWarehouseRecipeBook.MAX_RECIPES) {
            if (!this.capWarned) {
                this.capWarned = true;
                LOGGER.warn("虚空仓库配方本已满（上限 {} 条），新配方不再记录。"
                                + "现有 {} 条仍可正常用；要记更多请调大 VoidWarehouseRecipeBook.MAX_RECIPES",
                        VoidWarehouseRecipeBook.MAX_RECIPES, this.recipeTexts.size());
            }
            return false;
        }
"""

OPS = [
    ("常量块", OLD_CONST, NEW_CONST),
    ("CODEC", OLD_CODEC, NEW_CODEC),
    ("构造器 javadoc 去重", OLD_CTOR_JAVADOC_2X, NEW_CTOR_JAVADOC_2X),
    ("构造器签名", OLD_CTOR, NEW_CTOR),
    ("构造器 body", OLD_CTOR_BODY, NEW_CTOR_BODY),
    ("旧构造器 + splitLegacyBlob", OLD_AFTER_CTOR, NEW_AFTER_CTOR),
    ("getter", OLD_GETTER, NEW_GETTER),
    ("撞顶告警字段", OLD_CAP_FIELD, NEW_CAP_FIELD),
    ("撞顶告警日志", OLD_CAP, NEW_CAP),
]


def detect_eol(raw):
    """这几个版本的文件在仓库里存的是 '\r\r\n'（双 CR，历史遗留，已提交）。

    不改它 —— 一旦顺手「修正」成 '\r\n'，整文件 2600 多行全部变成改动，
    真正的差异就被淹了。这里只做「探测 + 原样还原」。
    """
    if "\r\r\n" in raw:
        return "\r\r\n"
    if "\r\n" in raw:
        return "\r\n"
    return "\n"


def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    return raw, detect_eol(raw)


def normalize(raw):
    """抹掉所有 CR，得到纯 LF 文本（'\r\r\n' 也能一次归一）。"""
    return raw.replace("\r", "")


def write(p, lf_text, eol):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(lf_text.replace("\n", eol))


def main():
    check_only = "--check" in sys.argv
    codec_body = normalize(read(CODEC_SRC)[0])
    total_ok = 0
    for ver in VERSIONS:
        target = os.path.join(ROOT, ver, REL, "VoidWarehouseData.java")
        raw, eol = read(target)
        text = normalize(raw)

        if "RECIPES_LINES_KEY" in text:
            print("[skip] %-18s 已经同步过" % ver)
            total_ok += 1
            continue

        problems = []
        for name, old, _new in OPS:
            n = text.count(old)
            if n != 1:
                problems.append("%s 锚点命中 %d 次" % (name, n))
        if problems:
            print("[FAIL] %-18s %s" % (ver, "; ".join(problems)))
            continue
        if check_only:
            print("[dry ] %-18s 9 个锚点全部命中 1 次（eol=%r）" % (ver, eol))
            continue

        for _name, old, new in OPS:
            text = text.replace(old, new, 1)
        write(target, text, eol)

        codec_dst = os.path.join(ROOT, ver, REL, "RecipeLinesCodec.java")
        if not os.path.exists(codec_dst):
            io.open(codec_dst, "w", encoding="utf-8", newline="\n").write(codec_body)
        print("[ ok ] %-18s 9 处改动 + RecipeLinesCodec.java" % ver)
        total_ok += 1

    print("\n完成 %d/%d" % (total_ok, len(VERSIONS)))


if __name__ == "__main__":
    main()
