# MyT - Android 实时屏幕翻译工具

<div align="center">

![Version](https://img.shields.io/badge/version-1.4-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![MinSDK](https://img.shields.io/badge/minSdk-24-orange)

 Android 屏幕实时翻译应用，支持多种翻译模式和引擎

[功能特性] • [下载安装]• [使用指南]• [技术架构]
</div>

---

## 功能特性

### 核心功能

支持openai兼容大模型，支持多模态大模型以提高图像识别精准度。
- **多种翻译模式**：
  - 单次翻译：全屏或区域选择翻译
  - 固定区域：循环翻译指定区域（适合游戏对话框）
  - 自动检测：智能检测屏幕变化自动翻译（含字幕模式）
- **灵活显示方式**：
  - 覆盖原文：译文直接覆盖在原文位置
  - 独立窗口：译文显示在可拖动的浮窗中
<div align="center">
  <img src="https://github.com/user-attachments/assets/31ce73b7-0272-4919-89c9-07c6d1618949" width="250" alt="截图1">
  <img src="https://github.com/user-attachments/assets/73164abd-d94a-45c7-aa75-eaadf009e7d2" width="250" alt="截图2">
  <img src="https://github.com/user-attachments/assets/302225f5-5571-4904-8ef9-ccc3cad0cea4" width="250" alt="截图3">
</div>
### 🔧 翻译引擎

#### 本地 OCR 引擎
- **PaddleOCR**：高精度中日韩文字识别
- **ML Kit**：Google 离线 OCR 引擎

#### 翻译引擎
- **微软离线词典**：无需网络的本地翻译
- **免费在线翻译**：谷歌翻译、Bing 翻译
- **自定义 API**：支持 OpenAI 兼容接口
- **多模态视觉模型**：直接识图翻译，跳过本地OCR，精准文本识别
  
   <img src="https://github.com/user-attachments/assets/0703cc4d-385a-42ed-a2e7-64d8b9a2f1f6" width="200" alt="截图3" height="202">  

### 功能

- **漫画优化**：针对日漫竖排文字优化，复杂漫画可使用多模态大模型识别
- **翻译缓存**：LRU 缓存机制，提升重复翻译速度
- **资源管理**：内存监控、HTTP 连接池、OCR 引擎池等优化
- **图文翻译**：支持从相册或相机选择图片进行翻译
- **兽音加解密**：加解密
- **ASCII码进制转换**：加解密
<div align="center">
<img width="1260" height="2800" alt="Screenshot_20260422_230533" src="https://github.com/user-attachments/assets/c01af7ff-eb88-4fb2-b60b-a8048d2db895" />
<img width="1260" height="2800" alt="Screenshot_20260422_230448" src="https://github.com/user-attachments/assets/c838efe7-5744-4d5f-819b-1bc40184d0a8" />
</div>
---

---

## 🏗️ 技术架构

### 技术栈
- **语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **架构模式**：MVVM + Repository
- **异步处理**：Kotlin Coroutines + Flow
- **数据存储**：DataStore (Preferences)
- **网络请求**：OkHttp3
- **OCR 引擎**：PaddleOCR、ML Kit

### 核心模块

```
app/src/main/java/com/example/mytransl/
├── data/                    # 数据层
│   ├── ocr/                # OCR 引擎实现
│   ├── translation/        # 翻译引擎实现
│   └── settings/           # 设置数据管理
├── domain/                  # 领域层
│   ├── ocr/                # OCR 接口定义
│   └── translation/        # 翻译接口定义
├── service/                 # 服务层
│   └── TranslationService  # 核心翻译服务
├── system/                  # 系统层
│   ├── capture/            # 屏幕截图
│   ├── overlay/            # 悬浮窗管理
│   ├── permissions/        # 权限管理
│   └── resource/           # 资源管理
└── ui/                      # UI 层
    ├── home/               # 主页
    ├── translate/          # 图文翻译
    ├── settings/           # 设置页面
    └── permissions/        # 权限引导
```

### 性能优化

- **内存管理**：Bitmap 复用池、内存监控、自动清理
- **网络优化**：HTTP 连接池、请求超时控制
- **OCR 优化**：引擎池管理、rowPadding 检查
- **翻译优化**：LRU 缓存、防抖机制



### 开发环境
- Android Studio Hedgehog | 2023.1.1 或更高版本
- JDK 17
- Gradle 8.0+



<div align="center">

**如果这个项目对你有帮助，请给个 ⭐️ Star 支持一下！**

Made with ❤️ by [INK666](https://github.com/INK666)

</div>
