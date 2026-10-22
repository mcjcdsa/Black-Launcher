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

- 版本下载（BMCLAPI）、Fabric / Forge / Quilt / NeoForge
- Modrinth 模组 / 光影 / 整合包浏览与安装
- 整合包导入导出（`.zip` / `.mrpack`，支持拖拽）
- 微软正版登录（设备码 / PKCE）
- 版本设置、路径迁移、独立启动脚本导出

## 许可

见安装包内 [`installer/license.txt`](installer/license.txt) 用户许可协议。
