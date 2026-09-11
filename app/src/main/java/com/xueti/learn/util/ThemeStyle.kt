package com.xueti.learn.util

import android.content.Context
import com.xueti.learn.R

/** 界面风格（配色主题） */
enum class ThemeStyle(
    val key: String,
    val labelRes: Int,
    val styleRes: Int
) {
    PURPLE("purple", R.string.style_purple, R.style.Theme_XueTi),
    BLUE("blue", R.string.style_blue, R.style.Theme_XueTi_Blue),
    GREEN("green", R.string.style_green, R.style.Theme_XueTi_Green),
    ORANGE("orange", R.string.style_orange, R.style.Theme_XueTi_Orange),
    PINK("pink", R.string.style_pink, R.style.Theme_XueTi_Pink);

    companion object {
        fun fromKey(key: String?): ThemeStyle =
            entries.firstOrNull { it.key == key } ?: PURPLE
    }
}
