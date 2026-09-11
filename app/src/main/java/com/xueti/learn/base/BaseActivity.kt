package com.xueti.learn.base

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.xueti.learn.data.SettingsStore
import com.xueti.learn.util.BackgroundHelper
import com.xueti.learn.util.ThemeStyle

/**
 * 所有界面的基类：
 * - 创建前套用用户选择的界面风格
 * - 设置内容视图后自动应用自定义背景图
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val settings = SettingsStore(this)
        setTheme(ThemeStyle.fromKey(settings.themeStyle).styleRes)
        super.onCreate(savedInstanceState)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applyAppBackground(view)
    }

    /** 应用/刷新自定义背景（更换背景后可手动调用） */
    protected fun applyAppBackground(root: View) {
        val settings = SettingsStore(this)
        BackgroundHelper.apply(root, settings.backgroundUri, settings.backgroundDim)
    }
}
