#!/usr/bin/env python3
"""
把 26.3 的本轮文案改动同步到其余四个版本。

本轮四处改动：
  1. VoidWarehouseText    —— 前缀 [信息] → [虚空仓库]；删掉 RESTOCK_PREFIX 与
                             info(String, MutableComponent) 重载（前缀只留一个来源）
  2. VoidWarehouseWithdraw—— 取货成功提示：数量后补物品名；全散货时去掉「（200 散货）」；
                             抽出 successMessage(...) 纯函数（含前缀包装）
  3. PrinterRestockNetwork—— 改用统一前缀；注释跟上
  4. VoidWarehouseMenu    —— 模式开关「开 / 关」改亮绿（新增 switchMessage 辅助）

各版本差异：
  · Text / Withdraw  五版本完全一致 → 整文件复制
  · PrinterRestockNetwork  26.x 一致、1.21.11 有真差异（playC2S/playS2C 等）→ 26.x 整文件复制，
    1.21.11 打补丁
  · Menu  各版本有真差异 → 四个版本全部打补丁

行尾：Text / PrinterRestockNetwork 在 26.2/26.1.1/26.1.2/1.21.11 是 CRLF（26.3 是 LF），
整文件复制时**保留目标原本的行尾**，否则会制造整文件假 diff。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'modern/26.3/src/main/java/neko/voidwarehouse'

ALL = {
    '26.2': ROOT / 'modern/26.2/src/main/java/neko/voidwarehouse',
    '26.1.1': ROOT / 'modern/26.1.1/src/main/java/neko/voidwarehouse',
    '26.1.2': ROOT / 'modern/26.1.2/src/main/java/neko/voidwarehouse',
    '1.21.11': ROOT / 'versions/1.21.11/src/main/java/neko/voidwarehouse',
}
MODERN = {k: v for k, v in ALL.items() if k != '1.21.11'}


def read_norm(path):
    return Path(path).read_bytes().decode('utf-8').replace('\r\n', '\n')


def write_keep_eol(path, text):
    """按目标文件原本的行尾写回（LF → CRLF 转换按目标决定）"""
    raw = Path(path).read_bytes()
    eol = '\r\n' if b'\r\n' in raw else '\n'
    Path(path).write_bytes(text.replace('\r\n', '\n').replace('\n', eol).encode('utf-8'))


def copy_whole(rel, targets):
    text = read_norm(SRC / rel)
    for name, base in targets.items():
        write_keep_eol(base / rel, text)
    print(f'[copy] {rel} → {", ".join(targets)}')


def patch(rel, old, new, what, targets=ALL):
    assert old != new, f'{what}: old == new'
    for name, base in targets.items():
        p = base / rel
        text = read_norm(p)
        n = text.count(old)
        assert n == 1, f'{name}: 锚点出现 {n} 次，期望 1 次（{what}）\n---\n{old}\n---'
        out = text.replace(old, new)
        assert out != text, f'{name}: {what} 替换后没有变化'
        write_keep_eol(p, out)
        print(f'[patch] {name}: {what}')


def main():
    # ---------------------------------------------------------- 1. Text（整文件）
    copy_whole('VoidWarehouseText.java', ALL)

    # ---------------------------------------------------------- 2. Withdraw（整文件）
    copy_whole('VoidWarehouseWithdraw.java', ALL)

    # ---------------------------------------------------------- 3. PrinterRestockNetwork
    copy_whole('restock/PrinterRestockNetwork.java', MODERN)
    patch('restock/PrinterRestockNetwork.java',
          '        // 前缀是「虚空仓库」而不是默认的「[信息]」—— 这条是自动、高频发的消息，\n'
          '        // 得让玩家一眼看出是谁在补货。\n',
          '        // 前缀走全局统一的「[虚空仓库]」—— 这条是自动、高频发的消息，\n'
          '        // 玩家得一眼看出是谁在补货。\n',
          '补货提示注释（1.21.11）', targets={'1.21.11': ALL['1.21.11']})
    patch('restock/PrinterRestockNetwork.java',
          '        player.sendSystemMessage(VoidWarehouseText.info(VoidWarehouseText.RESTOCK_PREFIX,\n'
          '                VoidWarehouseText.body("取走 ")\n',
          '        player.sendSystemMessage(VoidWarehouseText.info(\n'
          '                VoidWarehouseText.body("取走 ")\n',
          '改用统一前缀（1.21.11）', targets={'1.21.11': ALL['1.21.11']})

    # ---------------------------------------------------------- 4. Menu（打补丁）
    patch('VoidWarehouseMenu.java',
          '    // 聊天提示统一走 VoidWarehouseText.info()：亮金色 [信息] 前缀 + 灰色正文，\n',
          '    // 聊天提示统一走 VoidWarehouseText.info()：亮金色 [虚空仓库] 前缀 + 灰色正文，\n',
          'Menu 提示注释')
    patch('VoidWarehouseMenu.java',
          '    private static final Component MSG_SHULKER_ON = VoidWarehouseText.info("潜影盒模式开");\n'
          '    private static final Component MSG_SHULKER_OFF = VoidWarehouseText.info("潜影盒模式关");\n',
          '    private static final Component MSG_SHULKER_ON = switchMessage("潜影盒模式", "开");\n'
          '    private static final Component MSG_SHULKER_OFF = switchMessage("潜影盒模式", "关");\n',
          '潜影盒开关消息')
    patch('VoidWarehouseMenu.java',
          '    private static final Component MSG_SORT_ON = VoidWarehouseText.info("数量排序开");\n'
          '    private static final Component MSG_SORT_OFF = VoidWarehouseText.info("数量排序关");\n',
          '    private static final Component MSG_SORT_ON = switchMessage("数量排序", "开");\n'
          '    private static final Component MSG_SORT_OFF = switchMessage("数量排序", "关");\n'
          '\n'
          '    /**\n'
          '     * 「xxx：开 / 关」这种模式开关提示。\n'
          '     *\n'
          '     * <p>名称用正文灰、<b>状态用亮绿</b>：玩家点开关时真正要读的是「现在开还是关」，\n'
          '     * 让这两个字自己跳出来，不用把整句读完再回头找结论。</p>\n'
          '     */\n'
          '    private static Component switchMessage(String name, String state) {\n'
          '        return VoidWarehouseText.info(VoidWarehouseText.body(name + "：")\n'
          '                .append(VoidWarehouseText.key(state)));\n'
          '    }\n',
          '排序开关消息 + switchMessage 辅助')

    # ---------------------------------------------------------- 收尾自检
    print('\n=== 校验：不该再有的东西 ===')
    for name, base in ALL.items():
        text = read_norm(base / 'VoidWarehouseText.java')
        assert 'RESTOCK_PREFIX' not in text, f'{name}: Text 里还留着 RESTOCK_PREFIX'
        assert '"[信息] "' not in text, f'{name}: Text 里还留着 [信息] 前缀'
        net = read_norm(base / 'restock/PrinterRestockNetwork.java')
        assert 'RESTOCK_PREFIX' not in net, f'{name}: Network 里还引用 RESTOCK_PREFIX'
        menu = read_norm(base / 'VoidWarehouseMenu.java')
        assert '潜影盒模式开' not in menu, f'{name}: Menu 里还留着「潜影盒模式开」'
        assert 'switchMessage("潜影盒模式", "开")' in menu, f'{name}: Menu 缺 switchMessage 调用'
        print(f'  {name}: OK')
    print('\n全部同步完成。')


if __name__ == '__main__':
    main()
