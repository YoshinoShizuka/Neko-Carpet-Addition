## ✨ What's new in 1.0.16

### Block drops go straight into the void warehouse (`voidWarehouseBlockDrops`)
Blocks you mine drop **directly into the shared warehouse** instead of landing on the ground — no inventory slots used, unaffected by a full inventory.

- Vanilla loot rules completely untouched: enchants, Fortune, Silk Touch, seeds from grass — all computed by vanilla before depositing (intercepts the single funnel `ServerLevel#addFreshEntity`)
- **Only blocks broken by a player** — TNT, creepers, pistons, mob drops and player-thrown items behave vanilla
- Deposits merge exactly like the hopper intake does; a chat summary (same style as the restock notice) reports what you mined and what the warehouse now holds, coalesced to one line per second
- **Never swallows items** — every failure path falls back to vanilla behaviour
- Disabled by default; requires `voidWarehouse`

### Printer leftovers return to the warehouse
litematica-printer now returns unused leftover stacks to the warehouse when finishing a build — a schematic with dozens of material types no longer clogs your inventory with partly-used stacks. The server reads its own slots (clients only send slot numbers), so this channel **cannot create items**.

### Void warehouse search bar (server half)
An optional companion **client mod** ([neko-voidwarehouse-search](https://modrinth.com/mod/neko-voidwarehouse-search)) adds a search box on top of the warehouse GUI — filter by **Chinese name, full pinyin, pinyin initials or registry id** (`铁锭` / `tieding` / `td` / `iron_ingot`). The vanilla chest GUI stays untouched; filtering only changes which entries fill each page. Without the client mod nothing changes.

## 🧪 Testing
Server-side self-check: **44 assertions / 7 groups, green on all five targets** (26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11).

## ⚠️ Upgrade notes
- Two new mixins (`ServerLevelAddEntityMixin`, `ServerGamePacketListenerImplMixin` + search/reload plumbing); rule names, config keys and save format unchanged — **just swap the jar**
- New rules are off by default: `/carpet voidWarehouseBlockDrops true` (requires `voidWarehouse`)
- Note: with block drops enabled, mined blocks never touch the ground — collection systems that rely on water-streamed drops will stop working

## 中文
- **方块掉落物直接进虚空仓库**（`voidWarehouseBlockDrops`）：玩家挖掉的方块直接入库，掉落表照原版算；聊天栏说一句、每秒最多一条；失败路径一律照常掉地上
- **打印机余料退回**：打印机收工时把用不完的整组余料退回仓库，只退仓库给的那部分
- **虚空仓库搜索框**：配套客户端模组（见上）在界面上叠加搜索框，中文 / 拼音 / 注册名筛选；服务端 GUI 零改动，不装客户端模组无任何变化
- 直接替换 jar 即可升级，规则名、配置键、存档格式均未变
