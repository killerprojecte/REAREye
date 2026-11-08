package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

// A shared icon primitive: Mochi has no standalone Icon renderer. Vendor types stay internal.
@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = currentContentColor()) =
    top.yukonga.miuix.kmp.basic.Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = currentContentColor()) =
    top.yukonga.miuix.kmp.basic.Icon(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier, tint = tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = currentContentColor()) =
    top.yukonga.miuix.kmp.basic.Icon(painter = painter, contentDescription = contentDescription, modifier = modifier, tint = tint)

@Composable
fun Icon(painter: Painter, tint: ColorProducer?, contentDescription: String?, modifier: Modifier = Modifier) =
    top.yukonga.miuix.kmp.basic.Icon(painter = painter, tint = tint, contentDescription = contentDescription, modifier = modifier)
