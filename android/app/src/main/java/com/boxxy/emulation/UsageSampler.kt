package com.boxxy.emulation

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * CPU and GPU utilisation for the performance overlay.
 *
 * CPU is reported per emulator thread, not device-wide: each figure is the
 * share of ONE host core that thread used since the last sample.  That is the
 * number that says what the bottleneck is -- the x86 vCPU runs on a single
 * thread, so "CPU 30%" device-wide on an 8-core SoC can hide a vCPU pinned at
 * 100%.  Threads are found by the names QEMU gives them (see
 * qemu_thread_naming() in ui/xemu.c).
 *
 * Note the vCPU reading is not headroom: Halo busy-waits on a clock between
 * frames (FINDINGS section M), so it reads near 100% even when frames fit.
 *
 * GPU is the Adreno busy percentage from kgsl.  Absent on other GPUs or where
 * SELinux denies it; each part of the readout returns null when unavailable
 * and the overlay leaves it out.
 */
class UsageSampler {
    class Threads(val vcpuPct: Int?, val nv2aPct: Int?)
    class Gpu(val busyPct: Int, val mhz: Int?)

    private val clkTck = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
    private var lastVcpuTicks = -1L
    private var lastNv2aTicks = -1L
    private var lastSampleMs = 0L
    private var gpuReadable = true

    /** Forget the previous sample, so a pause is not averaged into the next. */
    fun reset() { lastSampleMs = 0L }

    /** Returns per-thread load since the previous call; null on the first. */
    fun sampleThreads(): Threads? {
        var vcpu = -1L
        var nv2a = -1L
        File("/proc/self/task").listFiles()?.forEach { task ->
            val stat = try { File(task, "stat").readText() } catch (e: Exception) { return@forEach }
            // comm is in parentheses and may itself contain spaces or ')'.
            val close = stat.lastIndexOf(')')
            if (close < 0) return@forEach
            val comm = stat.substring(stat.indexOf('(') + 1, close)
            val isVcpu = comm.startsWith("CPU ") || comm.startsWith("ALL CPUs")
            val isNv2a = comm.startsWith("nv2a.pfifo")
            if (!isVcpu && !isNv2a) return@forEach
            // Fields after ')' start at field 3 (state); utime/stime are 14/15.
            val f = stat.substring(close + 2).split(' ')
            val ticks = (f.getOrNull(11)?.toLongOrNull() ?: return@forEach) +
                        (f.getOrNull(12)?.toLongOrNull() ?: return@forEach)
            if (isVcpu) vcpu = vcpu.coerceAtLeast(0) + ticks
            else        nv2a = nv2a.coerceAtLeast(0) + ticks
        }
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastSampleMs
        val result = if (lastSampleMs != 0L && elapsedMs > 0)
            Threads(pct(vcpu, lastVcpuTicks, elapsedMs), pct(nv2a, lastNv2aTicks, elapsedMs))
        else null
        lastVcpuTicks = vcpu
        lastNv2aTicks = nv2a
        lastSampleMs = now
        return result
    }

    private fun pct(now: Long, before: Long, elapsedMs: Long): Int? {
        if (now < 0 || before < 0 || now < before) return null
        return ((now - before) * 100_000 / clkTck / elapsedMs).toInt()
    }

    fun sampleGpu(): Gpu? {
        if (!gpuReadable) return null
        val busy = readSysfs("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            ?.filter { it.isDigit() }?.toIntOrNull()
        if (busy == null) { gpuReadable = false; return null }
        val hz = readSysfs("/sys/class/kgsl/kgsl-3d0/gpuclk")?.trim()?.toLongOrNull()
        return Gpu(busy, hz?.let { (it / 1_000_000).toInt() })
    }

    private fun readSysfs(path: String): String? =
        try { File(path).readText() } catch (e: Exception) { null }
}
