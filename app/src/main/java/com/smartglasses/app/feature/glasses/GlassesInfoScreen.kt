package com.smartglasses.app.feature.glasses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartglasses.app.R
import com.smartglasses.app.core.ui.components.PrimaryButton
import com.smartglasses.app.core.ui.theme.Spacing
import com.smartglasses.app.domain.model.GlassesInfo

/** About Glasses → Glasses Info: read from the glasses each time the page opens. */
@Composable
fun GlassesInfoScreen(onBack: () -> Unit, viewModel: GlassesInfoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassesPage(stringResource(R.string.glasses_info_title), onBack) {
        when (val s = state) {
            GlassesInfoState.Loading ->
                Box(Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            is GlassesInfoState.Loaded -> {
                RowsCard {
                    infoRows(s.info).forEachIndexed { index, (label, value) ->
                        if (index > 0) RowDivider()
                        DetailRow(label, value = value)
                    }
                }
            }
            GlassesInfoState.Unavailable -> {
                PageNote(stringResource(R.string.glasses_info_unavailable))
                PrimaryButton(stringResource(R.string.action_retry), onClick = viewModel::load)
            }
        }
    }
}

/** The fields the glasses filled in, in the guide's order (§4.3). */
@Composable
private fun infoRows(info: GlassesInfo): List<Pair<String, String>> = listOf(
    R.string.glasses_info_name to info.name,
    R.string.glasses_info_manufacturer to info.manufacturer,
    R.string.glasses_info_model to info.model,
    R.string.glasses_info_edition to info.edition,
    R.string.glasses_info_bluetooth_address to info.bluetoothAddress,
    R.string.glasses_info_serial to info.serialNumber,
    R.string.glasses_info_firmware to info.firmware,
    R.string.glasses_info_bluetooth_stack to info.bluetoothStack,
    R.string.glasses_info_protocol to info.protocol,
    R.string.glasses_info_ble_name to info.bleName,
    R.string.glasses_info_ble_address to info.bleAddress,
).mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { stringResource(label) to it.trim() } }
