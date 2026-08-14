# SilentAlarm — Private Earphone Alarm

[中文](#chinese) | English

SilentAlarm plays alarms through earphones (wired / BT / USB), with vibrate-only or speaker fallback. Kotlin + Jetpack Compose.

## Screenshot

<img src="./pic/sc1.png" width="300" alt="Screenshot">  <img src="./pic/sc2.png" width="300" alt="Screenshot">

## Features

- Multi-alarm — one-shot or recurring
- Earphone-first routing (wired / BT / USB), 500 ms silent BT wake-up
- No-earphone fallback — vibrate-only or speaker
- Ring timeout + fallback, 5-min snooze
- Separate earphone / speaker volume, custom ringtone
- Per-alarm timezone, search, Quick Settings Tile, 30+ languages

## Keeping the alarm alive

Chinese OEM ROMs (MIUI, EMUI, ColorOS, …) aggressively kill background apps. Three layers keep the alarm reliable:

1. **Exact alarm** (on by default) — scheduled via `setAlarmClock`; fires even if the process is killed. No setup needed.
2. **Notification keep-alive** (optional) — a persistent notification plus a 2-hour auto-recovery alarm.
3. **Shizuku / root watchdog** (optional, strongest) — battery-bucket whitelist plus a daemon that restarts the app within ~20 s.

**Enable:** turn on *Notification keep-alive* in the app; install [Shizuku](https://shizuku.rikka.app/) (or have root) and turn on the *Shizuku* toggle for the strongest protection.

## Build

- minSdk 29 / targetSdk 36 · Kotlin 2.2.10 · Compose BOM 2026.02 · AGP 9.3

---

## <a id="chinese"></a>SilentAlarm — 隐私耳机闹钟

耳机优先响铃的 Android 闹钟，无耳机时可选仅振动或外放。Kotlin + Jetpack Compose。

### 功能

- 多闹钟 — 单次或每周重复
- 耳机优先响铃（有线 / 蓝牙 / USB），500 ms 静音唤醒
- 无耳机回退 — 仅振动或扬声器
- 响铃超时 + 回退、5 分钟贪睡
- 耳机 / 扬声器音量分开调节、自定义铃声
- 单闹钟时区、搜索、快捷磁贴、30+ 语言

### 保活

国产 ROM（MIUI、EMUI、ColorOS 等）会激进杀后台。三层保活确保闹钟可靠：

1. **精确闹钟**（默认开启）— 用 `setAlarmClock` 调度，进程被杀也会响，无需配置。
2. **通知保活**（可选）— 常驻通知 + 2 小时自动恢复。
3. **Shizuku / root 看门狗**（可选，最强）— 电池桶白名单 + 进程死亡约 20 秒内自动拉起。

**启用方式：** 应用内开启「通知保活」；安装 [Shizuku](https://shizuku.rikka.app/)（或有 root）后开启「Shizuku」开关，获得最强保护。

### 构建

- minSdk 29 / targetSdk 36 · Kotlin 2.2.10 · Compose BOM 2026.02 · AGP 9.3
