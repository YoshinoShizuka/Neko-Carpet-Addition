"""在指定目录树里找出「哪个 jar 定义了某条规则」。

用法：python find_rule_source.py <搜索根目录> <关键词...>
原理：class 文件常量池里的字符串是 modified UTF-8，直接按原始字节搜即可；
命中后按 .class 条目分组报出，并顺带打印 fabric.mod.json 里的 mod id/name。
"""
import sys
import os
import zipfile

roots = sys.argv[1].split(os.pathsep)
needles = [n.encode("utf-8") for n in sys.argv[2:]]
if not needles:
    print("needle required")
    sys.exit(2)

# 排除的第三方内嵌 jar（fabric-api 之类）不跳过，一并报告
hits = []
for root in roots:
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if not fn.endswith(".jar"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                zf = zipfile.ZipFile(path)
            except Exception as e:
                print(f"!! 打不开 {path}: {e}")
                continue
            with zf:
                matched = []
                for info in zf.infolist():
                    if info.is_dir():
                        continue
                    try:
                        data = zf.read(info)
                    except Exception:
                        continue
                    if any(n in data for n in needles):
                        matched.append(info.filename)
                if not matched:
                    continue
                modid = ""
                try:
                    import json
                    fmj = json.loads(zf.read("fabric.mod.json").decode("utf-8"))
                    modid = f"{fmj.get('id')} / {fmj.get('name')}"
                except KeyError:
                    modid = "(无 fabric.mod.json → 可能是内嵌 jar 或 forge 模组)"
                except Exception:
                    modid = "(fabric.mod.json 解析失败)"
                hits.append((path, modid, matched))

print(f"命中 {len(hits)} 个 jar：")
for path, modid, matched in hits:
    print(f"\n=== {path}")
    print(f"    mod: {modid}")
    for m in matched[:40]:
        print(f"    · {m}")
    if len(matched) > 40:
        print(f"    · …还有 {len(matched) - 40} 个")
if not hits:
    print("（无命中）")
