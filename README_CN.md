# GitHelper - AI Generated IntelliJ Plugin

**完全由AI生成的IntelliJ IDEA插件，用于在Git提交时自动格式化代码**

## 🎉 隆重介绍

> **注意：这是一个完全由AI生成的插件！** 本插件的所有代码均由AI助手根据需求自动生成，无需人工编写任何代码。插件实现了完整的功能，包括Git钩子管理、代码格式化、异步处理等高级特性。

## 📝 项目介绍

GitHelper是一个强大的IntelliJ IDEA插件，旨在通过在提交时自动运行代码格式化工具来增强您的Git工作流程。它主要集成了Spotless Maven插件，可以在提交前自动格式化Java文件，确保代码风格的一致性。

### 核心特性

- **智能代码格式化**：在Git提交前自动运行Spotless Maven插件格式化更改的Java文件
- **双模式支持**：
  - Git预提交钩子模式（推荐）：使用异步通信，不阻塞IDE界面
  - IntelliJ提交处理器模式：同步执行，传统方式
- **进度显示**：在格式化过程中显示进度指示器
- **可配置设置**：可启用/禁用功能，自定义Maven可执行文件路径
- **无缝集成**：与Git提交工作流程无缝集成
- **通用兼容性**：使用Git钩子模式时可与任何Git客户端配合工作

## 🚀 安装与使用

### 安装方式

> **注意：此插件目前处于测试阶段，尚未在IntelliJ IDEA插件市场发布。**

1. 从 [Releases](https://github.com/leya521/git-helper/releases) 页面下载插件ZIP文件
2. 在IntelliJ IDEA中，进入 `Settings/Preferences` → `Plugins` → `⚙️` (齿轮图标) → `Install Plugin from Disk...`
3. 选择下载的ZIP文件
4. 重启IDE以激活插件

### 配置与使用

1. **打开设置**：
   - 进入 `Settings/Preferences` → `Tools` → `GitHelper`
   
2. **选择集成方式**：
   - **Git预提交钩子模式（推荐）**：使用异步通信，不会阻塞IDE界面
   - **IntelliJ提交处理器模式**：同步执行，传统方式

3. **配置Git钩子**：
   - 在设置中启用"Auto-install hooks for new projects"
   - 或使用 `Tools` → `Configure Git Hooks` 菜单手动配置
   - 插件会自动在Git仓库中安装预提交钩子

4. **启用Spotless格式化**：
   - 确保"Enable Spotless formatting on commit"选项已勾选
   - 可根据需要自定义Maven可执行文件路径

### 工作原理

#### Git预提交钩子模式（推荐）
- 插件在IDEA中拦截提交操作，启动后台格式化任务
- Git钩子脚本监听状态文件，等待格式化完成
- 格式化完成后自动重新暂存文件并继续提交流程

#### IntelliJ提交处理器模式
- 在提交时直接在IDEA中同步执行Spotless格式化
- 显示进度对话框，用户可取消操作
- 格式化完成后继续正常的提交流程

## 🛠️ 开发说明

### 构建项目

```bash
./gradlew build
```

### 运行插件

```bash
./gradlew runIde
```

### 项目结构

```
src/
├── main/
│   ├── kotlin/
│   │   └── com/noorall/githelper/
│   │       ├── actions/        # 动作处理器
│   │       ├── git/            # Git集成模块
│   │       ├── logging/        # 日志模块
│   │       ├── maven/          # Maven集成模块
│   │       ├── settings/       # 设置模块
│   │       └── status/         # 状态管理模块
│   └── resources/
│       └── META-INF/
│           └── plugin.xml      # 插件配置文件
```

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。

## 🤖 AI生成声明

本插件完全由AI生成，展示了AI在软件开发领域的强大能力。从需求分析、架构设计到代码实现，均由AI助手完成，无需人工干预即可生成功能完整的IntelliJ插件。

## 📞 联系方式

如有任何问题或建议，请联系：[leya5211@gmail.com](mailto:leya5211@gmail.com)