package com.example.smarttag.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.smarttag.ui.screen.BroadcastScreen
import com.example.smarttag.ui.screen.NameUpdateScreen
import com.example.smarttag.ui.screen.ScanScreen
import com.example.smarttag.ui.screen.TagDetailScreen
import com.example.smarttag.viewmodel.ScanViewModel

object Routes {
    const val SCAN        = "scan"
    const val TAG_DETAIL  = "detail/{address}"
    const val BROADCAST   = "broadcast"
    const val NAME_UPDATE = "name_update/{address}"

    fun tagDetail(address: String)   = "detail/${address.replace(":", "_")}"
    fun nameUpdate(address: String)  = "name_update/${address.replace(":", "_")}"
    fun parseAddress(encoded: String) = encoded.replace("_", ":")
}

@Composable
fun AppNavGraph(navController: NavHostController, viewModel: ScanViewModel) {
    NavHost(navController = navController, startDestination = Routes.SCAN) {
        composable(Routes.SCAN) {
            ScanScreen(
                viewModel        = viewModel,
                onTagClick       = { address -> navController.navigate(Routes.tagDetail(address)) },
                onBroadcastClick = { navController.navigate(Routes.BROADCAST) }
            )
        }
        composable(Routes.TAG_DETAIL) { backStack ->
            val encoded = backStack.arguments?.getString("address") ?: return@composable
            TagDetailScreen(
                address           = Routes.parseAddress(encoded),
                viewModel         = viewModel,
                onBack            = { navController.popBackStack() },
                onNameUpdateClick = { address -> navController.navigate(Routes.nameUpdate(address)) }
            )
        }
        composable(Routes.BROADCAST) {
            BroadcastScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
        composable(Routes.NAME_UPDATE) { backStack ->
            val encoded = backStack.arguments?.getString("address") ?: return@composable
            NameUpdateScreen(
                address   = Routes.parseAddress(encoded),
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
