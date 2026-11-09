package com.financegenie.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.financegenie.android.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @StringRes val emptyTitle: Int,
    @StringRes val emptyDescription: Int,
    val icon: ImageVector,
) {
    Overview(
        route = "overview",
        label = R.string.destination_overview,
        emptyTitle = R.string.overview_empty_title,
        emptyDescription = R.string.overview_empty_description,
        icon = Icons.Outlined.Home,
    ),
    Transactions(
        route = "transactions",
        label = R.string.destination_transactions,
        emptyTitle = R.string.transactions_empty_title,
        emptyDescription = R.string.transactions_empty_description,
        icon = Icons.AutoMirrored.Outlined.List,
    ),
    Budgets(
        route = "budgets",
        label = R.string.destination_budgets,
        emptyTitle = R.string.budgets_empty_title,
        emptyDescription = R.string.budgets_empty_description,
        icon = Icons.Outlined.DateRange,
    ),
    Settings(
        route = "settings",
        label = R.string.destination_settings,
        emptyTitle = R.string.settings_empty_title,
        emptyDescription = R.string.settings_empty_description,
        icon = Icons.Outlined.Settings,
    ),
}
