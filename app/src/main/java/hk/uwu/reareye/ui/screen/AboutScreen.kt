package hk.uwu.reareye.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.chrisbanes.haze.HazeState
import hk.uwu.reareye.R
import hk.uwu.reareye.generated.AppProperties
import hk.uwu.reareye.repository.contributor.ContributorLoadState
import hk.uwu.reareye.repository.contributor.ContributorProfile
import hk.uwu.reareye.repository.contributor.ContributorRepository
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.motion.ArtRevealItem
import hk.uwu.reareye.ui.components.motion.ArtStaggeredReveal
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import hk.uwu.reareye.utils.blend.ColorBlendToken
import hk.uwu.reareye.utils.effect.BgEffectBackground
import hk.uwu.reareye.utils.other.DeviceConfigTools
import hk.uwu.reareye.utils.other.LibraryItem
import hk.uwu.reareye.utils.other.OSVersionTools
import hk.uwu.reareye.utils.other.loadLibraries
import hk.uwu.reareye.utils.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.shapes.SmoothRoundedCornerShape
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.concurrent.ConcurrentHashMap

private val AboutPageHorizontalPadding = 12.dp
private val AboutDeviceInfoCardTopPadding = 20.dp
private val AboutDeviceInfoCardBottomPadding = 12.dp
private val AboutDeviceInfoRowVerticalPadding = 8.dp
private val AboutDeviceInfoHeaderBottomSpacing = 8.dp
private val AboutCardSpacing = 8.dp

private val contributorAvatarHttpClient = OkHttpClient()

private object ContributorAvatarCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun peek(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        return cache[key]
    }

    suspend fun preload(urls: List<String>) {
        urls.distinct().forEach { url ->
            load(url)
        }
    }

    suspend fun load(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        cache[key]?.let { return it }

        val image = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(key)
                    .build()
                contributorAvatarHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    BitmapFactory.decodeStream(response.body.byteStream())?.asImageBitmap()
                }
            }.getOrNull()
        }

        if (image != null) {
            cache.putIfAbsent(key, image)
        }
        return cache[key] ?: image
    }
}

private sealed interface AboutRoute {
    data object Root : AboutRoute
    data object Contributors : AboutRoute
    data object Licenses : AboutRoute
}

private data class AboutAnimatedRoute(
    val route: AboutRoute,
    val depth: Int,
)

private data class CreditEntry(
    val titleRes: Int,
    val summaryRes: Int,
    val url: String,
)

private data class AboutVisualTokens(
    val isDarkTheme: Boolean,
    val backgroundColor: Color,
    val cardBlendColors: List<BlendColorEntry>,
    val logoBlendColors: List<BlendColorEntry>,
)

@Composable
private fun rememberAboutVisualTokens(): AboutVisualTokens {
    val surface = colorScheme.surface
    val isDarkTheme = surface.luminance() < 0.5f

    return remember(surface, isDarkTheme) {
        AboutVisualTokens(
            isDarkTheme = isDarkTheme,
            backgroundColor = surface,
            cardBlendColors = aboutCardBlendColors(isDarkTheme),
            logoBlendColors = aboutLogoBlendColors(isDarkTheme),
        )
    }
}

private fun aboutCardBlendColors(isDarkTheme: Boolean): List<BlendColorEntry> {
    return if (isDarkTheme) {
        ColorBlendToken.Overlay_Thin_Light
    } else {
        ColorBlendToken.Pured_Regular_Light
    }
}

private fun aboutLogoBlendColors(isDarkTheme: Boolean): List<BlendColorEntry> {
    return if (isDarkTheme) {
        listOf(
            BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
        )
    } else {
        listOf(
            BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
        )
    }
}

@Composable
private fun rememberSkeletonPulseAlpha(label: String): Float {
    val infiniteTransition = rememberInfiniteTransition(label = label)
    val alpha = infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
    return alpha.value
}

@Composable
fun AboutScreen(bottomInnerPadding: Dp = 0.dp) {
    val versionText = rememberVersionText()
    val contributorState by ContributorRepository.state.collectAsState()
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }

    var route by remember { mutableStateOf<AboutRoute>(AboutRoute.Root) }
    var animateRootContent by remember { mutableStateOf(true) }
    val animatedRoute = remember(route) {
        AboutAnimatedRoute(
            route = route,
            depth = if (route is AboutRoute.Root) 0 else 1,
        )
    }

    val entries = remember {
        listOf(
            CreditEntry(
                titleRes = R.string.credits_github_title,
                summaryRes = R.string.credits_github_desc,
                url = "https://github.com/killerprojecte/REAREye",
            ),
            CreditEntry(
                titleRes = R.string.credits_docs,
                summaryRes = R.string.credits_docs_desc,
                url = "https://reareye.uwu.hk"
            ),
            CreditEntry(
                titleRes = R.string.credits_afdian_title,
                summaryRes = R.string.credits_afdian_desc,
                url = "https://ifdian.net/a/rgbmc",
            ),
            CreditEntry(
                titleRes = R.string.credits_qq_title,
                summaryRes = R.string.credits_qq_desc,
                url = "https://qm.qq.com/q/cg2MU3kw6W"
            ),
            CreditEntry(
                titleRes = R.string.credits_coolapk_title,
                summaryRes = R.string.credits_coolapk_desc,
                url = "https://www.coolapk.com/u/7190992"
            )
        )
    }

    LaunchedEffect(Unit) {
        ContributorRepository.preload()
    }

    LaunchedEffect(route) {
        if (route is AboutRoute.Contributors) {
            ContributorRepository.ensureLoaded(force = false)
        }
    }

    LaunchedEffect(contributorState) {
        val loadedState = contributorState as? ContributorLoadState.Loaded ?: return@LaunchedEffect
        ContributorAvatarCache.preload(
            loadedState.contributors.mapNotNull { it.avatar?.takeIf(String::isNotBlank) }
        )
    }

    BackHandler(enabled = route is AboutRoute.Contributors || route is AboutRoute.Licenses) {
        route = AboutRoute.Root
    }

    AnimatedContent(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface)
            .graphicsLayer { clip = true },
        targetState = animatedRoute,
        contentKey = { it.route },
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth

            fadeIn(
                animationSpec = tween(
                    durationMillis = 210,
                    delayMillis = 50,
                    easing = LinearOutSlowInEasing,
                )
            ) + slideInHorizontally(
                animationSpec = tween(
                    durationMillis = 280,
                    easing = FastOutSlowInEasing,
                )
            ) { fullWidth ->
                if (forward) fullWidth / 9 else -fullWidth / 9
            } togetherWith (
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 110,
                            easing = FastOutLinearInEasing,
                        )
                    ) + slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = 190,
                            easing = FastOutLinearInEasing,
                        )
                    ) { fullWidth ->
                        if (forward) -fullWidth / 12 else fullWidth / 12
                    }
                    )
        },
        label = "AboutRouteTransition",
    ) { target ->
        when (target.route) {
            AboutRoute.Root -> AboutRootPage(
                bottomInnerPadding = bottomInnerPadding,
                versionText = versionText,
                entries = entries,
                lazyListState = lazyListState,
                logoHeightPx = logoHeightPx,
                animateEnter = animateRootContent,
                onLogoHeightChanged = { logoHeightPx = it },
                onOpenContributors = {
                    animateRootContent = false
                    route = AboutRoute.Contributors
                },
                onOpenLibraries = {
                    animateRootContent = false
                    route = AboutRoute.Licenses
                },
            )

            AboutRoute.Contributors -> AboutSecondaryPage(
                title = stringResource(R.string.credits_contributors_title),
                onBack = { route = AboutRoute.Root },
            ) { paddingValues, scrollBehavior, hazeState ->
                ContributorListContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                    hazeState = hazeState,
                    state = contributorState,
                )
            }

            AboutRoute.Licenses -> AboutSecondaryPage(
                title = stringResource(R.string.licenses_name),
                onBack = { route = AboutRoute.Root },
            ) { paddingValues, scrollBehavior, hazeState ->
                LicenseContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                    hazeState = hazeState,
                )
            }
        }
    }
}

@Composable
private fun AboutRootPage(
    bottomInnerPadding: Dp,
    versionText: String,
    entries: List<CreditEntry>,
    lazyListState: LazyListState,
    logoHeightPx: Int,
    animateEnter: Boolean,
    onLogoHeightChanged: (Int) -> Unit,
    onOpenContributors: () -> Unit,
    onOpenLibraries: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val scrollProgress by remember {
        derivedStateOf {
            val index = lazyListState.firstVisibleItemIndex
            val offset = lazyListState.firstVisibleItemScrollOffset

            if (index > 0) {
                1f
            } else if (logoHeightPx <= 0) {
                0f
            } else {
                (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.about_navigation),
                scrollBehavior = scrollBehavior,
                color = colorScheme.surface.copy(alpha = if (scrollProgress == 1f) 1f else 0f),
                titleColor = colorScheme.onSurface.copy(alpha = scrollProgress),
                defaultWindowInsetsPadding = false,
                navigationIconPadding = 12.dp,
            )
        }
    ) { paddingValues ->
        AboutRootContent(
            bottomInnerPadding = bottomInnerPadding,
            paddingValues = paddingValues,
            scrollBehavior = scrollBehavior,
            hazeState = hazeState,
            versionText = versionText,
            entries = entries,
            onOpenContributors = onOpenContributors,
            scrollProgress = scrollProgress,
            onLogoHeightChanged = onLogoHeightChanged,
            lazyListState = lazyListState,
            onOpenLibraries = onOpenLibraries,
            animateEnter = animateEnter,
        )
    }
}

@Composable
private fun AboutSecondaryPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, ScrollBehavior, HazeState) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                navigationIconPadding = 12.dp,
                scrollBehavior = scrollBehavior,
            )
        }
    ) { paddingValues ->
        content(
            paddingValues,
            scrollBehavior,
            hazeState,
        )
    }
}

@Composable
private fun AboutRootContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    versionText: String,
    entries: List<CreditEntry>,
    onOpenContributors: () -> Unit,
    scrollProgress: Float,
    onLogoHeightChanged: (Int) -> Unit,
    lazyListState: LazyListState,
    onOpenLibraries: () -> Unit,
    animateEnter: Boolean,
) {
    val context = LocalContext.current
    val visualTokens = rememberAboutVisualTokens()

    val backdrop = rememberLayerBackdrop()

    val scrollPadding = pageContentPadding(
        paddingValues,
        paddingValues,
        false,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateRightPadding(LayoutDirection.Ltr),
    )
    val logoPadding = pageContentPadding(
        paddingValues,
        paddingValues,
        false,
        extraTop = 10.dp,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateRightPadding(LayoutDirection.Ltr),
    )

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(200.dp) }
    var logoAreaY by remember { mutableFloatStateOf(0f) }
    var iconY by remember { mutableFloatStateOf(0f) }
    var projectNameY by remember { mutableFloatStateOf(0f) }
    var versionCodeY by remember { mutableFloatStateOf(0f) }

    var iconProgress by remember { mutableFloatStateOf(0f) }
    var projectNameProgress by remember { mutableFloatStateOf(0f) }
    var versionCodeProgress by remember { mutableFloatStateOf(0f) }
    var initialLogoAreaY by remember { mutableFloatStateOf(0f) }
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }


    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .onEach { offset ->
                if (lazyListState.firstVisibleItemIndex > 0) {
                    if (iconProgress != 1f) iconProgress = 1f
                    if (projectNameProgress != 1f) projectNameProgress = 1f
                    return@onEach
                }

                if (initialLogoAreaY == 0f && logoAreaY > 0f) {
                    initialLogoAreaY = logoAreaY
                }
                val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY

                val stage1TotalLength = refLogoAreaY - versionCodeY
                val stage2TotalLength = versionCodeY - projectNameY
                val stage3TotalLength = projectNameY - iconY

                val versionCodeDelay = stage1TotalLength * 0.5f
                versionCodeProgress =
                    ((offset.toFloat() - versionCodeDelay) / (stage1TotalLength - versionCodeDelay).coerceAtLeast(
                        1f
                    ))
                        .coerceIn(0f, 1f)
                projectNameProgress =
                    ((offset.toFloat() - stage1TotalLength) / stage2TotalLength.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                iconProgress =
                    ((offset.toFloat() - stage1TotalLength - stage2TotalLength) / stage3TotalLength.coerceAtLeast(
                        1f
                    ))
                        .coerceIn(0f, 1f)
            }
            .collect { }
    }
    BgEffectBackground(
        dynamicBackground = runtimeShaderSupported,
        modifier = Modifier.fillMaxSize(),
        bgModifier = Modifier.layerBackdrop(backdrop),
        backgroundColor = visualTokens.backgroundColor,
        isDarkTheme = visualTokens.isDarkTheme,
        effectBackground = runtimeShaderSupported,
        alpha = { 1f - scrollProgress },
    ) {
        // ── 修改：Logo 固定悬浮在 LazyColumn 下方，与 AboutPage 结构一致 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = logoPadding.calculateRightPadding(LayoutDirection.Ltr),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .graphicsLayer {
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (iconY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        iconY = y + size.height
                    },
            ) {
                Image(
                    modifier = Modifier
                        .size(90.dp)
                        .textureBlur(
                            backdrop = backdrop,
                            shape = SmoothRoundedCornerShape(24.dp),
                            blurRadius = 150f,
                            colors = BlurColors(
                                blendColors = visualTokens.logoBlendColors,
                            ),
                            contentBlendMode = BlendMode.DstIn,
                            enabled = true,
                        ),
                    painter = painterResource(R.drawable.ic_about_logo_hollow),
                    contentDescription = null,
                )
            }

            // 应用名称（带 textureBlur + 消失动画）
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .onGloballyPositioned { coordinates ->
                        if (projectNameY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        projectNameY = y + size.height
                    }
                    .graphicsLayer {
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .textureBlur(
                        backdrop = backdrop,
                        shape = SmoothRoundedCornerShape(16.dp),
                        blurRadius = 150f,
                        colors = BlurColors(
                            blendColors = visualTokens.logoBlendColors,
                        ),
                        contentBlendMode = BlendMode.DstIn,
                        enabled = true,
                    ),
                text = stringResource(R.string.app_name),
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )

            // ── 修改：版本号补上 graphicsLayer 动画 + onGloballyPositioned 追踪 ──
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (versionCodeY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        versionCodeY = y + size.height
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = versionText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .rearAcrylicSource(hazeState)
                .padding(horizontal = AboutPageHorizontalPadding),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
            ),
        ) {
            // ── 透明占位，高度与 Logo 区域匹配，LazyColumn 滑过它时 Logo 淡出 ──
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        )
                        .onSizeChanged { size ->
                            onLogoHeightChanged(size.height)
                        }
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            logoAreaY = y + size.height
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }

            item(key = "content") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
                ) {
                    AboutDeviceInfoCard(
                        context = context,
                        backdrop = backdrop,
                        visualTokens = visualTokens,
                        animateEnter = animateEnter,
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
                    ) {
                        AboutReveal(
                            enabled = animateEnter,
                            revealKey = "contributors",
                            delayMillis = 36,
                        ) {
                            Card(
                                modifier = Modifier
                                    .textureBlur(
                                        backdrop = backdrop,
                                        shape = SmoothRoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        noiseCoefficient = 0.001f,
                                        colors = BlurColors(
                                            blendColors = visualTokens.cardBlendColors,
                                            brightness = 0f,
                                            contrast = 1f,
                                            saturation = 1f,
                                        ),
                                        enabled = true,
                                    ),
                                colors = CardDefaults.defaultColors(
                                    Color.Transparent,
                                    Color.Transparent,
                                ),
                            ) {
                                SuperCard(
                                    title = stringResource(R.string.credits_contributors_title),
                                    summary = stringResource(R.string.credits_contributors_desc),
                                    onClick = onOpenContributors,
                                    endActions = {
                                        Icon(
                                            imageVector = MiuixIcons.Create,
                                            tint = colorScheme.onSurface,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }

                        entries.forEachIndexed { index, entry ->
                            AboutReveal(
                                enabled = animateEnter,
                                revealKey = entry.url,
                                delayMillis = (54 + index * 18).coerceAtMost(150),
                            ) {
                                Card(
                                    modifier = Modifier
                                        .textureBlur(
                                            backdrop = backdrop,
                                            shape = SmoothRoundedCornerShape(16.dp),
                                            blurRadius = 60f,
                                            noiseCoefficient = 0.001f,
                                            colors = BlurColors(
                                                blendColors = visualTokens.cardBlendColors,
                                                brightness = 0f,
                                                contrast = 1f,
                                                saturation = 1f,
                                            ),
                                            enabled = true,
                                        ),
                                    colors = CardDefaults.defaultColors(
                                        Color.Transparent,
                                        Color.Transparent,
                                    ),
                                ) {
                                    SuperCard(
                                        title = stringResource(entry.titleRes),
                                        summary = stringResource(entry.summaryRes),
                                        onClick = {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    entry.url.toUri()
                                                )
                                            )
                                        },
                                        endActions = {
                                            Icon(
                                                imageVector = MiuixIcons.Link,
                                                tint = colorScheme.onSurface,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        AboutReveal(
                            enabled = animateEnter,
                            revealKey = "licenses",
                            delayMillis = (54 + entries.size * 18).coerceAtMost(150),
                        ) {
                            Card(
                                modifier = Modifier
                                    .textureBlur(
                                        backdrop = backdrop,
                                        shape = SmoothRoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        noiseCoefficient = 0.001f,
                                        colors = BlurColors(
                                            blendColors = visualTokens.cardBlendColors,
                                            brightness = 0f,
                                            contrast = 1f,
                                            saturation = 1f,
                                        ),
                                        enabled = true,
                                    ),
                                colors = CardDefaults.defaultColors(
                                    Color.Transparent,
                                    Color.Transparent,
                                ),
                            ) {
                                SuperCard(
                                    title = stringResource(R.string.licenses_name),
                                    summary = stringResource(R.string.licenses_name_summary),
                                    onClick = onOpenLibraries,
                                    endActions = {
                                        Icon(
                                            imageVector = MiuixIcons.Info,
                                            tint = colorScheme.onSurface,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun AboutDeviceInfoCard(
    context: android.content.Context,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    visualTokens: AboutVisualTokens,
    animateEnter: Boolean,
) {
    AboutReveal(
        enabled = animateEnter,
        revealKey = "device_info_card",
        delayMillis = 0,
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val deviceInfoCardPadding = PaddingValues(
            start = BasicComponentDefaults.InsideMargin.calculateStartPadding(layoutDirection),
            top = AboutDeviceInfoCardTopPadding,
            end = BasicComponentDefaults.InsideMargin.calculateEndPadding(layoutDirection),
            bottom = AboutDeviceInfoCardBottomPadding,
        )
        val deviceInfoRowPadding = PaddingValues(vertical = AboutDeviceInfoRowVerticalPadding)
        val subscreenVer = DeviceConfigTools.getSubScreenVersion(context)

        Card(
            modifier = Modifier
                .textureBlur(
                    backdrop = backdrop,
                    shape = SmoothRoundedCornerShape(16.dp),
                    blurRadius = 60f,
                    noiseCoefficient = 0.001f,
                    colors = BlurColors(
                        blendColors = visualTokens.cardBlendColors,
                        brightness = 0f,
                        contrast = 1f,
                        saturation = 1f,
                    ),
                    enabled = true,
                ),
            colors = CardDefaults.defaultColors(
                Color.Transparent,
                Color.Transparent,
            ),
        ) {
            Column(
                modifier = Modifier.padding(deviceInfoCardPadding),
            ) {
                Text(
                    text = DeviceConfigTools.deviceName,
                    modifier = Modifier.padding(bottom = AboutDeviceInfoHeaderBottomSpacing),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = BasicComponentDefaults.titleColor().color,
                )

                BasicComponent(
                    title = stringResource(R.string.device_name),
                    summary = DeviceConfigTools.marketName,
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.android_version),
                    summary = DeviceConfigTools.androidVersion,
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.os_version),
                    summary = OSVersionTools.addVersionSuffix(context),
                    insideMargin = deviceInfoRowPadding,
                )

                if (subscreenVer != "UNKNOWN") {
                    BasicComponent(
                        title = stringResource(R.string.subsceen_version),
                        summary = subscreenVer,
                        insideMargin = deviceInfoRowPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutReveal(
    enabled: Boolean,
    revealKey: Any,
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        ArtStaggeredReveal(
            visible = true,
            revealKey = revealKey,
            delayMillis = delayMillis,
            content = content,
        )
    } else {
        content()
    }
}

@Composable
private fun ContributorListContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    state: ContributorLoadState,
) {
    val avatarPlaceholderAlpha = rememberSkeletonPulseAlpha("contributor-avatar-skeleton")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .rearAcrylicSource(hazeState)
            .padding(horizontal = AboutPageHorizontalPadding),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
        overscrollEffect = null,
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
        }

        when (state) {
            ContributorLoadState.Idle,
            ContributorLoadState.Loading,
                -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfiniteProgressIndicator()
                            Text(text = stringResource(R.string.credits_contributors_loading))
                        }
                    }
                }
            }

            ContributorLoadState.Failed -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SuperCard(
                            title = stringResource(R.string.credits_contributors_title),
                            summary = stringResource(R.string.credits_contributors_load_failed),
                            bottomAction = {
                                Button(
                                    onClick = { ContributorRepository.ensureLoaded(force = true) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(text = stringResource(R.string.credits_contributors_retry))
                                }
                            },
                        )
                    }
                }
            }

            is ContributorLoadState.Loaded -> {
                if (state.contributors.isEmpty()) {
                    item {
                        ArtRevealItem(visible = true, delayMillis = 40) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.credits_contributors_empty),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        state.contributors,
                        key = { _, item -> item.link?.takeIf { it.isNotBlank() } ?: item.name },
                    ) { index, item ->
                        val revealKey = item.link?.takeIf { it.isNotBlank() } ?: item.name
                        ArtStaggeredReveal(
                            visible = true,
                            revealKey = revealKey,
                            delayMillis = (36 + index * 18).coerceAtMost(150),
                        ) {
                            ContributorCard(
                                item = item,
                                avatarPlaceholderAlpha = avatarPlaceholderAlpha,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
) {
    val context = LocalContext.current
    val data = remember {
        loadLibraries(context)
    }


    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .rearAcrylicSource(hazeState)
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        overscrollEffect = null,
    ) {
        items(data.libraries) {
            LibraryItem(it,data.licenses)
        }
    }

}

@Composable
private fun ContributorCard(
    item: ContributorProfile,
    avatarPlaceholderAlpha: Float,
) {
    val context = LocalContext.current
    val link = item.link?.takeIf { it.isNotBlank() }
    val hasLink = link != null

    Card(modifier = Modifier.fillMaxWidth()) {
        SuperCard(
            title = item.name,
            summary = item.description.takeIf { it.isNotBlank() },
            startAction = {
                ContributorAvatar(
                    avatarUrl = item.avatar,
                    placeholderAlpha = avatarPlaceholderAlpha,
                )
            },
            onClick = link?.let { targetLink ->
                {
                    context.startActivity(Intent(Intent.ACTION_VIEW, targetLink.toUri()))
                }
            },
            endActions = {
                if (hasLink) {
                    Icon(
                        imageVector = MiuixIcons.Link,
                        tint = colorScheme.onSurface,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

@Composable
private fun rememberVersionText(): String {
    return "${AppProperties.PROJECT_APP_VERSION_NAME}-${AppProperties.GIT_HASH}-r${AppProperties.BUILD_NUMBER}-${AppProperties.BUILD_CHANNEL}"
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String?,
    placeholderAlpha: Float,
    modifier: Modifier = Modifier,
) {
    var imageBitmap by remember(avatarUrl) {
        mutableStateOf(ContributorAvatarCache.peek(avatarUrl))
    }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isNullOrBlank()) {
            return@LaunchedEffect
        }

        imageBitmap = ContributorAvatarCache.load(avatarUrl)
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = null,
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(colorScheme.secondaryContainer.copy(alpha = placeholderAlpha)),
        )
    }
}
