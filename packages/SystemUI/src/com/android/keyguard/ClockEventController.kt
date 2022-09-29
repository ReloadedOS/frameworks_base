/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.keyguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.text.format.DateFormat
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.Clock
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shared.regionsampling.RegionSamplingInstance
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import com.android.systemui.statusbar.policy.ConfigurationController
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Controller for a Clock provided by the registry and used on the keyguard. Instantiated by
 * [KeyguardClockSwitchController]. Functionality is forked from [AnimatableClockController].
 */
class ClockEventController @Inject constructor(
        private val statusBarStateController: StatusBarStateController,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val batteryController: BatteryController,
        private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
        private val configurationController: ConfigurationController,
        @Main private val resources: Resources,
        private val context: Context,
        @Main private val mainExecutor: Executor,
        @Background private val bgExecutor: Executor,
        private val featureFlags: FeatureFlags
) {
    var clock: Clock? = null
        set(value) {
            field = value
            if (value != null) {
                value.initialize(resources, dozeAmount, 0f)
                updateRegionSamplers(value)
            }
        }

    private var isDozing = false
        private set

    private var isCharging = false
    private var dozeAmount = 0f
    private var isKeyguardShowing = false

    private val regionSamplingEnabled =
            featureFlags.isEnabled(com.android.systemui.flags.Flags.REGION_SAMPLING)

    private val updateFun = object : RegionSamplingInstance.UpdateColorCallback {
        override fun updateColors() {
            smallClockIsDark = smallRegionSamplingInstance.currentRegionDarkness()
            largeClockIsDark = largeRegionSamplingInstance.currentRegionDarkness()

            clock?.events?.onColorPaletteChanged(resources, smallClockIsDark, largeClockIsDark)
        }
    }

    fun updateRegionSamplers(currentClock: Clock?) {
        smallRegionSamplingInstance.stopRegionSampler()
        largeRegionSamplingInstance.stopRegionSampler()

        smallRegionSamplingInstance = RegionSamplingInstance(
                currentClock?.smallClock,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled,
                updateFun
        )

        largeRegionSamplingInstance = RegionSamplingInstance(
                currentClock?.largeClock,
                mainExecutor,
                bgExecutor,
                regionSamplingEnabled,
                updateFun
        )

        smallRegionSamplingInstance.startRegionSampler()
        largeRegionSamplingInstance.startRegionSampler()

        updateFun.updateColors()
    }

    var smallRegionSamplingInstance: RegionSamplingInstance = RegionSamplingInstance(
            clock?.smallClock,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            updateFun
    )

    var largeRegionSamplingInstance: RegionSamplingInstance = RegionSamplingInstance(
            clock?.largeClock,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            updateFun
    )

    private var smallClockIsDark = smallRegionSamplingInstance.currentRegionDarkness()
    private var largeClockIsDark = largeRegionSamplingInstance.currentRegionDarkness()

    private val configListener = object : ConfigurationController.ConfigurationListener {
        override fun onThemeChanged() {
            updateFun.updateColors()
        }
    }

    private val batteryCallback = object : BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            if (isKeyguardShowing && !isCharging && charging) {
                clock?.animations?.charge()
            }
            isCharging = charging
        }
    }

    private val localeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            clock?.events?.onLocaleChanged(Locale.getDefault())
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            clock?.animations?.doze(linear)

            isDozing = linear > dozeAmount
            dozeAmount = linear
        }
    }

    private val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onKeyguardVisibilityChanged(showing: Boolean) {
            isKeyguardShowing = showing
            if (!isKeyguardShowing) {
                clock?.animations?.doze(if (isDozing) 1f else 0f)
            }
        }

        override fun onTimeFormatChanged(timeFormat: String) {
            clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
        }

        override fun onTimeZoneChanged(timeZone: TimeZone) {
            clock?.events?.onTimeZoneChanged(timeZone)
        }

        override fun onUserSwitchComplete(userId: Int) {
            clock?.events?.onTimeFormatChanged(DateFormat.is24HourFormat(context))
        }
    }

    init {
        isDozing = statusBarStateController.isDozing
        clock?.events?.onColorPaletteChanged(resources, smallClockIsDark, largeClockIsDark)
    }

    fun registerListeners() {
        dozeAmount = statusBarStateController.dozeAmount
        isDozing = statusBarStateController.isDozing || dozeAmount != 0f

        broadcastDispatcher.registerReceiver(
            localeBroadcastReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED)
        )
        configurationController.addCallback(configListener)
        batteryController.addCallback(batteryCallback)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        statusBarStateController.addCallback(statusBarStateListener)
        smallRegionSamplingInstance.startRegionSampler()
        largeRegionSamplingInstance.startRegionSampler()
    }

    fun unregisterListeners() {
        broadcastDispatcher.unregisterReceiver(localeBroadcastReceiver)
        configurationController.removeCallback(configListener)
        batteryController.removeCallback(batteryCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        statusBarStateController.removeCallback(statusBarStateListener)
        smallRegionSamplingInstance.stopRegionSampler()
        largeRegionSamplingInstance.stopRegionSampler()
    }

    /**
     * Dump information for debugging
     */
    fun dump(pw: PrintWriter) {
        pw.println(this)
        clock?.dump(pw)
        smallRegionSamplingInstance.dump(pw)
        largeRegionSamplingInstance.dump(pw)
    }

    companion object {
        private val TAG = ClockEventController::class.simpleName
        private const val FORMAT_NUMBER = 1234567890
    }
}
