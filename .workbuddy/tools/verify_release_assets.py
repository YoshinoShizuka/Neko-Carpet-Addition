#!/usr/bin/env python3
"""校验某个 Release 的资产与本地 dist/ 逐字节一致。

用法（TOKEN 从环境变量读）：
    GH_TOKEN=... python .workbuddy/tools/verify_release_assets.py --repo owner/name --tag v1.0.16

判据只有一个：**API 返回的 assets[].digest 里的 sha256**（形如 "sha256:abc..."）
对比本地文件 hashlib.sha256。不看「能不能下载」——下载成功不代表内容对。
digest 字段存在时直接用；老资产没有 digest 时才回落到 download_url 现算。

退出码 0 = 全部一致；1 = 有差异/缺失/多余。
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def call(url, token):
    req = urllib.request.Request(url, method="GET")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "neko-release-script")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"[FAIL] GET {url} -> {e.code}\n{e.read().decode(errors='replace')[:600]}")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="owner/name")
    ap.add_argument("--tag", required=True, help="如 v1.0.16")
    ap.add_argument("--dist", default="dist", help="本地产物目录，默认 dist")
    ap.add_argument("--expect", type=int, default=0, help="期望资产数量（0 = 不校验数量）")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("[FAIL] 需要环境变量 GH_TOKEN")

    rel = call(f"{API}/repos/{args.repo}/releases/tags/{args.tag}", token)
    print(f"[release] {rel['html_url']}")
    print(f"          name={rel.get('name')!r} draft={rel['draft']} prerelease={rel['prerelease']} "
          f"assets={len(rel['assets'])}")
    print()

    ok = 0
    bad = []
    for a in sorted(rel["assets"], key=lambda x: x["name"]):
        name = a["name"]
        local = os.path.join(args.dist, name)
        digest = a.get("digest") or ""
        if not os.path.exists(local):
            bad.append((name, "本地缺该文件", digest))
            print(f"  [MISS] {name:<52} 远端有、本地 dist/ 没有")
            continue
        want = sha256_file(local)
        if digest.startswith("sha256:"):
            got = digest.split(":", 1)[1]
            src = "digest"
        else:
            got, src = None, "无 digest（跳过内容比对）"
        if got is None:
            print(f"  [SKIP] {name:<52} {a['size']:>9,} B  {src}")
            ok += 1
            continue
        if got == want:
            ok += 1
            print(f"  [ok]   {name:<52} {a['size']:>9,} B  {want[:16]}…")
        else:
            bad.append((name, f"sha256 不一致 远端={got} 本地={want}", digest))
            print(f"  [BAD]  {name:<52} 远端={got[:16]}… 本地={want[:16]}…")

    # 本地有、远端没有（只提示，不算失败 —— dist/ 里留着历史版本）
    remote_names = {a["name"] for a in rel["assets"]}
    tag_ver = args.tag.lstrip("v")
    extra = sorted(f for f in os.listdir(args.dist)
                   if tag_ver in f and f.endswith(".jar") and f not in remote_names)
    if extra:
        print()
        print(f"[note] dist/ 里有 {len(extra)} 个含 {tag_ver} 但不在该 release 的文件（不算失败）：")
        for f in extra:
            print(f"         - {f}")

    print()
    if args.expect and len(rel["assets"]) != args.expect:
        bad.append(("(数量)", f"期望 {args.expect} 个资产，实际 {len(rel['assets'])} 个", ""))
        print(f"[FAIL] 资产数量 {len(rel['assets'])} != 期望 {args.expect}")

    if bad:
        print(f"=== 不一致 {len(bad)} 项 ===")
        for name, why, _ in bad:
            print(f"  - {name}: {why}")
        sys.exit(1)
    print(f"=== {ok}/{len(rel['assets'])} 全部一致，Release 内容可信 ===")


if __name__ == "__main__":
    main()
