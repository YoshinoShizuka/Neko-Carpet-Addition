#!/usr/bin/env python3
"""把「返还回执带逐物品明细」同步到五个版本。

改的是 `neko/voidwarehouse/restock/` 下三个文件：
  1. `PrinterReturnResultPayload.java`  —— 三个清单（itemIds / counts / remainings）
  2. `PrinterReturnService.java`        —— 逐物品记账 + 入库后读仓库剩余量
  3. `PrinterReturnNetwork.java`        —— 回执带上明细

前两个文件五版本逐字节相同 ⇒ 直接从 26.3 整份复制。
第三个文件各版本有 javadoc/注册入口的差异（1.21.11 是 playC2S/playS2C），
所以<b>只按锚点替换代码</b>，不覆盖版本专属的注释。

铁律（本项目踩过多次）：
  - 每个锚点必须<b>恰好命中 1 次</b>，0 次或 2 次都算错；
  - 已经改过的（new 已在）就跳过，重复跑安全；
  - 逐文件保留原换行（26.x 是 LF，1.21.11 是 CRLF，core.autocrlf=true）；
  - 收尾逐字节校验：前两个文件与源一致，第三个文件含全部 new 且不含任何 old。
"""
import io
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse", "restock")
SRC_VERSION = "modern/26.3"
TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

COPY_AS_IS = ["PrinterReturnResultPayload.java", "PrinterReturnService.java"]
NETWORK = "PrinterReturnNetwork.java"

# ---- 网络层：代码锚点（各版本一致） ----

IMPORTS_OLD = """import java.util.Map;
import java.util.UUID;
"""

IMPORTS_NEW = """import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
"""

THROTTLE_OLD = """            reply(player, nonce, PrinterReturnResultPayload.THROTTLED, 0);
"""

THROTTLE_NEW = """            reply(player, PrinterReturnResultPayload.of(nonce, PrinterReturnResultPayload.THROTTLED));
"""

HANDLE_OLD = """        PrinterReturnService.ReturnOutcome outcome =
                PrinterReturnService.returnSlots(context.server(), player, payload.slots());
        reply(player, nonce, outcome.status(), outcome.returnedCount());
    }
"""

HANDLE_NEW = """        PrinterReturnService.ReturnOutcome outcome =
                PrinterReturnService.returnSlots(context.server(), player, payload.slots());
        reply(player, outcome.status() == PrinterReturnResultPayload.OK
                ? toPayload(nonce, outcome)
                : PrinterReturnResultPayload.of(nonce, outcome.status()));
    }

    /**
     * 成功路径：把逐物品明细也带上。
     *
     * <p>三个清单在这里一次性从 {@code moved} 摊平 —— 服务端算好、客户端只负责显示，
     * 谁都不用在客户端重算「仓库还剩多少」。</p>
     */
    private static PrinterReturnResultPayload toPayload(int nonce, PrinterReturnService.ReturnOutcome outcome) {
        List<PrinterReturnService.Moved> moved = outcome.moved();
        List<String> itemIds = new ArrayList<>(moved.size());
        List<Integer> counts = new ArrayList<>(moved.size());
        List<Long> remainings = new ArrayList<>(moved.size());
        for (PrinterReturnService.Moved entry : moved) {
            itemIds.add(entry.itemId());
            counts.add(entry.count());
            remainings.add(entry.remaining());
        }
        return new PrinterReturnResultPayload(nonce, outcome.status(), outcome.returnedCount(),
                itemIds, counts, remainings);
    }
"""

REPLY_OLD = """    private static void reply(ServerPlayer player, int nonce, int status, int returned) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, new PrinterReturnResultPayload(nonce, status, returned));
    }
"""

REPLY_NEW = """    private static void reply(ServerPlayer player, PrinterReturnResultPayload payload) {
        if (player == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnResultPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }
"""

# ---- 网络层：javadoc「为什么服务端自己不发聊天提示」，两个版本变体 ----

DOC_OLD_26 = """ * <h2>为什么不发聊天提示</h2>
 * <p>取货只有成功且真的放了东西才发一条（玩家需要知道仓库在给他补货）；
 * 返还则相反 —— 它是<b>后台清理</b>，一次建造可能退几十批，
 * 每条都发会把聊天栏刷爆。仓库里的数量变化玩家随时可以开界面看。</p>
"""

DOC_NEW_26 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>取货只有成功且真的放了东西才发一条（玩家需要知道仓库在给他补货），
 * 而返还是<b>后台清理</b> —— 一次建造可能退几十批，服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>放进回执（{@code PrinterReturnResultPayload}
 * 里的逐物品明细 + 仓库剩余量），提示由客户端自己拼、自己控节奏。</p>
"""

DOC_OLD_12111 = """ * <h2>为什么不发聊天提示</h2>
 * <p>返还是后台清理，一次建造可能退几十批，每条都发会把聊天栏刷爆。
 * 仓库数量玩家随时可以开界面看。</p>
"""

DOC_NEW_12111 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>返还是后台清理，一次建造可能退几十批 —— 服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>放进回执
 * （{@code PrinterReturnResultPayload} 里的逐物品明细 + 仓库剩余量），提示由客户端自己拼。</p>
"""


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    return text.replace("\r\n", "\n"), ("\r\n" in text)


def write(path, text, crlf):
    if crlf:
        text = text.replace("\n", "\r\n")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def apply_hunk(text, old, new, where):
    """old 在就换（必须恰 1 次）；new 已在就跳过；都没有就报错。"""
    if new in text:
        return text, False
    hits = text.count(old)
    if hits != 1:
        raise SystemExit("!! %s 的锚点命中 %d 次（应为 1）：%r" % (where, hits, old[:60]))
    return text.replace(old, new, 1), True


def main():
    src_dir = os.path.join(ROOT, SRC_VERSION, PKG)
    bodies = {}
    for name in COPY_AS_IS:
        text, _ = read(os.path.join(src_dir, name))
        bodies[name] = text

    for target in TARGETS:
        dst_dir = os.path.join(ROOT, target, PKG)
        # ---- 1. 逐字节相同的两份：整份复制
        for name in COPY_AS_IS:
            dst = os.path.join(dst_dir, name)
            _, crlf = read(dst)
            if os.path.normpath(dst) != os.path.normpath(os.path.join(src_dir, name)):
                write(dst, bodies[name], crlf)
                print("%s: 已复制 %s（%s）" % (target, name, "CRLF" if crlf else "LF"))

        # ---- 2. 网络层：只按锚点替换
        path = os.path.join(dst_dir, NETWORK)
        text, crlf = read(path)
        changed = []
        text, hit = apply_hunk(text, IMPORTS_OLD, IMPORTS_NEW, target + "/imports")
        changed.append(hit)
        text, hit = apply_hunk(text, THROTTLE_OLD, THROTTLE_NEW, target + "/throttle")
        changed.append(hit)
        text, hit = apply_hunk(text, HANDLE_OLD, HANDLE_NEW, target + "/handle")
        changed.append(hit)
        text, hit = apply_hunk(text, REPLY_OLD, REPLY_NEW, target + "/reply")
        changed.append(hit)
        # javadoc：两种历史措辞，哪个在就换哪个
        if DOC_NEW_26 in text or DOC_NEW_12111 in text:
            doc_hit = False
        elif DOC_OLD_26 in text:
            text, doc_hit = apply_hunk(text, DOC_OLD_26, DOC_NEW_26, target + "/doc26")
        elif DOC_OLD_12111 in text:
            text, doc_hit = apply_hunk(text, DOC_OLD_12111, DOC_NEW_12111, target + "/doc12111")
        else:
            raise SystemExit("!! %s 的 javadoc 段落两种历史措辞都找不到" % target)
        changed.append(doc_hit)
        write(path, text, crlf)
        print("%s: 网络层 %s（%s）" % (target, "已改 %d 处" % sum(changed) if any(changed) else "无变化", "CRLF" if crlf else "LF"))

    # ---- 收尾门禁
    print("---- 校验")
    for target in TARGETS:
        dst_dir = os.path.join(ROOT, target, PKG)
        for name in COPY_AS_IS:
            got, _ = read(os.path.join(dst_dir, name))
            if got != bodies[name]:
                raise SystemExit("!! %s 的 %s 与源不一致" % (target, name))
        net, _ = read(os.path.join(dst_dir, NETWORK))
        for needle in ("toPayload(", "PrinterReturnResultPayload.of(nonce",
                       "private static void reply(ServerPlayer player, PrinterReturnResultPayload payload)",
                       "import java.util.ArrayList;", "为什么服务端自己不发聊天提示"):
            if needle not in net:
                raise SystemExit("!! %s 的 %s 缺少 %r" % (target, NETWORK, needle))
        for stale in ("reply(player, nonce, PrinterReturnResultPayload.THROTTLED, 0)",
                      "new PrinterReturnResultPayload(nonce, status, returned)",
                      "int nonce, int status, int returned) {"):
            if stale in net:
                raise SystemExit("!! %s 的 %s 还残留旧代码 %r" % (target, NETWORK, stale))
        # 每个版本自己的注册入口不能被误改
        reg = "playC2S" if target.startswith("versions/") else "serverboundPlay"
        if reg not in net:
            raise SystemExit("!! %s 的注册入口 %s 丢了" % (target, reg))
    print("五版本：Payload/Service 逐字节一致，Network 的改动齐全且没碰注册入口")


if __name__ == "__main__":
    main()
