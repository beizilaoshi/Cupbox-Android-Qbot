# Cupbox Android 1.0.0

专注 QQ 机器人功能的原生 Android 客户端，沿用 Cupbox Logo 与柔和配色，全程竖屏。

## 功能

- 本机 QQ 扫码登录，自动准备 Ubuntu、Linux QQ 与 NapCat 环境，也支持远程 OneBot。
- 首页三步引导：登录 QQ → 配置模型 → 设置回复范围，手动开启自动回复。
- 内置蓝色大肥鱼 / 鲸鱼娘人设，支持原版、自由演绎与自定义人格。
- 原 Logo 启动动画、真实文件进度、安装阶段提示、等待时离线冷知识。
- 关于页面、应用属性、QQ群 638500178、GitHub 与问题反馈入口。
- 检查本仓库正式 Release，支持版本比较、更新说明、取消及 API 限流时的官方发行页备用检查。
- 群聊与私聊规则、冷却、频率限制、会话记忆、Keystore 加密配置与深浅主题。

## 安装与使用

下载 `Cupbox-Android-Qbot-1.0.0-release.apk`。支持 Android 8.0+ 的 64 位 ARM / x86 设备，安装包约 130 MiB，内置 Ubuntu 基础环境。首次仍需联网下载 QQ、NapCat 和系统依赖，至少预留 3 GB 空间。

沿用旧版签名，可覆盖升级。安装后按首页提示完成登录、模型与回复范围配置，再连接机器人并手动开启回复。

附带 `.sha256` 校验文件。完整说明见[项目 README](https://github.com/beizilaoshi/Cupbox-Android-Qbot/blob/main/README.md)，验证记录见 [VALIDATION](https://github.com/beizilaoshi/Cupbox-Android-Qbot/blob/main/docs/VALIDATION.md)。

## 验证

17 项核心与更新检查测试通过；Android 12 页面、属性与群号复制、许可证、签名与覆盖升级已验证。此前已取得真实 QQ 登录二维码，完成停止清理与复用检查。

尚未登录真实账号验证群聊收发，也未进行骁龙 480 真机测试。不同设备的后台策略可能影响长期运行。
