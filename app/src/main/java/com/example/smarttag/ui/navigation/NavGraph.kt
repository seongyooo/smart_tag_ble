package com.example.smarttag.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.smarttag.ui.screen.CategoryScreen
import com.example.smarttag.ui.screen.NameUpdateScreen
import com.example.smarttag.ui.screen.PriceUpdateScreen
import com.example.smarttag.ui.screen.ScanScreen
import com.example.smarttag.ui.screen.TagDetailScreen
import com.example.smarttag.viewmodel.ScanViewModel

object Routes {
    const val SCAN         = "scan"
    const val TAG_DETAIL   = "detail/{address}"
    const val PRICE_UPDATE = "price_update?groupId={groupId}"
    const val NAME_UPDATE  = "name_update/{address}"
    const val CATEGORY     = "category"

    fun tagDetail(address: String)       = "detail/${address.replace(":", "_")}"
    fun nameUpdate(address: String)      = "name_update/${address.replace(":", "_")}"
    fun priceUpdate(groupId: Int? = null) =
        if (groupId != null) "price_update?groupId=$groupId" else "price_update"

    fun parseAddress(encoded: String) = encoded.replace("_", ":")
}

@Composable
fun AppNavGraph(navController: NavHostController, viewModel: ScanViewModel) {
    NavHost(navController = navController, startDestination = Routes.SCAN) {

        composable(Routes.SCAN) {
            ScanScreen(
                viewModel             = viewModel,
                onTagClick            = { address -> navController.navigate(Routes.tagDetail(address)) },
                onPriceUpdateClick    = { groupId -> navController.navigate(Routes.priceUpdate(groupId)) },
                onCategoryManageClick = { navController.navigate(Routes.CATEGORY) }
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

        composable(
            route = Routes.PRICE_UPDATE,
            arguments = listOf(
                navArgument("groupId") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStack ->
            val groupId = backStack.arguments?.getInt("groupId")?.takeIf { it > 0 }
            PriceUpdateScreen(
                initialGroupId = groupId,
                viewModel      = viewModel,
                onBack         = { navController.popBackStack() }
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

        composable(Routes.CATEGORY) {
            CategoryScreen(
                viewModel = viewModel,
                onBack    = { navController.popBackStack() }
            )
        }
    }
}
