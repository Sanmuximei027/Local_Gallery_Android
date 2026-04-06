package com.example.localgallery

import java.io.File

/**
 * 智能分类引擎的核心！
 * 在这里，我们将所有散乱的图片名，归纳到它们各自专属的“相册/文件夹”里。
 */
object SmartClassifier {

    private val numRegex = Regex("\\d+")

    fun classify(
        images: List<ImageItem>,
        customRules: Map<String, String> = emptyMap()
    ): Map<String, List<ImageItem>> {
        // 性能优化：提前提取所有图片的第一个数字用于极速排序，避免每次比较时都执行正则匹配 (性能提升巨大)
        val imageNumbers = images.associate { it.path to (numRegex.find(it.path)?.value?.toLongOrNull() ?: 0L) }
        
        val grouped = images.groupBy { image ->
            // --- 0. 优先级最高：用户自定义规则 ---
            if (customRules.containsKey(image.path)) {
                return@groupBy customRules[image.path]!!
            }

            val name = image.name.lowercase()
            // 去掉后缀名 (例如 .jpg, .png)，只分析文件名主体
            val nameWithoutExt = name.substringBeforeLast(".")

            when {
                // 1. 微信 / QQ 截图相关
                name.startsWith("wx_camera") || name.startsWith("mmexport") || name.startsWith("micro") -> "微信保存"
                name.startsWith("qq_") -> "QQ 保存"
                
                // 2. Pixiv 插画平台
                name.contains("pixiv") || name.contains("illust_") -> "Pixiv 插画"
                
                // 3. Konachan / Booru 等动漫图站 (包含 " - " 分隔符)
                name.contains(" - ") -> {
                    val siteName = image.name.substringBefore(" - ").trim()
                    siteName.replace(".com", "", ignoreCase = true)
                            .replace(".net", "", ignoreCase = true)
                }

                // 4. 截图 (Screenshot)
                name.contains("screenshot") -> "手机截图"

                // 5. 【增强版：直接使用非标准父文件夹名称，支持特殊结构和系统白名单】
                else -> {
                    val parentFile = File(image.path).parentFile
                    var parentName = parentFile?.name
                    
                    if (parentName != null) {
                        val lowerParent = parentName.lowercase()
                        
                        // 先匹配标准白名单系统路径：将这些被忽略的系统文件夹转换为中文优质相册名
                        val standardDirs = mapOf(
                            "camera" to "相机照片",
                            "dcim" to "相机照片",
                            "pictures" to "普通图片",
                            "images" to "普通图片",
                            "download" to "下载目录",
                            "downloads" to "下载目录",
                            "weixin" to "微信",
                            "wechat" to "微信"
                        )
                        
                        if (standardDirs.containsKey(lowerParent)) {
                            return@groupBy standardDirs[lowerParent]!!
                        }

                        // 检测是否是系统级无用名，跳过
                        if (lowerParent !in listOf("0", "emulated", "media")) {
                            
                            // 🌟 往上追溯一层的触发条件：
                            // 1. 文件夹名是纯粹的哈希标识，例如 [f5960170] 或纯字母数字乱码
                            val isHash = (parentName.startsWith("[") && parentName.endsWith("]")) ||
                                         parentName.matches(Regex("^[a-fA-F0-9_-]{8,}$"))
                            
                            // 2. 文件夹名是章节标识，例如纯数字，或带有"章","话"等
                            val isChapter = parentName.all { it.isDigit() } ||
                                    Regex("第?\\d+(话|章|卷|回)").matches(parentName) ||
                                    lowerParent.startsWith("chapter") ||
                                    lowerParent.startsWith("ch") ||
                                    lowerParent.startsWith("vol")
                            
                            val grandParentFile = parentFile?.parentFile
                            val grandParentName = grandParentFile?.name
                            val lowerGrand = grandParentName?.lowercase() ?: ""
                            
                            // 如果符合条件，且爷爷目录不是被忽略的系统无用名，就提取爷爷的名字作为真正相册！
                            if ((isChapter || isHash) && grandParentName != null && 
                                lowerGrand !in listOf("0", "emulated", "dcim", "pictures", "download", "camera", "images", "media")) {
                                return@groupBy grandParentName
                            } else {
                                return@groupBy parentName // 否则正常使用它本身的名字
                            }
                        }
                    }
                    
                    // --- 如果连上级文件夹名称都没有获取到，则继续进行以下模式匹配 ---
                    // 6. 纯数字、乱码、或者像 n06_2_08 这样的序列号
                    if (nameWithoutExt.all { it.isDigit() } ||
                        (nameWithoutExt.length > 12 && !nameWithoutExt.contains("_") && !nameWithoutExt.contains("-")) ||
                        nameWithoutExt.matches(Regex("^[a-zA-Z]{0,5}[_-]?\\d+([_-]\\d+)*$"))
                    ) {
                        "未分类序列图"
                    } 
                    // 7. 通用前缀匹配 (下划线或连字符)
                    else if (name.contains("_")) {
                        val prefix = name.substringBefore("_")
                        if (prefix.length in 2..15) {
                            prefix.replaceFirstChar { it.uppercase() }
                        } else {
                            "未分类图库"
                        }
                    } 
                    // 8. 其他所有无法识别的
                    else {
                        "未分类图库"
                    }
                }
            }
        }

        // 🌟 【解决痛点补充问题】：孤儿相册清理！
        val finalGroups = mutableMapOf<String, MutableList<ImageItem>>()
        val others = mutableListOf<ImageItem>()

        // 🌟 自然排序器（已优化性能，读取预处理好的数字）
        val naturalOrderComparator = Comparator<ImageItem> { a, b ->
            val aNum = imageNumbers[a.path] ?: 0L
            val bNum = imageNumbers[b.path] ?: 0L
            if (aNum == bNum) a.path.compareTo(b.path) else aNum.compareTo(bNum)
        }

        for ((albumName, imgs) in grouped) {
            // 对每个相册内部的图片进行【乱序修复：数字自然排序】
            val sortedImgs = imgs.sortedWith(naturalOrderComparator)

            // 保护白名单：即使只有 1 张，这些系统级相册也保留
            val whiteList = listOf("微信保存", "QQ 保存", "Pixiv 插画", "手机截图")
            
            if (sortedImgs.size == 1 && albumName !in whiteList) {
                // 只有 1 张图的孤儿，踢出独立相册，加入杂项
                others.addAll(sortedImgs)
            } else {
                // 正常的相册，保留
                finalGroups[albumName] = sortedImgs.toMutableList()
            }
        }

        // 把收集到的所有孤儿图片，统一放进一个特定的相册
        if (others.isNotEmpty()) {
            finalGroups["散落的图片 (未成组)"] = others
        }

        return finalGroups
    }
}
