package dev.lowrespalmtree.zmingz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private enum class MediaType { IMAGE, VIDEO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_PICK_IMG, REQ_PICK_VID -> handlePickResult(requestCode, resultCode, data)
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
        val menu = PopupMenu(this, v)
        val saveItem = menu.menu.add(R.string.save)
        val shareItem = menu.menu.add(R.string.share)
        menu.setOnMenuItemClickListener { item ->
            val path = when (v.id) {
                imageView1.id, videoView1.id -> path1
                imageView2.id, videoView2.id -> path2
                else -> return@setOnMenuItemClickListener false
            }
            when (item.itemId) {
                saveItem.itemId -> {
                    val type = when (v.id) {
                        imageView1.id, imageView2.id -> MediaType.IMAGE
                        videoView1.id, videoView2.id -> MediaType.VIDEO
                        else -> return@setOnMenuItemClickListener false
                    }
                    save(path, type)
                }
                else -> {}
            }
            true
        }
        menu.show()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handlePickResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
                ?: return Unit .also { Log.e(TAG, "No intent or data") }
        val uriPath = uri.path
                ?: return Unit .also { Log.e(TAG, "No path in URI") }
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

        imageLayout.visibility = View.GONE
        videoLayout.visibility = View.GONE
        Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show()

        val outputFile1 = File.createTempFile("output1", ".$extension", cacheDir)
        MirrorTask(WeakReference(this), requestCode, 1)
            .execute(inputFile.canonicalPath, outputFile1.canonicalPath, VF1)
        val outputFile2 = File.createTempFile("output2", ".$extension", cacheDir)
        MirrorTask(WeakReference(this), requestCode, 2)
            .execute(inputFile.canonicalPath, outputFile2.canonicalPath, VF2)
    }

    class MirrorTask(
        private val activity: WeakReference<MainActivity>,
        private val requestCode: Int,
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
                activity.get()?.updateViews(requestCode, index, outputPath)
            }
        }

    }

    internal fun updateViews(requestCode: Int, viewIndex: Int, outputPath: String) {
        if (requestCode == REQ_PICK_IMG) {
            if (imageLayout.visibility != View.VISIBLE)
                imageLayout.visibility = View.VISIBLE
            val mirrored = BitmapFactory.decodeFile(outputPath)
            val view = when (viewIndex) {
                1 -> { path1 = outputPath; imageView1 }
                2 -> { path2 = outputPath; imageView2 }
                else -> return
            }
            view.setImageBitmap(mirrored)
        } else if (requestCode == REQ_PICK_VID) {
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

    companion object {
        private const val TAG = "ZMINGZ"
        private const val REQ_PICK_IMG = 1
        private const val REQ_PICK_VID = 2
        private const val REQ_WRITE_PERM = 3
        private const val VF1 = "crop=iw/2:ih:0:0,split[left][tmp];[tmp]hflip[right];[left][right] hstack"
        private const val VF2 = "crop=iw/2:ih:iw/2:0,split[left][tmp];[tmp]hflip[right];[right][left] hstack"

        private fun getFileExtension(path: String): String {
            if (!path.contains('.'))
                return ""
            return path.substring(path.lastIndexOf('.') + 1)
        }
    }

}