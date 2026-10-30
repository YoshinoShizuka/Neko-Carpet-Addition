#!/usr/bin/env python3
"""git 传输被墙时的替代推送：用 GitHub 的 git-data REST API 把本地提交原样搬上去。

适用条件：`api.github.com` 能通，但 `github.com:443`（git 的 smart-http 端点）连不上。
本项目 2026-10-30 就撞上了这个状态：直连 github.com 超时、走本机代理 502，
而 api.github.com 直连 200。

做的是 git 协议里一模一样的事，一个提交一个提交来：
    逐个改动文件 POST /git/blobs  →  POST /git/trees（base_tree = 父提交的树）
    →  POST /git/commits（作者/提交者/时间戳都照抄本地）
最后 PATCH /git/refs/heads/<branch>。

内容一律取自本地 git 对象（`git cat-file blob`），**不读工作区** —— 换行
（26.3 是 LF、其余 CRLF 的那点历史包袱）和可执行位因此不会变形。
作者/提交者/时间戳也照抄，所以正常情况下生成的提交 SHA 与本地**逐字节相同**，
脚本最后会拿它当验收判据。

用法：
    GH_TOKEN=... python api_push.py --repo owner/name [--branch main] [--dry-run]
    # 上一次也是用本脚本推的（远端 SHA 与本地不同）→ 指明「远端 HEAD 对应本地哪个提交」
    GH_TOKEN=... python api_push.py --repo owner/name --from <上次的本地 HEAD>

token 也可以用 `git credential fill` 取（脚本内置），不必写进命令行。
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def sh(*args, binary=False):
    out = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return out.stdout if binary else out.stdout.decode("utf-8").strip()


def token_from_credential():
    """从 Git Credential Manager 里取 token，不落命令行、不进 shell 历史"""
    try:
        p = subprocess.run(["git", "credential", "fill"], input=b"protocol=https\nhost=github.com\n\n",
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True)
    except subprocess.CalledProcessError:
        return None
    for line in p.stdout.decode("utf-8").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


def call(method, url, token, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        raise SystemExit("!! %s %s -> %s\n%s" % (method, url, e.code, body[:800]))


def entries_for(commit):
    """这个提交相对父提交改了什么：[(mode, path, blob_sha_or_None)]"""
    parent = commit + "^"
    raw = subprocess.run(["git", "diff-tree", "-r", "-z", "--no-commit-id", "--name-status", parent, commit],
                         stdout=subprocess.PIPE, check=True).stdout
    parts = [p for p in raw.split(b"\0") if p]
    out = []
    i = 0
    while i < len(parts):
        status = parts[i].decode()
        path = parts[i + 1].decode("utf-8", "surrogateescape")
        i += 2
        if status.startswith("D"):
            out.append(("100644", path, None))
            continue
        if status.startswith("R") or status.startswith("C"):
            path = parts[i].decode("utf-8", "surrogateescape")
            i += 1
        # ls-tree 拿真实 mode（可能有 100755）
        line = sh("git", "ls-tree", commit, "--", path)
        if not line:
            raise SystemExit("!! 取不到 %s 在 %s 里的 tree 项" % (path, commit))
        meta = line.split("\t")[0].split()
        out.append((meta[0], path, meta[2]))
    return out


def commit_meta(commit):
    fmt = "%an%x00%ae%x00%aI%x00%cn%x00%ce%x00%cI"
    a_n, a_e, a_d, c_n, c_e, c_d = sh("git", "log", "-1", "--format=" + fmt, commit).split("\0")
    msg = subprocess.run(["git", "log", "-1", "--format=%B", commit],
                         stdout=subprocess.PIPE, check=True).stdout.decode("utf-8")
    return msg, {"name": a_n, "email": a_e, "date": a_d}, {"name": c_n, "email": c_e, "date": c_d}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--branch", default="main")
    ap.add_argument("--from", dest="base", metavar="LOCAL_SHA",
                    help="远端 HEAD 对应的本地提交。只有上个提交是用本脚本推的、SHA 已经不同时才需要给；"
                         "给了它会先校验两边的 tree 完全一致，再把这些提交重放到远端 HEAD 之上")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or token_from_credential()
    if not token:
        raise SystemExit("!! 没有 token：设 GH_TOKEN，或先让 git credential 记住它")

    st, ref = call("GET", "%s/repos/%s/git/ref/heads/%s" % (API, args.repo, args.branch), token)
    remote_sha = ref["object"]["sha"]
    local_sha = sh("git", "rev-parse", "HEAD")
    print("远端 %s = %s" % (args.branch, remote_sha[:7]))
    print("本地 HEAD  = %s" % local_sha[:7])
    if remote_sha == local_sha:
        print("已经一致，不需要推送")
        return

    # 远端必须是本地 HEAD 的祖先，否则就是在瞎覆盖。
    # 例外：上一次就是用本脚本推的（SHA 因时区归一而不同），这时用 --from 指明
    # 「远端 HEAD 对应本地哪个提交」，改为校验**两边的 tree 完全一致**。
    if args.base:
        base_sha = sh("git", "rev-parse", args.base)
        local_tree = sh("git", "rev-parse", base_sha + "^{tree}")
        remote_tree = call("GET", "%s/repos/%s/git/commits/%s" % (API, args.repo, remote_sha), token)[1]["tree"]["sha"]
        if local_tree != remote_tree:
            raise SystemExit("!! --from %s 的 tree 与远端 %s 不一致 —— 说明本地基线与远端内容已经不同，"
                             "别硬推，先查清楚" % (base_sha[:7], remote_sha[:7]))
        print("[ok] 基线校验通过：%s 与远端 %s 内容一致（tree %s）"
              % (base_sha[:7], remote_sha[:7], local_tree[:7]))
        commits = sh("git", "rev-list", "--reverse", "%s..%s" % (base_sha, local_sha)).split()
    else:
        try:
            subprocess.run(["git", "merge-base", "--is-ancestor", remote_sha, local_sha], check=True)
        except subprocess.CalledProcessError:
            raise SystemExit("!! 远端 %s 不是本地 HEAD 的祖先 —— 先 pull，别硬推" % remote_sha[:7])
        commits = sh("git", "rev-list", "--reverse", "%s..%s" % (remote_sha, local_sha)).split()
    print("要搬 %d 个提交：%s" % (len(commits), " ".join(c[:7] for c in commits)))

    parent = remote_sha
    for c in commits:
        subject = sh("git", "log", "-1", "--format=%s", c)
        changes = entries_for(c)
        print("  %s %s（%d 个文件）" % (c[:7], subject, len(changes)))
        if args.dry_run:
            for mode, path, blob in changes:
                print("      %s %s %s" % ("删" if blob is None else "写", path,
                                          "" if blob is None else blob[:7]))
            continue

        tree = []
        for mode, path, blob in changes:
            if blob is None:
                tree.append({"path": path, "mode": mode, "type": "blob", "sha": None})
                continue
            content = sh("git", "cat-file", "blob", blob, binary=True)
            st, created = call("POST", "%s/repos/%s/git/blobs" % (API, args.repo), token, {
                "content": base64.b64encode(content).decode("ascii"),
                "encoding": "base64",
            })
            tree.append({"path": path, "mode": mode, "type": "blob", "sha": created["sha"]})
            print("      blob %s <- %s (%d B)" % (created["sha"][:7], path, len(content)))

        parent_tree = call("GET", "%s/repos/%s/git/commits/%s" % (API, args.repo, parent), token)[1]["tree"]["sha"]
        st, new_tree = call("POST", "%s/repos/%s/git/trees" % (API, args.repo), token,
                            {"base_tree": parent_tree, "tree": tree})
        msg, author, committer = commit_meta(c)
        st, new_commit = call("POST", "%s/repos/%s/git/commits" % (API, args.repo), token, {
            "message": msg, "tree": new_tree["sha"], "parents": [parent],
            "author": author, "committer": committer,
        })
        same = "与本地一致" if new_commit["sha"] == c else "⚠ 与本地 SHA 不同（内容已同，元数据可能被服务端改写）"
        print("      commit %s（%s）" % (new_commit["sha"][:7], same))
        parent = new_commit["sha"]

    if args.dry_run:
        print("dry-run：没有写远端")
        return

    call("PATCH", "%s/repos/%s/git/refs/heads/%s" % (API, args.repo, args.branch), token, {
        "sha": parent, "force": False,
    })
    print("[ok] %s 已更新到 %s" % (args.branch, parent[:7]))
    subprocess.run(["git", "update-ref", "refs/remotes/origin/" + args.branch, parent], check=False)
    if parent == local_sha:
        print("[ok] 远端 SHA 与本地 HEAD 逐字节相同")
    else:
        # 已知：GitHub 会把 author/committer 的时间归一成 UTC（+08:00 → Z），
        # 于是提交对象的字节不同、SHA 也不同。**内容（tree）仍然逐字节一致**，
        # 用 /git/commits/<sha> 的 tree.sha 与 `git rev-parse <本地>^{tree}` 比对即可证明。
        # 代价：本地与远端从此不是同一条 SHA 链 —— git 传输恢复后要
        #   git fetch && git reset --hard origin/<branch>
        # 收敛成远端的 SHA 再继续提交，否则下一次 push 会被判成 non-fast-forward。
        print("[warn] 远端 SHA %s != 本地 %s（时区归一导致；tree 一致就是内容一致）"
              % (parent[:7], local_sha[:7]))
        print("[warn] git 传输恢复后：git fetch && git reset --hard origin/" + args.branch)


if __name__ == "__main__":
    main()
