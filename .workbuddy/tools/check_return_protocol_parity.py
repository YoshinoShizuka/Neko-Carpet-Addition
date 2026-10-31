#!/usr/bin/env python3
"""校验「服务端的返还协议」与「客户端镜像」是同一套。

两个仓库里各有一份 `neko/voidwarehouse/restock/` 下的同名类：
服务端原件与客户端镜像<b>同包名同类名</b>，靠「逐字节等价」保证解码对得上。
这个脚本把它变成机器判据，而不是靠人眼比对：

  1. `PrinterReturnResultPayload`（回执：nonce + status + returned，销账用）
  2. `PrinterReturnDetailPayload`（明细：nonce + 三份平行清单，聊天提示用）
  3. 客户端提示类里必须有 `[虚空仓库] ` 与 `仓库共`

每个 payload 检查四项：record 字段清单（顺序敏感）/ 通道与状态码常量 /
composite 的字段顺序 / 便捷构造 `of(...)`。

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

SERVER_PKG = os.path.join("modern/26.3/src/main/java/neko/voidwarehouse/restock")
MIRROR_PKG = os.path.join("src/main/java/neko/voidwarehouse/restock")

PAYLOADS = ["PrinterReturnResultPayload", "PrinterReturnDetailPayload"]

NOTICE_REL = os.path.join("src/main/java/me/aleksilassila/litematica/printer/"
                                    "integration/voidwarehouse/VoidWarehouseReturnNotice.java")


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


def record_fields(lines, cls):
    """找 record 的字段清单（顺序敏感）。跨行写法拼到括号闭合为止。"""
    head = "public record %s(" % cls
    joined = ""
    for line in lines:
        joined += line
        if head in joined:
            after = joined.split(head, 1)[1]
            if ")" in after:
                return head + after.split(")", 1)[0] + ")"
    return None


def composite(lines):
    """composite 里的字段绑定行（含尾部 ::new 那行）。"""
    return [line for line in lines
            if "::" in line and (line.endswith(",") or line.endswith("::new"))]


def of_block(lines, cls):
    out, grab = [], False
    for line in lines:
        if ("public static %s of(" % cls) in line:
            grab = True
        if grab:
            out.append(line)
            if line.endswith("}"):
                break
    return out


def constants(lines):
    return [line for line in lines
            if line.startswith(("public static final", "private static final",
                                "ByteBufCodecs", "IDS_CODEC", "COUNTS_CODEC", "REMAININGS_CODEC"))
            and "CODEC = StreamCodec.composite" not in line]


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


def check_payload(cls, server_path, mirror_path):
    print("== %s" % cls)
    if not os.path.isfile(mirror_path):
        print("  [BAD]  客户端镜像不存在：%s" % mirror_path)
        return False
    server = strip_comments(read(server_path))
    mirror = strip_comments(read(mirror_path))
    ok = True

    fs, fm = record_fields(server, cls), record_fields(mirror, cls)
    if fs is None or fm is None:
        print("  [BAD]  有一边找不到 record 声明")
        return False
    ok &= compare("record 字段清单（顺序敏感）", [fs], [fm])
    ok &= compare("通道 / 常量 / 列表 codec", constants(server), constants(mirror))
    ok &= compare("composite 字段顺序", composite(server), composite(mirror))
    ok &= compare("便捷构造 of(...)", of_block(server, cls), of_block(mirror, cls))
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--printer", default=os.path.join(os.path.dirname(ROOT), "litematica-printer"),
                    help="litematica-printer 仓库根目录")
    args = ap.parse_args()

    ok = True
    for cls in PAYLOADS:
        name = cls + ".java"
        ok &= check_payload(cls, os.path.join(ROOT, SERVER_PKG, name),
                            os.path.join(args.printer, MIRROR_PKG, name))

    print("== 客户端提示类")
    notice = os.path.join(args.printer, NOTICE_REL)
    if os.path.isfile(notice):
        text = read(notice)
        has_prefix = "[虚空仓库] " in text
        has_remaining = "仓库共" in text
        has_total = "余料已退回" in text
        print("  %s 提示前缀 / %s 「仓库共」 / %s 「余料已退回」"
              % ("[ok]" if has_prefix else "[BAD]",
                 "[ok]" if has_remaining else "[BAD]",
                 "[ok]" if has_total else "[BAD]"))
        ok &= has_prefix and has_remaining and has_total
    else:
        print("  [BAD]  没找到 VoidWarehouseReturnNotice.java")
        ok = False

    print()
    if not ok:
        print("=== 协议不一致：客户端与服务端必须同版本 ===")
        sys.exit(1)
    print("=== 两端协议逐行一致，回执与明细可以互通 ===")


if __name__ == "__main__":
    main()
