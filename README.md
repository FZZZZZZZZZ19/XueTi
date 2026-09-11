# 学缇 (XueTi)

一款简洁的**英语学习 App**。v1.2 版本包含基础 UI 框架、核心功能**六级单词每日学习**（内置完整六级词库 6662 词），以及外观定制、应用内更新与每日提醒。

> 📦 **直接下载安装**：[GitHub Releases v1.2](https://github.com/FZZZZZZZZZ19/XueTi/releases/tag/v1.2)（`XueTi-v1.2-debug.apk`，Debug 签名，手机下载后直接安装）

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 首页 | 今日学习进度卡片（已学 / 目标 + 进度条 + 状态提示）、功能入口宫格 |
| **每日单词**（v1.0 核心） | 卡片式学习：正面单词 + 音标，点击卡片翻转查看词性、释义、例句与翻译 |
| 认识 / 不认识 | 「认识」标记掌握；「不认识」自动排到本轮队尾再练一次（不重复计入今日进度） |
| 词库进度 | 按每日目标（默认 20 词）从未学词中取词，学完整轮显示完成页 |
| 我的词库 | 已学单词列表（含音标、释义、认识/需复习标签）与统计 |
| 设置 | 每日学习目标（5/10/20/30/50 词）、清空学习记录、版本信息 |
| **界面风格**（v1.2） | 5 套配色自选：默认紫 / 海洋蓝 / 森林绿 / 日落橙 / 樱花粉（含深色模式适配，切换立即生效） |
| **自定义背景图**（v1.2） | 从相册选择图片作为全局背景，可调「淡化程度」（0-95）保证文字可读，一键恢复默认背景 |
| **应用内更新**（v1.2） | 直连 GitHub 仓库 Release 检查新版本：显示版本号与更新说明 → 应用内下载 APK → 调起系统安装；支持启动时自动检查（每天一次，可关闭） |
| **每日提醒**（v1.2） | 通知栏每日提醒学习：自定义提醒时间，通知内容显示今日进度与剩余词数，点击直达学习页 |
| 界面 | Material 3 风格，支持深色模式 |

## 内置词库（完整六级词汇）

- `app/src/main/assets/cet6_words.json` —— **6662 个单词**（完整六级大纲：六级词汇 + 四级基础词）
  - 数据来源：开源词库 [KyleBing/dict](https://github.com/KyleBing/dict) 的 6 本词书（CET6_1/2/3 + CET4_1/2/3）并集去重
  - 每词包含：单词、音标（英式优先）、词性、中文释义（最多 4 条）、英文例句、例句翻译
  - **6468 词带例句**（约 97%），无例句的词条自动隐藏例句区域
  - 按"六级核心词在前、四级基础词在后"的顺序排列，学习时优先遇到六级词
- 词库为纯 JSON，可继续扩充或替换为其他考试词库（保持 `word/phonetic/pos/meaning/example/exampleCn` 字段即可）

### 大词库性能处理

| 措施 | 说明 |
| --- | --- |
| 流式解析 | 用 `android.util.JsonReader` 逐条读取，不构建完整 JSON 树（比 org.json 更快、内存占用更低） |
| 异步加载 | 解析在 `Dispatchers.IO` 执行 + Mutex 保证只解析一次，主线程不阻塞 |
| 冷启动预载 | `Application.onCreate` 后台预加载词库，用户点进学习页时通常已就绪 |
| 加载态 | 极端情况下学习页显示"正在加载词库…"，避免白屏

## 构建 APK

### 已附带可安装 APK

`apk/XueTi-v1.2-debug.apk`（Debug 签名，直接安装到手机）

```bash
adb install -r apk/XueTi-v1.2-debug.apk
```

### 从源码构建

```bash
# 环境：JDK 17+、Android SDK（compileSdk 37 / build-tools 36.0.0）
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 打开项目根目录也可直接 Sync + Build。

## 项目结构

```
app/src/main/
├── assets/cet6_words.json               # 六级词库（6662 词）
├── java/com/xueti/learn/
│   ├── App.kt                           # Application：词库/进度/设置 + 通知渠道 + 提醒恢复
│   ├── MainActivity.kt                  # 首页（今日进度 + 功能入口 + 启动自动检查更新）
│   ├── StudyActivity.kt                 # 每日单词学习（卡片翻转 / 认识度判断 / 重新排队）
│   ├── WordListActivity.kt              # 我的词库（已学单词列表）
│   ├── SettingsActivity.kt              # 设置中心（目标/提醒/外观/更新/数据）
│   ├── adapter/WordListAdapter.kt
│   ├── base/BaseActivity.kt             # 套用界面风格 + 自定义背景
│   ├── data/
│   │   ├── WordRepository.kt            # 词库流式解析（JsonReader，异步 + 缓存）
│   │   ├── ProgressStore.kt             # 学习进度持久化
│   │   └── SettingsStore.kt             # 应用设置（风格/背景/更新/提醒）
│   ├── model/Word.kt                    # Word / Familiarity / LearnRecord
│   ├── update/
│   │   ├── UpdateChecker.kt             # 直连 GitHub Release 检查版本
│   │   ├── ApkDownloader.kt             # 下载 APK + FileProvider 安装
│   │   └── UpdateUi.kt                  # 更新弹窗 / 下载进度
│   ├── util/
│   │   ├── ThemeStyle.kt                # 5 套界面风格
│   │   ├── BackgroundHelper.kt          # 背景图解码（降采样）+ 蒙版
│   │   └── NotificationHelper.kt        # 通知渠道
│   └── work/
│       ├── ReminderWorker.kt            # 每日提醒通知
│       └── ReminderScheduler.kt         # WorkManager 周期调度
└── res/                                 # 布局、5 套主题（含 values-night）、图标、字符串
```

## 技术要点

- **Kotlin + Material 3 + ViewBinding**，minSdk 26 / targetSdk 34
- 构建：AGP 9.3.0（**使用内置 Kotlin，不应用 KGP 插件**）+ Gradle 9.7.1 + JDK 17
- 进度持久化：SharedPreferences 存 JSON；「今日已学」按日期自动归零（跨天重置）
- 词库：`JsonReader` 流式解析 1.4MB / 6662 词，IO 线程 + 单次缓存 + 冷启动预载
- 学习流程：队列制；不认识的词重新入队，`counted` 标记避免重复计入今日进度
- 主题：`values` + `values-night` 双套配色；图标全部为 vector drawable（无 PNG 资源）

## 后续规划（未实现）

- 学习统计页（每日曲线、记忆曲线）
- 单词发音（TTS）
- 复习模式（艾宾浩斯间隔复习）
- 词库扩充 / 导入自定义词库
- 学习提醒（通知）

## 版本

| 版本 | 内容 |
| --- | --- |
| v1.0 | 基础 UI（首页 / 我的词库 / 设置）+ 六级单词每日学习功能（示例词库 103 词） |
| v1.1 | **完整六级词库 6662 词**（含四级基础，97% 带例句）；词库改为流式解析 + 异步加载 + 冷启动预载；无例句词条自动隐藏例句区 |
| v1.2 | **界面风格自选**（5 套配色，含深色适配）；**自定义背景图**（选图 + 淡化程度 + 恢复默认）；**应用内更新**（直连 GitHub Release 检查 → 下载 → 安装，支持启动自动检查）；**每日通知提醒**（自定义时间 + 进度提示 + 点击进学习页） |
