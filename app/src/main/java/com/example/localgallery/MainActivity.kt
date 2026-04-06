package com.example.localgallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.example.localgallery.ui.theme.LocalGalleryTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalGalleryTheme {
                val navController = rememberNavController()
                // 全局共享的 ViewModel，实现状态持久化和“零延迟”跳转
                val galleryViewModel: GalleryViewModel = viewModel()
                
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    galleryViewModel.loadWallpaper(context)
                }

                // 🌟 配置带有 GIF 解码能力的 ImageLoader
                val imageLoader = remember {
                    ImageLoader.Builder(context)
                        .components {
                            if (Build.VERSION.SDK_INT >= 28) {
                                add(ImageDecoderDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                        }
                        .build()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 全局自定义壁纸 (现在完美支持静态图和 GIF 动图了)
                    if (galleryViewModel.customWallpaperUri != null) {
                        AsyncImage(
                            model = galleryViewModel.customWallpaperUri,
                            imageLoader = imageLoader,
                            contentDescription = "Background Wallpaper",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 暗色遮罩，保证上层文字可见 (支持动态护眼)
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent // 让 Scaffold 变透明，透出背景
                    ) { innerPadding ->
                        GalleryNavHost(
                            navController = navController,
                            viewModel = galleryViewModel,
                            innerPadding = innerPadding // 修复半透明漏底：把 padding 往下传，别在 NavHost 这里统一套
                        )
                    }
                }
            }
        }
    }
}

// 🌟 导航中心
@Composable
fun GalleryNavHost(navController: NavHostController, viewModel: GalleryViewModel, innerPadding: PaddingValues) {
    NavHost(navController = navController, startDestination = "splash") {
        
        // 【0. 闪屏动画页】
        composable("splash") {
            SplashScreen(navController = navController)
        }

        // 【1. 首页：网格展示大封面 (主分类)】
        composable("home") {
            HomeScreen(viewModel = viewModel, navController = navController, modifier = Modifier.padding(innerPadding))
        }
        
        // 【2. 子分类相册页：展示主分类下的各个合集/章节】
        composable("sub_album_list") {
            SubAlbumListScreen(viewModel = viewModel, navController = navController, modifier = Modifier.padding(innerPadding))
        }
        
        // 【3. 相册详情页：网格展示具体子相册内的所有照片】
        composable("album_detail") {
            AlbumDetailScreen(viewModel = viewModel, navController = navController, modifier = Modifier.padding(innerPadding))
        }

        // 【4. 沉浸式全屏图册：支持左右滑动翻页】(🌟 这里不加 innerPadding，实现真正的 100% 满屏无缝黑色背景)
        composable("fullscreen_pager") {
            FullScreenPagerScreen(viewModel = viewModel, navController = navController)
        }
    }
}

// =========================================================================
// 页面 0：高级闪屏动画 (Splash Screen)
// =========================================================================
@Composable
fun SplashScreen(navController: NavHostController) {
    val scale = remember { Animatable(0.5f) }
    val alpha = remember { Animatable(0f) }

    // 启动动画序列
    LaunchedEffect(Unit) {
        // 并行执行放大和渐显动画
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800)
            )
        }
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800)
            )
        }
        
        // 动画完成后再停留 600ms 让用户欣赏
        delay(600)
        
        // 平滑跳转到首页，并且从回退栈中清空闪屏页（按返回键不会再回到闪屏页）
        navController.navigate("home") {
            popUpTo("splash") { inclusive = true }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer(
                scaleX = scale.value,
                scaleY = scale.value,
                alpha = alpha.value
            )
        ) {
            // 中心 Logo (修复了因使用 Android 系统自适应图标导致的闪退问题)
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "App Logo",
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary // 改为跟随主题色的精美图标
            )
            Spacer(modifier = Modifier.height(16.dp))
            // 欢迎标语
            Text(
                text = "PicHub",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// =========================================================================
// 页面 1：首页 (大封面网格)
// =========================================================================
@Composable
fun HomeScreen(viewModel: GalleryViewModel, navController: NavHostController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(false) }

    // 如果正在搜索，点击返回键会清除搜索框，而不是退出应用
    BackHandler(enabled = viewModel.searchQuery.isNotEmpty()) {
        viewModel.searchQuery = ""
    }

    // 动态判断权限
    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (Build.VERSION.SDK_INT >= 34) { Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED }
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            permissionGranted = isGranted
            if (isGranted) {
                // 有权限了，通知 ViewModel 去扫描（它内部会防重）
                viewModel.loadImagesIfNeeded(context)
            }
        }
    )

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionToRequest)
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                // 请求永久读取权限，避免重启后失效
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    viewModel.setCustomWallpaper(context, uri.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    )

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PicHub",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            // 修改壁纸按钮为更高级的图标
            IconButton(onClick = { documentLauncher.launch(arrayOf("image/*")) }) {
                Icon(
                    imageVector = Icons.Default.Wallpaper,
                    contentDescription = "更换壁纸",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // 添加搜索框 (模糊搜索)
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.searchQuery = it },
            placeholder = { Text("搜索相册...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
            trailingIcon = {
                if (viewModel.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp)
        )

        if (!permissionGranted) {
            Text("请授予读取存储权限，否则无法加载图片。")
        } else if (viewModel.isLoading) {
            Text("正在飞速扫描全盘图库中...")
        } else if (viewModel.categorizedImages.isEmpty()) {
            Text("未扫描到任何图片...")
        } else {
            // 🌟 模糊搜索过滤
            val albumNames = viewModel.categorizedImages.keys.filter {
                it.contains(viewModel.searchQuery, ignoreCase = true)
            }.toList()
            
            if (albumNames.isEmpty() && viewModel.searchQuery.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("未找到相册 🥺", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), // 2列网格
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albumNames) { topAlbumName ->
                        val subAlbums = viewModel.categorizedImages[topAlbumName]!!
                        
                        // 提取第一张作为封面，并且计算总图片数
                        var totalImages = 0
                        var coverImage: ImageItem? = null
                        for ((_, imgs) in subAlbums) {
                            totalImages += imgs.size
                            if (coverImage == null) coverImage = imgs.first()
                        }

                        if (coverImage != null) {
                            AlbumCoverCard(
                                coverImage = coverImage,
                                albumName = topAlbumName,
                                imageCount = totalImages,
                                onClick = {
                                    // 记录当前点击的主相册
                                    viewModel.currentTopAlbumName = topAlbumName
                                    
                                    // 🌟 智能化跳转：如果该大类下只有一个子类（比如只有"全部"），直接跳过二级目录，进入详情
                                    if (subAlbums.size == 1) {
                                        viewModel.currentSubAlbumName = subAlbums.keys.first()
                                        navController.navigate("album_detail")
                                    } else {
                                        // 如果有多个子类（多个章节），进入二级目录
                                        navController.navigate("sub_album_list")
                                    }
                                }
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(40.dp)) }
                }
            }
        }
    }
}

// 首页的精美相册卡片组件
@Composable
fun AlbumCoverCard(coverImage: ImageItem, albumName: String, imageCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // 正方形封面
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景封面图
            AsyncImage(
                model = coverImage.path,
                contentDescription = albumName,
                contentScale = ContentScale.Crop, // 裁剪填充
                modifier = Modifier.fillMaxSize()
            )

            // 底部黑色毛玻璃渐变遮罩 (保护文字可读性)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1 // 超长防换行
                    )
                    Text(
                        text = "$imageCount 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// =========================================================================
// 页面 2：二级分类相册页 (展示主分类下的各个合集/章节)
// =========================================================================
@Composable
fun SubAlbumListScreen(viewModel: GalleryViewModel, navController: NavHostController, modifier: Modifier = Modifier) {
    val topAlbumName = viewModel.currentTopAlbumName
    val subAlbums = viewModel.categorizedImages[topAlbumName] ?: emptyMap()

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部导航栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = topAlbumName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 网格展示子相册
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            items(subAlbums.keys.toList()) { subAlbumName ->
                val imagesInSubAlbum = subAlbums[subAlbumName]!!
                val coverImage = imagesInSubAlbum.first()

                AlbumCoverCard(
                    coverImage = coverImage,
                    albumName = if (subAlbumName == "全部") topAlbumName else subAlbumName,
                    imageCount = imagesInSubAlbum.size,
                    onClick = {
                        viewModel.currentSubAlbumName = subAlbumName
                        navController.navigate("album_detail")
                    }
                )
            }
            item { Spacer(modifier = Modifier.height(40.dp)) }
        }
    }
}

// =========================================================================
// 页面 3：相册详情页 (所有照片的网格墙)
// =========================================================================
@Composable
fun AlbumDetailScreen(viewModel: GalleryViewModel, navController: NavHostController, modifier: Modifier = Modifier) {
    val albumName = if (viewModel.currentSubAlbumName == "全部") {
        viewModel.currentTopAlbumName
    } else {
        "${viewModel.currentTopAlbumName} - ${viewModel.currentSubAlbumName}"
    }
    val images = viewModel.getCurrentImageList()

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部导航栏 (改为返回箭头)
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { navController.popBackStack() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = albumName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        // 🌟 网格展示相册内所有小图 (一行三列)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)
        ) {
            itemsIndexed(images) { index, image ->
                AsyncImage(
                    model = image.path,
                    contentDescription = image.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f) // 保证缩略图是正方形
                        .clickable {
                            // 记录点击了第几张，然后去全屏页面！
                            viewModel.currentInitialIndex = index
                            navController.navigate("fullscreen_pager")
                        }
                )
            }
        }
    }
}

// =========================================================================
// 页面 3：沉浸式全屏图册 (支持左右滑动翻页)
// =========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPagerScreen(viewModel: GalleryViewModel, navController: NavHostController) {
    val images = viewModel.getCurrentImageList()
    val pagerState = rememberPagerState(
        initialPage = viewModel.currentInitialIndex,
        pageCount = { images.size }
    )
    
    // 控制底部详情面板的显示状态
    val sheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }
    
    // 控制屏幕上的小部件 (返回箭头、信息按钮等) 是否显示 (沉浸式体验)
    var showUI by remember { mutableStateOf(true) }
    
    // 🌟 动态背景色动画：显示 UI 时保持透明以透出动态壁纸，隐藏 UI (沉浸模式) 时渐变为纯黑！
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (showUI) Color.Transparent else Color.Black,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
        label = "bg_color"
    )
    
    // 当前正在全屏查看的图片
    val currentImage = images[pagerState.currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val image = images[page]
            
            // 加入双击缩放和手势缩放功能
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            
            // 切换图片时恢复原比例
            LaunchedEffect(page) {
                scale = 1f
                offset = Offset.Zero
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showUI = !showUI }, // 单击隐藏或显示 UI
                            onDoubleTap = {
                                // 双击放大 2 倍，或还原
                                scale = if (scale > 1f) 1f else 2f
                                offset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // 🌟 修复手势冲突：只在放大状态下拦截拖拽，保证 1x 比例下 Pager 可以左右滑动！
                        awaitEachGesture {
                            awaitFirstDown()
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                
                                // 正在双指捏合缩放 (超过1个手指)，或者已经是放大状态时，拦截手势！
                                if (event.changes.size > 1 || scale > 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val maxX = (size.width * (scale - 1)) / 2
                                    val maxY = (size.height * (scale - 1)) / 2
                                    
                                    val newOffsetX = offset.x + pan.x * scale
                                    val newOffsetY = offset.y + pan.y * scale
                                    
                                    offset = Offset(
                                        x = newOffsetX.coerceIn(-maxX, maxX),
                                        y = newOffsetY.coerceIn(-maxY, maxY)
                                    )
                                    // 消费该事件，不让外层的 HorizontalPager 拿到
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            ) {
                AsyncImage(
                    model = image.path,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }

        if (showUI) {
            // 顶部悬浮栏：返回按钮箭头 + 当前页码指示器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // 底部悬浮栏：极其简化的小巧的 [i] 属性按钮
            IconButton(
                onClick = { showBottomSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(50))
            ) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "查看属性", tint = Color.White)
            }
        }
    }

    // 🌟 从底部弹出的属性详情面板 (BottomSheet)
    if (showBottomSheet) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var showMoveDialog by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("图片详细信息", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                
                val displayAlbumName = if (viewModel.currentSubAlbumName == "全部") {
                    viewModel.currentTopAlbumName
                } else {
                    "${viewModel.currentTopAlbumName} - ${viewModel.currentSubAlbumName}"
                }
                DetailItem(title = "当前相册", value = displayAlbumName)
                DetailItem(title = "推测来源", value = currentImage.inferredSource)
                DetailItem(title = "文件名称", value = currentImage.name)
                DetailItem(title = "文件大小", value = currentImage.formattedSize)
                DetailItem(title = "存储路径", value = currentImage.path)
                
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { showMoveDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("移动到自定义相册 (覆盖规则)")
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // --- 移动相册的 Dialog ---
        if (showMoveDialog) {
            var newAlbumName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showMoveDialog = false },
                title = { Text("移动到新相册") },
                text = {
                    Column {
                        Text("输入你想把这张图片放入的相册名：")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newAlbumName,
                            onValueChange = { newAlbumName = it },
                            label = { Text("相册名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newAlbumName.isNotBlank()) {
                                // 启动协程保存规则到 Room 数据库
                                coroutineScope.launch {
                                    val db = AppDatabase.getDatabase(context)
                                    db.ruleDao().insertRule(RuleEntity(currentImage.path, newAlbumName))
                                    
                                    // 刷新全库
                                    showMoveDialog = false
                                    showBottomSheet = false
                                    viewModel.reloadRulesAndReclassify(context)
                                    navController.popBackStack("home", inclusive = false)
                                }
                            }
                        }
                    ) {
                        Text("确定移动")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showMoveDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

// 详情面板里的单行数据组件
@Composable
fun DetailItem(title: String, value: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
    }
}

