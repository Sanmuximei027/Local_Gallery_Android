package com.example.localgallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 🌟 状态大管家 (ViewModel)
 */
class GalleryViewModel : ViewModel() {
    
    // 所有的相册数据字典 (TopLevel -> SubLevel -> Images)
    var categorizedImages by mutableStateOf<Map<String, Map<String, List<ImageItem>>>>(emptyMap())
        private set

    // 是否正在加载中
    var isLoading by mutableStateOf(true)
        private set

    // 记录当前点击进入的是哪个大相册
    var currentTopAlbumName by mutableStateOf("")
    
    // 记录当前点击进入的是哪个子相册
    var currentSubAlbumName by mutableStateOf("")

    // 记录当前点击的是相册里的第几张图片 (用于全屏左右滑动定位)
    var currentInitialIndex by mutableStateOf(0)

    // 自定义壁纸 URI
    var customWallpaperUri by mutableStateOf<String?>(null)
        private set

    // 搜索关键词
    var searchQuery by mutableStateOf("")

    // 标记是否已经扫描过，防止重复扫描
    private var hasLoaded = false

    fun loadImagesIfNeeded(context: Context) {
        if (hasLoaded) return // 内存中已有数据，直接返回
        
        viewModelScope.launch {
            val cacheFile = File(context.cacheDir, "gallery_snapshot.json")
            var hasDiskCache = false

            // 1. 尝试从本地磁盘极速加载缓存 (实现冷启动“秒开”机制)
            withContext(Dispatchers.IO) {
                if (cacheFile.exists()) {
                    try {
                        val json = cacheFile.readText()
                        val type = object : TypeToken<Map<String, Map<String, List<ImageItem>>>>() {}.type
                        val cachedData: Map<String, Map<String, List<ImageItem>>> = Gson().fromJson(json, type)
                        
                        withContext(Dispatchers.Main) {
                            categorizedImages = cachedData
                            isLoading = false
                            hasLoaded = true
                            hasDiskCache = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // 缓存可能损坏，静默跳过
                    }
                }
            }

            // 如果连磁盘缓存都没有（比如第一次安装），必须显示 Loading 圈
            if (!hasDiskCache) {
                isLoading = true
            }
            
            // 2. 无论有没有缓存，后台都静默开启真正的全量扫描（不卡主 UI）
            val categorized = withContext(Dispatchers.Default) {
                val raw = MediaStoreHelper.fetchAllImages(context)
                val db = AppDatabase.getDatabase(context)
                val customRulesMap = db.ruleDao().getAllRules().associate { it.imagePath to it.customAlbumName }
                SmartClassifier.classify(raw, customRulesMap)
            }
            
            // 3. 如果后台扫描发现新增了照片（或者第一次刚扫描完），才刷新 UI 并保存新快照
            if (categorized != categorizedImages) {
                categorizedImages = categorized
                isLoading = false
                hasLoaded = true
                
                withContext(Dispatchers.IO) {
                    try {
                        val json = Gson().toJson(categorized)
                        cacheFile.writeText(json)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // 获取当前正在查看的相册里的所有图片
    fun getCurrentImageList(): List<ImageItem> {
        return categorizedImages[currentTopAlbumName]?.get(currentSubAlbumName) ?: emptyList()
    }

    // 重新加载并分类数据，用于用户新增或删除了相册规则后刷新
    fun reloadRulesAndReclassify(context: Context) {
        hasLoaded = false
        loadImagesIfNeeded(context)
    }

    // 初始化时加载保存的壁纸
    fun loadWallpaper(context: Context) {
        val prefs = context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        customWallpaperUri = prefs.getString("custom_wallpaper", null)
    }

    // 设置并保存自定义壁纸
    fun setCustomWallpaper(context: Context, uriString: String?) {
        val prefs = context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("custom_wallpaper", uriString).apply()
        customWallpaperUri = uriString
    }
}