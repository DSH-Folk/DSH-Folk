package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

@Destination<RootGraph>
@Composable
fun ExtensionPageScreen(
    navigator: DestinationsNavigator,
    pageId: String = "",
) {
    Box(Modifier.fillMaxSize()) {
        Text(pageId)
    }
}
