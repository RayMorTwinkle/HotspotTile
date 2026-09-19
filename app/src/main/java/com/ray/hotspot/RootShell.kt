package com.ray.hotspot

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** 最小化 su 执行器：带可用性探测（结果缓存）与输出转储，避免大输出撑满管道。 */
object RootShell {
    private const val TAG = "RootShell"
    @Volatile private var cacheDir: File? = null
    @Volatile private var rootCache: Boolean? = null

    fun init(dir: File) {
        cacheDir = dir
        // 进程上次被强杀可能留下 sh_* 临时文件，启动时顺手清掉
        dir.listFiles()?.forEach { if (it.name.startsWith("sh_")) it.delete() }
    }

    /** 设备是否有可用的 su（Magisk 首次会弹授权，用户拒绝后缓存为 false）。 */
    fun available(): Boolean {
        rootCache?.let { return it }
        val r = exec("id", timeoutMs = 4000)
        val ok = r.exitCode == 0 && r.out.contains("uid=0")
        // 超时/异常（exitCode=-1）不算结论：Magisk 授权弹窗期间会阻塞超时，
        // 用户随后授权成功也不应被判死刑，留给下次重新探测
        if (r.exitCode != -1) rootCache = ok
        Log.d(TAG, "root available=$ok cached=${rootCache != null}")
        return ok
    }

    /** 丢弃可用性缓存（设置页「重新检测 Root」调用）。 */
    fun forgetCache() {
        rootCache = null
    }

    data class ShellResult(val exitCode: Int, val out: String)

    /**
     * 以 `su -c` 执行命令，输出转储到临时文件。
     * 注意：Android 没有进程组 kill——超时后 destroyForcibly() 只杀 su 壳进程，
     * `-c` 命令的子进程可能仍以 root 身份继续跑完，调用方勿假定"超时=没执行"。
     * 必须在后台线程调用（waitFor 阻塞可达 timeoutMs）。
     */
    fun exec(command: String, timeoutMs: Long = 8000): ShellResult {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val out = File(dir, "sh_${System.nanoTime()}.txt")
        return try {
            val pb = ProcessBuilder("su", "-c", command)
            pb.redirectErrorStream(true)
            pb.redirectOutput(out)
            pb.redirectInput(File("/dev/null")) // 子命令读 stdin 时不至于卡满整个 timeout
            val p = pb.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(200, TimeUnit.MILLISECONDS)
                ShellResult(-1, "timeout")
            } else {
                ShellResult(
                    p.exitValue(),
                    runCatching { out.readText() }.getOrDefault("").take(128 * 1024)
                )
            }
        } catch (t: Throwable) {
            ShellResult(-1, "err: ${t.message ?: t.toString()}")
        } finally {
            out.delete()
        }
    }
}
