package com.smartglasses.app.feature.home

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.permissions.AppPermissions
import com.smartglasses.app.core.ui.components.AppCard
import com.smartglasses.app.core.ui.components.FeatureCard
import com.smartglasses.app.core.ui.components.GlassesImage
import com.smartglasses.app.core.ui.components.IconCircle
import com.smartglasses.app.core.ui.components.ListRow
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.components.SectionLabel
import com.smartglasses.app.core.ui.theme.AppTheme
import com.smartglasses.app.core.ui.theme.Radius
import com.smartglasses.app.core.ui.theme.Sizes
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.BluetoothAvailability
import com.smartglasses.app.domain.model.ConnectFailure
import com.smartglasses.app.domain.model.ConnectionState
import com.smartglasses.app.domain.model.GlassesDevice
import com.smartglasses.app.domain.model.ScanState

@Composable
fun HomeScreen(
    onOpenMenu: () -> Unit,
    onOpenTranslate: () -> Unit,
    onOpenGallery: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permissions or the Bluetooth switch may change while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAvailability() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAvailability()
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAvailability()
    }

    val connected = state.connection as? ConnectionState.Connected
    var showBrightness by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HeaderCard(state.connection, onOpenMenu)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (connected != null) {
                ConnectedBar(connected.device.name, viewModel::disconnect)
                QuickControls(
                    touchpadOn = state.touchpadPanel,
                    onBrightness = { showBrightness = true },
                    onTouchpad = { viewModel.setTouchpadPanel(!state.touchpadPanel) },
                )
                SectionLabel(stringResource(R.string.section_features))
                FeatureCard(
                    icon = Icons.Filled.Translate,
                    tint = AppTheme.colors.translateIcon,
                    tileColor = AppTheme.colors.translateTile,
                    title = stringResource(R.string.feature_translate_title),
                    subtitle = stringResource(R.string.feature_translate_subtitle),
                    onClick = onOpenTranslate,
                )
                FeatureCard(
                    icon = Icons.Filled.Image,
                    tint = AppTheme.colors.galleryIcon,
                    tileColor = AppTheme.colors.galleryTile,
                    title = stringResource(R.string.feature_gallery_title),
                    subtitle = stringResource(R.string.feature_gallery_subtitle),
                    onClick = onOpenGallery,
                )
            } else {
                ConnectionCard(
                    state = state,
                    onRequestPermission = { permissionLauncher.launch(AppPermissions.bluetooth.toTypedArray()) },
                    onTurnOnBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    onOpenLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onScan = viewModel::scan,
                    onStop = viewModel::stopScan,
                    onDismissFailure = viewModel::dismissFailure,
                )
                ScanResults(state.scan, state.connection, viewModel::connect)
            }
        }
    }

    // The popup controls the connected glasses only; it closes when the link ends.
    LaunchedEffect(connected == null) { if (connected == null) showBrightness = false }
    if (showBrightness && connected != null) BrightnessDialog(onDismiss = { showBrightness = false })
}

/** Row of square shortcuts under the connection bar: brightness and the floating touchpad. */
@Composable
private fun QuickControls(touchpadOn: Boolean, onBrightness: () -> Unit, onTouchpad: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        QuickTile(Icons.Outlined.LightMode, stringResource(R.string.cd_brightness), onClick = onBrightness)
        QuickTile(Icons.Outlined.TouchApp, stringResource(R.string.cd_touchpad_toggle), selected = touchpadOn, onClick = onTouchpad)
    }
}

/** [selected]: a switch tile that is on (blue, like the reference app). Null: a plain button. */
@Composable
private fun QuickTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    val on = selected == true
    val modifier = Modifier.size(Sizes.quickTile).let {
        if (selected == null) it else it.semantics { toggleableState = ToggleableState(on); role = Role.Switch }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.tile),
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shadowElevation = Sizes.cardElevation,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (on) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** Rounded white header: menu, title, glasses image + status (disconnected) or name + battery (connected). */
@Composable
private fun HeaderCard(connection: ConnectionState, onOpenMenu: () -> Unit) {
    val connected = connection as? ConnectionState.Connected
    Surface(
        shape = RoundedCornerShape(bottomStart = Radius.header, bottomEnd = Radius.header),
        shadowElevation = Sizes.cardElevation,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.statusBarsPadding().padding(bottom = Spacing.xl)) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm)) {
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                }
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
            if (connected == null) {
                Column(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassesImage(Sizes.heroImageWidth)
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        stringResource(R.string.status_not_connected),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.muted,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassesImage(Sizes.rowImageWidth)
                    Spacer(Modifier.width(Spacing.xl))
                    Text(
                        connected.device.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    connected.battery?.let { Battery(it, connected.charging) }
                }
            }
        }
    }
}

@Composable
private fun Battery(percent: Int, charging: Boolean) {
    val description = stringResource(R.string.cd_battery, percent) + if (charging) " " + stringResource(R.string.cd_charging) else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(R.string.battery_percent, percent), style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.muted)
    }
}

@Composable
private fun ConnectedBar(name: String, onDisconnect: () -> Unit) {
    AppCard(contentPadding = Spacing.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AppTheme.colors.success)
            Spacer(Modifier.width(Spacing.md))
            Text(
                stringResource(R.string.status_connected_to, name),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.action_disconnect), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Ready to Connect" card; the same slot shows the other states (permission, Bluetooth off, connecting, failure). */
@Composable
private fun ConnectionCard(
    state: HomeUiState,
    onRequestPermission: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    val connection = state.connection
    val primary = MaterialTheme.colorScheme.primary
    val circle = AppTheme.colors.iconCircle
    AppCard {
        when {
            connection is ConnectionState.Connecting -> {
                CardHeading(Icons.Filled.Bluetooth, stringResource(R.string.connect_connecting_title), connection.device.name, loading = true)
            }
            connection is ConnectionState.Reconnecting -> {
                CardHeading(
                    Icons.Filled.Bluetooth, stringResource(R.string.connect_reconnecting_title),
                    stringResource(R.string.connect_reconnecting_subtitle, connection.device.name, connection.attempt, connection.maxAttempts),
                    loading = true,
                )
            }
            connection is ConnectionState.Failed -> {
                CardHeading(
                    Icons.Filled.ErrorOutline, stringResource(R.string.connect_failed_title),
                    failureText(connection.reason, connection.device.name), error = true,
                )
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_retry), onClick = { onDismissFailure(); onScan() })
            }
            state.availability == BluetoothAvailability.UNSUPPORTED ->
                CardHeading(Icons.Filled.BluetoothDisabled, stringResource(R.string.bt_unsupported_title), stringResource(R.string.bt_unsupported_subtitle), error = true)
            state.availability == BluetoothAvailability.PERMISSION_REQUIRED -> {
                CardHeading(Icons.Filled.Bluetooth, stringResource(R.string.bt_permission_title), stringResource(R.string.bt_permission_subtitle))
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_allow), onClick = onRequestPermission)
            }
            state.availability == BluetoothAvailability.OFF -> {
                CardHeading(Icons.Filled.BluetoothDisabled, stringResource(R.string.bt_off_title), stringResource(R.string.bt_off_subtitle))
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_turn_on_bluetooth), onClick = onTurnOnBluetooth)
            }
            state.availability == BluetoothAvailability.LOCATION_OFF -> {
                CardHeading(Icons.Filled.Bluetooth, stringResource(R.string.location_off_title), stringResource(R.string.location_off_subtitle))
                Spacer(Modifier.height(Spacing.xl))
                PrimaryButton(stringResource(R.string.action_location_settings), onClick = onOpenLocationSettings)
            }
            else -> {
                CardHeading(Icons.Filled.Bluetooth, stringResource(R.string.connect_ready_title), stringResource(R.string.status_not_connected))
                Spacer(Modifier.height(Spacing.xl))
                val scanning = state.scan is ScanState.Scanning
                PrimaryButton(
                    text = stringResource(if (scanning) R.string.action_stop else R.string.action_scan),
                    onClick = if (scanning) onStop else onScan,
                )
            }
        }
    }
}

@Composable
private fun failureText(reason: ConnectFailure, name: String): String = when (reason) {
    ConnectFailure.TIMEOUT -> stringResource(R.string.connect_failed_timeout, name)
    ConnectFailure.REJECTED -> stringResource(R.string.connect_failed_rejected, name)
    ConnectFailure.LOST -> stringResource(R.string.connect_failed_lost, name)
    ConnectFailure.BLUETOOTH_OFF -> stringResource(R.string.connect_failed_bluetooth_off)
    ConnectFailure.SDK_ERROR -> stringResource(R.string.connect_failed_sdk, name)
}

@Composable
private fun CardHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, loading: Boolean = false, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            Box(Modifier.size(Sizes.iconCircle), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            IconCircle(
                icon,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                background = AppTheme.colors.iconCircle,
            )
        }
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
        }
    }
}

/** Devices found by the scan, in the card shown under the connection card. */
@Composable
private fun ScanResults(scan: ScanState, connection: ConnectionState, onSelect: (GlassesDevice) -> Unit) {
    val devices = when (scan) {
        is ScanState.Scanning -> scan.devices
        is ScanState.Finished -> scan.devices
        else -> return
    }
    if (connection is ConnectionState.Connecting || connection is ConnectionState.Reconnecting) return
    val scanning = scan is ScanState.Scanning
    AppCard(contentPadding = Spacing.lg) {
        Row(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.md))
            }
            Text(
                stringResource(if (scanning) R.string.scan_scanning else R.string.scan_finished, devices.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (devices.isEmpty() && !scanning) {
            Text(
                stringResource(R.string.scan_none_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            )
            Text(
                stringResource(R.string.scan_none_tips),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.muted,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
        devices.forEachIndexed { index, device ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ListRow(
                icon = Icons.Filled.Bluetooth,
                title = device.name,
                subtitle = when {
                    device.simulated -> stringResource(R.string.device_test)
                    device.bonded -> stringResource(R.string.device_paired)
                    else -> device.address
                },
                onClick = { onSelect(device) },
            ) {
                device.rssi?.let {
                    Text(stringResource(R.string.device_rssi, it), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.muted)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AppTheme.colors.muted)
            }
        }
    }
}
