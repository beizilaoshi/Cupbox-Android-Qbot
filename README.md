# Cupbox · Android QQ 机器人

Cupbox 的轻量 Android 原生版，只保留 QQ 机器人对话。沿用桌面版 Logo、低饱和深浅配色与卡片风格，重新设计为手机底部导航。

**这是通过 OneBot 连接 QQ 协议端的 Android 客户端。它不内置 QQ 登录、不直接运行 Windows NapCat，也不是完整桌面功能的移植。** 需要另一台设备上已登录 QQ 的 OneBot v11 协议端，以及兼容 Chat Completions 的远程或自建模型服务。手机可以单独承担 AI 机器人处理，但 QQ 登录协议端必须持续在线。

## 安装与开始

本机构建的安装包位于 `release/Cupbox-Android-Qbot-0.1.0-release.apk`，支持 Android 8.0 / API 26 及以上。允许文件管理器安装应用后，打开 APK 安装。

1. 在 QQ 协议端开启 **OneBot v11 正向 WebSocket**，消息段格式设为 **array**，配置 Access Token 并启用心跳。确保手机能访问该端口。
2. 手机打开「连接」，填写 `ws://协议端局域网IP:端口` 与 Token。`127.0.0.1` 代表手机自身；公网请使用 `wss://`。
3. 填写模型 API 基础地址（例如 `https://服务商域名/v1`）、API Key 与准确模型 ID。地址不要包含 `/chat/completions`。无密钥的自建接口可留空 Key。
4. 使用「测试模型连接」核对接口。测试会发起一次小请求，可能产生 API 费用。
5. 在「规则」设置群白名单与触发词，按需开启私聊；在「人格」设置语气。各页的保存按钮保存全部已编辑草稿。
6. 回到概览，连接机器人，确认登录状态后再手动开启自动回复。首次可允许通知，以便从通知栏暂停和停止。

默认不回复任何群聊、关闭私聊、关闭自动发言。保存设置会停止旧连接，需重新连接。断线后退避重连，但自动回复保持暂停，需手动恢复。系统强制停止或重启手机后不自动恢复发言。

## 当前功能

- OneBot 正向 WebSocket、Token 认证、登录信息核验、心跳离线检测与自动重连。
- 群白名单 / 黑名单 / 所有群、真实 @、关键词、私聊开关与回复 @。
- 同会话冷却、每分钟上限、消息去重、单并发；忙时不积压旧消息。
- 兼容 Chat Completions 的文本模型、自定义人设、温度、输出上限与连接测试。
- 按机器人 QQ、群 / 私聊隔离的本地记忆，可关闭、查看和清空。
- 前台服务、通知栏暂停 / 停止、深浅主题、五页原生手机界面。
- 仅在 QQ 返回成功投递回执后写入记忆；回执超时不自动重发。暂停取消未完成模型请求与后续投递，但不能撤回已发出的 QQ 消息。

当前没有移植桌面端的扫码部署、图片识别、表情包、插件、联网搜索、本地文件工具、免费模型聚合与复杂人格系统。模型接口需支持 `temperature`、`max_tokens` 和非流式文本回复；专用推理 API 不一定兼容。

## 低配设备设计与实测边界

以骁龙 480 级别手机为目标：原生 Java 控件，无 Electron、WebView、Node、Python、本地推理、大背景或持续动画；没有原生 ABI 依赖，同一 APK 可用于 ARM64 / ARMv7 / x86 设备。

运行时限制：最多一个模型请求、最多 32 个记忆会话、每会话 20 条历史、512 条去重 ID、64 项冷却记录、80 条日志；每条输入最多 4000 字，模型响应体最多 256 KB，输出最多 1800 字。HTTP 请求最长 75 秒，投递回执等待 15 秒。AI 速度主要取决于模型服务和网络。

已经通过 Android 12 模拟器验证，不等于骁龙 480 真机性能或锁屏保活保证。真机验收需核对冷启动、持续收发、锁屏一小时、系统省电策略、内存与耗电；这部分尚未完成。应用不会申请永久唤醒锁，后台需按手机系统要求允许自启动和后台活动。

## 数据与许可

配置和密钥使用 Android Keystore AES-GCM 加密，禁用云备份和设备迁移备份。会话历史是应用私有 SQLite 中的本机明文，开启记忆后相关内容会发送给配置的模型服务。群聊中未触发的消息不记录，日志不保存聊天正文和密钥。清空记忆会停止机器人；卸载删除本机配置与记忆。协议端维护的 QQ 登录资料不在手机应用中。

源代码采用项目原有 MIT 许可证；沿用的 Logo 等图片继续受原版素材保留权利条款约束，参见 [LICENSE](LICENSE)。本次按项目维护者要求复用原版品牌素材，不改变素材许可。非官方 QQ 接入可能受到平台限制，建议使用专用机器人账号。

第三方运行依赖：OkHttp 4.12.0 / Okio（Apache-2.0）、Kotlin 标准库（Apache-2.0）及 Android desugar_jdk_libs（GPLv2 with Classpath Exception）。不捆绑 NapCat 或第三方 QQ 登录运行时。

## 构建

需要 JDK 17–21、Android SDK Platform 35、Build Tools 34.0.0，以及可访问 Google Maven / Maven Central 的网络。AGP 8.7.3 配合 Gradle 8.9，版本依据 [Android 官方兼容表](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。

用 Android Studio 打开本目录，或在 `local.properties` 指定 `sdk.dir`；也可设置 `ANDROID_HOME`。本机已在 `.tools/android-sdk` 准备 SDK，该目录不提交。

```powershell
# PowerShell 7；首次构建生成本机专用发布签名
./scripts/build.ps1
# 调试包
./scripts/build.ps1 -DebugBuild
# 核心测试、静态检查
./gradlew.bat testDebugUnitTest lintDebug
# 仅在专用测试设备运行：会清空测试应用配置与记忆
./gradlew.bat connectedDebugAndroidTest
```

发布签名在 `.keys/cupbox-release.jks`，密码配置在 `signing.properties`，两者均被 Git 忽略。**请备份这两份文件，后续覆盖安装必须沿用相同签名。** 请勿公开签名或密钥配置。GitHub Actions 仅生成调试 APK，正式发布包用本机签名脚本生成。

## 验证

9 项 JVM 核心测试覆盖默认白名单、伪造 @、黑名单、关键词、私聊与账号隔离、配置边界、消息段、真实 HTTP 请求格式、模型异常和响应体大小。Android 集成测试覆盖 Keystore 加密、数据库有界淘汰、模拟 OneBot 收发完整链路、投递回执、去重、暂停，以及五页界面和深浅主题截图。

测试只使用本机模拟模型与协议端，未登录真实 QQ 或调用付费服务。详细结果见 [docs/VALIDATION.md](docs/VALIDATION.md)。
