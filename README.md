# IdleGuard 闲置守护

Android arm64-v8a 后台守护 App。检测到系统闲置达到设定时长后，自动执行：

1. 通过无障碍服务发送 `GLOBAL_ACTION_HOME`，回到桌面（免 root 关掉前台 App 的近似做法）
2. 通过勿扰权限启用 `INTERRUPTION_FILTER_NONE`，静音
3. 通过设备管理器 `lockNow()`，息屏

用户解锁回到系统（`ACTION_USER_PRESENT`）后自动取消勿扰、恢复原音量策略、重新计时循环。

## 权限

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — 前台服务保活
- `RECEIVE_BOOT_COMPLETED` — 开机自启
- `ACCESS_NOTIFICATION_POLICY` — 勿扰静音
- `SCHEDULE_EXACT_ALARM` — 精准计时
- 设备管理员（Device Admin，仅 `force-lock`）
- 无障碍服务（用户交互检测 + 回桌面）

## 构建

推送代码后，GitHub Actions 会自动编译并 attach debug APK 到 Release。

也可以本地跑：

```
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 和 Android SDK 34。

## 安装与授权

`adb install idle-guard.apk` 或直接把 APK 拷到手机侧载安装。首次进入 App：

1. 授予无障碍服务
2. 授予设备管理员
3. 授予勿扰权限
4. 建议：关闭电池优化
5. 建议：国产 ROM 加入自启动白名单
6. 打开开关启用守护
