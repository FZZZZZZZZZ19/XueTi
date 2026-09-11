package com.xueti.learn

import android.app.Application
import com.xueti.learn.data.ProgressStore
import com.xueti.learn.data.WordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var wordRepository: WordRepository
        private set
    lateinit var progressStore: ProgressStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        wordRepository = WordRepository(this)
        progressStore = ProgressStore(this)
        // 后台预加载词库（约 6600 词），用户点进学习页时通常已就绪
        appScope.launch { wordRepository.load() }
    }
}
