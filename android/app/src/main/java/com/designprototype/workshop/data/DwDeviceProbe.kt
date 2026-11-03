package com.designprototype.workshop.data

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock

fun dwProbeDevice(context: Context): DwDeviceMeasurement {
    val app = context.applicationContext

    val activity = runCatching { app.getSystemService(ActivityManager::class.java) }.getOrNull()
    val memory = runCatching {
        activity?.let { manager ->
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        }
    }.getOrNull()

    val lowRam = runCatching { activity?.isLowRamDevice }.getOrNull()

    val storage = runCatching { StatFs(app.filesDir.absolutePath).availableBytes }.getOrNull()

    val abis = runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())

    return DwDeviceMeasurement(
        totalRamBytes = memory?.totalMem,
        availableRamBytes = memory?.availMem,
        lowRamDevice = lowRam,
        freeStorageBytes = storage,
        abis = abis,
        thermal = dwThermalState(app),
        charging = dwCharging(app),
        takenAtElapsedMs = SystemClock.elapsedRealtime(),
    )
}

private fun dwThermalState(context: Context): DwThermalState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DwThermalState.UNMEASURED
    val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        ?: return DwThermalState.UNMEASURED
    val status = runCatching { power.currentThermalStatus }.getOrNull() ?: return DwThermalState.UNMEASURED
    return when (status) {
        PowerManager.THERMAL_STATUS_NONE -> DwThermalState.NONE
        PowerManager.THERMAL_STATUS_LIGHT -> DwThermalState.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> DwThermalState.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> DwThermalState.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> DwThermalState.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> DwThermalState.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> DwThermalState.SHUTDOWN
        else -> DwThermalState.UNMEASURED
    }
}

private fun dwCharging(context: Context): Boolean? =
    runCatching { context.getSystemService(BatteryManager::class.java)?.isCharging }.getOrNull()
