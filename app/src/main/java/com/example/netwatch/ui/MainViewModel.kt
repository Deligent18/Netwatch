package com.example.netwatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netwatch.data.Device
import com.example.netwatch.data.MonitorRepository
import com.example.netwatch.scan.PortScanner
import com.example.netwatch.work.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MonitorRepository(app)

    val devices: StateFlow<List<Device>> =
        repo.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _openPorts = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    val openPorts = _openPorts.asStateFlow()

    private val _portScanning = MutableStateFlow(false)
    val portScanning = _portScanning.asStateFlow()

    private val _traffic = MutableStateFlow<Pair<Long, Long>?>(null)
    val traffic = _traffic.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            runCatching { repo.scan() }
                .onSuccess { fresh -> fresh.forEach { Notifier.newDevice(getApplication(), it) } }
                .onFailure { _message.value = "Scan failed: ${it.message}" }
            _scanning.value = false
        }
    }

    fun setTrusted(key: String, trusted: Boolean) = viewModelScope.launch { repo.setTrusted(key, trusted) }
    fun rename(key: String, name: String) = viewModelScope.launch { repo.rename(key, name) }

    fun portScan(device: Device) {
        viewModelScope.launch {
            _portScanning.value = true
            val open = PortScanner.scan(device.ip)
            _openPorts.value = _openPorts.value + (device.key to open)
            _portScanning.value = false
        }
    }

    fun loadTraffic(device: Device) {
        _traffic.value = null
        val mac = device.mac ?: return
        viewModelScope.launch { _traffic.value = repo.trafficFor(mac) }
    }

    fun disconnect(device: Device) {
        viewModelScope.launch {
            _message.value = repo.disconnect(device) ?: "${device.displayName} disconnected and banned for 1 hour."
        }
    }

    fun routerConfigured() = repo.settings.url.isNotBlank()
    fun routerConfig() = Triple(repo.settings.url, repo.settings.user, repo.settings.password)
    fun saveRouter(url: String, user: String, pass: String) {
        repo.settings.url = url
        repo.settings.user = user
        repo.settings.password = pass
        _message.value = "Router settings saved."
    }
}
