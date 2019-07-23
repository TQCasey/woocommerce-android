package com.woocommerce.android.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.woocommerce.android.BuildConfig
import com.woocommerce.android.util.WooLog

abstract class AppUpgradeActivity : AppCompatActivity(),
        AppUpgradeActivityView,
        InstallStateUpdatedListener {
    companion object {
        private const val REQUEST_CODE_IN_APP_UPDATE = 19888
    }

    private lateinit var appUpdateManager: AppUpdateManager

    /**
     * The type of in app update to display:
     * [AppUpdateType.FLEXIBLE] OR [AppUpdateType.IMMEDIATE]
     */
    private val inAppUpdateType = BuildConfig.IN_APP_UPDATE_TYPE.toInt()

    private var appUpdateStarted: Boolean = false

    /**
     * Listener that is passed to the calling activity, if the update has failed for some reason.
     * If the user clicks on the Retry button, the update process will be tried again.
     */
    private val updateFailedActionListener: View.OnClickListener = View.OnClickListener {
        checkForAppUpdates()
    }

    /**
     * Listener that is passed to the calling activity, if the update has succeeded.
     * If the user clicks on the Restart button, the install process will begin and the app
     * will be restarted.
     */
    private val updateSuccessActionListener: View.OnClickListener = View.OnClickListener {
        appUpdateManager.completeUpdate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // initialise appUpdateManager
        appUpdateManager = AppUpdateManagerFactory.create(this)
    }

    override fun onResume() {
        super.onResume()
        handleAppUpdateOnResumed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_IN_APP_UPDATE) {
            when (resultCode) {
                //  handle user's rejection
                Activity.RESULT_CANCELED -> {
                    appUpdateStarted = false
                    // TODO: store the user's preference in SharedPrefs and don't show the upgrade dialog again
                }
                //  handle update failure
                ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> showAppUpdateFailedSnack(updateFailedActionListener)
            }
            return
        }
    }

    override fun onStateUpdate(installState: InstallState) {
        // Show module progress, log state, or install the update.
        when (installState.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                // After the update is downloaded, show a notification
                // and request user confirmation to restart the app.
                appUpdateManager.unregisterListener(this)
                showAppUpdateSuccessSnack(updateSuccessActionListener)
            }
            InstallStatus.FAILED -> {
                // App update failed for some reason. This could happen due to network
                // issues as well. Display an error snack and ask users to try again
                appUpdateManager.unregisterListener(this)
                showAppUpdateFailedSnack(updateFailedActionListener)
            }
        }
    }

    /**
     * Method is called from the child activity to check if there are any app updates pending.
     * This will display either [AppUpdateType.FLEXIBLE] or [AppUpdateType.IMMEDIATE] dialog to the user,
     * if there is a new app update.
     */
    internal fun checkForAppUpdates() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    // Checks that the platform will allow the specified type of update
                    if (appUpdateInfo.isUpdateTypeAllowed(inAppUpdateType)) {
                        if (isAppUpdateImmediate()) {
                            // initiate immediate update flow
                            requestAppUpdate(appUpdateInfo)
                        } else if (isAppUpdateFlexible()) {
                            // Before starting an update, register a listener for updates.
                            // initiate flexible update flow
                            appUpdateManager.registerListener(this)
                            requestAppUpdate(appUpdateInfo)
                        }
                        // the app update process has started
                        appUpdateStarted = true
                    }
                }
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                    WooLog.v(WooLog.T.UTILS, "App update not available")
                }
            }
        }
    }

    /**
     * Method is called from the [onResume] of the activity to check if the update process is
     * started and if so, verify that the UI is updated accordingly
     */
    private fun handleAppUpdateOnResumed() {
        // Check if the app update process has started.
        // If not, there is no point in checking if app update is available, so do nothing
        if (!appUpdateStarted) {
            return
        }
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    // A flexible update means that the user is able to use the app while the update is
                    // being downloaded. if the user goes back or kills the app, or gets a call etc, and the app goes
                    // into the background, it won’t stop the update process. But once the app comes back to the
                    // foreground, if the update is already downloaded, we need to ask the user to manually restart the
                    // app.
                    if (isAppUpdateFlexible() && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                        if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                            // display a Snackbar here to ask the user to manually restart the app
                            showAppUpdateSuccessSnack(updateSuccessActionListener)
                        }
                    }
                }

                // The integer UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS means that an immediate update
                // has been started, and update is still in progress. Triggering the request flow using update info’s
                // intent will ask Google Play to show that blocking, immediate app update screen.
                // Post the update, Play will automatically restart the app.
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    // After calling appUpdateManager.startUpdateFlowForResult() for an immediate update
                    // the play store takes control. The user will see the update progress till the time they are on the
                    // new version. And if the user goes back or kills the app, or gets a call etc, and the app goes
                    // into the background, it won’t stop the update process. This should be communicated to the user
                    // the moment the app gets back to the foreground.
                    if (isAppUpdateImmediate() && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        requestAppUpdate(appUpdateInfo)
                    }
                }
            }
        }
    }

    private fun isAppUpdateImmediate() = AppUpdateType.IMMEDIATE == inAppUpdateType
    private fun isAppUpdateFlexible() = AppUpdateType.FLEXIBLE == inAppUpdateType

    private fun requestAppUpdate(appUpdateInfo: AppUpdateInfo?) {
        appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo,
                inAppUpdateType,
                this,
                REQUEST_CODE_IN_APP_UPDATE
        )
    }
}
