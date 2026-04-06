package com.example.localgallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 🌟 状态大管家 (ViewModel)
 */
class GalleryViewModel : ViewModel() {
    
    // 所有的相册数据字典
    var categorizedImages by mutableStateOf<Map<String, List<ImageItem>>>(emptyMap())
        private set

    // 是否正在加载中
    var isLoading by mutableStateOf(true)
        private set

    // 记录当前点击进入的是哪个相册
    var currentAlbumName by mutableStateOf("")

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
        if (hasLoaded) return // 已经扫描过了，直接返回，实现“秒开”
        
        viewModelScope.launch {
            isLoading = true
            
            // 🌟 性能优化：将耗时的读取和正则分类操作全部移入后台线程！避免阻塞主线程（UI卡顿）
            val categorized = withContext(Dispatchers.Default) {
                // 1. 去底层数据库极速拉取原始数据
                val raw = MediaStoreHelper.fetchAllImages(context)
                
                // 2. 读取 Room 数据库里的用户自定义规则
                val db = AppDatabase.getDatabase(context)
                val customRulesList = db.ruleDao().getAllRules()
                val customRulesMap = customRulesList.associate { it.imagePath to it.customAlbumName }

                // 3. 交给我们的智能引擎去分类 (传入自定义规则)
                SmartClassifier.classify(raw, customRulesMap)
            }
            
            categorizedImages = categorized
            isLoading = false
            hasLoaded = true
        }
    }

    // 获取当前正在查看的相册里的所有图片
    fun getCurrentImageList(): List<ImageItem> {
        return categorizedImages[currentAlbumName] ?: emptyList()
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