#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 `isBoxPart` 的文档从「一律不装盒」改成「材料账先预留」。

用法：
    python patch_isboxpart_doc.py

背景：第一版是把壳 / 箱整个挡在装盒之外（点一下给散货）。改成「预留材料」之后，
盒子照给，只是材料账要先扣掉「马上要离库的那批货」—— 所以 javadoc 的结论段必须跟着改，
否则下一个人会照着旧文档把判据又写回「直接挡掉」。

🔴 逐行处理、按每行自己的换行写回：26.2 / 26.1.x / 1.21.11 是 CRCRLF，
   整份重写会炸 diff（这些文件都是定点改动）。
"""
import io
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

FILES = [
    "modern/26.3/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "modern/26.2/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "modern/26.1.2/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "modern/26.1.1/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
    "versions/1.21.11/src/main/java/neko/voidwarehouse/VoidWarehouseShulker.java",
]

HEAD_OLD = "<h2>为什么这两个也要单独挡一道</h2>"
HEAD_NEW = "<h2>为什么这两样的材料账要单独算</h2>"

PARA_OLD_KEY = "<p>所以这两样<b>一律不装盒</b>"
PARA_NEW = [
    "     * <p>所以取这两样时，材料账必须先<b>把「马上要离库的那批货」刨掉</b>"
    "（{@link VoidWarehouseData#hasBoxMaterials(BoxMaterials, ItemStack, BigInteger)}",
    "     * 与 {@link VoidWarehouseData#maxBoxesUpTo(long, ItemStack)}）—— 盒子照给，"
    "但成本只能从那批货<b>之外</b>的库存里出。",
    "     * {@link #pack} 绕过了原版 {@code canFitInsideContainerItems}，"
    "凡是能当材料的东西都得自己把账算清楚。</p>",
]


def split_keepends(raw):
    """按 \\r\\n / \\n 切行，保留每行自己的结尾。"""
    out = []
    i, n = 0, len(raw)
    while i < n:
        j = raw.find(b"\n", i)
        if j < 0:
            out.append(raw[i:])
            break
        j += 1
        # CRCRLF 也算一行（\\r\\r\\n 的最后一个 \\n 就是上面找到的那个）
        out.append(raw[i:j])
        i = j
    return out


def eol_of(line):
    if line.endswith(b"\r\r\n"):
        return b"\r\r\n"
    if line.endswith(b"\r\n"):
        return b"\r\n"
    if line.endswith(b"\n"):
        return b"\n"
    return b"\n"


def patch(path):
    raw = open(path, "rb").read()
    if HEAD_NEW.encode("utf-8") in raw:
        print("  = 已是新文档，跳过 " + path)
        return True
    lines = split_keepends(raw)
    head_hit = para_hit = -1
    for i, line in enumerate(lines):
        text = line.decode("utf-8", "replace")
        if HEAD_OLD in text and head_hit < 0:
            head_hit = i
        if PARA_OLD_KEY in text and para_hit < 0:
            para_hit = i
    if head_hit < 0 or para_hit < 0:
        print("  ✗ 找不到文档锚点（head=%d para=%d）%s" % (head_hit, para_hit, path))
        return False

    # 段落固定是 3 行（当初插入时就是 3 行），先断言这一点再替换
    tail = "".join(l.decode("utf-8", "replace") for l in lines[para_hit:para_hit + 3])
    if "凡是「盒子自己的材料」都不能从「正要装进盒子的那批货」里出" not in tail:
        print("  ✗ 段落不是预期的 3 行，人工核对 " + path)
        return False

    eol = eol_of(lines[para_hit])
    lines[head_hit] = lines[head_hit].decode("utf-8").replace(HEAD_OLD, HEAD_NEW) \
        .encode("utf-8")
    lines[para_hit:para_hit + 3] = [(s.encode("utf-8") + eol) for s in PARA_NEW]
    open(path, "wb").write(b"".join(lines))
    print("  ✓ 文档已改为「预留材料」口径 " + path)
    return True


def main():
    ok = True
    for rel in FILES:
        ok = patch(os.path.join(ROOT, rel)) and ok
    if not ok:
        print("失败：有文件没改到")
        return 1
    print("全部完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
