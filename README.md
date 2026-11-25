# Carpet Neko Addition [![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE) [![Build](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/actions/workflows/build.yml/badge.svg)](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/actions/workflows/build.yml) ![Minecraft](https://img.shields.io/badge/Minecraft-26.3%20%7C%2026.2%20%7C%2026.1.2%20%7C%2026.1.1%20%7C%201.21.11-2ea043) ![Side](https://img.shields.io/badge/side-%E7%BA%AF%E6%9C%8D%E5%8A%A1%E7%AB%AF-informational)

纯服务端 Carpet 扩展，面向技术生存与生电。全部规则默认关闭，客户端可为原版。

## 规则

| 规则 | 默认 | 说明 |
| --- | --- | --- |
| `autoMending` | `false` | 每秒把一件带经验修补的物品修满 |
| `voidWarehouse` | `false` | 铁砧改名的工作台成为全服共享仓库，一种物品一格、数量无上限 |
| `voidWarehouseRestock` | `false` | 允许 litematica-printer 缺料时从仓库领一组（依赖 `voidWarehouse`） |
| `voidWarehouseDirectPlace` | `false` | 打印机缺料时从仓库预扣一件并直接放置（依赖 `voidWarehouse` + `voidWarehouseRestock`） |
| `voidWarehouseExchange` | `false` | 成品缺货时用 N 合 1 配方的原料顶替（依赖 `voidWarehouse`） |
| `voidWarehouseCraft` | `false` | 成品缺货时按学过的配方扣原料（依赖 `voidWarehouse`） |
| `voidWarehouseBlockDrops` | `false` | 玩家挖掉的方块掉落物直接进仓库（依赖 `voidWarehouse`） |
| `concretePowderToGlass` | `false` | 普通熔炉把 16 种混凝土粉末烧成染色玻璃 |

用法 `/carpet <规则> true`，`/carpet list` 查看全部；分类 `neko` / `survival`。

### 虚空仓库速览

铁砧把**工作台**改名为 `虚空仓库`，手持右键开界面；潜行右键方块可摆成终端。一种物品一格、数量无上限，全服共享、跨维度、重启不丢。左键取 1 个、右键取一组；第 45 格切「合成页」，按已学过的配方扣原料现做。漏斗朝终端=入库、终端压漏斗=出料，双向自动化。

装 [neko-client](https://github.com/YoshinoShizuka/neko-client) 可叠加搜索框、快捷键开仓、`Ctrl+Alt` 填满背包。

## 依赖

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 必需 | Fabric 运行所需 API |
| [Carpet](https://modrinth.com/mod/carpet) | 必需 | 规则框架，版本需 ≥ 对应 MC 版本 |
| [litematica-printer](https://github.com/Yur1Ca/litematica-printer) | 可选 | 虚空仓库补货 / 直接放置的客户端 |
| [neko-client](https://github.com/YoshinoShizuka/neko-client) | 可选 | 搜索框、快捷键开仓、填满背包手势 |

## 版本支持

| MC 版本 | 子工程 | 工具链 |
| --- | --- | --- |
| 26.3 / 26.2 / 26.1.2 / 26.1.1 | `modern/<版本>` | 不混淆，`fabric-loom`，Java 25 |
| 1.21.11 | `versions/1.21.11` | 混淆，`fabric-loom-remap` + Mojang 映射，Java 21 |

纯逻辑（设置、翻译、算法）在 `common/`，各版本以 `implementation project(':common')` 引用。

## 安装

1. 服务端装 **Fabric Loader ≥ 0.16.0**，`mods/` 放 **Fabric API** + **Carpet** + 本模组（按 MC 版本选 `carpet-Neko-Addition-mc<版本>-<模组版本>.jar`）。
2. 启动后用 `/carpet <规则> true` 逐条开启。
3. 客户端可原版直连；要用补货 / 直放另装 `litematica-printer`，要搜索框另装 `neko-client`。

从 [Releases](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/releases/latest) 下载产物。

## 构建

需要 **JDK 25** 与 **Gradle 9.7.0**（wrapper 已固定）。**逐版本**构建，一次只跑一个子工程：

```bat
.\gradlew.bat :modern:26.3:build
```

产物在 `build/libs/`，`dist` 任务另复制一份到仓库根 `dist/`。不要一次性 `build`：五个版本挤进同一 JVM，TinyRemapper 在 JDK 25 上会崩（CI 用 matrix 逐版本跑）。

> 旧版 `carpet-automending`（包 `carpetaddition`）的仓库数据不兼容，升级等于换一个新仓库。

## 文档

- [更新日志](CHANGELOG.md)

## 许可证

MIT，完整文本见 [LICENSE](LICENSE)。

## 反馈

请通过 [GitHub Issues](https://github.com/YoshinoShizuka/Neko-Carpet-Addition/issues) 反馈。
