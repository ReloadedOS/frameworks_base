/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs

import com.android.systemui.settings.brightness.BrightnessController
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.settings.brightness.MirroredBrightnessController
import com.android.systemui.statusbar.policy.BrightnessMirrorController

import javax.inject.Inject

/**
 * Controls brightness slider in QQS. It's responsible for
 * showing/hiding it when appropriate and (un)registering listeners
 */
class QuickQSBrightnessController @Inject constructor(
    private val brightnessControllerFactory: BrightnessController.Factory,
    private val brightnessSliderControllerFactory: BrightnessSliderController.Factory,
    private val quickQSPanel: QuickQSPanel
) : MirroredBrightnessController {

    private var isListening = false
    private var brightnessController: BrightnessController? = null
    private var mirrorController: BrightnessMirrorController? = null

    private fun createBrightnessController(): BrightnessController {
        val slider = brightnessSliderControllerFactory.create(
            quickQSPanel.context, quickQSPanel
        ).also { it.init() }
        quickQSPanel.setBrightnessView(slider.rootView)
        return brightnessControllerFactory.create(slider, quickQSPanel.brightnessView)
    }

    /**
     * Starts/Stops listening for brightness changing events.
     * It's fine to call this function even if slider is not visible (which would be the case for
     * all small screen devices), it will just do nothing in that case
     */
    fun setListening(listening: Boolean) {
        if (listening) {
            // controller can be null when slider was never shown
            if (!isListening && brightnessController != null) {
                brightnessController?.registerCallbacks()
                isListening = true
            }
        } else {
            brightnessController?.unregisterCallbacks()
            isListening = false
        }
    }

    fun checkRestrictionAndSetEnabled() {
        brightnessController?.checkRestrictionAndSetEnabled()
    }

    fun refreshVisibility(showSlider: Boolean) {
        if (showSlider) {
            showBrightnessSlider()
        } else {
            hideBrightnessSlider()
        }
    }

    override fun setMirror(controller: BrightnessMirrorController) {
        mirrorController = controller
        mirrorController?.let { brightnessController?.setMirror(it) }
    }

    private fun hideBrightnessSlider() {
        brightnessController?.hideSlider()
    }

    private fun showBrightnessSlider() {
        if (brightnessController == null) {
            brightnessController = createBrightnessController()
            mirrorController?.also { brightnessController?.setMirror(it) }
            brightnessController?.registerCallbacks()
            isListening = true
        }
        brightnessController?.showSlider()
    }
}
