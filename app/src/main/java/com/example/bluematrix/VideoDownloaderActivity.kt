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
        progressDownload.isIndeterminate = false
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

                val body = response.body ?: throw Exception("Empty response body")
                val totalBytes = body.contentLength() // can be -1 if unknown
                var downloadedBytes = 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(outFile)

                val buffer = ByteArray(8192)

                // For speed calculation
                val startTime = System.currentTimeMillis()
                var lastUiUpdateTime = startTime

                var done = false

                while (isDownloading && !Thread.currentThread().isInterrupted) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) {
                        done = true
                        break
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    // Update UI at most every 500ms (or on completion)
                    if (now - lastUiUpdateTime >= 500) {
                        lastUiUpdateTime = now

                        val elapsedSec = (now - startTime) / 1000.0
                        val speedBytesPerSec =
                            if (elapsedSec > 0) downloadedBytes / elapsedSec else 0.0
                        val speedText = humanReadableSpeed(speedBytesPerSec)

                        handler.post {
                            txtDownloadStatus.text = "Downloading... ($speedText)"

                            if (totalBytes > 0) {
                                // Proper percentage when content-length is known
                                val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                progressDownload.isIndeterminate = false
                                progressDownload.progress = progress
                                txtDownloadPercent.text = "$progress%"
                            } else {
                                // Unknown file size – show how much is downloaded
                                progressDownload.isIndeterminate = true
                                txtDownloadPercent.text =
                                    "${humanReadableSize(downloadedBytes)} downloaded"
                            }
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

                if (!done) {
                    // Thread ended for some reason (interrupted / error)
                    handler.post {
                        isDownloading = false
                        txtDownloadStatus.text = "Download stopped unexpectedly"
                        Toast.makeText(this, "Download stopped", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // Mark done and prepare URI to opening
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
                    progressDownload.isIndeterminate = false
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

    // ===== Helpers for nice text output =====

    private fun humanReadableSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return "0 KB/s"

        val kilo = 1024.0
        val mega = kilo * 1024
        val giga = mega * 1024

        return when {
            bytesPerSec >= giga -> String.format("%.2f GB/s", bytesPerSec / giga)
            bytesPerSec >= mega -> String.format("%.2f MB/s", bytesPerSec / mega)
            bytesPerSec >= kilo -> String.format("%.2f KB/s", bytesPerSec / kilo)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }

    private fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val z = (63 - java.lang.Long.numberOfLeadingZeros(bytes)) / 10
        return String.format(
            "%.1f %sB",
            bytes.toDouble() / (1L shl (z * 10)),
            " KMGTPE"[z]
        )
    }
}
