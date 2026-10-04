#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「出料漏斗速率对齐入库漏斗（无 8 gt 节流）」同步到其余四个版本。

背景：VoidWarehouseExtractor.eject 原来每次只搬 1 个（对齐原版 ejectItems），
      搬成功返回 true ⇒ tryMoveItems 收尾 setCooldown(8) ⇒ 下游每 8 gt 才拿到 1 个
      （2.5 个/秒）。而入库端是「一 tick 搬空」。本轮把 eject 改成整格搬，
      并在 HopperBlockEntityMixin 的 pushItemsTick 收尾对出料漏斗也清冷却。

改动三处（都在 voidwarehouse 包）：
  1. VoidWarehouseExtractor.java —— 类 javadoc + eject 方法体 + 相关注释（整格搬）
  2. VoidWarehouseHopperFilter.java —— resetCooldown 的 javadoc（入库+出料都要清）
  3. mixin/HopperBlockEntityMixin.java —— ③ 节标题/正文 + unlimitedCooldown 的判定
                                        （isIntake || isExtractor）

注意四个版本的 VoidWarehouseExtractor 有一处<b>与本次无关的既有差异</b>：
26.2/26.1.x 的 isExtractor javadoc 写「已登记的仓库终端 / isTerminal」，
26.3 与 1.21.11 写「工作台 / isWorkbench」（实际代码五个版本都是 isWorkbench）。
这里只碰本次要改的片段，不动那处既有措辞。

每处替换都断言「替换前后确实变了」，没变就报错 —— 静默失效比崩掉更可怕。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TARGETS = ["modern/26.2", "modern/26.1.1", "modern/26.1.2", "versions/1.21.11"]

PKG = "src/main/java/neko/voidwarehouse"

# ---------------------------------------------------------------- 替换片段

# ---- ① VoidWarehouseExtractor：类 javadoc 的速率段 ----
EX_OLD_SPEED = """ * <p>补料<b>没有速率上限</b>：不踩原版「每 8 gt 搬 1 个」的节流，每个 tick 都会把格子补满，
 * 所以漏斗永远是个满仓的缓冲。往<b>下游容器</b>那一段仍是原版速率（每 8 gt 1 个）——
 * 虚空仓库这一端不再是瓶颈，瓶颈回到玩家自己搭的容器那一端。</p>
"""

EX_NEW_SPEED = """ * <p><b>整条链都无速率上限</b>（两端都比原版快，谁也不拖累谁）：</p>
 * <ul>
 *   <li><b>仓库 → 漏斗</b>：{@link #pull} 不踩原版「每 8 gt 搬 1 个」的节流，
 *       每个 tick 都会把格子补满，所以漏斗永远是个满仓的缓冲；</li>
 *   <li><b>漏斗 → 下游容器</b>：{@link #eject} 接管原版的出料动作，<b>整格搬</b>
 *       （原版是每次 1 个），再叠上 {@code pushItemsTick} 收尾清零冷却
 *       （见 {@code HopperBlockEntityMixin#carpetAutomending$unlimitedCooldown}）——
 *       于是下游每个 tick 都能拿到漏斗那一刻的全部存货。</li>
 * </ul>
 * <p>两段加起来 = 与入库漏斗同一个口径：<b>一 tick 搬空</b>，只取决于下游容器接得下多少。</p>
"""

# ---- ① VoidWarehouseExtractor：分类物段里的一句 ----
EX_OLD_KEEP = " *   <li>仓库还有料时：格子被补满 → 下游按原版速率取用 → 格子始终 ≥ 2 → 正常出货；</li>"
EX_NEW_KEEP = " *   <li>仓库还有料时：格子被 {@link #pull} 补满 → 下游按「整格搬」取用 → 格子始终 ≥ 2 → 正常出货；</li>"

# ---- ① VoidWarehouseExtractor：eject 的 javadoc + 方法体（整段替换） ----
EX_OLD_EJECT = """    /**
     * 出料（接管原版 {@code ejectItems}）：把漏斗里的物品送进下游容器，
     * <b>但每格永远留下 {@link #KEEP_AS_CLASSIFIER} 个</b>。
     *
     * <p>原版的动作是「从头扫，谁非空就搬 1 个走」；这里的差别只有一处：
     * <b>数量 ≤ 1 的格子直接跳过</b>。所以</p>
     * <ul>
     *   <li>正常出货时：格子被 {@link #pull} 补满，跳过规则完全不生效，出料速率与原版一致
     *       （每次尝试搬 1 个、搬成功进 8 gt 冷却）；</li>
     *   <li>仓库抽空后：格子只剩分类物，跳过规则接管，下游容器<b>吸不走</b>它。</li>
     * </ul>
     *
     * <p>搬运用的是原版自己的 {@link HopperBlockEntity#addItem}，所以下游容器的
     * 各种限制（熔炉只收燃料、朝向过滤、堆叠上限…）全部照旧生效，一个都不绕过。</p>
     *
     * @return 是否真的搬走了 1 个（原版用这个返回值决定要不要进 8 gt 冷却）
     */
    public static boolean eject(ServerLevel level, BlockPos pos, HopperBlockEntity hopper) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof HopperBlock)) {
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        Container target = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
        if (target == null) {
            return false;
        }
        // 与原版一致的快筛：下游一个位置都放不下时直接返回，避免白跑 5 次 addItem
        Direction side = facing.getOpposite();
        if (isFullContainer(target, side)) {
            return false;
        }
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || stack.getCount() <= KEEP_AS_CLASSIFIER) {
                // 空格子没东西可搬；只剩分类物的格子**不许搬** —— 下游容器不能吸收分类物
                continue;
            }
            // 每次只搬 1 个，与原版速率一致
            ItemStack leftover = HopperBlockEntity.addItem(hopper, target, stack.copyWithCount(1), side);
            if (leftover.isEmpty()) {
                hopper.setItem(i, stack.copyWithCount(stack.getCount() - 1));
                target.setChanged();
                hopper.setChanged();
                return true;
            }
        }
        return false;
    }
"""

EX_NEW_EJECT = """    /**
     * 出料（接管原版 {@code ejectItems}）：把漏斗里的物品送进下游容器，
     * <b>但每格永远留下 {@link #KEEP_AS_CLASSIFIER} 个</b>，而且<b>整格搬</b>（无速率上限）。
     *
     * <h2>与原版的差别（两处）</h2>
     * <ol>
     *   <li><b>整格搬</b>：原版 {@code ejectItems} 是「从头扫、谁非空就 {@code removeItem(slot, 1)}
     *       搬走 1 个」；这里改成把该格<b>除余量以外的全部</b>一次性推下去。
     *       配合调用方在 {@code pushItemsTick} 收尾把冷却清零，下游每个 tick
     *       都能拿到漏斗那一刻的全部存货 —— 与入库端 {@code VoidWarehouseHopper}
     *       的「整格搬」是同一个口径。</li>
     *   <li><b>余量保护</b>：数量 ≤ {@link #KEEP_AS_CLASSIFIER} 的格子直接跳过 ——
     *       那是分类物，绝不能外流。</li>
     * </ol>
     * <p>于是：正常出货时格子被 {@link #pull} 补满 → 整格推下去；仓库抽空后
     * 格子只剩那 1 个分类物 → 跳过，下游容器<b>吸不走</b>它，分类不会丢。</p>
     *
     * <h2>下游容器的限制一条都不绕过</h2>
     * <p>搬运用的是原版自己的 {@link HopperBlockEntity#addItem}，所以「熔炉只收燃料、
     * 朝向过滤、堆叠上限、容器白名单」全部照旧生效。<b>装不下就是装不下</b>：
     * {@code addItem} 会把剩菜原样退回来，这里把退回来的那部分写回漏斗原格
     * （不是丢掉，也不是硬塞）—— 既不会丢东西，也不会让一次出料越过下游容量的上限。</p>
     *
     * @return 是否真的搬走了东西（原版用这个返回值决定要不要进 8 gt 冷却；
     *         本模组在 {@code pushItemsTick} 收尾把冷却清零，所以这个 8 不再起作用）
     */
    public static boolean eject(ServerLevel level, BlockPos pos, HopperBlockEntity hopper) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof HopperBlock)) {
            return false;
        }
        Direction facing = state.getValue(HopperBlock.FACING);
        Container target = HopperBlockEntity.getContainerAt(level, pos.relative(facing));
        if (target == null) {
            return false;
        }
        // 与原版一致的快筛：下游一个位置都放不下时直接返回，避免白跑 5 次 addItem
        Direction side = facing.getOpposite();
        if (isFullContainer(target, side)) {
            return false;
        }
        boolean moved = false;
        int slots = hopper.getContainerSize();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.isEmpty() || stack.getCount() <= KEEP_AS_CLASSIFIER) {
                // 空格子没东西可搬；只剩分类物的格子**不许搬** —— 下游容器不能吸收分类物
                continue;
            }
            // 整格搬：留下 KEEP_AS_CLASSIFIER 个当分类物，其余全部推下去
            int payload = stack.getCount() - KEEP_AS_CLASSIFIER;
            ItemStack leftover = HopperBlockEntity.addItem(hopper, target, stack.copyWithCount(payload), side);
            int accepted = payload - leftover.getCount();
            if (accepted <= 0) {
                // 下游这一格吃不下（满 / 不是它收的品类）→ 换下一格试试
                continue;
            }
            // 只减掉真被收下的那部分：退回来的（leftover）原样留在漏斗这一格里。
            // 用 copyWithCount 造新实例，绝不把同一个 ItemStack 挂在两处（防复制）。
            hopper.setItem(i, stack.copyWithCount(stack.getCount() - accepted));
            moved = true;
        }
        if (moved) {
            target.setChanged();
            hopper.setChanged();
        }
        return moved;
    }
"""

# ---- ② VoidWarehouseHopperFilter：resetCooldown 的 javadoc ----
FL_OLD = """     * <p>原版漏斗「每 8 gt 才动一次」的节流全靠这个字段：{@code tryMoveItems} 开头检查
     * {@code isOnCooldown()}（>0 就直接出队），结尾只要搬成了就 {@code setCooldown(8)}。
     * 入库漏斗要无速率上限，就必须在<b>整条 tick 走完之后</b>把它清掉。</p>
"""

FL_NEW = """     * <p>原版漏斗「每 8 gt 才动一次」的节流全靠这个字段：{@code tryMoveItems} 开头检查
     * {@code isOnCooldown()}（>0 就直接出队），结尾只要搬成了就 {@code setCooldown(8)}。
     * <b>入库漏斗与出料漏斗都要无速率上限</b>，所以两者都要在<b>整条 tick 走完之后</b>把它清掉
     * （见 {@code HopperBlockEntityMixin#carpetAutomending$unlimitedCooldown}）。</p>
"""

# ---- ③ mixin：③ 节标题 + 正文 ----
MX_OLD_SEC3 = """ * <h2>③ 分类物保护 —— 接管 {@code ejectItems}</h2>
 * <p>出料模式下，漏斗每格物品的<b>最后 1 个</b>是分类物，绝不能被下游容器吸走
 * （不然仓库一抽空，分类就没了）。原版 {@code ejectItems} 是「从头扫、谁非空就搬 1 个」，
 * 会毫不犹豫地把最后一并搬走，所以这里在 HEAD 处整段接管，换成
 * {@link VoidWarehouseExtractor#eject}（逻辑与原版只差「跳过数量 ≤ 1 的格子」这一处，
 * 下游容器的各种限制仍然照旧生效）。</p>
 * <p>非出料模式的漏斗一个字节都不碰，走的还是原版方法体。</p>
"""

MX_NEW_SEC3 = """ * <h2>③ 分类物保护 + 无上限出料 —— 接管 {@code ejectItems}</h2>
 * <p>出料模式下，漏斗每格物品的<b>最后 1 个</b>是分类物，绝不能被下游容器吸走
 * （不然仓库一抽空，分类就没了）。原版 {@code ejectItems} 是「从头扫、谁非空就搬 1 个」，
 * 会毫不犹豫地把最后一并搬走，所以这里在 HEAD 处整段接管，换成
 * {@link VoidWarehouseExtractor#eject}（跳过数量 ≤ 1 的格子，并把该格其余存货<b>整格</b>推下去）。</p>
 * <p>整格搬 + 收尾清冷却（{@link #carpetAutomending$unlimitedCooldown}）合起来，
 * 出料端就与入库端同一个速率口径：<b>一 tick 把漏斗能倒的全倒出去</b>，
 * 下游容器的容量才是唯一的限制。</p>
 * <p>非出料模式的漏斗一个字节都不碰，走的还是原版方法体。</p>
"""

# ---- ③ mixin：unlimitedCooldown 的 javadoc + 判定（整段替换） ----
MX_OLD_TAIL = """     * <b>把 8 gt 节流真正拆掉</b>：整条 tick 走完之后，把入库漏斗的搬运冷却清零。
"""
MX_NEW_TAIL_HEAD = """     * <b>把 8 gt 节流真正拆掉</b>：整条 tick 走完之后，把这条漏斗（入库<b>或</b>出料）的搬运冷却清零。
"""

MX_OLD_TAIL_NOTE = """     * <p>这一手与 Carpet-TIS 的 {@code hopperCountersUnlimitedSpeed} 收尾的
     * {@code setCooldown(0)} 是同一个意思：让漏斗永远处于「冷却已过」的状态。</p>
     *
     * <p>注意：<b>搬运本身已经不依赖这个cool down了</b>（{@code intake} 挂在 HEAD，每 tick 必跑），
     * 这里清零只是顺手把原版那两条路径（{@code suckInItems} 本体、
     * 以及 {@code entityInside} 触发的 {@code tryMoveItems}）也放开，
     * 让它们不再被 8 gt 卡着。只对入库漏斗生效，普通漏斗一个字节都不碰。</p>
"""

MX_NEW_TAIL_NOTE = """     * <h2>为什么出料端也要清（本轮新增）</h2>
     * <p>出料漏斗的两段是「{@link VoidWarehouseExtractor#pull} 挂 HEAD 每 tick 补满」
     * 与「{@link VoidWarehouseExtractor#eject} 接管原版出料」。第二段是走
     * {@code ejectItems} 的，<b>它搬成功就会触发 {@code setCooldown(8)}</b> ——
     * 只清入库不清出料的话，仓库这端每 tick 补满、下游却每 8 gt 才被喂一次，
     * 「入库无上限、出库 2.5 个/秒」这种自相矛盾的状态就出现了。
     * 现在两端共用同一个收尾，速率才真正对齐（都是「一 tick 搬空」）。</p>
     *
     * <p>这一手与 Carpet-TIS 的 {@code hopperCountersUnlimitedSpeed} 收尾的
     * {@code setCooldown(0)} 是同一个意思：让漏斗永远处于「冷却已过」的状态。</p>
     *
     * <p>注意：<b>搬运本身已经不依赖这个冷却了</b>（{@code intake} / {@code pull} 都挂在 HEAD，
     * 每 tick 必跑），这里清零只是把原版那两条路径也放开 ——
     * 对入库漏斗是 {@code suckInItems} 本体、{@code entityInside} 触发的那次；
     * 对出料漏斗就是 {@code tryMoveItems} 里的 {@code ejectItems}。
     * <b>普通漏斗一个字节都不碰</b>（两个判定都不满足时直接返回）。</p>
"""

MX_OLD_JUDGE = """        if (!VoidWarehouseHopper.isIntake(serverLevel, pos, state)) {
            return;
        }
        ((VoidWarehouseHopperFilter) hopper).resetCooldown();
    }
"""

MX_NEW_JUDGE = """        // 入库或出料，两者都是「本模组接管的漏斗」，一律清冷却；其余漏斗（普通漏斗）原样不动
        if (!VoidWarehouseHopper.isIntake(serverLevel, pos, state)
                && !VoidWarehouseExtractor.isExtractor(serverLevel, pos, state)) {
            return;
        }
        ((VoidWarehouseHopperFilter) hopper).resetCooldown();
    }
"""


def crlf(text, s):
    """源文件可能是 CRLF 而字面量是 LF —— 统一成源文件的换行再匹配"""
    return s.replace("\n", "\r\n") if "\r\n" in text else s


def read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def patch(path, old, new, label):
    text = read(path)
    old_c, new_c = crlf(text, old), crlf(text, new)
    if new_c in text:
        print("   %-30s 已经是新的，跳过" % label)
        return
    if old_c not in text:
        print("!! %s: 找不到待替换片段 [%s]" % (path, label))
        sys.exit(1)
    write(path, text.replace(old_c, new_c, 1))
    print("   %-30s OK" % label)


def main():
    for ver in TARGETS:
        base = os.path.join(ROOT, ver, PKG)
        extractor = os.path.join(base, "VoidWarehouseExtractor.java")
        filt = os.path.join(base, "VoidWarehouseHopperFilter.java")
        mixin = os.path.join(base, "mixin", "HopperBlockEntityMixin.java")
        print("== %s" % ver)
        patch(extractor, EX_OLD_SPEED, EX_NEW_SPEED, "类 javadoc 速率段")
        patch(extractor, EX_OLD_KEEP, EX_NEW_KEEP, "分类物段那句")
        patch(extractor, EX_OLD_EJECT, EX_NEW_EJECT, "eject javadoc+方法体")
        patch(filt, FL_OLD, FL_NEW, "resetCooldown javadoc")
        patch(mixin, MX_OLD_SEC3, MX_NEW_SEC3, "③ 节标题+正文")
        patch(mixin, MX_OLD_TAIL, MX_NEW_TAIL_HEAD, "unlimitedCooldown 标题")
        patch(mixin, MX_OLD_TAIL_NOTE, MX_NEW_TAIL_NOTE, "unlimitedCooldown 说明")
        patch(mixin, MX_OLD_JUDGE, MX_NEW_JUDGE, "unlimitedCooldown 判定")
    print("完成。接下来：")
    print("  1) 四版本逐个 build（逐版本跑，别全量）")
    print("  2) 五版本 runServer 自检（判据：日志有 SMOKE RESULT ... failures=0）")
    print("  3) 性能基准 + 文档 + 工作记忆 + git")


if __name__ == "__main__":
    main()
