package org.muilab.notigpt.view.component

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import org.muilab.notigpt.view.screen.HomeScreen
import org.muilab.notigpt.view.screen.SettingsScreen
import org.muilab.notigpt.viewModel.DrawerViewModel
import org.muilab.notigpt.viewModel.GPTViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabContent(
    tabData: List<Pair<String, ImageVector>>,
    pagerState: PagerState,
    context: Context,
    drawerViewModel: DrawerViewModel,
    gptViewModel: GPTViewModel
) {
    HorizontalPager(state = pagerState) { index ->
        when (index) {
            0 -> HomeScreen(context, drawerViewModel, gptViewModel)
            1 -> SettingsScreen()
            2 -> SettingsScreen()
        }
    }

}

fun getTabList(): List<Pair<String, ImageVector>> {
    return listOf(
        "Notifications" to Icons.Default.Notifications,
        "Senders" to Icons.Default.Person,
        "Settings" to Icons.Default.Settings,
    )
}