package com.chaitanya.hingeopener.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaitanya.hingeopener.OpenerResult

/** UI state shared by the bubble and result card. Main thread only. */
class OverlayState {
    var captureCount by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var result by mutableStateOf<OpenerResult?>(null)
    var error by mutableStateOf<String?>(null)
    /** Bumped on every successful capture to replay the bubble pulse. */
    var captureTick by mutableIntStateOf(0)
}
