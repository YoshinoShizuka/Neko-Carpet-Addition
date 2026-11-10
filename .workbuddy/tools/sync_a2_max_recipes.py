# -*- coding: utf-8 -*-
"""把 MAX_RECIPES 从 512 放宽到 2048（A2 之后存档不再是瓶颈，见 A2 文档）。

26.3 已手改；这里补其余四个版本。锚点取自 26.2 原文（三个版本 md5 相同，
1.21.11 只差 Identifier 那两行，不在锚点范围内）。
"""
import io, os

ROOT = "C:/Users/12286/WorkBuddy/MC/carpet-Neko-Addition"
VERSIONS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]
REL = "src/main/java/neko/voidwarehouse/VoidWarehouseRecipeBook.java"

OLD = """    /**
     * 配方本最多记多少条。
     *
     * <p>编码后的文本按内容去重，所以条数的天然上限就是「这台服务器上真被合成过的不同配方数」。
     * 原版大约 1500 条，加上模组也就几千 —— 512 是一个偏保守的界：
     * 正常情况下永远撞不到，撞到了说明配方本在被人拿奇怪的方式灌，
     * 那就停止记录（而不是让存档无限长大）。</p>
     */
    public static final int MAX_RECIPES = 512;
"""

NEW = """    /**
     * 配方本最多记多少条。
     *
     * <p>这个数现在<b>只受内存与重建代价约束</b>，不再受存储格式约束（见
     * {@code VoidWarehouseData} 里 {@code learned_recipes_lines} 的注释：一条一个元素之后，
     * 单个 {@code StringTag} 的 65535 字节上限再也碰不到）。</p>
     *
     * <p>为什么还要有个上限：每学一条 {@code learn()} 都会把整本重新解一遍
     * （O(N²) 的累计代价，实测 512 条 56.8 ms、2048 条 439~489 ms），
     * 而仓库又是个会被玩家不断触发的热路径。真撞到上限说明配方本在被人用奇怪的方式灌，
     * 那就停止记录，而不是让服务器越跑越慢。</p>
     *
     * <p><b>2048 是怎么来的</b>：原版可学配方约 1500 条，重模组包会更多；
     * 2048 在「够用」和「代价可控」之间。历史上的 512 是错的 ——
     * 那个值连原版都可能撞到，而且撞到之后是<b>静默不记</b>，玩家完全不知道。</p>
     */
    public static final int MAX_RECIPES = 2048;
"""


def detect_eol(raw):
    if "\r\r\n" in raw:
        return "\r\r\n"
    if "\r\n" in raw:
        return "\r\n"
    return "\n"


for ver in VERSIONS:
    p = os.path.join(ROOT, ver, REL)
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    eol = detect_eol(raw)
    text = raw.replace("\r", "")
    if "MAX_RECIPES = 2048" in text:
        print("[skip] %-18s 已是 2048" % ver)
        continue
    n = text.count(OLD)
    if n != 1:
        print("[FAIL] %-18s 锚点命中 %d 次" % (ver, n))
        continue
    text = text.replace(OLD, NEW, 1)
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", eol))
    print("[ ok ] %-18s 512 -> 2048（eol=%r）" % (ver, eol))
