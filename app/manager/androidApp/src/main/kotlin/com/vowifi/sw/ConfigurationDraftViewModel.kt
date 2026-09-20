package com.vowifi.sw

import androidx.lifecycle.ViewModel
import com.vowifi.sw.manager.ui.ConfigurationDraftMemory

class ConfigurationDraftViewModel : ViewModel() {
    val memory = ConfigurationDraftMemory()
}
