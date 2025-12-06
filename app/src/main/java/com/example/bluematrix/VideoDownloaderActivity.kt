package com.example.bluematrix

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class VideoDownloaderActivity : AppCompatActivity() {

    private val okHttpClient = OkHttpClient()

    private var lastDownloadedUri: Uri? = null
    private var currentDownloadThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var edtVideoUrl: EditText
    private lateinit var txtDownloadStatus: TextView
    private lateinit var txtDownloadPercent: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var btnPauseResume: Button
    private lateinit var btnDownload: Button
    private lateinit var btnPreview: Button
    private lateinit var imgThumbnail: ImageView

    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_downloader)

        edtVideoUrl = findViewById(R.id.edtVideoUrl)
        txtDownloadStatus = findViewById(R.id.txtDownloadStatus)
        txtDownloadPercent = findViewById(R.id.txtDownloadPercent)
        progressDownload = findViewById(R.id.progressDownload)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnDownload = findViewById(R.id.btnDownload)
        btnPreview = findViewById(R.id.btnPreview)
        imgThumbnail = findViewById(R.id.imgThumbnail)

        btnPreview.setOnClickListener {
            val url = edtVideoUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter a video URL first", Toast.LENGTH_SHORT).show()
            } else if (!url.startsWith("http")) {
                Toast.makeText(this, "Invalid URL. It must start with http or https.", Toast.LENGTH_SHORT).show()
            } else {
                loadThumbnailPreview(url)
            }
        }

        btnDownload.setOnClickListener {
            val url = edtVideoUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a video URL", Toast.LENGTH_SHORT).show()
            } else if (!url.startsWith("http")) {
                Toast.makeText(this, "Invalid URL. It must start with http or https.", Toast.LENGTH_SHORT).show()
            } else {
                startDownloadWithOkHttp(url)
            }
        }

        btnPauseResume.setOnClickListener {
            // Simple: cancel the active download
            if (isDownloading) {
                cancelDownload()
                Toast.makeText(this, "Download cancelled.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "To resume, tap Download again. (True resume with byte ranges is more advanced.)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
    }

    private fun cancelDownload() {
        isDownloading = false
        currentDownloadThread?.interrupt()
        currentDownloadThread = null
    }

    /**
     * Load a thumbnail from the remote video URL using MediaMetadataRetriever.
     * This may not work for all streaming/protected URLs, but will work for many direct URLs.
     */
    private fun loadThumbnailPreview(url: String) {
        txtDownloadStatus.text = "Loading preview..."
        imgThumbnail.setImageBitmap(null)

        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                // For network URLs, use the setDataSource(String, Map<String, String>?)
                retriever.setDataSource(url, HashMap())

                // Get a frame at time 0 (or you could use another timestamp)
                val bitmap = retriever.frameAtTime
                retriever.release()

                if (bitmap != null) {
                    handler.post {
                        imgThumbnail.setImageBitmap(bitmap)
                        txtDownloadStatus.text = "Preview loaded"
                    }
                } else {
                    handler.post {
                        txtDownloadStatus.text = "Could not load preview"
                        Toast.makeText(
                            this@VideoDownloaderActivity,
                            "Preview not available for this video.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post {
                    txtDownloadStatus.text = "Preview failed"
                    Toast.makeText(
                        this@VideoDownloaderActivity,
                        "Preview not supported for this URL.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun startDownloadWithOkHttp(url: String) {
        if (isDownloading) {
            Toast.makeText(this, "A download is already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val externalMoviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (externalMoviesDir == null) {
            Toast.makeText(this, "Cannot access external files directory.", Toast.LENGTH_LONG).show()
            return
        }

        if (!externalMoviesDir.exists()) {
            externalMoviesDir.mkdirs()
        }

        val fileName = URLUtil.guessFileName(url, null, null)
        val outFile = File(externalMoviesDir, fileName)

        txtDownloadStatus.text = "Starting download..."
        txtDownloadPercent.text = "0%"
        progressDownload.progress = 0
        isDownloading = true

        currentDownloadThread = Thread {

            try {
                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP error code: ${response.code}")
                }

                // Your snippet integrated here
                val body = response.body ?: return@Thread
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(outFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int = 0

                while (isDownloading && inputStream.read(buffer).also { bytesRead = it } != -1) {

                    outputStream.write(buffer, 0, bytesRead)

                    downloadedBytes += bytesRead

                    if (totalBytes > 0) {
                        val progress = (downloadedBytes * 100 / totalBytes).toInt()
                        handler.post {
                            progressDownload.progress = progress
                            txtDownloadPercent.text = "$progress%"
                            txtDownloadStatus.text = "Downloading..."
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                if (!isDownloading) {
                    // Cancelled
                    handler.post {
                        txtDownloadStatus.text = "Download cancelled"
                    }
                    return@Thread
                }

                // Mark done and prepare URI for opening
                val uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    outFile
                )
                lastDownloadedUri = uri

                handler.post {
                    isDownloading = false
                    txtDownloadStatus.text = "Download complete"
                    txtDownloadPercent.text = "100%"
                    progressDownload.progress = 100
                    showOpenInGalleryDialog()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                handler.post {
                    isDownloading = false
                    txtDownloadStatus.text = "Download failed: ${e.message}"
                    Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        currentDownloadThread?.start()
    }

    private fun showOpenInGalleryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Open in Gallery")
            .setMessage("Your video has been downloaded. Do you want to open it now?")
            .setPositiveButton("Open") { _, _ ->
                openVideoInGallery()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVideoInGallery() {
        val uri = lastDownloadedUri

        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } else {
            // Fallback: open generic video picker
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "video/*"
            }
            startActivity(intent)
        }
    }
}
