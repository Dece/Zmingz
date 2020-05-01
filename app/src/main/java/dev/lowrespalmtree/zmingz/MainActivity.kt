package dev.lowrespalmtree.zmingz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

class MainActivity: AppCompatActivity() {

    /** Path for first image/video. */
    private var path1 = ""
    /** Path for second image/video. */
    private var path2 = ""
    /** Temporary path for the media to save, to use after perms being granted. */
    private var savingPath = ""
    /** Temporary type for the media to save, to use after perms being granted. */
    private var savingType = MediaType.IMAGE
    /** Path for storing camera image. */
    private var cameraImagePath = ""

    /** Path to media cache dir, ensuring it exists. */
    private val mediaCacheDir: File?
        get() {
            val mediaCacheDir = File(cacheDir, "media")
            if (!mediaCacheDir.exists() && !mediaCacheDir.mkdir()) {
                Log.e(TAG, "Could not create cache media dir")
                Toast.makeText(this, R.string.write_error, Toast.LENGTH_SHORT).show()
                return null
            }
            return mediaCacheDir
        }

    enum class MediaType { IMAGE, VIDEO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (intent.action == Intent.ACTION_SEND) {
            val type = if (intent.type?.startsWith("image") == true) {
                MediaType.IMAGE
            } else if (intent.type?.startsWith("video") == true) {
                MediaType.VIDEO
            } else {
                null
            }
            val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (type != null && uri != null)
                processMedia(uri, type)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_PICK_IMG, REQ_PICK_VID -> handlePickResult(requestCode, resultCode, data)
            REQ_TAKE_IMG -> handleTakeImageResult(resultCode)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQ_WRITE_PERM) {
            if (permissions[0] == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    saveWithPerm(savingPath, savingType)
                    return
                }
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun getUriForFile(file: File): Uri =
        FileProvider.getUriForFile(
            this,
            "dev.lowrespalmtree.fileprovider",
            file
        )

    fun openFile(v: View) {
        val req: Int
        val pickIntent = when (v.id) {
            buttonImage.id -> {
                req = REQ_PICK_IMG
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            }
            buttonVideo.id -> {
                req = REQ_PICK_VID
                Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
            else -> return
        }
        startActivityForResult(pickIntent, req)
    }

    fun openViewMenu(v: View) {
        val path = when (v.id) {
            imageView1.id, videoView1.id -> path1
            imageView2.id, videoView2.id -> path2
            else -> return
        }
        val type = mediaTypeFromViewId(v.id)
            ?: return

        val menu = PopupMenu(this, v)
        menu.menu.add(R.string.save).setOnMenuItemClickListener {
            save(path, type)
            true
        }
        menu.menu.add(R.string.share).setOnMenuItemClickListener {
            share(path, type)
            true
        }
        menu.show()
    }

    fun openCamera(v: View) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Log.e(TAG, "No camera feature")
            return
        }

        when (v.id) {
            buttonCamera.id -> {
                val inputFile = File(mediaCacheDir, "${System.currentTimeMillis()}.jpg")
                cameraImagePath = inputFile.canonicalPath
                val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .apply { putExtra(MediaStore.EXTRA_OUTPUT, getUriForFile(inputFile)) }
                startActivityForResult(captureIntent, REQ_TAKE_IMG)
            }
            buttonCameraVideo.id -> {

            }
        }
    }

    private fun mediaTypeFromViewId(id: Int): MediaType? =
        when (id) {
            imageView1.id, imageView2.id -> MediaType.IMAGE
            videoView1.id, videoView2.id -> MediaType.VIDEO
            else -> null
        }

    @Suppress("UNUSED_PARAMETER")
    private fun handlePickResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK)
            return
        val uri = data?.data
            ?: return Unit.also { Log.e(TAG, "No intent or data") }
        val type = when (requestCode) {
            REQ_PICK_IMG -> MediaType.IMAGE
            REQ_PICK_VID -> MediaType.VIDEO
            else -> return
        }
        processMedia(uri, type)
    }

    private fun handleTakeImageResult(resultCode: Int) {
        if (resultCode != Activity.RESULT_OK)
            return
        processMedia(cameraImagePath, MediaType.IMAGE)
    }

    /** Process media at URI, copying to a local cache file for FFmpeg beforehand. */
    private fun processMedia(uri: Uri, type: MediaType) {
        val uriPath = uri.path
            ?: return Unit.also { Log.e(TAG, "No path in URI") }
        val extension = getFileExtension(uriPath)
        // Copy picked file to cache dir for FFmpeg.
        val inputFile = File.createTempFile("input", ".$extension", cacheDir)
        contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null)
                return Unit .also { Log.e(TAG, "Could not open input file") }
            FileOutputStream(inputFile).use { fos ->
                fos.buffered().write(inputStream.buffered().readBytes())
            }
        }

        processMedia(inputFile.canonicalPath, type)
    }

    /** Start the async mirroring tasks. */
    private fun processMedia(path: String, type: MediaType) {
        imageLayout.visibility = View.GONE
        videoLayout.visibility = View.GONE
        Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()

        val extension = getFileExtension(path)
        val outputFile1 = File.createTempFile("output1", ".$extension", mediaCacheDir)
        MirrorTask(WeakReference(this), type, 1)
            .execute(path, outputFile1.canonicalPath, VF1)
        val outputFile2 = File.createTempFile("output2", ".$extension", mediaCacheDir)
        MirrorTask(WeakReference(this), type, 2)
            .execute(path, outputFile2.canonicalPath, VF2)
    }

    class MirrorTask(
        private val activity: WeakReference<MainActivity>,
        private val type: MediaType,
        private val index: Int
    ): AsyncTask<String, Void, Boolean>() {
        private lateinit var outputPath: String

        override fun doInBackground(vararg params: String?): Boolean {
            val inputPath = params[0]!!
            outputPath = params[1]!!
            val vf = params[2]!!
            val command = "-i $inputPath -vf \"$vf\" -y $outputPath"
            return when (val rc = FFmpeg.execute(command)) {
                RETURN_CODE_SUCCESS -> true. also { Log.d(TAG, "FFmpeg success") }
                RETURN_CODE_CANCEL -> false .also { Log.d(TAG, "FFmpeg canceled") }
                else -> false .also { Log.d(TAG, "FFmpeg failed with rc $rc") }
            }
        }

        override fun onPostExecute(result: Boolean?) {
            if (result == true) {
                activity.get()?.updateViews(type, index, outputPath)
            }
        }

    }

    internal fun updateViews(type: MediaType, viewIndex: Int, outputPath: String) {
        when (type) {
            MediaType.IMAGE -> {
                if (imageLayout.visibility != View.VISIBLE)
                    imageLayout.visibility = View.VISIBLE
                val mirrored = BitmapFactory.decodeFile(outputPath)
                val view = when (viewIndex) {
                    1 -> { path1 = outputPath; imageView1 }
                    2 -> { path2 = outputPath; imageView2 }
                    else -> return
                }
                view.setImageBitmap(mirrored)
            }
            MediaType.VIDEO -> {
                if (videoLayout.visibility != View.VISIBLE)
                    videoLayout.visibility = View.VISIBLE
                val view = when (viewIndex) {
                    1 -> { path1 = outputPath; videoView1 }
                    2 -> { path2 = outputPath; videoView2 }
                    else -> return
                }
                view.setVideoPath(outputPath)
                view.setOnPreparedListener { mp -> mp.isLooping = true }
                view.start()
            }
        }
    }

    private fun save(path: String, type: MediaType) {
        val writePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val permissionStatus = checkSelfPermission(writePermission)
        if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
            saveWithPerm(path, type)
        } else {
            savingPath = path
            savingType = type
            requestPermissions(arrayOf(writePermission), REQ_WRITE_PERM)
        }
    }

    private fun saveWithPerm(path: String, type: MediaType) {
        // Ensure media directories for the app exist.
        val envDirId = when (type) {
            MediaType.IMAGE -> Environment.DIRECTORY_PICTURES
            MediaType.VIDEO -> Environment.DIRECTORY_MOVIES
        }
        val externalMediaDir = getExternalFilesDir(envDirId)
            ?: return Unit .also { Log.e(TAG, "Can't find an external dir") }
        val mediaDir = File(externalMediaDir, getString(R.string.app_name))
        if (!mediaDir.exists() && !mediaDir.mkdir()) {
            Log.e(TAG, "Failed to create app media dir: $mediaDir")
            Toast.makeText(this, R.string.write_error, Toast.LENGTH_SHORT).show()
            return
        }

        val extension = getFileExtension(path).let { if (it.isEmpty()) "xxx" else it }
        val outputFile = File(mediaDir.canonicalPath, "${System.currentTimeMillis()}.$extension")
        if (!outputFile.createNewFile()) {
            Log.e(TAG, "Failed to create new file: $outputFile")
            Toast.makeText(this, R.string.write_error, Toast.LENGTH_SHORT).show()
            return
        }

        FileInputStream(path).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                fos.buffered().write(fis.buffered().readBytes())
            }
        }
        Log.i(TAG, "File saved at $outputFile")
    }

    private fun share(path: String, type: MediaType) {
        val uri = getUriForFile(File(path))
        Log.i(TAG, "share with uri $uri")
        val shareIntent = Intent().also {
            it.action = Intent.ACTION_SEND
            it.putExtra(Intent.EXTRA_STREAM, uri)
            it.type = when (type) {
                MediaType.IMAGE -> "image/*"
                MediaType.VIDEO -> "video/*"
            }
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.send)))
    }

    companion object {
        private const val TAG = "ZMINGZ"
        private const val REQ_PICK_IMG = 1
        private const val REQ_PICK_VID = 2
        private const val REQ_WRITE_PERM = 3
        private const val REQ_TAKE_IMG = 4
        private const val REQ_TAKE_VID = 5
        private const val VF1 = "crop=iw/2:ih:0:0,split[left][tmp];[tmp]hflip[right];[left][right] hstack"
        private const val VF2 = "crop=iw/2:ih:iw/2:0,split[left][tmp];[tmp]hflip[right];[right][left] hstack"

        private fun getFileExtension(path: String): String {
            if (!path.contains('.'))
                return ""
            return path.substring(path.lastIndexOf('.') + 1)
        }
    }

}