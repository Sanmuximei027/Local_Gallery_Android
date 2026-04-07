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
    
    // 🌟 新增：记录当前查看的图片 ID，方便跨相册全屏无缝滑动
    var currentImageId by mutableStateOf(-1L)

    // 自定义壁纸 URI
    var customWallpaperUri by mutableStateOf<String?>(null)
        private set

    // 搜索关键词
    var searchQuery by mutableStateOf("")
        private set // 设为私有，通过方法更新以触发异步搜索

    // 🌟 用于 UI 展示的已过滤相册 (避免在 UI 线程直接计算)
    var displayedImages by mutableStateOf<Map<String, Map<String, List<ImageItem>>>>(emptyMap())
        private set

    private var searchJob: kotlinx.coroutines.Job? = null

    // 🌟 高性能异步防抖搜索算法
    fun updateSearchQuery(query: String) {
        searchQuery = query
        searchJob?.cancel() // 如果用户还在疯狂打字，取消上一次还没执行的搜索
        
        searchJob = viewModelScope.launch(Dispatchers.Default) { // 强制在后台 CPU 线程执行，绝不卡顿 UI！
            if (query.isEmpty()) {
                withContext(Dispatchers.Main) { displayedImages = categorizedImages }
                return@launch
            }
            
            kotlinx.coroutines.delay(300) // 防抖：等用户停手 300 毫秒后再搜

            val lowerQuery = query.lowercase()
            val result = mutableMapOf<String, Map<String, List<ImageItem>>>()

            for ((top, subs) in categorizedImages) {
                val topMatch = top.lowercase().contains(lowerQuery)
                val matchingSubs = mutableMapOf<String, List<ImageItem>>()
                
                for ((sub, imgs) in subs) {
                    // 🌟 核心提速 (Early Exit / 短路机制)：
                    // 如果相册名字（主相册或子相册）已经命中了搜索词，就说明这个相册是用户想找的，
                    // 直接把整个相册端过去即可！绝不再去遍历它里面那成百上千张图片的路径！(计算量砍掉 90%)
                    if (topMatch || sub.lowercase().contains(lowerQuery)) {
                        matchingSubs[sub] = imgs
                    } else {
                        // 只有当相册名没命中时，才深入到图片路径里去大海捞针
                        val matchingImgs = imgs.filter { 
                            it.path.lowercase().contains(lowerQuery) || it.name.lowercase().contains(lowerQuery) 
                        }
                        if (matchingImgs.isNotEmpty()) {
                            matchingSubs[sub] = matchingImgs
                        }
                    }
                }
                if (matchingSubs.isNotEmpty()) {
                    result[top] = matchingSubs
                }
            }
            
            withContext(Dispatchers.Main) {
                displayedImages = result
            }
        }
    }

    // 标记是否已经扫描过，防止重复扫描
    private var hasLoaded = false

    fun loadImagesIfNeeded(context: Context) {
        if (hasLoaded) return // 内存中已有最新数据，直接返回
        
        viewModelScope.launch {
            val cacheFile = File(context.cacheDir, "gallery_snapshot.json")
            var hasDiskCache = false

            // 1. 尝试从本地磁盘极速加载缓存
            withContext(Dispatchers.IO) {
                if (cacheFile.exists() && categorizedImages.isEmpty()) {
                    try {
                        val json = cacheFile.readText()
                        val type = object : TypeToken<Map<String, Map<String, List<ImageItem>>>>() {}.type
                        val cachedData: Map<String, Map<String, List<ImageItem>>> = Gson().fromJson(json, type)
                        
                        withContext(Dispatchers.Main) {
                            categorizedImages = cachedData
                            displayedImages = cachedData // 同步一份到展示列表
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

            // 如果连磁盘缓存都没有（比如第一次安装），且内存里也没有，必须显示 Loading 圈
            if (!hasDiskCache && categorizedImages.isEmpty()) {
                isLoading = true
            }
            
            // 2. 无论有没有缓存，后台都静默开启真正的全量扫描（不卡主 UI）
            val categorized = withContext(Dispatchers.Default) {
                val raw = MediaStoreHelper.fetchAllImages(context)
                val db = AppDatabase.getDatabase(context)
                val customRulesMap = db.ruleDao().getAllRules().associate { it.imagePath to it.customAlbumName }
                SmartClassifier.classify(raw, customRulesMap)
            }
            
            // 3. 刷新 UI 并保存新快照
            if (categorized != categorizedImages) {
                categorizedImages = categorized
                displayedImages = categorized // 更新完立刻同步
                
                withContext(Dispatchers.IO) {
                    try {
                        val json = Gson().toJson(categorized)
                        cacheFile.writeText(json)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            isLoading = false
            hasLoaded = true
        }
    }
    
    // 🌟 核心：仅从指定的当前大相册中提取图片
    fun extractImagesFromSpecificAlbum(
        context: Context,
        sourceTopAlbum: String,
        keyword: String,
        newAlbumName: String,
        onComplete: (Int) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val subs = categorizedImages[sourceTopAlbum] ?: return@launch
            
            // 找出这个相册下，所有路径或文件名包含该关键词的图片
            val imagesToMove = subs.values.flatten().filter {
                it.path.contains(keyword, ignoreCase = true) || it.name.contains(keyword, ignoreCase = true)
            }

            if (imagesToMove.isEmpty()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            // 获取数据库，为这些图片插入【精准的绝对路径规则】
            val db = AppDatabase.getDatabase(context)
            val ruleDao = db.ruleDao()
            for (image in imagesToMove) {
                ruleDao.insertRule(
                    RuleEntity(
                        imagePath = image.path, // 存绝对路径！绝不会误伤其他相册的同名图
                        customAlbumName = newAlbumName
                    )
                )
            }

            // 规则已更新，删除旧快照缓存
            val cacheFile = File(context.cacheDir, "gallery_snapshot.json")
            if (cacheFile.exists()) {
                cacheFile.delete()
            }

            // 切回主线程，触发 UI 全局刷新并执行成功回调
            withContext(Dispatchers.Main) {
                forceRefreshImages(context) {
                    onComplete(imagesToMove.size)
                }
            }
        }
    }

    // 手动下拉刷新，强制重新扫描
    fun forceRefreshImages(context: Context, onComplete: () -> Unit) {
        viewModelScope.launch {
            val categorized = withContext(Dispatchers.Default) {
                val raw = MediaStoreHelper.fetchAllImages(context)
                val db = AppDatabase.getDatabase(context)
                val customRulesMap = db.ruleDao().getAllRules().associate { it.imagePath to it.customAlbumName }
                SmartClassifier.classify(raw, customRulesMap)
            }
            
            if (categorized != categorizedImages) {
                categorizedImages = categorized
                displayedImages = categorized
                
                withContext(Dispatchers.IO) {
                    try {
                        val cacheFile = File(context.cacheDir, "gallery_snapshot.json")
                        val json = Gson().toJson(categorized)
                        cacheFile.writeText(json)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            hasLoaded = true
            onComplete()
        }
    }

    // 获取当前正在查看的相册里的所有图片
    fun getCurrentImageList(): List<ImageItem> {
        return categorizedImages[currentTopAlbumName]?.get(currentSubAlbumName) ?: emptyList()
    }
    
    // 🌟 新增：获取整个大类下所有子相册合并的图片，按照章节数字排序 (实现沉浸式跨文件夹浏览！)
    fun getFlattenedImageList(): List<ImageItem> {
        val subs = categorizedImages[currentTopAlbumName] ?: return emptyList()
        // 自然排序子相册名字，保证章节顺序 (比如 Chapter 1, Chapter 2 能够按顺序播放)
        return subs.entries.sortedBy { entry ->
            val numStr = Regex("\\d+").find(entry.key)?.value ?: "0"
            val num = numStr.toIntOrNull() ?: 0
            String.format("%06d_%s", num, entry.key)
        }.flatMap { it.value }
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
