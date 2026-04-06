package com.example.localgallery

import java.io.File

/**
 * 智能分类引擎的核心！
 * 在这里，我们将所有散乱的图片名，归纳到它们各自专属的“相册/文件夹”里，
 * 并支持“主分类 -> 子分类”的两级嵌套结构！
 */
object SmartClassifier {

    private val numRegex = Regex("\\d+")

    // 定义二级结构的 Key
    data class AlbumKey(val top: String, val sub: String)

    fun classify(
        images: List<ImageItem>,
        customRules: Map<String, String> = emptyMap()
    ): Map<String, Map<String, List<ImageItem>>> {
        // 性能优化：提前提取所有图片的第一个数字用于极速排序，避免每次比较时都执行正则匹配
        val imageNumbers = images.associate { it.path to (numRegex.find(it.path)?.value?.toLongOrNull() ?: 0L) }
        
        // 1. 初步分组：将每张图片映射到一个 AlbumKey (主分类 -> 子分类)
        val grouped = images.groupBy { image ->
            // --- 0. 优先级最高：用户自定义规则 ---
            if (customRules.containsKey(image.path)) {
                return@groupBy AlbumKey(customRules[image.path]!!, "自定义合并")
            }

            val name = image.name.lowercase()
            val nameWithoutExt = name.substringBeforeLast(".")

            when {
                // 1. 微信 / QQ 截图相关
                name.startsWith("wx_camera") || name.startsWith("mmexport") || name.startsWith("micro") -> AlbumKey("微信保存", "全部")
                name.startsWith("qq_") -> AlbumKey("QQ 保存", "全部")
                
                // 2. Pixiv 插画平台
                name.contains("pixiv") || name.contains("illust_") -> AlbumKey("Pixiv 插画", "全部")
                
                // 3. Konachan / Booru 等动漫图站
                name.contains(" - ") -> {
                    val siteName = image.name.substringBefore(" - ").trim()
                    val cleanName = siteName.replace(".com", "", ignoreCase = true)
                                            .replace(".net", "", ignoreCase = true)
                    AlbumKey(cleanName, "全部")
                }

                // 4. 截图 (Screenshot)
                name.contains("screenshot") -> AlbumKey("手机截图", "全部")

                // 5. 【两级目录解析：找到主分类与子分类】
                else -> {
                    var currentDir = File(image.path).parentFile
                    var topName: String? = null
                    var subName = "全部"
                    
                    // 向上追溯，最多追溯 4 层
                    for (i in 0 until 4) {
                        val dirName = currentDir?.name ?: break
                        val lowerDir = dirName.lowercase()
                        
                        if (lowerDir in listOf("0", "emulated", "media")) break
                        
                        // 白名单匹配
                        val standardDirs = mapOf(
                            "camera" to "相机照片", "dcim" to "相机照片",
                            "pictures" to "普通图片", "images" to "普通图片",
                            "download" to "下载目录", "downloads" to "下载目录",
                            "weixin" to "微信", "wechat" to "微信"
                        )
                        if (standardDirs.containsKey(lowerDir)) {
                            topName = standardDirs[lowerDir]!!
                            break
                        }
                        
                        // 检查当前目录是否是无意义层级 (子分类)
                        val isHash = dirName.contains(Regex("\\[.*?\\]")) || 
                                     dirName.contains(Regex("\\(.*?\\)")) ||
                                     dirName.matches(Regex("^[a-fA-F0-9_-]{6,}$"))
                                     
                        val isChapter = dirName.all { it.isDigit() } ||
                                        Regex("第?\\d+(话|章|卷|回)").matches(dirName) ||
                                        lowerDir.startsWith("chapter") ||
                                        lowerDir.startsWith("ch") ||
                                        lowerDir.startsWith("vol")
                                        
                        if (isHash || isChapter) {
                            // 如果是哈希/章节，我们将最底层遇到的那个当作“子分类”
                            if (subName == "全部") subName = dirName
                        } else {
                            // 找到了第一个“有意义”的名字！把它当作主分类
                            topName = dirName
                            break
                        }
                        
                        currentDir = currentDir.parentFile
                    }
                    
                    if (topName != null) {
                        return@groupBy AlbumKey(topName, subName)
                    }
                    
                    // --- 兜底匹配 ---
                    if (nameWithoutExt.all { it.isDigit() } ||
                        (nameWithoutExt.length > 12 && !nameWithoutExt.contains("_") && !nameWithoutExt.contains("-")) ||
                        nameWithoutExt.matches(Regex("^[a-zA-Z]{0,5}[_-]?\\d+([_-]\\d+)*$"))
                    ) {
                        AlbumKey("未分类序列图", "全部")
                    } else if (name.contains("_")) {
                        val prefix = name.substringBefore("_")
                        if (prefix.length in 2..15) {
                            AlbumKey("未分类图库", prefix.replaceFirstChar { it.uppercase() })
                        } else {
                            AlbumKey("未分类图库", "全部")
                        }
                    } else {
                        AlbumKey("未分类图库", "全部")
                    }
                }
            }
        }

        // 🌟 孤儿相册清理与数字自然排序
        val finalMap = mutableMapOf<String, MutableMap<String, List<ImageItem>>>()
        val others = mutableListOf<ImageItem>()

        val naturalOrderComparator = Comparator<ImageItem> { a, b ->
            val aNum = imageNumbers[a.path] ?: 0L
            val bNum = imageNumbers[b.path] ?: 0L
            if (aNum == bNum) a.path.compareTo(b.path) else aNum.compareTo(bNum)
        }

        for ((key, imgs) in grouped) {
            val sortedImgs = imgs.sortedWith(naturalOrderComparator)
            val whiteList = listOf("微信保存", "QQ 保存", "Pixiv 插画", "手机截图")
            
            // 如果这个主相册总共就 1 张图，且不在白名单，踢入杂项
            if (sortedImgs.size == 1 && key.top !in whiteList && key.sub == "全部") {
                others.addAll(sortedImgs)
            } else {
                if (!finalMap.containsKey(key.top)) {
                    finalMap[key.top] = mutableMapOf()
                }
                finalMap[key.top]!![key.sub] = sortedImgs
            }
        }

        if (others.isNotEmpty()) {
            if (!finalMap.containsKey("散落的图片 (未成组)")) {
                finalMap["散落的图片 (未成组)"] = mutableMapOf()
            }
            finalMap["散落的图片 (未成组)"]!!["全部"] = others.sortedWith(naturalOrderComparator)
        }

        return finalMap
    }
}
