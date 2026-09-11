# 学缇 (XueTi)

一款简洁的**英语学习 App**。当前 v1.0 版本实现基础 UI 框架与第一个核心功能：**六级单词每日学习**。

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 首页 | 今日学习进度卡片（已学 / 目标 + 进度条 + 状态提示）、功能入口宫格 |
| **每日单词**（v1.0 核心） | 卡片式学习：正面单词 + 音标，点击卡片翻转查看词性、释义、例句与翻译 |
| 认识 / 不认识 | 「认识」标记掌握；「不认识」自动排到本轮队尾再练一次（不重复计入今日进度） |
| 词库进度 | 按每日目标（默认 20 词）从未学词中取词，学完整轮显示完成页 |
| 我的词库 | 已学单词列表（含音标、释义、认识/需复习标签）与统计 |
| 设置 | 每日学习目标（5/10/20/30/50 词）、清空学习记录、版本信息 |
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

`apk/XueTi-v1.1-debug.apk`（Debug 签名，直接安装到手机）

```bash
adb install -r apk/XueTi-v1.1-debug.apk
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
├── assets/cet6_words.json               # 六级词库
├── java/com/xueti/learn/
│   ├── App.kt                           # Application：全局 WordRepository / ProgressStore
│   ├── MainActivity.kt                  # 首页（今日进度 + 功能入口）
│   ├── StudyActivity.kt                 # 每日单词学习（卡片翻转 / 认识度判断 / 重新排队）
│   ├── WordListActivity.kt              # 我的词库（已学单词列表）
│   ├── SettingsActivity.kt              # 设置（每日目标 / 清空记录）
│   ├── adapter/WordListAdapter.kt
│   ├── data/
│   │   ├── WordRepository.kt            # 词库读取（assets JSON，带缓存）
│   │   └── ProgressStore.kt             # 学习进度持久化（SharedPreferences）
│   └── model/Word.kt                    # Word / Familiarity / LearnRecord
└── res/                                 # 布局、主题（含 values-night）、图标、字符串
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
