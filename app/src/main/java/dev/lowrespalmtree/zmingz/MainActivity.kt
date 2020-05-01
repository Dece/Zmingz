package dev.lowrespalmtree.zmingz

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.AsyncTask
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS
import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

class MainActivity: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_PICK_IMG, REQ_PICK_VID -> handlePickResult(requestCode, resultCode, data)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handlePickResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
                ?: return Unit .also { Log.e(TAG, "No intent or data.") }
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
                RETURN_CODE_SUCCESS -> true. also { Log.d(TAG, "ffmpeg success") }
                RETURN_CODE_CANCEL -> false .also { Log.d(TAG, "ffmpeg canceled") }
                else -> false .also { Log.d(TAG, "ffmpeg failed with rc $rc") }
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
            if (imageLayout.visibility != View.VISIBLE) {
                videoLayout.visibility = View.GONE
                imageLayout.visibility = View.VISIBLE
            }
            val mirrored = BitmapFactory.decodeFile(outputPath)
            val view = when (viewIndex) {
                1 -> imageView1
                2 -> imageView2
                else -> return
            }
            view.setImageBitmap(mirrored)
        } else if (requestCode == REQ_PICK_VID) {
            if (videoLayout.visibility != View.VISIBLE) {
                imageLayout.visibility = View.GONE
                videoLayout.visibility = View.VISIBLE
            }
            val view = when (viewIndex) {
                1 -> videoView1
                2 -> videoView2
                else -> return
            }
            view.setVideoPath(outputPath)
            view.setOnPreparedListener { mp -> mp.isLooping = true }
            view.start()
        }
    }

    companion object {
        private const val TAG = "ZMINGZ"
        private const val REQ_PICK_IMG = 1
        private const val REQ_PICK_VID = 2
        private const val VF1 = "crop=iw/2:ih:0:0,split[left][tmp];[tmp]hflip[right];[left][right] hstack"
        private const val VF2 = "crop=iw/2:ih:iw/2:0,split[left][tmp];[tmp]hflip[right];[right][left] hstack"

        private fun getFileExtension(path: String): String {
            if (!path.contains('.'))
                return ""
            return path.substring(path.lastIndexOf('.') + 1)
        }
    }

}
