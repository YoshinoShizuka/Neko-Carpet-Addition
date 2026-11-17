#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「可兑表重算成本」探针装进一个版本并跑一次。

用法：
    python install_offer_probe.py <ver> install
    python install_offer_probe.py <ver> remove
    python install_offer_probe.py <ver> run

<ver> ∈ 26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11

探针（`OfferCostProbe`）用与 `SystemTest` 的 MP 峰值**完全相同**的种子
（9 合 1 每条边的 small 侧各 6400），分别量「只算 9 合 1」与「加上配方本」两段的重算耗时，
并报出其中有多少条是「仓库里本来就有这件物品」（= 合成页新增出来的那些）。
"""
import io, os, subprocess, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

TEMPLATE = os.path.join(SCRIPT_DIR, "OfferCostProbe.java.template")
NAME = "OfferCostProbe.java"

ROOTS = {
    "26.3": ("modern/26.3", ":modern:26.3"),
    "26.2": ("modern/26.2", ":modern:26.2"),
    "26.1.2": ("modern/26.1.2", ":modern:26.1.2"),
    "26.1.1": ("modern/26.1.1", ":modern:26.1.1"),
    "1.21.11": ("versions/1.21.11", ":versions:1.21.11"),
}

HOOK_OLD = """        VoidWarehouseData.noteServerTick(server.getTickCount());
"""
HOOK_NEW = HOOK_OLD + """
        // 【临时探针】可兑表重算成本，跑完删除（探针自己 server.halt）
        OfferCostProbe.run(server);
"""


def paths(ver):
    root = ROOTS[ver][0]
    return ("%s/src/main/java/neko/voidwarehouse/VoidWarehouseExtension.java" % root,
            "%s/src/main/java/neko/voidwarehouse/%s" % (root, NAME))


def read_raw(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write_as_was(p, text, had_crlf):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(text.replace("\n", "\r\n") if had_crlf else text)


def install(ver):
    ext, src = paths(ver)
    raw = read_raw(ext)
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "OfferCostProbe.run" in text:
        print("[%s] hook 已存在，跳过" % ver)
    else:
        assert text.count(HOOK_OLD) == 1, "[%s] hook 锚点不唯一" % ver
        write_as_was(ext, text.replace(HOOK_OLD, HOOK_NEW, 1), had_crlf)
        print("[%s] hook 已挂" % ver)
    shutil.copyfile(TEMPLATE, src)
    print("[%s] %s 已安装" % (ver, NAME))


def remove(ver):
    ext, src = paths(ver)
    raw = read_raw(ext)
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "OfferCostProbe.run" in text:
        assert HOOK_NEW in text, "[%s] hook 内容与预期不符" % ver
        write_as_was(ext, text.replace(HOOK_NEW, HOOK_OLD, 1), had_crlf)
        print("[%s] hook 已还原" % ver)
    if os.path.exists(src):
        os.remove(src)
        print("[%s] %s 已删除" % (ver, NAME))


def run(ver):
    log = os.path.join(SCRIPT_DIR, "probe_%s.log" % ver)
    install(ver)
    env = dict(os.environ)
    env["JAVA_HOME"] = "C:/Users/12286/.jdks/ms-25.0.4.1"
    env["TARGET_MC_VERSIONS"] = ver
    try:
        with io.open(log, "w", encoding="utf-8", newline="") as f:
            subprocess.run(["bash", "./gradlew", ROOTS[ver][1] + ":runServer", "--no-daemon"],
                           stdout=f, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
    finally:
        remove(ver)
    with io.open(log, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if "PROBE" in line:
                print(line.strip().split("[STDOUT]: ")[-1])
    print("[%s] 日志 %s" % (ver, log))


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ROOTS:
        raise SystemExit("用法: install_offer_probe.py <ver> install|remove|run")
    ver, action = sys.argv[1], sys.argv[2]
    if action == "install":
        install(ver)
    elif action == "remove":
        remove(ver)
    elif action == "run":
        run(ver)
    else:
        raise SystemExit("用法: install | remove | run")


if __name__ == "__main__":
    main()
