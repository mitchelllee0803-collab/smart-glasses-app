package com.smartglasses.app.feature.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.smartglasses.app.R
import com.smartglasses.app.domain.model.RecordSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The user's title, or "Record N". */
@Composable
fun RecordSummary.displayTitle(): String = customTitle ?: stringResource(R.string.record_default_title, number)

/** Date and time as in the design, e.g. 2026-03-25 12:23:00. */
fun formatRecordTime(millis: Long): String = SimpleDateFormat(RECORD_TIME_PATTERN, Locale.US).format(Date(millis))

private const val RECORD_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"
