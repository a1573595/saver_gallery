package com.mhz.savegallery.saver_gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import com.mhz.savegallery.saver_gallery.utils.MediaStoreUtils.getMIMEType

/**
 * Legacy [SaverDelegate] for API levels below 29 (Android 9 and below).
 * Writes directly to public external storage and notifies the media scanner.
 * Android 10+ uses [SaverDelegateAndroidT] instead.
 *
 * @param context The application context.
 */
class SaverDelegateDefault(context: Context) : SaverDelegate(context) {

    private val mainScope = CoroutineScope(Dispatchers.IO)

    /**
     * Saves an image to the gallery.
     *
     * @param imageBytes The image data in bytes.
     * @param quality The quality of the image (applicable for JPEG).
     * @param fileName The name of the file to save.
     * @param extension The file extension (e.g., "jpg", "png").
     * @param relativePath The relative path in the gallery where the file will be saved.
     * @param skipIfExists If true, skips saving if the file already exists.
     * @param result The method result to communicate success or failure.
     */
    override fun saveImageToGallery(
        imageBytes: ByteArray,
        quality: Int,
        fileName: String,
        extension: String,
        relativePath: String,
        skipIfExists: Boolean,
        result: MethodChannel.Result
    ) {
        mainScope.launch {
            val saveResult = saveImage(imageBytes, quality, extension, fileName, skipIfExists, relativePath)
            result.success(saveResult)
        }
    }

    /**
     * Saves a file to the gallery.
     *
     * @param filePath The path of the file to be saved.
     * @param fileName The name of the file to save in the gallery.
     * @param relativePath The relative path in the gallery where the file will be saved.
     * @param skipIfExists If true, skips saving if the file already exists.
     * @param result The method result to communicate success or failure.
     */
    override fun saveFileToGallery(
        filePath: String,
        fileName: String,
        relativePath: String,
        skipIfExists: Boolean,
        result: MethodChannel.Result
    ) {
        mainScope.launch {
            val saveResult = saveFile(filePath, fileName, relativePath, skipIfExists)
            result.success(saveResult)
        }
    }

    /**
     * Saves multiple files to the gallery in batch.
     *
     * @param files List of file data maps containing filePath, fileName, and relativePath.
     * @param skipIfExists If true, skips saving if a file already exists.
     * @param result The method result to communicate success or failure.
     */
    override fun saveFilesToGallery(
        files: List<Map<String, String>>,
        skipIfExists: Boolean,
        result: MethodChannel.Result
    ) {
        mainScope.launch {
            var successCount = 0
            var failureCount = 0
            val errors = mutableListOf<String>()
            val savedUris = mutableListOf<String>()

            for (fileData in files) {
                val filePath = fileData["filePath"] ?: continue
                val fileName = fileData["fileName"] ?: continue
                val relativePath = fileData["relativePath"] ?: "Download"

                val saveResult = saveFile(filePath, fileName, relativePath, skipIfExists)
                val isSuccess = saveResult["isSuccess"] as? Boolean ?: false

                if (isSuccess) {
                    (saveResult["savedUri"] as? String)?.let { savedUris.add(it) }
                    successCount++
                } else {
                    failureCount++
                    val errorMsg = saveResult["errorMessage"] as? String
                    if (errorMsg != null) {
                        errors.add("$fileName: $errorMsg")
                    }
                }
            }

            val finalResult = if (failureCount == 0) {
                SaveResultModel(true, null, savedUris = savedUris).toHashMap()
            } else {
                val errorMessage = "Saved $successCount files, failed $failureCount files. Errors: ${errors.joinToString("; ")}"
                SaveResultModel(successCount > 0, errorMessage, savedUris = savedUris).toHashMap()
            }

            result.success(finalResult)
        }
    }

    /**
     * Saves an image to the gallery with the specified parameters.
     *
     * @param imageBytes The image data in bytes.
     * @param quality The quality of the image (applicable for JPEG).
     * @param extension The file extension (e.g., "jpg", "png").
     * @param fileName The name of the file to save.
     * @param skipIfExists If true, skips saving if the file already exists.
     * @param relativePath The relative path in the gallery where the file will be saved.
     * @return A [SaveResultModel] indicating the outcome of the save operation.
     */
    private fun saveImage(
        imageBytes: ByteArray,
        quality: Int,
        extension: String,
        fileName: String,
        skipIfExists: Boolean,
        relativePath: String
    ): HashMap<String, Any?> {
        val existingUri = if (skipIfExists) findExistingUri(relativePath, fileName) else null
        return if (skipIfExists && existingUri != null) {
            SaveResultModel(true, null, savedUri = existingUri.toString()).toHashMap()
        } else {
            try {
                val fileUri = generateFileUri(fileName, relativePath)
                context.contentResolver?.openOutputStream(fileUri)?.use { outputStream ->
                    saveBitmapToStream(imageBytes, quality, extension, outputStream)
                    outputStream.flush()
                }
                notifyGallery(fileUri)
                val isSuccess = fileUri.toString().isNotEmpty()
                SaveResultModel(isSuccess, null, savedUri = if (isSuccess) fileUri.toString() else null).toHashMap()
            } catch (e: Exception) {
                e.printStackTrace()
                SaveResultModel(false, "Failed to save image: ${e.message}").toHashMap()
            }
        }
    }

    /**
     * Saves a file from the specified path to the gallery.
     *
     * @param filePath The path of the file to be saved.
     * @param fileName The name of the file to save in the gallery.
     * @param relativePath The relative path in the gallery where the file will be saved.
     * @param skipIfExists If true, skips saving if the file already exists.
     * @return A [SaveResultModel] indicating the outcome of the save operation.
     */
    private fun saveFile(
        filePath: String,
        fileName: String,
        relativePath: String,
        skipIfExists: Boolean
    ): HashMap<String, Any?> {
        val existingUri = if (skipIfExists) findExistingUri(relativePath, fileName) else null
        return if (skipIfExists && existingUri != null) {
            SaveResultModel(true, null, savedUri = existingUri.toString()).toHashMap()
        } else {
            try {
                val fileUri = generateFileUri(fileName, relativePath)
                FileInputStream(File(filePath)).use { fileInputStream ->
                    context.contentResolver?.openOutputStream(fileUri)?.use { outputStream ->
                        val buffer = ByteArray(1024)
                        var bytesRead: Int
                        while (fileInputStream.read(buffer).also { bytesRead = it } > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        outputStream.flush()
                    }
                }
                notifyGallery(fileUri)
                val isSuccess = fileUri.toString().isNotEmpty()
                SaveResultModel(isSuccess, null, savedUri = if (isSuccess) fileUri.toString() else null).toHashMap()
            } catch (e: Exception) {
                e.printStackTrace()
                SaveResultModel(false, "Failed to save file: ${e.message}").toHashMap()
            }
        }
    }

    /**
     * Generates a file URI for a new file in the given relative path.
     *
     * @param fileName The name of the file.
     * @param relativePath The relative path in the gallery.
     * @return The URI where the file will be saved.
     * @throws IOException If the target directory cannot be created.
     */
    private fun generateFileUri(fileName: String, relativePath: String): Uri {
        val mimeType = getMIMEType(fileName.substringAfterLast('.', ""))
        val targetDirectory = resolveLegacyTargetDirectory(relativePath, mimeType)
        return Uri.fromFile(File(targetDirectory, fileName))
    }

    private fun findExistingUri(relativePath: String, fileName: String): Uri? {
        val mimeType = getMIMEType(fileName.substringAfterLast('.', ""))
        return try {
            val targetDirectory = resolveLegacyTargetDirectory(relativePath, mimeType)
            val existingFile = File(targetDirectory, fileName)
            if (existingFile.exists()) Uri.fromFile(existingFile) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun resolveLegacyTargetDirectory(relativePath: String, mimeType: String?): File {
        val resolvedPath = LegacyRelativePathResolver.resolve(relativePath, mimeType)
        val publicDirectory = Environment.getExternalStoragePublicDirectory(resolvedPath.publicDirectory)
        val targetDirectory = resolvedPath.childPath?.let { File(publicDirectory, it) } ?: publicDirectory

        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw IOException("Failed to create directory ${targetDirectory.absolutePath}")
        }
        if (!targetDirectory.isDirectory) {
            throw IOException("Path is not a directory: ${targetDirectory.absolutePath}")
        }

        return targetDirectory
    }

    /**
     * Saves a bitmap to the provided output stream.
     *
     * @param imageBytes The image data in bytes.
     * @param quality The quality of the image (applicable for JPEG).
     * @param extension The file extension (e.g., "jpg", "png").
     * @param outputStream The output stream to write the image data.
     */
    private fun saveBitmapToStream(
        imageBytes: ByteArray,
        quality: Int,
        extension: String,
        outputStream: java.io.OutputStream
    ) {
        if (extension.equals("gif", ignoreCase = true)) {
            outputStream.write(imageBytes)
        } else {
            var bitmap: Bitmap? = null
            try {
                bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                val format = if (extension.equals("png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, quality, outputStream)
            } finally {
                bitmap?.recycle()
            }
        }
    }

    /**
     * Notifies the media gallery about the newly added file.
     *
     * @param fileUri The URI of the file to notify.
     */
    private fun notifyGallery(fileUri: Uri) {
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileUri))
    }

    /**
     * Releases resources when the delegate is closed.
     */
    override fun onClose() {
        super.onClose()
        mainScope.cancel()
    }
}
