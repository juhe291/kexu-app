# 课序 · 大学生智能待办与课表 Android App

「课序」是一个**离线优先**的大学生课程表 / 待办 / 时间规划 App：导入教务课表，记录任务与截止日期，自动在课程之外的空闲时间给出安排建议，并用通知 / 精确闹钟提醒，最后按周复盘。

- 平台：Android 8.0+（minSdk 26 / targetSdk 35），Kotlin + Jetpack Compose + Room
- 最新正式版 APK：见 [Releases](https://github.com/juhe291/kexu-app/releases)
- 数据保存在本机；仅「检查更新」会联网访问 GitHub Releases，不上传任何数据

## 功能

- **多格式智能课表导入引擎**：CSV / ICS / PDF（文字型 + 扫描版）/ XLSX / HTML / 图片（PNG、JPG）/ JSON 模板；本地 ML Kit 中文 OCR 离线识别；统一可编辑预览（逐条勾选、改课程名/教师/地点/星期/节次/周次、实时校验冲突与重复、确认后事务写库）；外部 AI 模板导出（CSV / JSON / 提示词）
- **课表**：固定日期栏、双向滑动、双指缩放、课程色块 / 任务虚线块、午休晚餐横带、单双周与非连续周次
- **课程单次例外**：停课 / 调课（改星期、节次、教室），只影响该教学周、可撤销；课表 / 今天 / 规划 / 提醒全部跟随
- **今天**：下一节课大卡片、今日日程时间轴、截止提醒、待安排任务、长期学习目标、本周复盘
- **待办**：筛选 / 排序、自选时间、每天 / 每周重复任务、长按更多操作、长期学习目标推进
- **智能规划**：按课程与空闲时间生成建议、换任务 / 换时间 / 确认加入日程
- **提醒**：WorkManager 兜底 + AlarmManager 精确闹钟（Android 12+ 授权后准点触发，重启 / 改时区自动重建）
- **数据**：Room v3（1→2→3 迁移）、JSON 备份恢复、ICS 课程表导出、任务 CSV 导出
- **外观**：浅色 / 深色 / 跟随系统、主题色、四档字号、辅助说明透明度、色块透明度、玻璃拟态与玻璃边缘畸变
- **检查更新**：从 GitHub Releases 读取最新版本并引导下载（仅此功能联网）

## 技术栈

- Kotlin 1.9.25、Jetpack Compose（BOM 2024.10.01）、Material 3
- Room 2.6.1（KSP）、DataStore Preferences、WorkManager 2.9.1
- PDFBox-Android 2.0.27、jsoup 1.17、ML Kit 中文文字识别（bundled，离线 OCR）
- 架构：`domain/` 纯 Kotlin 逻辑（周次解析、课表结构恢复、排程与提醒计划、重复任务等，均有 JVM 单测）→ `data/` Room/DataStore → `ui/` Compose 页面

## 构建

环境：JDK 17、Android SDK Platform 35 / Build-Tools 35.0.0。

```bash
cd 课序App
./gradlew :app:assembleDebug        # 调试版
./gradlew :app:testDebugUnitTest    # JVM 单元测试
./gradlew :app:assembleRelease      # 正式版（R8；需自备签名配置）
```

`local.properties` 由本机 Android Studio/Gradle 生成，不随仓库上传；项目路径含中文时需开启 `android.overridePathCheck=true`（已配置）。

## 测试

- JVM 测试 203 个，覆盖：教学周 / 单双周解析、PDF 版面恢复、XLSX / HTML / CSV / ICS / JSON 导入、OCR 表格结构恢复、导入校验、备份往返、Room 迁移、停课调课、重复任务、提醒计划、检查更新等。
- **真实课表测试样例（PDF / XLSX / expected.csv）含个人信息，不在本仓库提供**；缺少样例时相关测试会自动跳过。想运行可把自己的课表放到 `课序App/app/src/test/resources/`：
  - `cug_wh_timetable.pdf`、`cug_wh_timetable.xlsx`、`cug_expected.csv`

## 目录结构

```
课序App/                     # Android 工程（Kotlin + Compose）
├─ app/src/main/java/com/kexu/app/
│  ├─ domain/                # 纯逻辑：周次、日期、课表结构、排程、提醒、重复任务
│  ├─ data/                  # Room、DataStore、导入解析器、检查更新
│  ├─ pdf/ ocr/              # PDF 版面恢复、本地 OCR 流水线
│  ├─ ui/                    # Compose 页面、组件、主题
│  └─ notify/                # WorkManager / AlarmManager 提醒
├─ app/src/test/             # JVM 单元测试
├─ app/schemas/              # Room schema 导出
└─ README.md                 # 版本更新记录
```

## 隐私

- 核心功能完全离线：课程、任务、设置只保存在本机 Room/DataStore，不收集个人信息。
- 「外部 AI 导入」只提供模板与提示词，由用户交给自己的 AI 生成文件；App 不读取聊天软件内容。
- 唯一联网功能是「检查更新」：只在用户点击时请求一次 GitHub Releases API，不携带、不上传任何本地数据。
- 本公开仓库不含真实个人课表数据。

## 已知限制

- 非「中国地质大学（武汉）」的文字型 PDF 目前是「地大适配器 → 失败后本地 OCR 兜底」；其他学校建议导出 CSV/JSON 模板交给 AI 转换，或直接用图片/扫描版 OCR。
- 无限 RRULE 的 ICS 事件周次留空（按每周解释，并在预览标记待确认）。
- 课表导入暂不支持解析中途取消（一般数秒内完成）。
- 正式版 R8 包与 debug 包同签名，可直接覆盖安装；上架应用商店需自行配置 release 签名。
- 桌面小组件、周报增强不在当前 1.x 功能范围。

## 版本记录

见 [课序App/README.md](课序App/README.md)。
