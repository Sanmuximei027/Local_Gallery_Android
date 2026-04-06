package com.example.localgallery

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImageItem(
    val id: Long,
    val name: String,
    val path: String,
    val dateAdded: Long,
    val sizeBytes: Long // 新增：文件大小
) {
    // 扩展属性：格式化文件大小 (如 2.5 MB)
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format("%.1f %s", sizeBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }

    // 扩展属性：尝试从文件名推断来源网站
    val inferredSource: String
        get() {
            val lowerName = name.lowercase()
            return when {
                lowerName.contains("konachan") -> "Konachan (动漫图库)"
                lowerName.contains("pixiv") -> "Pixiv (P站)"
                lowerName.contains("yande") -> "Yande.re"
                lowerName.contains("gelbooru") -> "Gelbooru"
                lowerName.contains("danbooru") -> "Danbooru"
                lowerName.startsWith("wx_") || lowerName.startsWith("mmexport") -> "微信保存"
                lowerName.startsWith("qq_") -> "QQ 保存"
                lowerName.contains("weibo") -> "新浪微博"
                lowerName.contains("screenshot") -> "手机截图"
                else -> "未知来源 (可能是本地相机或未识别网站)"
            }
        }
}

object MediaStoreHelper {
    
    suspend fun fetchAllImages(context: Context): List<ImageItem> = withContext(Dispatchers.IO) {
        val imageList = mutableListOf<ImageItem>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE // 增加读取文件大小
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Unknown_$id"
                val path = cursor.getString(dataColumn) ?: continue
                val dateAdded = cursor.getLong(dateAddedColumn)
                val size = cursor.getLong(sizeColumn)

                imageList.add(ImageItem(id, name, path, dateAdded, size))
            }
        }
        
        return@withContext imageList
    }
}
