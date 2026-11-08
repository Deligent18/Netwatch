package com.example.netwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.netwatch.data.Device
import java.text.DateFormat
import java.util.Date

private sealed interface Screen {
    data object Home : Screen
    data class Detail(val key: String) : Screen
    data object Settings : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetWatchRoot(vm: MainViewModel = viewModel()) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val devices by vm.devices.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(Unit) { vm.scan() }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Home -> "NetWatch"
                            is Screen.Detail -> "Device"
                            Screen.Settings -> "Router"
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = { screen = Screen.Home }) { Text("Back") }
                },
                actions = {
                    if (screen == Screen.Home) TextButton(onClick = { screen = Screen.Settings }) { Text("Router") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Home -> DeviceList(vm, devices) { screen = Screen.Detail(it) }
                is Screen.Detail -> devices.firstOrNull { it.key == s.key }
                    ?.let { DeviceDetail(vm, it) }
                    ?: Text("Device not found", Modifier.padding(16.dp))
                Screen.Settings -> RouterSettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun DeviceList(vm: MainViewModel, devices: List<Device>, onOpen: (String) -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val online = devices.count { it.online }
    val unknown = devices.count { !it.trusted }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("$online online · ${devices.size} known", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$unknown not yet marked as trusted",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unknown > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = vm::scan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan now") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(devices, key = { it.key }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(d.key) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("●", color = if (d.online) Color(0xFF2E9E5B) else Color.Gray)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(d.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(d.ip, d.mac).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (d.blocked) Text("Banned", color = MaterialTheme.colorScheme.error)
                    else if (!d.trusted) Text("Unreviewed", color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DeviceDetail(vm: MainViewModel, d: Device) {
    val portScanning by vm.portScanning.collectAsState()
    val ports by vm.openPorts.collectAsState()
    val traffic by vm.traffic.collectAsState()
    var name by remember(d.key) { mutableStateOf(d.name ?: "") }
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(d.key) { vm.loadTraffic(d) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("Status: ${if (d.online) "online" else "offline"}")
        Text("IP: ${d.ip}")
        Text("MAC: ${d.mac ?: "unknown (set up your router to see it)"}")
        d.hostname?.let { Text("Hostname: $it") }
        Text("First seen: ${fmt.format(Date(d.firstSeen))}")
        Text("Last seen: ${fmt.format(Date(d.lastSeen))}")
        traffic?.let { (rx, tx) -> Text("Wi-Fi traffic this session: ↓ ${mb(rx)}  ↑ ${mb(tx)}") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("I recognise this device", Modifier.weight(1f))
            Switch(checked = d.trusted, onCheckedChange = { vm.setTrusted(d.key, it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nickname") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.rename(d.key, name) }) { Text("Save") }
        }

        OutlinedButton(onClick = { vm.portScan(d) }, enabled = !portScanning && d.online) {
            Text(if (portScanning) "Scanning ports…" else "Scan common ports")
        }
        ports[d.key]?.let {
            Text(if (it.isEmpty()) "No common ports open." else "Open ports: ${it.joinToString(", ")}")
        }

        Button(
            onClick = { confirm = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            enabled = d.mac != null,
        ) { Text("Disconnect & ban for 1 hour") }
        Text(
            "Works for Wi-Fi devices on a router you administer (configured under Router).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect ${d.displayName}?") },
            text = { Text("The router will drop this device and refuse it for 1 hour. Only do this on a network you own.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.disconnect(d) }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun RouterSettingsScreen(vm: MainViewModel) {
    val (u0, n0, p0) = remember { vm.routerConfig() }
    var url by remember { mutableStateOf(u0) }
    var user by remember { mutableStateOf(n0) }
    var pass by remember { mutableStateOf(p0) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Optional. Enables MAC addresses, hostnames, sleeping-device detection, traffic totals and " +
                "disconnecting devices. Currently supports OpenWrt routers. The password is stored encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Router URL (e.g. http://192.168.1.1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.saveRouter(url, user, pass) }) { Text("Save") }
    }
}
