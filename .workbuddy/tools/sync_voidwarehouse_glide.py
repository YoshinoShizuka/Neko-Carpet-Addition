#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「虚空仓库界面去掉 8 格距离限制」这一处修复同步到其余四个版本。

背景：VoidWarehouseMenu#stillValid 里有
        player.distanceToSqr(this.openPos) <= MAX_DISTANCE_SQ   // 8 格
      而 ServerPlayer#tick 每 tick 都会
        if (!containerMenu.stillValid(this)) { closeContainer(); }
      ⇒ 鞘翅滑翔时右键开界面，飞出 8 格（约两秒）界面就被原版强行关掉。
      触发物是手里的物品（不是世界里的方块），仓库内容也是按维度存的 SavedData，
      位置在语义上就是不相关的量 ⇒ 直接去掉这条距离闸门。

各版本 VoidWarehouseMenu 有真差异（ContainerInput / ClickType / Prediction.SERVER_ONLY /
VoidWarehouseText.info），所以这里只能打补丁，不能整文件复制。
每处替换都断言「替换前后确实变了」，没变就报错 —— 静默失效比崩掉更可怕。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TARGETS = [
    "modern/26.2",
    "modern/26.1.1",
    "modern/26.1.2",
    "versions/1.21.11",
]

# ---------------------------------------------------------------- 补丁片段

OLD_IMPORT = "import net.minecraft.world.phys.Vec3;\n"

OLD_CONST = """    /** 打开界面的位置允许的最大距离（单位：格），对齐原版箱子的 8 格 */
    public static final double MAX_DISTANCE = 8.0;
    private static final double MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

"""

OLD_FIELD = "    private final Vec3 openPos;\n"
OLD_ASSIGN = "        this.openPos = opener.position();\n"

OLD_VALID = """    /**
     * 对应 Yarn 的 {@code canUse}。返回 false 时原版会安全地关掉界面，
     * 而本类的 {@link #removed(Player)} 不会丢弃仓库内容，所以关掉不会丢东西。
     */
    @Override
    public boolean stillValid(Player player) {
        // 规则被关掉时，已经打开的界面也要安全关闭
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!player.isAlive()) {
            return false;
        }
        // 切维度校验：跨维度时坐标没有可比性，直接让界面失效
        if (!player.level().dimension().equals(this.openDimension)) {
            return false;
        }
        return player.distanceToSqr(this.openPos) <= MAX_DISTANCE_SQ;
    }
"""

NEW_VALID = """    /**
     * 对应 Yarn 的 {@code canUse}。返回 false 时原版会在下一次 tick 安全地关掉界面
     * （{@code ServerPlayer#tick} 里就是 {@code if (!containerMenu.stillValid(this)) closeContainer()}），
     * 而本类的 {@link #removed(Player)} 不会丢弃仓库内容，所以关掉不会丢东西。
     *
     * <p><b>这里刻意没有「离打开位置不能超过 8 格」这类距离限制</b>。
     * 曾经有（对齐原版箱子的 8 格），但那是把虚拟仓库当成了「世界里的一个方块容器」：
     * 玩家鞘翅滑翔时右键开界面，飞出 8 格后界面就被原版强行关掉（约两秒），
     * 疾跑 / 骑马 / 划船同理。而实际上：</p>
     * <ul>
     *   <li>触发物是<b>手里的物品</b> —— {@link VoidWarehouseTrigger#isVoidWarehouse} 只判
     *       「工作台 + 自定义名」，对空气右键也能开，压根没有「容器方块」可以走远；</li>
     *   <li>内容存在 {@link VoidWarehouseData}（按维度的 SavedData）里，不在任何方块上。</li>
     * </ul>
     * 所以位置在语义上就是不相关的量，拿来当闸门只会误伤。仍然保留的三道闸门是：
     * 规则开关、玩家存活、维度一致（跨维度时坐标没有可比性，且仓库数据本身就是按维度存的）。
     */
    @Override
    public boolean stillValid(Player player) {
        // 规则被关掉时，已经打开的界面也要安全关闭
        if (!VoidWarehouseSettings.voidWarehouse) {
            return false;
        }
        if (!player.isAlive()) {
            return false;
        }
        // 切维度校验：跨维度时坐标没有可比性，直接让界面失效
        return player.level().dimension().equals(this.openDimension);
    }
"""

OLD_PAGE_DOC = "    /** 距离 / 存活校验统一放在 {@link VoidWarehouseMenu#stillValid(Player)}，这里没有「有效范围」概念 */"
NEW_PAGE_DOC = "    /** 生效 / 失效的判定统一放在 {@link VoidWarehouseMenu#stillValid(Player)}，这里没有「有效范围」概念 */"


def patch(path, old, new, label):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    if new in text and old not in text:
        print("   %-22s 已经是新的，跳过" % label)
        return
    if old not in text:
        print("!! %s: 找不到待替换片段 [%s]" % (path, label))
        sys.exit(1)
    text = text.replace(old, new, 1)
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print("   %-22s OK" % label)


def main():
    for ver in TARGETS:
        menu = os.path.join(ROOT, ver, "src/main/java/neko/voidwarehouse/VoidWarehouseMenu.java")
        page = os.path.join(
            ROOT, ver, "src/main/java/neko/voidwarehouse/VoidWarehousePageContainer.java")
        print("== %s" % ver)
        patch(menu, OLD_IMPORT, "", "删 Vec3 import")
        patch(menu, OLD_CONST, "", "删 MAX_DISTANCE 常量")
        patch(menu, OLD_FIELD, "", "删 openPos 字段")
        patch(menu, OLD_ASSIGN, "", "删 openPos 赋值")
        patch(menu, OLD_VALID, NEW_VALID, "stillValid 去距离")
        patch(page, OLD_PAGE_DOC, NEW_PAGE_DOC, "PageContainer 注释")
    print("完成。接下来：")
    print("  1) 五版本全部 build")
    print("  2) 26.3 部署到实机")
    print("  3) 文档 + 工作记忆 + git")


if __name__ == "__main__":
    main()
