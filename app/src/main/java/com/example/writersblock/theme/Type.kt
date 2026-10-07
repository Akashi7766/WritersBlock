package com.example.writersblock.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.writersblock.R

/**
 * MedievalSharp for anything that is a name or a label — banners, titles, buttons. It is a display
 * face and tires the eye in paragraphs, so prose (notes, hints, explanations) is set in the system
 * serif, which keeps the bookish feel and stays readable at night.
 */
val Medieval = FontFamily(Font(R.font.medieval_sharp))
val Book = FontFamily.Serif

val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = Medieval, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = Medieval, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = Medieval, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Medieval, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Medieval, fontSize = 16.sp, lineHeight = 22.sp),

    bodyLarge = TextStyle(fontFamily = Book, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = Book, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Book, fontSize = 13.sp, lineHeight = 18.sp),

    labelLarge = TextStyle(fontFamily = Medieval, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = Medieval, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(
        fontFamily = Medieval,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
)
