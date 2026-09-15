package com.unlockguard.mcp.domain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 命令安全护栏的回归测试。
 *
 * 两个方向都重要：
 * - 破坏性命令必须被拦（漏拦 = 安全事故）；
 * - 正常命令必须放行（误伤 = 用户被迫绕开护栏）。
 */
class CommandGuardTest {

    @Test
    fun `destructive commands are blocked`() {
        listOf(
            // 关机会让设备离线且只能物理按键恢复 → 拦
            "poweroff",
            "svc power shutdown",
            "setprop sys.powerctl shutdown",
            "recovery --wipe_data",
            "wipe data",
            "rm -rf /",
            "rm -rf /sdcard",
            "rm -rf /data",
            "rm -r /system",
            "dd if=/dev/zero of=/dev/block/sda",
            "mkfs.ext4 /dev/block/sda1",
            "su -c 'ls /data'",
            "sudo ls",
            // 关闭 ADB 调试会让 Shizuku 直接失效：属于「自断通道」
            "settings put global adb_enabled 0",
            "pm uninstall com.unlockguard.mcp",
            // 串联绕过：只判断首词是守不住的
            "echo hi; rm -rf /",
            "echo hi; svc power shutdown",
        ).forEach { cmd ->
            assertNotNull("应被拦截却被放行：$cmd", CommandGuard.check(cmd))
        }
    }

    @Test
    fun `normal commands are allowed`() {
        listOf(
            "settings put system screen_brightness 120",
            "settings get global adb_enabled",
            "settings put global adb_enabled 1",
            "pm list packages",
            "pm uninstall com.example.other",
            "dumpsys battery",
            "getprop ro.build.version.release",
            "input keyevent 26",
            "input text hello",
            "svc wifi enable",
            // 重启放行（产品要求）：远程重启是常见运维诉求，可控且不丢数据
            "reboot",
            "reboot recovery",
            "svc power reboot",
            "setprop sys.powerctl reboot",
            "ls && reboot",
            "wm size 1080x2400",
            "ls /sdcard/Download",
            // 删除分区内的具体文件是正常清理，不该被拦（否则误伤率过高）
            "rm /sdcard/Download/a.apk",
            "cat /data/local/tmp/log.txt",
            "screencap -p /sdcard/s.png",
            "uiautomator dump",
            "cmd appops get com.example.foo",
            // 含 "su" 但不是提权：词边界必须挡住
            "pm suspend com.example.foo",
            "dumpsys statusbar",
        ).forEach { cmd ->
            assertNull("不该被拦截却被拦截：$cmd", CommandGuard.check(cmd))
        }
    }
}
