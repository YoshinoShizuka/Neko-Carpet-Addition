#!/usr/bin/env python3
"""把「返还明细走独立通道」同步到五个版本。

改的是 `neko/voidwarehouse/restock/` 下四个文件：
  1. `PrinterReturnDetailPayload.java`  —— 新建：明细通道（nonce + 三份平行清单）
  2. `PrinterReturnResultPayload.java`  —— 回退成三个数，一个字节都不动
  3. `PrinterReturnService.java`        —— 逐物品记账 + 入库后读仓库剩余量
  4. `PrinterReturnNetwork.java`        —— 成功路径「先发明细、再发回执」

前三个文件五版本逐字节相同 ⇒ 直接从 26.3 整份复制。
第四个文件各版本有 javadoc/注册入口的差异（1.21.11 是 playC2S/playS2C），
所以<b>只按锚点替换代码</b>，不覆盖版本专属的注释。

铁律（本项目踩过多次）：
  - 每个锚点必须<b>恰好命中 1 次</b>，0 次或 2 次都算错；
  - 已经改过的（new 已在）就跳过，重复跑安全；
  - 逐文件保留原换行（26.3 是 LF，其余 CRLF，core.autocrlf=true）；
    新建的文件按其版本惯例定型 —— 26.3 用 LF、其余用 CRLF；
  - 收尾逐字节校验，并断言注册入口没被误改。
"""
import io
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, "..", ".."))
PKG = os.path.join("src", "main", "java", "neko", "voidwarehouse", "restock")
SRC_VERSION = "modern/26.3"
TARGETS = ["modern/26.3", "modern/26.2", "modern/26.1.2", "modern/26.1.1", "versions/1.21.11"]

COPY_AS_IS = ["PrinterReturnDetailPayload.java", "PrinterReturnResultPayload.java",
              "PrinterReturnService.java"]
NETWORK = "PrinterReturnNetwork.java"

# ---- 网络层：代码锚点（各版本一致） ----

DOC_CLASS_OLD = "挂到网络上：注册两个 payload + 一个 C2S 接收端。\n"
DOC_CLASS_NEW = "挂到网络上：注册三个 payload + 一个 C2S 接收端。\n"

DOC_COUNT_OLD = " * 只要它自己的四个注册项不出错，就不会碰到取货。两边的通道名、限流表也都是分开的\n"
DOC_COUNT_NEW = " * 只要它自己的五个注册项不出错，就不会碰到取货。两边的通道名、限流表也都是分开的\n"

TABLE_OLD = """     *   <tr><td>{@code carpet_neko_addition:printer_return_result}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     * </table>
"""

TABLE_NEW = """     *   <tr><td>{@code carpet_neko_addition:printer_return_result}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     *   <tr><td>{@code carpet_neko_addition:printer_return_detail}</td><td>S2C</td>
     *       <td>服务端（本模组）+ 客户端幂等补位</td></tr>
     * </table>
"""

HANDLE_OLD = """        reply(player, outcome.status() == PrinterReturnResultPayload.OK
                ? toPayload(nonce, outcome)
                : PrinterReturnResultPayload.of(nonce, outcome.status()));
    }
"""

HANDLE_NEW = """        if (outcome.status() == PrinterReturnResultPayload.OK) {
            // 顺序有讲究：明细必须走在回执前面。同一连接上的包是有序的，
            // 客户端处理回执时明细已经在手里，不需要「等一等再拼提示」这种时序把戏。
            sendDetail(player, nonce, outcome);
        }
        reply(player, new PrinterReturnResultPayload(nonce, outcome.status(), outcome.returnedCount()));
    }
"""

METHOD_OLD = """    /**
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

METHOD_NEW = """    /**
     * 成功路径：把那批的逐物品明细单独发出去。
     *
     * <p>明细不塞进回执的理由见 {@link PrinterReturnDetailPayload}。这里用 {@code canSend}
     * 逐个通道判定：对面是很旧、还没有这条通道的客户端时就不发，它照样能靠回执销账，
     * 只是看不到「退了什么、还剩多少」。</p>
     *
     * <p>三个清单一次性从 {@code moved} 摊平 —— 服务端算好、客户端只负责显示，
     * 谁都不用在客户端重算「仓库还剩多少」。</p>
     */
    private static void sendDetail(ServerPlayer player, int nonce, PrinterReturnService.ReturnOutcome outcome) {
        List<PrinterReturnService.Moved> moved = outcome.moved();
        if (player == null || moved.isEmpty()) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, PrinterReturnDetailPayload.TYPE)) {
            return;
        }
        List<String> itemIds = new ArrayList<>(moved.size());
        List<Integer> counts = new ArrayList<>(moved.size());
        List<Long> remainings = new ArrayList<>(moved.size());
        for (PrinterReturnService.Moved entry : moved) {
            itemIds.add(entry.itemId());
            counts.add(entry.count());
            remainings.add(entry.remaining());
        }
        ServerPlayNetworking.send(player,
                new PrinterReturnDetailPayload(nonce, itemIds, counts, remainings));
    }
"""

# ---- 网络层：javadoc「为什么不发聊天提示」，两个版本变体 ----

DOC_MARK = " * <h2>回执为什么拆成两条通道</h2>\n"

DOC_OLD_26 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>取货只有成功且真的放了东西才发一条（玩家需要知道仓库在给他补货），
 * 而返还是<b>后台清理</b> —— 一次建造可能退几十批，服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>放进回执（{@code PrinterReturnResultPayload}
 * 里的逐物品明细 + 仓库剩余量），提示由客户端自己拼、自己控节奏。</p>
"""

DOC_NEW_26 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>取货只有成功且真的放了东西才发一条（玩家需要知道仓库在给他补货），
 * 而返还是<b>后台清理</b> —— 一次建造可能退几十批，服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>发出去（逐物品件数与仓库剩余量走
 * {@link PrinterReturnDetailPayload}，销账用的总量走 {@link PrinterReturnResultPayload}），
 * 提示由客户端自己拼、自己控节奏。</p>
 *
 * <h2>回执为什么拆成两条通道</h2>
 * <p>明细本来可以追加进 {@link PrinterReturnResultPayload} 的末尾，但那样
 * <b>新客户端配旧服务端</b>会解码失败（旧服务端后面没有字节）。拆成独立通道后，
 * 认不认得出这条通道由 {@code canSend} 在发包前逐个判定 —— 新旧客户端的任意组合都安全，
 * 而且两个包各自演进互不影响。</p>
"""

DOC_OLD_12111 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>返还是后台清理，一次建造可能退几十批 —— 服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>放进回执
 * （{@code PrinterReturnResultPayload} 里的逐物品明细 + 仓库剩余量），提示由客户端自己拼。</p>
"""

DOC_NEW_12111 = """ * <h2>为什么服务端自己不发聊天提示</h2>
 * <p>返还是后台清理，一次建造可能退几十批 —— 服务端这边不知道玩家此刻在不在看聊天栏，
 * 也不该替客户端决定节奏。所以服务端只把<b>事实</b>发出去
 * （逐物品件数与仓库剩余量走 {@link PrinterReturnDetailPayload}，销账用的总量走
 * {@link PrinterReturnResultPayload}），提示由客户端自己拼。</p>
 *
 * <h2>回执为什么拆成两条通道</h2>
 * <p>明细本来可以追加进 {@link PrinterReturnResultPayload} 的末尾，但那样
 * <b>新客户端配旧服务端</b>会解码失败（旧服务端后面没有字节）。拆成独立通道后，
 * 认不认得出这条通道由 {@code canSend} 在发包前逐个判定 —— 新旧任意组合都安全。</p>
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


def apply_hunk(text, old, new, where, optional=False):
    """old 在就换（必须恰 1 次）；new 已在就跳过；optional 时都不在也跳过，否则报错。"""
    if new in text:
        return text, False
    hits = text.count(old)
    if hits == 0 and optional:
        return text, False
    if hits != 1:
        raise SystemExit("!! %s 的锚点命中 %d 次（应为 1）：%r" % (where, hits, old[:70]))
    return text.replace(old, new, 1), True


def main():
    src_dir = os.path.join(ROOT, SRC_VERSION, PKG)
    bodies = {}
    for name in COPY_AS_IS:
        text, _ = read(os.path.join(src_dir, name))
        bodies[name] = text

    for target in TARGETS:
        dst_dir = os.path.join(ROOT, target, PKG)
        is_12111 = target.startswith("versions/")
        reg = "playS2C" if is_12111 else "clientboundPlay"
        reg_c2s = "playC2S" if is_12111 else "serverboundPlay"

        # ---- 1. 逐字节相同的三份：整份复制
        for name in COPY_AS_IS:
            dst = os.path.join(dst_dir, name)
            if os.path.isfile(dst):
                _, crlf = read(dst)
            else:
                # 新建文件用 LF：本仓库工作区里的源码实测全是 LF
                # （core.autocrlf=true 只影响 checkout，且这几个版本目录并没有被转成 CRLF）
                crlf = False
            if os.path.normpath(dst) != os.path.normpath(os.path.join(src_dir, name)):
                write(dst, bodies[name], crlf)
                print("%s: 已复制 %s（%s）" % (target, name, "CRLF" if crlf else "LF"))

        # ---- 2. 网络层：只按锚点替换
        path = os.path.join(dst_dir, NETWORK)
        text, crlf = read(path)
        changed = []

        reg_old = ("        PayloadTypeRegistry.%s().register(\n"
                   "                PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);\n" % reg)
        reg_new = (reg_old +
                   "        PayloadTypeRegistry.%s().register(\n"
                   "                PrinterReturnDetailPayload.TYPE, PrinterReturnDetailPayload.CODEC);\n" % reg)

        for old, new, label, opt in [
            (DOC_CLASS_OLD, DOC_CLASS_NEW, "doc-class", False),
            (DOC_COUNT_OLD, DOC_COUNT_NEW, "doc-count", True),
            (TABLE_OLD, TABLE_NEW, "doc-table", False),
            (reg_old, reg_new, "register", False),
            (HANDLE_OLD, HANDLE_NEW, "handle", False),
            (METHOD_OLD, METHOD_NEW, "sendDetail", False),
        ]:
            text, hit = apply_hunk(text, old, new, target + "/" + label, optional=opt)
            changed.append(hit)

        # javadoc：两种历史措辞，哪个在就换哪个
        if DOC_MARK in text:
            changed.append(False)
        elif DOC_OLD_26 in text:
            text, doc_hit = apply_hunk(text, DOC_OLD_26, DOC_NEW_26, target + "/doc26")
            changed.append(doc_hit)
        elif DOC_OLD_12111 in text:
            text, doc_hit = apply_hunk(text, DOC_OLD_12111, DOC_NEW_12111, target + "/doc12111")
            changed.append(doc_hit)
        else:
            raise SystemExit("!! %s 的 javadoc 段落两种历史措辞都找不到" % target)

        write(path, text, crlf)
        print("%s: 网络层 %s（%s）" % (target, "已改 %d 处" % sum(changed) if any(changed) else "无变化",
                                     "CRLF" if crlf else "LF"))

    # ---- 收尾门禁
    print("---- 校验")
    for target in TARGETS:
        dst_dir = os.path.join(ROOT, target, PKG)
        for name in COPY_AS_IS:
            got, _ = read(os.path.join(dst_dir, name))
            if got != bodies[name]:
                raise SystemExit("!! %s 的 %s 与源不一致" % (target, name))
        net, _ = read(os.path.join(dst_dir, NETWORK))
        for needle in ("sendDetail(player, nonce, outcome)",
                       "private static void sendDetail(",
                       "PrinterReturnDetailPayload.TYPE, PrinterReturnDetailPayload.CODEC);",
                       "new PrinterReturnResultPayload(nonce, outcome.status(), outcome.returnedCount())",
                       "注册三个 payload", "回执为什么拆成两条通道"):
            if needle not in net:
                raise SystemExit("!! %s 的 %s 缺少 %r" % (target, NETWORK, needle))
        for stale in ("toPayload(",
                      "PrinterReturnResultPayload(nonce, outcome.status(), outcome.returnedCount(),",
                      "放进回执",
                      "注册两个 payload",
                      "四个注册项"):
            if stale in net:
                raise SystemExit("!! %s 的 %s 还残留旧代码 %r" % (target, NETWORK, stale))
        # 每个版本自己的注册入口不能被误改
        c2s = "playC2S" if target.startswith("versions/") else "serverboundPlay"
        s2c = "playS2C" if target.startswith("versions/") else "clientboundPlay"
        for entry in (c2s, s2c):
            if entry not in net:
                raise SystemExit("!! %s 的注册入口 %s 丢了" % (target, entry))
        if net.count("PrinterReturnDetailPayload.TYPE") != 2:
            raise SystemExit("!! %s 的明细通道注册/发送处数不对（应为 2）" % target)
    print("五版本：三个文件逐字节一致，Network 的改动齐全且没碰注册入口")


if __name__ == "__main__":
    main()
