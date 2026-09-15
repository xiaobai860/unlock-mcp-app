package com.unlockguard.mcp.domain

/**
 * shell 命令安全护栏：拦截「一旦执行基本不可逆」的破坏性命令。
 *
 * ## 设计原则（决定误伤率，务必遵守）
 * 1. **只拦明确不可逆、且不属于本工具正常用途的操作**：恢复出厂、重启/关机、
 *    删除分区根目录、写磁盘分区、提权。
 * 2. **不拦「本身合法、只是被误用才危险」的命令**：例如 `rm /sdcard/Download/a.apk`
 *    是正常清理，`pm uninstall` 普通应用也是正常用途 —— 拦了只会逼用户绕开护栏。
 *    因此删除只在**分区根目录级别**拦截（`rm -rf /sdcard` 拦，`rm /sdcard/a.apk` 放行）。
 * 3. **额外拦「会废掉本服务自身」的命令**：关闭 ADB 调试会让 Shizuku 直接失效，
 *    设备从此失联、再也无法通过本服务管理 —— 这类"自断通道"必须拦。
 *
 * ## 匹配方式
 * 统一 `lowercase()` 后做正则包含匹配（不区分大小写）。刻意用"包含"而非"完全相等"：
 * `sh -c` 支持 `&&` / `;` / `|` 串联，只判断命令首词会被 `ls && reboot` 这类绕过。
 */
object CommandGuard {

    /** @return 命中规则时返回**拒绝原因**；未命中（允许执行）返回 null */
    fun check(cmd: String): String? {
        val c = cmd.lowercase()
        for ((re, reason) in RULES) {
            if (re.containsMatchIn(c)) return reason
        }
        return null
    }

    private val RULES: List<Pair<Regex, String>> = listOf(
        // ---- 恢复出厂 / 擦除分区 ----
        Regex("""recovery\s+--wipe""") to "恢复出厂（wipe）",
        Regex("""\bwipe\s+(data|cache|all|system|internal|external)\b""") to "擦除分区数据",

        // ---- 仅拦「关机」；重启按产品要求放行 ----
        // 原因：远程重启是常见运维诉求（卡死后自救、应用改动需重启生效），可控且不丢数据；
        // 而关机会让设备彻底离线、必须物理按键才能恢复，属于不可逆失联，故拦截。
        // 覆盖 `svc power shutdown`、`setprop sys.powerctl shutdown` 等所有变体。
        Regex("""\b(poweroff|shutdown)\b""") to "关机（设备将离线且无法远程恢复）",

        // ---- 删除：只拦「分区根目录」，不拦分区内的具体文件 ----
        // rm -rf /            → 拦
        // rm -rf /sdcard      → 拦
        // rm /sdcard/a.apk    → 放行（正常清理）
        Regex("""\brm\s+(?:-[a-z]+\s+)*/\s*(?:[;&|]|$)""") to "删除根目录",
        Regex("""\brm\s+(?:-[a-z]+\s+)*/(system|data|vendor|product|sdcard|storage|mnt|proc|sys|dev)\s*(?:[;&|]|$)""")
            to "删除系统或数据分区根目录",

        // ---- 磁盘 / 分区写入、刷机 ----
        // 注意：整组**末尾不能加 \b**。`dd if=` 以 `=` 结尾、`dd of=/dev` 以 `v` 结尾，
        // 若写成 `\b(...)\b`，`=` 与后面的 `/` 都是非单词字符、构不成单词边界，
        // 会导致 `dd if=/dev/zero of=...` 整条漏拦（已被单元测试捕获）。
        Regex("""\b(mkfs|mke2fs|dd\s+if=|dd\s+of=/dev|fastboot\s+flash|flash_image)""")
            to "写入磁盘或分区（不可逆）",

        // ---- 提权到 root（Shizuku 以 root 运行时风险更高）----
        // 词边界确保不会误伤 suspend / status 这类含 "su" 前缀的单词
        Regex("""\b(su|sudo)\b""") to "提权到 root",

        // ---- 自断通道：关闭 ADB 调试会让 Shizuku 立刻失效 ----
        // 只拦置 0（关闭）；置 1（开启）无害，放行
        Regex("""adb_enabled\s+0\b""") to "关闭 ADB 调试（会导致 Shizuku 失效、设备从此失联）",

        // ---- 自毁：卸载本应用自身 ----
        Regex("""pm\s+uninstall[^\n]*com\.unlockguard\.mcp""") to "卸载本应用自身（自毁）",
    )
}
