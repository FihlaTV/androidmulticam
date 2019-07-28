package com.dhirajgupta.multicam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.dhirajgupta.multicam.interfaces.CAMERASTATE_IDLE
import com.dhirajgupta.multicam.interfaces.CAMERASTATE_PREVIEW
import com.dhirajgupta.multicam.interfaces.ManagedCameraStatus
import com.dhirajgupta.multicam.services.ManagedCamera
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {
    ////////////////////////////////// Instance Vars //////////////////////////////////////////////

    var camera0: ManagedCamera? = null
    var camera1: ManagedCamera? = null

    ////////////////////////////////// Activity Constants //////////////////////////////////////////

    /**
     * Request code for Camera permission request
     */
    val PERMREQ_CAMERA = 101

    ////////////////////////////////// Activity Methods //////////////////////////////////////////
    /**
     * Set up the activity with all one-time activity init related steps
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.i("onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initCameras()
        camera0?.isPreviewing = true // Default to showing the first camera, it will automatically start when ready
        button_toggle_cam_0.setOnClickListener { toggleCamera(button_toggle_cam_0, camera0) }
        button_toggle_cam_1.setOnClickListener { toggleCamera(button_toggle_cam_1, camera1) }
        button_save.setOnClickListener {
            Timber.i("Save")
            camera0?.let {
                if (it.cameraState == CAMERASTATE_IDLE){
                    Timber.i("Ignoring Camera 0 because it is idle!")
                    return@let
                }
                it.lockFocus()
            }
            camera1?.let {
                if (it.cameraState == CAMERASTATE_IDLE){
                    Timber.i("Ignoring Camera 0 because it is idle!")
                    return@let
                }
                it.lockFocus()
            }
        }
    }

    /**
     * Even though we're not using Fragments, *right now*, we will ultimately do so in the future,
     * so it makes sense to override onResumeFragments instead of onResume
     * We will perform on-foreground steps in this method, and open the camera(s) that were operating
     * at last run
     */
    override fun onResumeFragments() {
        super.onResumeFragments()
        Timber.i("onResumeFragments")
        camera0?.prepareToPreview()
        camera1?.prepareToPreview()
    }

    /**
     * We must save the state of the app, including which cameras were operational and then,
     * relinquish control on the camera(s) so that the OS can allocate them as required
     */
    override fun onPause() {
        super.onPause()
        Timber.i("onPause Activity")
        camera0?.releaseResources()
        camera1?.releaseResources()
    }

    fun toggleCamera(view: Button, camera: ManagedCamera?) {
        camera?.let {
            it.isPreviewing = !it.isPreviewing
            it.updatePreviewStatus()
        }
    }


    /**
     * Handle Activity permission request responses
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMREQ_CAMERA) {
            //Camera permission code was requested, if not granted, then we finish the activity and quit the app
            // if the user wants to give permission, they will open the app again
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Timber.i("User denied camera permission, finishing activity!")
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    // User has permanently denied Camera permission, so we prompt them to grant it from the Application
                    // Settings screen
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Please grant Camera permission from App settings to allow this app to record video")
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            // Open app settings screen
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts("package", packageName, null)
                            })
                            finish() // App will be restarted by the user after they give permission
                        }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                            finish() // User declined to open the application settings screen
                        }
                        .create()
                        .show()
                } else {
                    finish() // We're still allowed to show rationale for requesting permission (which will show the next
                    // time the user launches the app), so we quit silently
                }
            } else {
                //The single permission in the results *is* Granted
            }
        }
    }

    //////////////////////////////////// Implementation and Utility methods ////////////////////////////////////////////////


    /**
     * Utility function to ask for Camera access permission, if required
     * If the function did not ask for permission, because it is granted already, returns false
     * else it asks for permission and returns true
     */
    fun askCameraPermission(): Boolean {
        val permission_status = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
        if (permission_status != PackageManager.PERMISSION_GRANTED) {
            Timber.i("Camera permission NOT granted")
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                Timber.i("Should show rationale")
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("This app needs your permission to Record Video to function properly. Press No to exit, or Yes to proceed")
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        // Ask permission again
                        requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMREQ_CAMERA)
                    }
                    .setNegativeButton(getString(R.string.no)) { _, _ ->
                        finish() // User chose No, so we quit the activity (and exit the app)
                    }
                    .create()
                    .show()
            } else {
                // Ask permission for the first time
                Timber.i("Requesting camera permission...")
                requestPermissions(arrayOf(Manifest.permission.CAMERA), PERMREQ_CAMERA)
            }
            return true
        }
        return false
    }

    val cameraStatusHandler = object : ManagedCameraStatus {
        override fun cameraFPSchanged(
            camera: ManagedCamera,
            fps: Int
        ) {
//            Timber.i("Cam ${camera.systemId} FPS changed: $fps")
            if (camera.cameraState == CAMERASTATE_IDLE) {
                Timber.w("Ignoring FPS change because Cam ${camera.systemId} is IDLE!!")
                return
            }
            if (camera.systemId == camera0?.systemId) {
                textview_cam0_description.text = getString(R.string.camera_description).format(camera.systemId, fps)
            } else {
                textview_cam1_description.text = getString(R.string.camera_description).format(camera.systemId, fps)
            }
        }

        override fun cameraStateChanged(
            camera: ManagedCamera,
            state: Int
        ) {
            Timber.i("Cam ${camera.systemId}  State changed: $state")
            if (state != CAMERASTATE_IDLE) {
                if (camera.systemId == camera0?.systemId) {
                    //Camera 0 is previewing
                    button_toggle_cam_0.text = "${getString(R.string.stop)} ${camera.systemId}"
                } else {
                    //Camera 1 is previewing
                    button_toggle_cam_1.text = "${getString(R.string.stop)} ${camera.systemId}"
                }
            } else {
                if (camera.systemId == camera0?.systemId) {
                    //Camera 0 is idle
                    button_toggle_cam_0.text = "${getString(R.string.start)} ${camera.systemId}"
                    textview_cam0_description.text = getString(R.string.camera_description_idle).format(camera.systemId)
                } else {
                    //Camera 1 is idle
                    button_toggle_cam_1.text = "${getString(R.string.start)} ${camera.systemId}"
                    textview_cam1_description.text = getString(R.string.camera_description_idle).format(camera.systemId)
                }
            }
        }

        override fun cameraSavedPhoto(camera: ManagedCamera, filePath: File) {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(getString(R.string.camera_saved_photo).format(camera.systemId,filePath.absolutePath))
                .create()
                .show()
        }
    }

    fun initCameras() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Timber.i("Available cameras: %s", Arrays.toString(manager.cameraIdList))
        if (manager.cameraIdList.size < 1) {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(getString(R.string.this_device_has_no_cameras))
                .setNeutralButton(getString(R.string.ok)) { _, _ ->
                    finish() //Finish the activity because there are no cameras
                }
                .create()
                .show()
        }
        camera0 = ManagedCamera(
            manager.cameraIdList[0],
            "Camera${manager.cameraIdList[0]}}",
            view_finder_0,
            cameraStatusHandler
        )
        if (manager.cameraIdList.size >= 2) {
            camera1 = ManagedCamera(
                manager.cameraIdList[1],
                "Camera${manager.cameraIdList[1]}}",
                view_finder_1,
                cameraStatusHandler
            )
        }
    }

}