package com.soreverse.mcp.blutter

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BlutterRunnerService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val jobs = ConcurrentHashMap<String, RunnerJob>()
    private val nextToken = AtomicLong(1)

    private val binder = object : IBlutterRunner.Stub() {
        override fun getManifestJson(): String = assets.open("blutter/runners.json").bufferedReader().use { it.readText() }

        override fun run(jobId: String, libraryName: String, libapp: ParcelFileDescriptor, libflutter: ParcelFileDescriptor, result: ParcelFileDescriptor, optionsJson: String, callback: IBlutterRunnerCallback) {
            val token = nextToken.getAndIncrement()
            val job = RunnerJob(token, libapp, libflutter, result)
            job.callback = callback
            jobs[jobId] = job
            val future = executor.submit {
                var exitCode = -1
                var errorCode = ""
                var message = ""
                try {
                    job.started.set(true)
                    if (job.cancelled.get()) {
                        // 被取消的任务体提前退出时也必须回调，否则调用方永远停在 running
                        job.closeDescriptors()
                        runCatching { callback.onCompleted(jobId, -1, "CANCELLED", "Job was cancelled before execution", 0L, "") }
                        stopSelf()
                        return@submit
                    }
                    callback.onProgress(jobId, "running", 10)
                    exitCode = NativeBlutterBridge.run(libraryName, libapp.fd, libflutter.fd, result.fd, optionsJson, token)
                    if (exitCode != 0) errorCode = "RUNNER_FAILED"
                } catch (error: Exception) {
                    errorCode = "RUNNER_EXCEPTION"
                    message = error.message ?: error.javaClass.simpleName
                } finally {
                    job.closeDescriptors()
                    jobs.remove(jobId, job)
                }
                runCatching { callback.onCompleted(jobId, exitCode, errorCode, message, 0L, "") }
                stopSelf()
            }
            job.future = future
            if (job.cancelled.get() && future.cancel(false)) job.closeDescriptors()
        }

        override fun cancel(jobId: String) {
            val job = jobs.remove(jobId) ?: return
            job.cancelled.set(true)
            if (!job.started.get() && job.future?.cancel(false) == true) {
                job.closeDescriptors()
                // 排队中的 FutureTask 被取消后任务体永远不会执行：这里主动回调，
                // 否则调用方收不到任何完成通知（UI 卡在 running，10% 不动）
                runCatching {
                    job.callback?.onCompleted(jobId, -1, "CANCELLED", "Job was cancelled before execution", 0L, "")
                }
                stopSelf()
            } else {
                NativeBlutterBridge.cancel(job.token)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        jobs.values.forEach { job ->
            job.cancelled.set(true)
            if (!job.started.get() && job.future?.cancel(false) == true) {
                job.closeDescriptors()
            } else {
                NativeBlutterBridge.cancel(job.token)
            }
        }
        jobs.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private class RunnerJob(
        val token: Long,
        private val libapp: ParcelFileDescriptor,
        private val libflutter: ParcelFileDescriptor,
        private val result: ParcelFileDescriptor,
    ) {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        @Volatile var future: Future<*>? = null
        @Volatile var callback: IBlutterRunnerCallback? = null
        private val closed = AtomicBoolean(false)

        fun closeDescriptors() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { libapp.close() }
            runCatching { libflutter.close() }
            runCatching { result.close() }
        }
    }
}
