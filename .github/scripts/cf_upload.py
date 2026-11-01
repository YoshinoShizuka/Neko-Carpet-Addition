#!/usr/bin/env python3
"""cf_upload.py — 在 CI 里把主 jar 上传到 CurseForge（作者上传端点）。

环境变量:
    CURSEFORGE_TOKEN  作者 API token（X-Api-Token）

参数:
    python cf_upload.py <project_id> <artifacts_dir> <changelog_md> <deps_comma_slugs>

- 只上传主 jar（文件名不含 -sources. —— 发 Release 不带 sources jar 的定规）
- 游戏版本与 Fabric loader 的 CF id 动态查询（CI 网络对 CF 放行）
- 文件名形如 <name>-mc<GV>-<version>.jar，从中提取游戏版本
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = "https://minecraft.curseforge.com/api"
TOKEN = os.environ.get("CURSEFORGE_TOKEN")
if not TOKEN:
    sys.exit("缺 CURSEFORGE_TOKEN")

PROJECT_ID, ARTIFACTS_DIR, CHANGELOG_PATH, DEPS = sys.argv[1:5]
UA = {"User-Agent": "neko-publish/1.0 (github.com/YoshinoShizuka)"}


def req(path, method="GET", data=None, headers=None):
    r = urllib.request.Request(f"{BASE}{path}", method=method)
    r.add_header("X-Api-Token", TOKEN)
    for k, v in UA.items():
        r.add_header(k, v)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    with urllib.request.urlopen(r, data, timeout=180) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def main():
    versions = req("/games/minecraft/versions")
    by_name = {v["name"]: v["id"] for v in versions}
    fabric_id = next((t["id"] for t in req("/games/minecraft/version-types")
                      if t.get("name") == "Fabric"), None)
    print("Fabric loader id:", fabric_id)

    jars = sorted(f for f in os.listdir(ARTIFACTS_DIR)
                  if f.endswith(".jar") and "-sources." not in f)
    assert jars, "artifacts 目录没有主 jar"
    changelog = open(CHANGELOG_PATH, encoding="utf-8").read()

    file_ids = []
    for jar in jars:
        m = re.search(r"-mc([0-9.]+)-", jar)
        assert m, jar
        gv = m.group(1)
        gvid = by_name.get(gv)
        assert gvid, f"CF 未收录游戏版本 {gv}"
        game_versions = [str(gvid)] + ([str(fabric_id)] if fabric_id else [])
        meta = {
            "displayName": jar[:-4],
            "fileName": jar,
            "gameVersions": game_versions,
            "releaseType": "release",
            "changelog": changelog,
            "changelogType": "markdown",
        }
        boundary = "----cfupload"
        parts = [
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
            f"Content-Type: application/json\r\n\r\n{json.dumps(meta)}\r\n".encode(),
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{jar}\"\r\n"
            f"Content-Type: application/java-archive\r\n\r\n".encode()
            + open(os.path.join(ARTIFACTS_DIR, jar), "rb").read()
            + f"\r\n--{boundary}--\r\n".encode(),
        ]
        try:
            d = req(f"/projects/{PROJECT_ID}/upload-file", method="POST", data=b"".join(parts),
                    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
        except urllib.error.HTTPError as e:
            raise SystemExit(f"❌ 上传 {jar} 失败: {e.code} {e.read().decode('utf-8', 'replace')[:300]}")
        fid = d["id"]
        file_ids.append(fid)
        print(f"  ✔ {jar} -> fileId {fid}")

    if DEPS:
        for fid in file_ids:
            req(f"/projects/{PROJECT_ID}/dependencies?fileId={fid}", method="PUT",
                data=json.dumps({"dependencies": [{"slug": s} for s in DEPS.split(",") if s]}).encode(),
                headers={"Content-Type": "application/json"})
        print("依赖已关联:", DEPS)
    print("完成，共上传", len(file_ids), "个文件")


if __name__ == "__main__":
    main()
