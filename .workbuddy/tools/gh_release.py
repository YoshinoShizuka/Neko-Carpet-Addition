#!/usr/bin/env python3
"""把 dist/ 里的产物发成 GitHub Release。

用法（TOKEN 从环境变量读，绝不进命令行）：
    GH_TOKEN=... python .workbuddy/tools/gh_release.py --repo owner/name --tag v1.0.15 \
        --name "Carpet Neko Addition 1.0.15" --body-file CHANGELOG.md --section "## Carpet Neko Addition 1.0.15" \
        --assets "dist/*1.0.15*.jar"

流程：先建 draft → 逐个上传资产 → 全部成功后才 publish。
任何一步失败都退出码非 0，draft 留着可以人工检查（不会半成品上线）。
"""
import argparse
import glob
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def call(method, url, token, payload=None, content_type="application/vnd.github+json", raw=None):
    data = None
    if raw is not None:
        data = raw
    elif payload is not None:
        data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Content-Type", content_type)
    req.add_header("User-Agent", "neko-release-script")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        raise SystemExit(f"[FAIL] {method} {url} -> {e.code}\n{detail[:800]}")


def extract_section(path, heading):
    """从 CHANGELOG 里取出某一段（到下一个 '## ' 或 '* * *' 分隔为止），并去掉段标题本身。"""
    lines = open(path, encoding="utf-8").read().splitlines()
    start = None
    for i, ln in enumerate(lines):
        if ln.strip() == heading.strip():
            start = i
            break
    if start is None:
        raise SystemExit(f"[FAIL] 找不到段标题：{heading}")
    out = []
    for ln in lines[start + 1:]:
        s = ln.strip()
        if s.startswith("## ") or s == "* * *":
            break
        out.append(ln)
    # 掐掉首尾空行
    while out and not out[0].strip():
        out.pop(0)
    while out and not out[-1].strip():
        out.pop()
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--body-file", required=True)
    ap.add_argument("--section", required=True)
    ap.add_argument("--assets", action="append", required=True)
    ap.add_argument("--target", default="main")
    ap.add_argument("--prerelease", action="store_true")
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("[FAIL] 环境变量 GH_TOKEN 没设")

    body = extract_section(args.body_file, args.section)
    files = []
    for pat in args.assets:
        hit = sorted(glob.glob(pat))
        if not hit:
            raise SystemExit(f"[FAIL] 资产通配符没匹配到文件：{pat}")
        files.extend(hit)
    seen = set()
    files = [f for f in files if not (f in seen or seen.add(f))]
    print(f"资产 {len(files)} 个：")
    for f in files:
        print(f"  {f}  ({os.path.getsize(f)} B)")

    st, rel = call("POST", f"{API}/repos/{args.repo}/releases", token, {
        "tag_name": args.tag,
        "target_commitish": args.target,
        "name": args.name,
        "body": body,
        "draft": True,
        "prerelease": bool(args.prerelease),
    })
    rid = rel["id"]
    print(f"[ok] draft 已建：id={rid}  tag={rel['tag_name']}")

    uploaded = []
    for f in files:
        name = os.path.basename(f)
        with open(f, "rb") as fh:
            raw = fh.read()
        st, a = call(
            "POST",
            f"{UPLOADS}/repos/{args.repo}/releases/{rid}/assets?name={urllib.parse.quote(name)}",
            token,
            content_type="application/java-archive",
            raw=raw,
        )
        uploaded.append((a["name"], a["size"]))
        print(f"[ok] 上传 {a['name']} ({a['size']} B, state={a['state']})")

    st, pub = call("PATCH", f"{API}/repos/{args.repo}/releases/{rid}", token, {"draft": False})
    print(f"[ok] 已发布：{pub['html_url']}")
    print(f"     资产 {len(pub['assets'])} 个，draft={pub['draft']} prerelease={pub['prerelease']}")
    for a in pub["assets"]:
        print(f"     - {a['name']}  {a['size']} B  {a['state']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
