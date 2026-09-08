package com.ray.hotspot

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** 最小化 su 执行器：带可用性探测（结果缓存）与输出转储，避免大输出撑满管道。 */
object RootShell {
    private const val TAG = "HotspotEngine"
    @Volatile private var cacheDir: File? = null
    @Volatile private var rootCache: Boolean? = null

    fun init(dir: File) {
        cacheDir = dir
    }

    /** 设备是否有可用的 su（Magisk 首次会弹授权，用户拒绝后缓存为 false）。 */
    fun available(): Boolean {
        rootCache?.let { return it }
        val r = exec("id", timeoutMs = 4000)
        val ok = r.exitCode == 0 && r.out.contains("uid=0")
        rootCache = ok
        Log.d(TAG, "root available=$ok")
        return ok
    }

    fun forgetCache() {
        rootCache = null
    }

    data class ShellResult(val exitCode: Int, val out: String)

    fun exec(command: String, timeoutMs: Long = 8000): ShellResult {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val out = File(dir, "sh_${System.nanoTime()}.txt")
        return try {
            val pb = ProcessBuilder("su", "-c", command)
            pb.redirectErrorStream(true)
            pb.redirectOutput(out)
            val p = pb.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                ShellResult(-1, "timeout")
            } else {
                ShellResult(
                    p.exitValue(),
                    runCatching { out.readText() }.getOrDefault("").take(128 * 1024)
                )
            }
        } catch (t: Throwable) {
            ShellResult(-1, "err: ${t.message}")
        } finally {
            out.delete()
        }
    }
}
