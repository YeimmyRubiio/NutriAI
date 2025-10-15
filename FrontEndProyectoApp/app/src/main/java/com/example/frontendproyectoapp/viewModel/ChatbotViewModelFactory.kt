package com.example.frontendproyectoapp.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ChatbotViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatbotViewModel::class.java)) {
            return ChatbotViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
