#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 26.3 上的「合成页 + 位置钉住」改动同步到其余四个版本。

做法：以 {@code bf377d9} 里的 26.3 为基线，用「基线 26.3 → 当前 26.3」的 unified diff
去打每一个目标版本的同名文件（这些副本本来就只差十几行平台差异）。
行尾一律先归一（去掉所有 CR）再比对，写回时按目标文件原本的风格还原。

用法：
    python sync_craftpage.py check     # 只打补丁、报 reject，不写回
    python sync_craftpage.py apply     # 打补丁并写回
"""
import io, os, subprocess, sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

BASE = "bf377d9"
FILES = ["VoidWarehouseData", "VoidWarehouseMenu", "VoidWarehouseNav",
         "VoidWarehousePageContainer", "VoidWarehouseViews"]
TARGETS = {
    "26.2": "modern/26.2",
    "26.1.2": "modern/26.1.2",
    "26.1.1": "modern/26.1.1",
    "1.21.11": "versions/1.21.11",
}
SRC = "modern/26.3/src/main/java/neko/voidwarehouse/%s.java"
TMP = "C:/tmp/sync_craftpage"


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def eol_style(raw):
    if raw.count(b"\r\r\n") > 0:
        return "\r\r\n"
    if raw.count(b"\r\n") > 0:
        return "\r\n"
    return "\n"


def repo_bytes(path):
    return sh(["git", "show", "%s:%s" % (BASE, path)]).stdout.encode("utf-8")


def text_of(raw):
    """归一成纯 LF：先把所有 CR 去掉（CRLF / CRCRLF 都退化成 LF）"""
    return raw.decode("utf-8").replace("\r", "")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    os.makedirs(TMP, exist_ok=True)
    failures = []
    for f in FILES:
        src_path = SRC % f
        old = text_of(repo_bytes(src_path))
        new = text_of(io.open(src_path, "rb").read())
        old_p = os.path.join(TMP, "old_%s.java" % f)
        new_p = os.path.join(TMP, "new_%s.java" % f)
        io.open(old_p, "w", encoding="utf-8", newline="").write(old)
        io.open(new_p, "w", encoding="utf-8", newline="").write(new)
        patch_p = os.path.join(TMP, "%s.diff" % f)
        with io.open(patch_p, "w", encoding="utf-8", newline="") as fh:
            fh.write(sh(["diff", "-u", old_p, new_p]).stdout)

        for name, root in TARGETS.items():
            tgt_rel = "%s/src/main/java/neko/voidwarehouse/%s.java" % (root, f)
            raw = io.open(tgt_rel, "rb").read()
            style = eol_style(raw)
            cur = text_of(raw)
            work_p = os.path.join(TMP, "tgt_%s_%s.java" % (name.replace(".", "_"), f))
            io.open(work_p, "w", encoding="utf-8", newline="").write(cur)
            # -l 忽略空白差异（各版本的缩进 / 空行本来就有一点点不同）
            r = sh(["patch", "-l", "-p0", "--fuzz=3", "--no-backup-if-mismatch",
                    "-i", patch_p, work_p])
            rej = work_p + ".rej"
            status = "ok"
            if r.returncode != 0:
                status = "PATCH_EXIT(%d)" % r.returncode
            if os.path.exists(rej):
                status = "REJECT(%d)" % io.open(rej, encoding="utf-8").read().count("@@")
            elif "FAILED" in r.stdout:
                status = "MISMATCH"
            elif "fuzz" in r.stdout or "offset" in r.stdout:
                status = "ok(fuzz/offset)"
            print("%-30s %-8s %-14s %s" % (f, name, status, r.stdout.strip().replace("\n", " | ")[:150]))
            if not status.startswith("ok"):
                failures.append((f, name))
                if os.path.exists(rej):
                    os.rename(rej, os.path.join(TMP, "rej_%s_%s.txt" % (name.replace(".", "_"), f)))
                continue
            if mode == "apply":
                out = io.open(work_p, encoding="utf-8", newline="").read()
                if style != "\n":
                    out = out.replace("\n", style)
                io.open(tgt_rel, "wb").write(out.encode("utf-8"))
    print("\n失败 %d 处%s" % (len(failures), "" if not failures else ": " + str(failures)))
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
