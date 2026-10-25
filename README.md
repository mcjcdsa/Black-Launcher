# Black Launcher（布莱克启动器）

我的世界 Java 版第三方启动器开源仓库。

仓库地址：[mcjcdsa/Black-Launcher](https://github.com/mcjcdsa/Black-Launcher)

## 环境要求

- JDK 17+
- Windows 10/11（安装包与主开发目标）
- 可选：[Inno Setup 6](https://jrsoftware.org/isinfo.php)（编译 Windows 安装包）

## 运行

```bat
gradlew.bat run
```

或：

```bat
gradlew.bat jar
java -jar build\libs\black-launcher-1.0.0.jar
```

## 打包安装程序

```bat
gradlew.bat stageInstallerPayload buildInstaller
```

生成：`build/dist/BlackLauncher-Setup-1.0.0.exe`  
说明见 [`installer/README.md`](installer/README.md)。

安装后目录结构：

```
{根目录}\
├─ BlackLauncher\     # 启动器程序
└─ MinecraftGame\     # 游戏数据（版本 / 模组 / 存档）
```

## 主要能力

### 版本与下载

- 版本下载与修复（BMCLAPI / 官方源，镜像测速与自动推荐）
- Fabric / Forge / Quilt / NeoForge 安装及配套 API
- 「仅校验」与「补全缺失」分离
- Modrinth 模组 / 光影 / 资源包 / 整合包浏览与安装

### 实例与资源

- 整合包导入导出（`.zip` / `.mrpack`，支持拖拽；完整包 / 轻量更新包）
- 整合包损坏修复（对照索引重下缺失文件）
- 版本复制 / 卸载 / 重命名、反馈包导出
- 模组启用禁用、冲突检查、Modrinth 更新检查
- 内容库：资源包 / 光影 / 数据包；存档备份策略与跨版本迁移
- 服务器列表（`servers.dat`）编辑

### 启动与稳定性

- 微软正版登录（设备码 / PKCE）与离线昵称
- 内存智能调优（按版本 `memory.json`）、系统辅助整理（可选）
- 崩溃诊断、一键复制建议、快速修复、反馈包
- Java 本机扫描与按版本不匹配提示
- 首次启动向导、设置内搜索

### 模组开发

- Fabric 模组模板生成器（对齐 [fabricmc.net/develop/template](https://fabricmc.net/develop/template)）
- 官方开发文档内嵌同步与离线检索（侧栏「模组开发」）

## 代码扫描（CodeQL）

仓库已配置 GitHub Actions 工作流 [`.github/workflows/codeql.yml`](.github/workflows/codeql.yml)，使用 [`github/codeql-action@v4.39.1`](https://github.com/github/codeql-action/releases/tag/v4.39.1)：

- 触发：`push` / `pull_request`（`main`）、每周定时、手动 `workflow_dispatch`
- 语言：Java（手动 `gradlew compileJava`）与 GitHub Actions 工作流
- 查询套件：`security-extended` + `security-and-quality`
- 结果：GitHub → **Security** → **Code scanning**

首次启用若看不到结果，请在仓库 Settings → Code security → Code scanning 中确认 Advanced setup 已生效（Actions 权限需允许 `security-events: write`）。

## 开源范围说明

本仓库为开源部分。以下内容**不开源**，不会出现在本 GitHub 仓库中：

- **联机（局域网）完整实现**（房间 / 本地服务器 / 发现与加入等）：本地开发时放在 `src/private/`，由 Gradle 在目录存在时自动编入；开源克隆仅显示占位页。
- **内部产品规划 / 需求文档**（本地保留，不纳入版本库）。

开源克隆可完整编译并使用除联机实现外的功能。

## 许可

见安装包内 [`installer/license.txt`](installer/license.txt) 用户许可协议。  
应用元数据声明为 MIT License（`AppInfo.LICENSE`）。
