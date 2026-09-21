package com.lumina.studio.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.studio.core.data.local.DatabaseProvider
import com.lumina.studio.core.data.local.Project
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DatabaseProvider.get(application)

    val recentProjects: StateFlow<List<Project>> =
        database.projectDao().observeRecent(10)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
