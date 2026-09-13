# 解锁守护 · 手机端解锁/锁屏 MCP 服务（Android 实现）

按《手机端解锁锁屏 MCP 方案 v1.2》落地的**真实 Android 工程**，UI 严格沿用 UI 设计师交付的高保真方案（鸢尾蓝品牌色、守护系视觉、信号环/徽章/深浅双主题）。本工程把方案中的全部功能实现为可在 Android Studio 编译运行的代码。

> 已按你的要求**移除配置二维码/扫码**（电脑端无法扫手机屏上的码）；首页改为「本机 + 局域网地址一键复制」，悬浮球改为**系统级悬浮窗**（由前台服务托管，退出应用后仍常驻）。

## 一、技术基线（与方案一致）
- `minSdk = 34` / `targetSdk = 36` / `compileSdk = 37`
- Gradle 9.7.1（Wrapper 内置）· AGP 9.3.0 · Kotlin 2.4.10 · Compose BOM 2026.08.00
  - AGP 9 起**内置 Kotlin 支持**，因此不再 apply `org.jetbrains.kotlin.android`（顶层 `buildscript` 将 KGP 对齐到 2.4.10）
- **字体：全部使用系统字体族，零字体资源依赖**（`FontFamily.SansSerif` / `FontFamily.Monospace`），无需手工放置任何 ttf，开箱即用
- MCP 传输：Streamable HTTP（Ktor CIO）
- 解锁双通道：**Shizuku（主）→ 无障碍（备，Keyguard PIN 输入）**
  - 主通道的事件注入**跑在 Shizuku 的 UserService 进程内**（shell uid 2000 / root uid 0），
    因为 `INJECT_EVENTS` 是 `signature|privileged` 权限、按 uid 判定，应用进程无论怎样反射都拿不到；
  - 注入后端双路互备：优先反射 `InputManager.injectInputEvent`（毫秒级），失败自动退化为
    `/system/bin/input` 命令（约百毫秒，零隐藏 API 依赖）；
  - **优先级为硬约束**：Shizuku 可用且注入生效时绝不使用无障碍；仅当 Shizuku 未授权、
    服务未运行或注入未生效时才降级，并在结果中明确标注实际使用的通道与后端。
- 存储：Bearer Token、PIN 本地加密（Keystore + EncryptedSharedPreferences）、租约、审计日志、速率限制 + 失败锁定

## 二、模块结构
```
app/src/main/java/com/unlockguard/mcp/
├─ MainApplication.kt            Application：构建 AppGraph、绑定 Shizuku
├─ core/AppGraph.kt              依赖容器：领域对象 + 解锁引擎 + MCP Server 控制（端口/局域网/Token/地址）
├─ core/ShizukuGate.kt           Shizuku 授权网关：状态判定 + 唤起官方授权弹窗 + 双发行包名兼容
├─ core/DevicePermissions.kt     权限自检（三态如实上报） + 系统设置页跳转（应用信息页为权限总入口）
├─ domain/                      领域层
│   ├─ Model.kt                 UnlockChannel / PhoneState / Lease
│   ├─ PinStore.kt              PIN 与 Token 本地加密存储
│   ├─ LeaseManager.kt          租约（TTL+上限1800s）、设置快照/还原、全局唯一
│   ├─ RateLimiter.kt           每分钟上限 + 连续失败5次锁定10分钟
│   └─ AuditLog.kt              调用留痕（时间/来源IP/工具/入参/结果/错误码）+ CSV 导出
├─ mcp/                         MCP 服务
│   ├─ McpServer.kt             Streamable HTTP：POST/GET/DELETE /mcp + /health + 鉴权 + 会话
│   ├─ McpContext.kt            运行上下文（注入依赖）
│   ├─ Tools.kt                工具实现（见下表）
│   └─ Errors.kt               统一错误结构 + 错误码常量
├─ unlock/                     解锁引擎（双通道 + 降级）
│   ├─ UnlockEngine.kt         引擎：Shizuku 优先 / 无障碍兜底；通道互校；解锁验证；永不假装成功
│   ├─ ShizukuChannel.kt       主通道：经 UserService 跨进程注入（唤醒/上滑/输入PIN/确认）
│   ├─ ShizukuUserServiceHub.kt  UserService 绑定与持有（一次绑定、长期复用）
│   ├─ IUnlockUserService.kt   UserService 跨进程接口（手写 Binder 协议，不依赖 AIDL 工具链）
│   ├─ UnlockUserService.kt    服务端实现：加载进 shizuku_server 进程，以 shell/root 身份执行
│   ├─ InputInjector.kt        注入后端：InputManager 反射优先，input 命令兜底
│   ├─ AccessibilityChannel.kt  备通道
│   └─ AccessibilityBridge.kt   无障碍服务进程内桥
├─ ui/overlay/                 系统级悬浮球
│   ├─ OverlayBallManager.kt   挂载/卸载 TYPE_APPLICATION_OVERLAY 窗口，位置持久化
│   └─ OverlayBallView.kt      悬浮球绘制与拖拽（纯 View，无 Compose 宿主依赖）
├─ device/ScreenLockAdmin.kt   设备管理员（lockNow 保底）
├─ service/                    McpForegroundService（前台服务+WifiLock+网络恢复自启）、BootReceiver
├─ accessibility/UnlockAccessibilityService.kt  焊死在 Keyguard 的 PIN 输入
└─ ui/                         UI（严格按设计稿 v1.2 复刻）
    ├─ theme/Theme.kt          设计令牌（颜色/字体/间距/形状，严格对齐原型）
    ├─ theme/AppText.kt        文字阶梯（kicker/h1/h2/lead/mono…，对齐原型 CSS）
    ├─ components/Components.kt 组件库（卡片/字段/药丸/信号环/步骤/日志行/权限行…）
    ├─ components/Overlays.kt  引导底部弹层 / 错误对话框 / PIN 弹窗 / Toast / 底部导航
    ├─ screens/Screens.kt      五个界面：引导 · 首页 · 状态 · 日志 · 设置
    ├─ AppViewModel.kt          状态聚合（每秒刷新）+ 动作
    └─ MainActivity.kt          装配主题与导航

> 界面实现说明：**引导页、悬浮球、引导弹层、错误对话框、Toast、5 项底部导航**均已按设计稿实现，
> 不是骨架。权限自检等「无法由系统 API 查询」的项会如实标为 `? 待确认`，不伪造绿色对勾。
>
> **悬浮球为系统级悬浮窗**（`TYPE_APPLICATION_OVERLAY`，由前台服务托管）：退出应用、回到桌面、
> 锁屏再唤醒都常驻；可拖动位置并持久化，单击回到应用。设置页「悬浮球」开关控制显隐。
> 注意它依赖守护服务：服务停止时悬浮球会一并消失，这是刻意设计（避免窗口无人托管而泄漏）。
>
> **状态页内置「解锁验证」**：勾选「先锁屏再验证」后点一下，即可实跑一次完整解锁，
> 逐步展示唤醒 / 上滑 / 输入 PIN / 提交 的结果、实际使用的通道与注入后端、耗时与结论，
> 用于在真实调用前确认手机能否被正常解开。
>
> 真机验收截图见 `verify/`（Android 17 / 1200×2670 实测）。
```

## 三、MCP 工具 ↔ 方案文档对应
| 工具 | 入参 | 实现 |
|---|---|---|
| `get_phone_state` | — | 屏幕/锁屏/Shizuku/无障碍/租约/锁定态如实上报 |
| `unlock_phone` | `ttl_seconds` | 双通道解锁 + 带 TTL 租约（上限 1800s，超额截断） |
| `release_lease` | `lease_id` | 显式释放并还原设置快照 |
| `lock_phone` | — | DeviceAdmin `lockNow()` + Shizuku `keyevent 223`(SLEEP) |
| `set_screen_timeout` | `screen_off_ms`/`lock_after_ms` | 两个旋钮（需 WRITE_SETTINGS） |
| `restore_settings` | — | 还原快照 |
| `grant_debug_auth` | — | 仅状态上报 + 引导 |
| `/health` | — | 免 Token 低敏检查（区分服务挂 vs Token 错） |

错误码：`SHIZUKU_UNAVAILABLE` / `ACCESSIBILITY_DISABLED` / `PIN_MISMATCH` / `LOCKED_OUT` / `LEASE_CONFLICT` / `PERMISSION_MISSING` / `RATE_LIMITED` 等，与 UI 引导对话框一致。

## 四、电脑端接入
```json
{ "mcpServers": { "phone-lock": { "url": "http://<手机IP>:8790/mcp", "headers": { "Authorization": "Bearer <token>" } } } }
```
仅支持 stdio 的客户端：`npx mcp-remote http://<手机IP>:8790/mcp --header "Authorization: Bearer <token>"`

## 五、构建与运行
1. 命令行（推荐）：`./gradlew :app:assembleRelease`（Gradle Wrapper 已内置，版本矩阵见第一节；首次会下载依赖）。
   - release 签名配置见本节第 6 条；若 `keystore.properties` 缺失会自动降级为「不签名」，`assembleDebug` 仍可正常构建。
2. **字体：无需任何手动操作**。已全部改用系统字体族（`FontFamily.SansSerif` / `FontFamily.Monospace`），零字体资源依赖、开箱即用；中文由系统 Noto Sans CJK 覆盖。
   - 若日后要换成品牌字体（如 Space Grotesk / Inter / JetBrains Mono）：把 ttf 放进 `app/src/main/res/font/`，再把 `ui/theme/Theme.kt` 中的 `FontDisplay` / `FontBody` / `FontMono` 三行换回
     `FontFamily(Font(R.font.xxx, FontWeight.Normal), ...)` 形式即可，其余代码无需改动。
3. 安装到 Android 14+ 真机（模拟器无锁屏/无障碍场景）。
4. 授权（引导页 5 步，或状态页「权限自检」卡片）：
   - **第 1 步「去授权」→ 应用信息页**（`ACTION_APPLICATION_DETAILS_SETTINGS`）：自启动 / 电池无限制 / 后台弹出 / 权限在该页集中设置。
     注意：不要用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 作为这一步的入口——真机实测（HyperOS）系统会把它重定向到**该应用的「电量详情页」**，
     用户在那里只能改电池，改不了自启动/后台弹出等权限。
   - **第 2 步「去授权」→ 唤起 Shizuku 官方授权弹窗**（`Shizuku.requestPermission`）。分流规则见 `core/ShizukuGate.kt`：
     未安装→下载页；服务未运行→打开 Shizuku 让用户先启动服务；已拒绝过→引导到 Shizuku「授权应用」手动开。无障碍服务与数字 PIN 同页设置。
5. 启动 MCP 服务（首页开关），复制地址到电脑端 AI 客户端。
6. **release 签名**：仓库根目录的 `keystore.properties` 保存 keystore 路径与口令（已被 `.gitignore` 忽略）。
   - 生成 keystore（本机已生成于 `app/keystore/unlockguard-release.jks`）：
     ```
     keytool -genkeypair -v -keystore app/keystore/unlockguard-release.jks -storetype JKS \
       -keyalg RSA -keysize 2048 -validity 10000 -alias unlockguard \
       -storepass <STORE_PWD> -keypass <KEY_PWD> \
       -dname "CN=UnlockGuard, OU=Dev, O=UnlockGuard, L=Shenzhen, ST=Guangdong, C=CN"
     ```
   - `app/build.gradle.kts` 读取该文件并挂到 `buildTypes.release.signingConfig`；文件不存在则跳过签名（不报错）。
   - 产物：`app/build/outputs/apk/release/app-release.apk`（已签名）。

## 六、M1 PoC 实测结论（方案 P0）
- ✅ **Shizuku 主通道已端到端实测通过**（HyperOS / Android 17 / Shizuku 13.6 真机）。
  曾经的根因：注入写在应用进程内，而 `INJECT_EVENTS` 是 `signature|privileged`、按 uid 判定，
  应用进程无论怎样反射都拿不到。现改为经 **Shizuku UserService** 在 `shizuku_server` 进程
  （shell uid 2000 / root uid 0）内注入，后端双路互备（`InputManager` 反射 → `input` 命令）。

  实测证据（状态页「解锁验证」勾选「先锁屏再验证」，日志见 `verify/shizuku_auth_timeline.log`）：
  ```
  UnlockUserService: UserService 已连接 · 注入后端=shell
  MIUIInput: Input key event injection from package: null  action ACTION_UP  keycode 66   ← 注入的 ENTER
  KeyguardViewMediator: finishTDispatcherByMiui keyguard going away
  KeyguardStateTracker: Transition finished: PRIMARY_BOUNCER -> GONE                    ← 锁屏真的消失
  AppViewModel: runVerify 结束 → ok=true channel=SHIZUKU backend=shell 耗时=4953ms
  ```
  即：先熄屏锁屏，再由 shell 特权进程注入「唤醒 → 上滑 → PIN → 回车」，锁屏状态机从
  `PRIMARY_BOUNCER` 走到 `GONE`。判定成功**只看** `KeyguardManager.isKeyguardLocked()` 变回 false，
  绝不把「注入下发成功」当作「解锁成功」。
- ⏳ **无障碍备通道**在 Keyguard 阶段的节点匹配（数字节点 text/contentDescription、确认键形态）
  仍需按目标机型校准，`UnlockAccessibilityService.findDigit/findConfirm` 已留校准点。
  主通道可用时它不会被触发。

## 七、安全边界（已实现）
- 鉴权：每请求 Bearer Token，可随时重新生成吊销（旧配置立即 401）。
- PIN：仅存本地加密，永不过 MCP 通道传输；网络侧拿不到 PIN 明文。
- 无障碍能力焊死在 Keyguard 窗口 + 白名单，绝不提供通用点击/坐标（见 `UnlockAccessibilityService` 注释与 `packageNames="com.android.systemui"`）。
- 局域网监听默认关（仅 127.0.0.1），首页明示明文风险。
- 限流 + 连续失败 5 次锁定 10 分钟。
- `allowBackup=false`，防云备份泄露密钥。

## 八、已知限制（同方案第十章）
- 局域网为明文 HTTP（同网段可嗅探 Token），HTTPS 自签列为后续增强。
- 多用户/手机分身下 Shizuku 与无障碍可能失效，如实上报。
- 遇生物识别/受限设置验证弹窗，立即停手并引导用户（不自动点）。

## 九、Shizuku 授权链路（真机实测行为，排障先读这一节）

Shizuku 的授权**不是普通运行时权限**：`Shizuku.requestPermission()` 能否弹出授权框，
取决于该 uid 的历史状态。真机逐条日志对账后的判据如下（`core/ShizukuGate.kt` 即按此分流）：

| `shouldShowRequestPermissionRationale()` | 实测行为 |
|---|---|
| `false`（从未请求过 / 刚被撤销） | **正常弹出**官方授权框，点「允许」即可 |
| `true`（此前被拒绝过） | `requestPermission()` 在 **2~5ms 内直接回调 DENIED、绝不弹窗**，调多少次都一样 |

所以**「点了去授权却什么都没发生」几乎必然是 `rationale==true`**。此时唯一出路是让 Shizuku
重置该 uid 的状态：

1. 打开 Shizuku →「应用管理」→ 找到本应用手动开启授权；或
2. **先在 Shizuku 中「关闭」本应用授权，再回到本应用点「去授权」** —— 实测这条最可靠：
   关闭授权会让系统发出 `permissions revoked` 并杀掉本应用进程，进程重建后 Shizuku 重新把本应用
   视为「可再次请求」，弹窗随即恢复。

> ⚠️ **每次覆盖安装本应用，Shizuku 授权都会失效**，需按第 2 条重新授权一次。
> 这是 Shizuku 的授权模型决定的（授权按 uid 记录），不是本应用的缺陷。

另外两个已修的坑：

- **发行包名必须认全**：`moe.shizuku.privileged.api`（GitHub / Play 正式版）与
  `moe.shizuku.manager`（早期构建）。旧代码只认后者，`getLaunchIntentForPackage` 返回 null，
  点击等于没反应。
- **binder 是异步送达的**：本应用为多进程（前台服务 / UserService），冷启动瞬间
  `pingBinder()` 可能为 false。若不补一次 `ShizukuProvider.requestBinderForNonProviderProcess()`
  就下结论，会把「binder 还没到」误判成「服务未运行」，把用户送去服务教程页（真机日志里已出现）。

> 排障便利：授权链路的关键日志 tag 统一为 `ShizukuGate`（含 binder 送达/断开、
> `installed / ping / granted / serverUid / rationale` 快照、授权结果回调），
> 一条 `adb logcat | grep ShizukuGate` 即可看全；解锁注入另见 `UnlockUserService`、
> `UserServiceManager` 与系统侧 `MIUIInput` 的 `Input key event injection`。
