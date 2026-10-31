#!/usr/bin/env python3
"""校验「服务端回执」与「客户端镜像」是同一套协议。

两个仓库里各有一份 `neko/voidwarehouse/restock/PrinterReturnResultPayload.java`：
服务端原件与客户端镜像<b>同包名同类名</b>，靠「逐字节等价」保证解码对得上。
这个脚本把它变成机器判据，而不是靠人眼比对：

  1. 抽出两边的 **CODEC 块 + 状态码常量 + 字段清单**（去掉注释与空行后逐行比对）；
  2. 断言 `record` 的字段顺序与名字完全相同（顺序错 = 线上必崩）；
  3. 断言三个 `MAX_ENTRIES` / 列表 codec 的类型参数一致。

用法：
    python check_return_protocol_parity.py --printer ../litematica-printer
"""
import argparse
import io
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))

SERVER_REL = os.path.join("modern/26.3/src/main/java/neko/voidwarehouse/restock/PrinterReturnResultPayload.java")
MIRROR_REL = os.path.join("src/main/java/neko/voidwarehouse/restock/PrinterReturnResultPayload.java")


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")


def strip_comments(text):
    """去掉 // 行注释、/* */ 块注释与行尾空白 —— 只留代码。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split("\n"):
        line = re.sub(r"//.*$", "", line).rstrip()
        if not line.strip():
            continue
        # 预处理守卫与占位符不属于协议内容
        if line.strip().startswith("//#"):
            continue
        out.append(line.strip())
    return out


def record_fields(lines):
    """找 record 的字段清单（顺序敏感）。"""
    for line in lines:
        if line.startswith("public record PrinterReturnResultPayload("):
            return line
    # 跨行写法：拼到 ')' 为止
    joined = ""
    for line in lines:
        joined += line
        if "public record PrinterReturnResultPayload(" in joined and ")" in joined.split("(", 1)[1]:
            return joined
    return None


def pick(lines, predicate):
    return [line for line in lines if predicate(line)]


def compare(name, a, b):
    if a == b:
        print("  [ok]   %s（%d 行）" % (name, len(a)))
        return True
    print("  [BAD]  %s 不一致" % name)
    only_a = [x for x in a if x not in b]
    only_b = [x for x in b if x not in a]
    if only_a:
        print("         服务端独有：")
        for line in only_a[:8]:
            print("           - " + line)
    if only_b:
        print("         客户端独有：")
        for line in only_b[:8]:
            print("           + " + line)
    if not only_a and not only_b:
        print("         行序不同（同样内容、顺序不同 —— 字段顺序变了就是协议变了）")
        for i, (x, y) in enumerate(zip(a, b)):
            if x != y:
                print("         第 %d 行：服务端 %r / 客户端 %r" % (i + 1, x, y))
                break
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--printer", default=os.path.join(os.path.dirname(ROOT), "litematica-printer"),
                    help="litematica-printer 仓库根目录")
    args = ap.parse_args()

    server_path = os.path.join(ROOT, SERVER_REL)
    mirror_path = os.path.join(args.printer, MIRROR_REL)
    for path in (server_path, mirror_path):
        if not os.path.isfile(path):
            raise SystemExit("!! 找不到 %s" % path)

    server = strip_comments(read(server_path))
    mirror = strip_comments(read(mirror_path))

    ok = True
    print("[1] record 字段清单（顺序敏感）")
    fs, fm = record_fields(server), record_fields(mirror)
    if fs is None or fm is None:
        raise SystemExit("!! 有一边找不到 record 声明")
    ok &= compare("字段清单", [fs], [fm])

    print("[2] CODEC 块 + 列表 codec 定义")
    ok &= compare("channel / 状态码 / MAX_ENTRIES / 列表 codec",
                  pick(server, lambda l: l.startswith(("public static final", "private static final",
                                                       "ByteBufCodecs", "IDS_CODEC", "COUNTS_CODEC",
                                                       "REMAININGS_CODEC"))
                       and "CODEC = StreamCodec.composite" not in l),
                   pick(mirror, lambda l: l.startswith(("public static final", "private static final",
                                                        "ByteBufCodecs", "IDS_CODEC", "COUNTS_CODEC",
                                                        "REMAININGS_CODEC"))
                        and "CODEC = StreamCodec.composite" not in l))

    print("[3] composite 的字段顺序（编解码两侧必须一模一样）")
    def composite(lines):
        return [line for line in lines if "::nonce" in line or "::status" in line
                or "::returned" in line or "::itemIds" in line or "::counts" in line
                or "::remainings" in line or line.strip() == "PrinterReturnResultPayload::new"]
    ok &= compare("composite 字段", composite(server), composite(mirror))

    print("[4] 便捷构造 of(...) 的空清单口径")
    def of_block(lines):
        out, grab = [], False
        for line in lines:
            if "public static PrinterReturnResultPayload of(" in line:
                grab = True
            if grab:
                out.append(line)
                if line.endswith("}"):
                    break
        return out
    ok &= compare("of(...)", of_block(server), of_block(mirror))

    print("[5] 文本类断言：客户端包里必须出现提示前缀")
    notice = os.path.join(args.printer, "src/main/java/me/aleksilassila/litematica/printer/"
                                        "integration/voidwarehouse/VoidWarehouseReturnNotice.java")
    if os.path.isfile(notice):
        text = read(notice)
        has_prefix = '[虚空仓库] ' in text
        has_remaining = "仓库共" in text
        print("  %s 提示前缀存在 / %s 「仓库共」存在" % ("[ok]" if has_prefix else "[BAD]",
                                                     "[ok]" if has_remaining else "[BAD]"))
        ok &= has_prefix and has_remaining
    else:
        print("  [warn] 没找到 VoidWarehouseReturnNotice.java，跳过第 5 项")

    print()
    if not ok:
        print("=== 协议不一致：客户端与服务端必须同版本 ===")
        sys.exit(1)
    print("=== 两端协议逐行一致，回执可以互通 ===")


if __name__ == "__main__":
    main()
