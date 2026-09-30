"""列出玩家 NBT 里的物品（背包 + 装备 + 末影箱），标注 Damage / 附魔 / 耐久。

用途：确认实机里「带经验修补且受损」的物品到底是哪件、Damage 多少。
只读。
"""
import sys

sys.path.insert(0, __file__.rsplit('\\', 1)[0].rsplit('/', 1)[0])
from nbt_dump import load  # noqa: E402


def desc(stack):
    if not isinstance(stack, dict):
        return None
    ident = stack.get('id', '?')
    cnt = stack.get('count', stack.get('Count', 1))
    dmg = stack.get('Damage')
    comps = stack.get('components', {})
    extra = ''
    if isinstance(comps, dict):
        if 'minecraft:damage' in comps:
            dmg = comps['minecraft:damage']
        ench = comps.get('minecraft:enchantments')
        if isinstance(ench, dict):
            lv = ench.get('levels', ench)
            if isinstance(lv, dict):
                extra = ' ench=' + ','.join(f'{k.split(":")[-1]}={v}' for k, v in lv.items())
        if 'minecraft:max_damage' in comps:
            extra += f' max={comps["minecraft:max_damage"]}'
    return f'{ident} x{cnt} dmg={dmg}{extra}'


def walk(root):
    for key in ('Inventory', 'EnderItems'):
        lst = root.get(key)
        if not isinstance(lst, list):
            continue
        print(f'--- {key} ({len(lst)} 项) ---')
        for e in lst:
            if isinstance(e, dict):
                s = e.get('Slot', e.get('slot'))
                print(f'  slot={s:>3}  {desc(e)}')
    # 装备（1.21+ 里在 equipment 组件里，也可能仍在根上）
    for key in ('equipment', 'Equipment'):
        eq = root.get(key)
        if isinstance(eq, dict):
            print(f'--- {key} ---')
            for k, v in eq.items():
                print(f'  {k}: {desc(v) if isinstance(v, dict) else v}')


if __name__ == '__main__':
    walk(load(sys.argv[1]))
