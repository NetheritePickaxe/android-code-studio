/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.pickaxe.jcode.fragments.onboarding

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.updatePadding
import com.github.appintro.SlidePolicy
import com.google.android.material.button.MaterialButton
import com.pickaxe.jcode.R
import com.pickaxe.jcode.dependencies.JdkSetupLauncher
import com.pickaxe.jcode.utils.flashError

/**
 * Onboarding slide that installs the JDK / toolchain.
 *
 * The JDK is not bundled with the app — it is installed into the app's Termux prefix by the
 * `idesetup` script. This slide shows an "Install" button that launches the terminal to run
 * the script. The user cannot proceed past this slide until a JDK is available.
 */
class ToolchainSetupFragment : OnboardingFragment(), SlidePolicy {

  companion object {

    @JvmStatic
    fun newInstance(context: Context): ToolchainSetupFragment {
      return ToolchainSetupFragment().apply {
        arguments =
            Bundle().apply {
              putCharSequence(
                  KEY_ONBOARDING_TITLE,
                  context.getString(R.string.onboarding_title_toolchain),
              )
              putCharSequence(
                  KEY_ONBOARDING_SUBTITLE,
                  context.getString(R.string.onboarding_subtitle_toolchain),
              )
            }
      }
    }
  }

  private var installButton: MaterialButton? = null

  override fun createContentView(parent: ViewGroup, attachToParent: Boolean) {
    val context = parent.context
    val density = resources.displayMetrics.density
    val dp = { value: Int -> (value * density).toInt() }
    val button =
        MaterialButton(context).apply {
          text = context.getString(R.string.onboarding_toolchain_install)
          cornerRadius = dp(12)
          updatePadding(top = dp(14), bottom = dp(14), left = dp(32), right = dp(32))
          setOnClickListener { launchInstall() }
        }
    parent.addView(button)
    installButton = button
    updateButtonState()
  }

  override fun onResume() {
    super.onResume()
    updateButtonState()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    installButton = null
  }

  private fun updateButtonState() {
    val button = installButton ?: return
    val context = button.context
    if (JdkSetupLauncher.isJdkAvailable()) {
      button.text = context.getString(R.string.onboarding_toolchain_installed)
      button.isEnabled = false
    } else {
      button.text = context.getString(R.string.onboarding_toolchain_install)
      button.isEnabled = true
    }
  }

  private fun launchInstall() {
    val activity = requireActivity()
    JdkSetupLauncher.launch(activity)
  }

  // ─── SlidePolicy ─────────────────────────────────────────────────────

  override val isPolicyRespected: Boolean
    get() = JdkSetupLauncher.isJdkAvailable()

  override fun onUserIllegallyRequestedNextPage() {
    activity?.flashError(R.string.onboarding_toolchain_required)
  }
}
