package com.unlockguard.mcp.unlock

/**
 * Shizuku UserService 服务端实现。
 *
 * 这个类**不在本应用进程里运行**：Shizuku 会把它加载进 `shizuku_server` 进程，
 * 以 shell(2000) / root(0) 身份执行，从而具备 `INJECT_EVENTS` 能力。
 *
 * 约束（由 Shizuku 加载机制决定）：
 * - 必须是 public 类且有无参构造函数；
 * - 只依赖 Android framework 与同 APK 内的类，不引用 Application / DI 容器等进程态对象。
 */
class UnlockUserService : IUnlockUserService.Stub() {

    private val injector: InputInjector by lazy { InputInjector() }

    override fun probe(): String = injector.backend

    override fun injectTap(x: Float, y: Float): Boolean = injector.tap(x, y)

    override fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int): Boolean =
        injector.swipe(x1, y1, x2, y2, durationMs)

    override fun injectKey(keyCode: Int): Boolean = injector.key(keyCode)

    override fun injectDigits(digits: String, gapMs: Int): Boolean = injector.digits(digits, gapMs)

    override fun exit() {
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}
