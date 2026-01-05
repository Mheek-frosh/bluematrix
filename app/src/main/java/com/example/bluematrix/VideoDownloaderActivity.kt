package com.example.bluematrix

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
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
import com.github.youtubedl_android.YoutubeDL
import com.github.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VideoDownloaderActivity : AppCompatActivity() {

    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var lastDownloadedFile: File? = null
    private var currentDownloadCall: Call? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var edtVideoUrl: EditText
    private lateinit var txtDownloadStatus: TextView
    private lateinit var txtDownloadPercent: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var btnPauseResume: Button
    private lateinit var btnDownload: Button
    private lateinit var btnPreview: Button
    private lateinit var imgThumbnail: ImageView

    private var isDownloading = false

    // API Keys (replace with your own from RapidAPI)
    private val instagramApiKey = "422d2d3f9emsh073d587da04581ep147d5bjsn51e5073df06c"

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
            } else if (!isValidUrl(url)) {
                Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            } else {
                loadThumbnail(url)
            }
        }

        btnDownload.setOnClickListener {
            val url = edtVideoUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a video URL", Toast.LENGTH_SHORT).show()
            } else if (!isValidUrl(url)) {
                Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show()
            } else {
                extractAndDownload(url)
            }
        }

        btnPauseResume.setOnClickListener {
            if (isDownloading) {
                cancelDownload()
                Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelDownload()
        scope.cancel()
    }

    private fun cancelDownload() {
        isDownloading = false
        currentDownloadCall?.cancel()
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun loadThumbnail(url: String) {
        imgThumbnail.visibility = View.VISIBLE
        imgThumbnail.setImageResource(android.R.drawable.ic_menu_gallery) // Placeholder

        scope.launch {
            try {
                val thumbnailUrl = withContext(Dispatchers.IO) {
                    when {
                        url.contains("youtube.com") || url.contains("youtu.be") -> {
                            getYouTubeThumbnail(url)
                        }
                        url.contains("instagram.com") || url.contains("instagram") -> {
                            getInstagramThumbnail(url)
                        }
                        else -> ""
                    }
                }

                if (thumbnailUrl.isNotEmpty()) {
                    val bitmap = withContext(Dispatchers.IO) {
                        downloadImage(thumbnailUrl)
                    }
                    if (bitmap != null) {
                        imgThumbnail.setImageBitmap(bitmap)
                        Toast.makeText(this@VideoDownloaderActivity, "Thumbnail loaded", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@VideoDownloaderActivity, "Failed to load thumbnail", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@VideoDownloaderActivity, "Thumbnail not available", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@VideoDownloaderActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getYouTubeThumbnail(url: String): String {
        val videoId = extractYouTubeId(url)
        if (videoId.isEmpty()) return ""

        val thumbnails = listOf(
            "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
            "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
        )

        for (thumbUrl in thumbnails) {
            if (isThumbnailAccessible(thumbUrl)) return thumbUrl
        }
        return ""
    }

    private fun isThumbnailAccessible(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun extractYouTubeId(url: String): String {
        val patterns = listOf(
            "(?<=watch\\?v=)[^&]+",
            "(?<=youtu.be/)[^&?]+",
            "(?<=embed/)[^&?]+"
        )

        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(url)
            if (match != null) return match.value
        }
        return ""
    }

    private fun getInstagramThumbnail(url: String): String {
        try {
            val request = Request.Builder()
                .url("https://instagram-downloader-download-instagram-videos-stories.p.rapidapi.com/index?url=${Uri.encode(url)}")
                .addHeader("X-RapidAPI-Key", instagramApiKey)
                .addHeader("X-RapidAPI-Host", "instagram-downloader-download-instagram-videos-stories.p.rapidapi.com")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful && responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                var thumbnailUrl = json.optString("thumbnail", "")
                if (thumbnailUrl.isEmpty()) thumbnailUrl = json.optString("thumb", "")
                if (thumbnailUrl.isEmpty()) thumbnailUrl = json.optString("thumbnail_url", "")
                if (thumbnailUrl.isEmpty()) {
                    val media = json.optJSONArray("media")
                    if (media != null && media.length() > 0) {
                        thumbnailUrl = media.getJSONObject(0).optString("thumbnail", "")
                    }
                }
                return thumbnailUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun downloadImage(imageUrl: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(imageUrl)
                .addHeader("User-Agent", "Mozilla/5.0")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val inputStream = response.body?.byteStream()
                BitmapFactory.decodeStream(inputStream)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractAndDownload(url: String) {
        txtDownloadStatus.text = "Extracting video URL..."
        progressDownload.isIndeterminate = true

        scope.launch {
            try {
                val downloadUrl = withContext(Dispatchers.IO) {
                    when {
                        url.contains("youtube.com") || url.contains("youtu.be") -> extractYouTubeUrl(url)
                        url.contains("instagram.com") || url.contains("instagram") -> extractInstagramUrl(url)
                        else -> url
                    }
                }

                if (downloadUrl.isNotEmpty()) startDirectDownload(downloadUrl)
                else throw Exception("Could not extract video URL")

            } catch (e: Exception) {
                e.printStackTrace()
                progressDownload.isIndeterminate = false
                txtDownloadStatus.text = "Extraction failed: ${e.message}"
                showErrorDialog(url)
            }
        }
    }

    private fun extractYouTubeUrl(url: String): String {
        return try {
            val request = YoutubeDLRequest(url)
            request.addOption("-f", "best[height<=720]")
            val result = YoutubeDL.getInstance().execute(request)
            val json = JSONObject(result.out)
            json.getJSONArray("formats").getJSONObject(0).getString("url")
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun extractInstagramUrl(url: String): String {
        return try {
            val request = Request.Builder()
                .url("https://instagram-downloader-download-instagram-videos-stories.p.rapidapi.com/index?url=${Uri.encode(url)}")
                .addHeader("X-RapidAPI-Key", instagramApiKey)
                .addHeader("X-RapidAPI-Host", "instagram-downloader-download-instagram-videos-stories.p.rapidapi.com")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful && responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                var videoUrl = json.optString("video_url", "")
                if (videoUrl.isEmpty()) videoUrl = json.optString("url", "")
                if (videoUrl.isEmpty()) {
                    val media = json.optJSONArray("media")
                    if (media != null && media.length() > 0) {
                        videoUrl = media.getJSONObject(0).optString("url", "")
                    }
                }
                videoUrl
            } else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun showErrorDialog(originalUrl: String) {
        val platform = when {
            originalUrl.contains("instagram") -> "Instagram"
            originalUrl.contains("youtube") || originalUrl.contains("youtu.be") -> "YouTube"
            else -> "this platform"
        }

        AlertDialog.Builder(this)
            .setTitle("Extraction Failed")
            .setMessage(
                """
                Unable to extract video from $platform.
                
                Possible reasons:
                • Invalid or expired URL
                • Private content
                • API limitation or change
                
                Try:
                1. Use another public link
                2. Check your API key
                3. Try again later
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startDirectDownload(downloadUrl: String) {
        if (isDownloading) {
            Toast.makeText(this, "Download already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: run {
            Toast.makeText(this, "Cannot access storage", Toast.LENGTH_LONG).show()
            return
        }

        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val fileName = URLUtil.guessFileName(downloadUrl, null, null)
        val outFile = File(downloadsDir, fileName)

        txtDownloadStatus.text = "Starting download..."
        txtDownloadPercent.text = "0%"
        progressDownload.progress = 0
        progressDownload.isIndeterminate = false
        isDownloading = true

        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                currentDownloadCall = okHttpClient.newCall(request)
                val response = currentDownloadCall!!.execute()

                if (!response.isSuccessful) throw IOException("HTTP error ${response.code}")

                val body = response.body ?: throw IOException("Empty response")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(outFile)
                val buffer = ByteArray(8192)

                while (isDownloading) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    withContext(Dispatchers.Main) {
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            progressDownload.progress = progress
                            txtDownloadPercent.text = "$progress%"
                        }
                    }
                }

                outputStream.close()
                inputStream.close()

                if (isDownloading) {
                    isDownloading = false
                    lastDownloadedFile = outFile
                    withContext(Dispatchers.Main) {
                        txtDownloadStatus.text = "Download complete!"
                        txtDownloadPercent.text = "100%"
                        progressDownload.progress = 100
                        showOpenDialog(outFile)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    txtDownloadStatus.text = "Download failed"
                    Toast.makeText(this@VideoDownloaderActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showOpenDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Download Complete")
            .setMessage("File: ${file.name}\n\nOpen now?")
            .setPositiveButton("Open") { _, _ -> openVideo(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openVideo(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening video", Toast.LENGTH_LONG).show()
        }
    }
}
