package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.UIKit.UIControlContentHorizontalAlignmentLeading
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIDatePicker
import platform.UIKit.UIDatePickerMode
import platform.UIKit.UIDatePickerStyle
import platform.UIKit.accessibilityLabel
import platform.darwin.NSObject

private val log = Log.tag("DS/DateField")

/** A compact `UIDatePicker`: the system shows the date and opens its calendar on tap. */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformDateField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    modifier: Modifier,
    enabled: Boolean,
) {
    val onDate by rememberUpdatedState(onValueChange)
    val formatter = remember { isoDateFormatter() }
    val target = remember { DateChangeTarget { date -> onDate(formatter.stringFromDate(date)) } }
    UIKitView(
        factory = {
            UIDatePicker().apply {
                datePickerMode = UIDatePickerMode.UIDatePickerModeDate
                preferredDatePickerStyle = UIDatePickerStyle.UIDatePickerStyleCompact
                contentHorizontalAlignment = UIControlContentHorizontalAlignmentLeading
                accessibilityLabel = text.label
                addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged)
            }
        },
        modifier = modifier.fillMaxWidth().height(HbTheme.dimensions.touchTarget),
        update = { picker ->
            picker.enabled = enabled
            formatter.dateFromString(value)?.let { picker.date = it }
        },
        onRelease = { picker -> picker.removeTarget(target, null, UIControlEventValueChanged) },
        properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
    )
}

/** `YYYY-MM-DD` of the local calendar day, independent of the user's region format. */
private fun isoDateFormatter(): NSDateFormatter = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd"
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    timeZone = NSTimeZone.localTimeZone
}

/** Receives value changes of the picker; UIKit holds targets weakly, so the field remembers it. */
@OptIn(BetaInteropApi::class)
private class DateChangeTarget(private val onDate: (NSDate) -> Unit) : NSObject() {
    @ObjCAction
    fun changed(sender: UIDatePicker) {
        log.i { "Date picked" }
        onDate(sender.date)
    }
}
