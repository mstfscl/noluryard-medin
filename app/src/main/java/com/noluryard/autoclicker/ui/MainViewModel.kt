package com.noluryard.autoclicker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noluryard.autoclicker.AutoClickerApp
import com.noluryard.autoclicker.data.ClickerSettings
import com.noluryard.autoclicker.engine.EngineState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AutoClickerApp.settingsRepository(application)

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ClickerSettings(),
    )

    val stats = EngineState.stats
    val calibration = EngineState.calibration
    val messages = EngineState.messages

    fun update(transform: (ClickerSettings) -> ClickerSettings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
