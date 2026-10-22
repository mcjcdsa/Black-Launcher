# 布莱克启动器 — Windows 安装包（§8）

## 产物

- `build/dist/BlackLauncher-Setup-1.0.0.exe` — 图形化安装向导（Inno Setup）
- 安装后目录结构：

```
C:\BlackLauncher\                 ← 用户选择的根目录
├─ BlackLauncher\                 ← 启动器程序（固定名）
│  ├─ BlackLauncher.bat
│  ├─ black-launcher-1.0.0.jar
│  ├─ install.json
│  └─ …
└─ MinecraftGame\                 ← 游戏数据（固定名，卸载默认保留）
   ├─ versions / libraries / assets
   ├─ mods / saves / …
   └─ …
```

## 编译步骤

1. 安装 [Inno Setup 6](https://jrsoftware.org/isinfo.php)（勾选 Chinese Simplified 语言包）
2. 在项目根目录执行：

```bat
gradlew.bat stageInstallerPayload
gradlew.bat buildInstaller
```

若未将 `ISCC.exe` 加入 PATH，可设置环境变量：

```bat
set ISCC="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
gradlew.bat buildInstaller
```

## 启动器约定

启动器启动时会：

1. 读取 `BlackLauncher/install.json`
2. 或识别同级 `MinecraftGame` 目录  
并自动将游戏目录指向 `MinecraftGame`，与程序目录分离。

## 卸载

「设置 → 应用」中卸载时会询问是否删除 `MinecraftGame`；默认否，保留存档与模组。

## 杀软说明

本安装包 / 启动器为自制程序，不含恶意行为；部分杀软可能误报，请添加信任后使用。
