package com.example.localgallery

import java.io.File

object SmartClassifier {

    private val numRegex = Regex("\\d+")

    data class AlbumKey(val top: String, val sub: String)

    fun classify(
        images: List<ImageItem>,
        customRules: Map<String, String> = emptyMap()
    ): Map<String, Map<String, List<ImageItem>>> {
        val imageNumbers = images.associate { it.path to (numRegex.find(it.path)?.value?.toLongOrNull() ?: 0L) }
        
        val grouped = images.groupBy { image ->
            if (customRules.containsKey(image.path)) {
                val parentPath = image.path.substringBeforeLast('/', "")
                var customSubName = if (parentPath.contains('/')) parentPath.substringAfterLast('/') else "全部"
                if (customSubName.matches(Regex("^[a-fA-F0-9_-]{6,}$")) || customSubName.matches(Regex("^\\d{10,}$"))) customSubName = "全部"
                return@groupBy AlbumKey(customRules[image.path]!!, customSubName)
            }

            val name = image.name.lowercase()
            val nameWithoutExt = name.substringBeforeLast(".")

            when {
                name.startsWith("wx_camera") || name.startsWith("mmexport") || name.startsWith("micro") -> AlbumKey("微信保存", "全部")
                name.startsWith("qq_") -> AlbumKey("QQ 保存", "全部")
                name.contains("pixiv") || name.contains("illust_") -> AlbumKey("Pixiv 插画", "全部")
                name.contains(" - ") -> {
                    val siteName = image.name.substringBefore(" - ").trim()
                    val cleanName = siteName.replace(".com", "", ignoreCase = true)
                                            .replace(".net", "", ignoreCase = true)
                    AlbumKey(cleanName, "全部")
                }
                name.contains("screenshot") -> AlbumKey("手机截图", "全部")
                else -> {
                    var currentDirPath = image.path.substringBeforeLast('/', "")
                    var topName: String? = null
                    var subName = "全部"
                    
                    val standardDirs = mapOf(
                        "camera" to "相机照片", "dcim" to "相机照片",
                        "pictures" to "普通图片", "images" to "普通图片",
                        "download" to "下载目录", "downloads" to "下载目录",
                        "weixin" to "微信", "wechat" to "微信",
                        "tachiyomi" to "Tachiyomi (漫画)"
                    )
                    
                    // 🌟 1. 自下而上 (Bottom-Up)：第一阶段，过滤并收集底部的“章节/乱码”层级
                    for (i in 0 until 6) {
                        if (currentDirPath.isEmpty() || !currentDirPath.contains('/')) break
                        val dirName = currentDirPath.substringAfterLast('/')
                        val lowerDir = dirName.lowercase()
                        
                        if (lowerDir in listOf("0", "emulated", "media", "storage", "sdcard", "android", "data")) break
                        
                        if (standardDirs.containsKey(lowerDir)) {
                            if (topName == null) topName = standardDirs[lowerDir]!!
                            break
                        }
                        
                        val isPureHash = dirName.matches(Regex("^\\[[a-fA-F0-9]{8,}\\]$")) || 
                                         dirName.matches(Regex("^[a-fA-F0-9_-]{8,}$"))
                                         
                        val cleanDirForChapter = dirName.replace(Regex("^\\[.*?\\]|^【.*?】|^\\(.*?\\)"), "").trim()
                        val lowerCleanDir = cleanDirForChapter.lowercase()
                        
                        // 强化章节判定，加入 page、part 等泛用词汇
                        val isChapter = cleanDirForChapter.all { it.isDigit() } ||
                                        Regex("^第?\\d+(话|章|卷|回|部|季|期|节).*").matches(cleanDirForChapter) ||
                                        lowerCleanDir.startsWith("chapter") ||
                                        lowerCleanDir.matches(Regex("^ch[\\s\\._-]?\\d+.*")) ||
                                        lowerCleanDir.startsWith("vol") ||
                                        lowerCleanDir.startsWith("disc") ||
                                        lowerCleanDir.contains(Regex("_?page\\s*\\d+")) || 
                                        lowerCleanDir.contains(Regex("_?part\\s*\\d+")) ||
                                        Regex("^\\d+(\\.\\d+)?[\\s\\-_]+.*").matches(cleanDirForChapter)
                                        
                        if (isPureHash || isChapter) {
                            // 是章节层，将其压入子相册路径，继续向上！
                            if (subName == "全部") {
                                subName = dirName
                            } else {
                                subName = "$dirName / $subName"
                            }
                        } else {
                            // 找到了第一个非章节的实体名字！(例如：崛与宫村)
                            topName = dirName
                            break
                        }
                        currentDirPath = currentDirPath.substringBeforeLast('/', "")
                    }
                    
                    if (topName == null && subName != "全部") {
                        topName = subName.substringBefore(" / ")
                        val remainingSub = subName.substringAfter(" / ", "")
                        subName = if (remainingSub.isEmpty()) "全部" else remainingSub
                    }
                    
                    if (topName != null) {
                        // 🌟 2. 自下而上 (Bottom-Up)：第二阶段，“同源/标签父级”向上吸收机制
                        // 目标：解决 `宫村2012/崛与宫村` 或 `[汉化组] 崛与宫村/崛与宫村` 这种多级冗余/相关分类
                        var peekDirPath = currentDirPath.substringBeforeLast('/', "")
                        var initialCleanTop = topName!!.replace(Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), "").trim()
                        
                        while (peekDirPath.contains('/') && initialCleanTop.isNotEmpty()) {
                            val peekName = peekDirPath.substringAfterLast('/')
                            val lowerPeek = peekName.lowercase()
                            
                            // 遇到系统级目录、标准目录或绝对的泛用垃圾词，立刻停止吸收
                            if (lowerPeek in listOf("0", "emulated", "media", "storage", "sdcard", "android", "data")) break
                            if (standardDirs.containsKey(lowerPeek)) break
                            if (lowerPeek in listOf("manga", "漫画", "comics", "comic", "animate", "anime", "doujinshi", "同人", "同人志", "cg", "bilibili")) break
                            
                            val cleanPeek = peekName.replace(Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), "").trim()
                            
                            // 核心判定：父级是否和当前级有“血缘关系”？
                            // 1. 包含标签 (通常是画师/汉化组外壳)
                            // 2. 名字互相包含 (例如 "崛与宫村" 和 "宫村2012"，或者 "[搬运] 宫村" 和 "宫村")
                            // 3. 拥有共同的关键字长度 >= 2
                            val hasTags = peekName.contains(Regex("\\[|【|\\("))
                            val isRedundant = cleanPeek.isNotEmpty() && (
                                cleanPeek.contains(initialCleanTop) || 
                                initialCleanTop.contains(cleanPeek) ||
                                // 提取两个名字中长度大于等于2的共同中文字符串
                                cleanPeek.filter { it.isLetter() }.windowed(2).any { initialCleanTop.contains(it) }
                            )
                                              
                            if (hasTags || isRedundant) {
                                // 判定为同源分类或标签分类！吸收它作为新的顶级分类，把原来的顶级分类降级塞入子分类
                                if (subName == "全部") {
                                    subName = topName!!
                                } else {
                                    subName = "$topName / $subName"
                                }
                                topName = peekName
                                initialCleanTop = cleanPeek.ifEmpty { initialCleanTop } // 更新对比基准
                                peekDirPath = peekDirPath.substringBeforeLast('/', "")
                            } else {
                                // 遇到没有标签且名字不相关的目录（说明已经爬出了这个作品的范围），立刻停止！
                                break
                            }
                        }

                        // 3. 智能多重标签提取 (提取顶级相册名中的画师/汉化组)
                        val tagRegex = Regex("^\\[(.*?)\\]|^【(.*?)】|^\\((.*?)\\)")
                        var currentTopName = topName!!.trim()
                        var extractedTag: String? = null
                        
                        while (true) {
                            val match = tagRegex.find(currentTopName) ?: break
                            val tagContent = match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }.trim()
                            
                            val isEvent = tagContent.matches(Regex("^(C\\d+|COMIC|例大祭|红楼梦|M3).*?", RegexOption.IGNORE_CASE))
                            val isNumber = tagContent.matches(Regex("^\\d+$"))
                            
                            if (!isEvent && !isNumber && extractedTag == null) {
                                extractedTag = tagContent
                            }
                            currentTopName = currentTopName.removePrefix(match.value).trim().trimStart('-', '_', ' ')
                        }
                        
                        if (extractedTag != null) {
                            topName = extractedTag
                            val bookName = currentTopName.ifEmpty { "全部" }
                            if (subName == "全部") {
                                subName = bookName
                            } else if (bookName != "全部") {
                                subName = "$bookName / $subName"
                            }
                        }

                        // 4. 处理单层平铺文件夹既包含书名又包含章节的情况 (例如 "MangaName - 第1话")
                        val chRegex1 = Regex("^(.*?)\\s*(?:[-_]\\s*)?(第\\d+(话|章|卷|回|部|季|期).*|(?:chapter|ch|vol)[\\.\\s_-]?\\d+.*)$", RegexOption.IGNORE_CASE)
                        val chRegex2 = Regex("^(.*?)\\s+[-_]\\s+(\\d+(\\.\\d+)?[\\s\\-_]*.*)$")
                        val match1 = chRegex1.find(topName!!)
                        val match2 = chRegex2.find(topName!!)
                        val chMatch = match1 ?: match2
                        
                        if (chMatch != null && chMatch.groupValues[1].isNotBlank()) {
                            val baseName = chMatch.groupValues[1].trim()
                            val chapterPart = chMatch.groupValues[2].trim()
                            topName = baseName
                            if (subName == "全部") {
                                subName = chapterPart
                            } else {
                                subName = "$chapterPart / $subName"
                            }
                        }

                        // 5. 应用自定义规则 (保留底层结构)
                        val keywordRule = customRules.entries.firstOrNull { it.key.length > 2 && image.path.contains(it.key, ignoreCase = true) }
                        if (keywordRule != null && topName != keywordRule.value) {
                            subName = if (subName == "全部") topName!! else "$topName / $subName"
                            topName = keywordRule.value
                        }

                        return@groupBy AlbumKey(topName!!, subName)
                    }
                    
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

        val finalMap = mutableMapOf<String, MutableMap<String, List<ImageItem>>>()
        val others = mutableListOf<ImageItem>()

        val naturalOrderComparator = Comparator<ImageItem> { a, b ->
            val aNum = imageNumbers[a.path] ?: 0L
            val bNum = imageNumbers[b.path] ?: 0L
            if (aNum == bNum) a.path.compareTo(b.path) else aNum.compareTo(bNum)
        }

        // To fix extreme fragmentation (1-image albums), we aggregate them by top level first
        val topLevelCounts = grouped.entries.groupBy { it.key.top }.mapValues { entry ->
            entry.value.sumOf { it.value.size }
        }

        for ((key, imgs) in grouped) {
            val sortedImgs = imgs.sortedWith(naturalOrderComparator)
            val whiteList = listOf("微信保存", "QQ 保存", "Pixiv 插画", "手机截图")
            
            val totalInTopLevel = topLevelCounts[key.top] ?: 0
            
            // 如果是用户自定义提取的相册，即使只有1张图，也绝对不允许被碎片化合并！
            if (totalInTopLevel == 1 && key.top !in whiteList && !customRules.containsValue(key.top)) {
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