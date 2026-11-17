#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「配方本浏览 / 删除」自检装到 / 摘出任意一个版本，并可直接跑。

用法：
    python install_recipe_check.py <ver> install
    python install_recipe_check.py <ver> remove
    python install_recipe_check.py <ver> run     # 装 → runServer → 摘 → 打印结果
    python install_recipe_check.py all run       # 五版本依次跑

<ver> ∈ 26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11

钩子挂在 VoidWarehouseExtension#onTick 的 noteServerTick 之后（任何提前 return 之前），
与 install_interaction_check.py 同一处锚点 —— 两者不能同时装。
换行符坑：一律先归一 LF 再匹配、写回按原样。
"""
import io, os, subprocess, sys, shutil

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
os.chdir(ROOT)

TEMPLATE = os.path.join(SCRIPT_DIR, "RecipeBrowseCheck.java.template")
NAME = "VoidWarehouseRecipeBrowseCheck.java"

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
        // 【临时自检】配方本浏览 / 删除，跑完删除（finish() 会 server.halt 停服）
        VoidWarehouseRecipeBrowseCheck.run(server);
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
    if "VoidWarehouseRecipeBrowseCheck.run" in text:
        print("[%s] hook 已存在，跳过" % ver)
    else:
        assert "VoidWarehouseInteractionCheck.run" not in text, \
            "[%s] 另一份自检的 hook 还挂着，先跑 install_interaction_check.py %s remove" % (ver, ver)
        assert HOOK_OLD in text, "[%s] hook 锚点不在（Extension 结构变了？）" % ver
        assert text.count(HOOK_OLD) == 1, "[%s] hook 锚点出现多次，人工核对" % ver
        write_as_was(ext, text.replace(HOOK_OLD, HOOK_NEW, 1), had_crlf)
        print("[%s] hook 已挂" % ver)
    shutil.copyfile(TEMPLATE, src)
    print("[%s] %s 已安装" % (ver, NAME))


def remove(ver):
    ext, src = paths(ver)
    raw = read_raw(ext)
    had_crlf = "\r\n" in raw
    text = raw.replace("\r\n", "\n")
    if "VoidWarehouseRecipeBrowseCheck.run" in text:
        assert HOOK_NEW in text, "[%s] hook 内容与预期不符，人工核对" % ver
        write_as_was(ext, text.replace(HOOK_NEW, HOOK_OLD, 1), had_crlf)
        print("[%s] hook 已还原" % ver)
    else:
        print("[%s] hook 本就不在" % ver)
    if os.path.exists(src):
        os.remove(src)
        print("[%s] %s 已删除" % (ver, NAME))


def run(ver):
    log = os.path.join(SCRIPT_DIR, "recipe_%s.log" % ver)
    install(ver)
    env = dict(os.environ)
    env["JAVA_HOME"] = "C:/Users/12286/.jdks/ms-25.0.4.1"
    env["TARGET_MC_VERSIONS"] = ver
    result = None
    try:
        with io.open(log, "w", encoding="utf-8", newline="") as f:
            proc = subprocess.run(["bash", "./gradlew", ROOTS[ver][1] + ":runServer", "--no-daemon"],
                                  stdout=f, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
        print("[%s] gradle 退出码 %d" % (ver, proc.returncode))
    finally:
        remove(ver)
    failures = []
    with io.open(log, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if "SMOKE RESULT vw-recipe" in line:
                result = line.strip().split("[STDOUT]: ")[-1]
            elif "RCP   FAIL" in line or "RCP   warn" in line or line.startswith("!!"):
                failures.append(line.strip().split("[STDOUT]: ")[-1])
    print("[%s] %s" % (ver, result or "<没跑到 finish，查日志>"))
    for line in failures:
        print("    " + line)
    print("[%s] 日志 %s" % (ver, log))
    return result is not None and "failures=0" in result


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: install_recipe_check.py <ver|all> install|remove|run")
    ver, action = sys.argv[1], sys.argv[2]
    if ver != "all":
        assert ver in ROOTS, "未知版本 %s" % ver
    vers = list(ROOTS) if ver == "all" else [ver]
    ok = True
    for v in vers:
        if action == "install":
            install(v)
        elif action == "remove":
            remove(v)
        elif action == "run":
            ok = run(v) and ok
        else:
            raise SystemExit("用法: install | remove | run")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
