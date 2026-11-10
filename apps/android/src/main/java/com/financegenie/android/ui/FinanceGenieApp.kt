package com.financegenie.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financegenie.android.R
import com.financegenie.android.privacy.PrivacyUiState
import com.financegenie.android.privacy.PrivacyViewModel
import com.financegenie.android.ui.navigation.TopLevelDestination

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FinanceGenieApp(model: LedgerViewModel, privacy: PrivacyUiState, privacyModel: PrivacyViewModel, authenticate: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val formError by model.formError.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val deleting by model.deleting.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val budgetCopy by model.budgetCopy.collectAsStateWithLifecycle()
    val budgetRemoval by model.budgetRemoval.collectAsStateWithLifecycle()
    val budgetError by model.budgetError.collectAsStateWithLifecycle()
    ReportDrawnWhen { !state.loading && state.ledger != null }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: "overview"
    val nested = route in setOf("accounts", "categories", "csv", "recovery")
    val csvState by model.csv.state.collectAsStateWithLifecycle()
    val recoveryState = model.recovery?.state?.collectAsStateWithLifecycle()?.value
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(notice?.id) {
        notice?.let {
            val result = snackbars.showSnackbar(context.getString(it.message),
                actionLabel = if (it.undoGroup != null) context.getString(R.string.undo) else null,
                duration = if (it.undoGroup != null) SnackbarDuration.Long else SnackbarDuration.Short,
                withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) model.undo(it) else model.consumeNotice(it.id)
        }
    }
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = { TopAppBar(title = { Text(stringResource(when (route) {
            "accounts" -> R.string.manage_accounts
            "categories" -> R.string.manage_categories
            "csv" -> R.string.csv_import
            "recovery" -> R.string.recovery_title
            else -> R.string.app_name
        })) }, navigationIcon = {
            if (nested) TextButton(onClick = { if (route == "csv") model.csv.cancel(); if (route == "recovery") model.recovery?.cancel(); navController.popBackStack() },
                enabled = (route != "csv" || csvState.canCancel) && (route != "recovery" || recoveryState?.canCancel != false), modifier = Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
        }) },
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(modifier = Modifier.testTag("tab_${destination.route}"),
                        selected = route == destination.route || (nested && destination == TopLevelDestination.Settings),
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }, icon = { Icon(destination.icon, contentDescription = null) }, label = { Text(stringResource(destination.label)) })
                }
            }
        },
        floatingActionButton = {
            if ((route == "overview" || route == "transactions") && state.ledger != null) {
                Button(onClick = model::newTransaction, enabled = !busy, modifier = Modifier.testTag("add_transaction")) { Text(stringResource(R.string.add_transaction)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbars, modifier = Modifier.testTag("ledger_snackbar")) },
    ) { padding ->
        NavHost(navController, startDestination = "overview", modifier = Modifier.padding(padding)) {
            composable("overview") { LedgerContent(state, model::retry) { OverviewScreen(state, model,
                accounts = { navController.navigate("accounts") }, transactions = { navController.navigate("transactions") }) } }
            composable("transactions") { LedgerContent(state, model::retry) { TransactionsScreen(state, model) } }
            composable("budgets") { LedgerContent(state, model::retry) { BudgetsScreen(state, model, busy) } }
            composable("settings") { SettingsScreen(privacy, privacyModel, authenticate,
                accounts = { navController.navigate("accounts") }, categories = { navController.navigate("categories") }, imports = { navController.navigate("csv") },
                recovery = if (model.recovery == null) null else ({ navController.navigate("recovery") })) }
            composable("accounts") { LedgerContent(state, model::retry) { AccountsScreen(state, model) } }
            composable("categories") { LedgerContent(state, model::retry) { CategoriesScreen(state, model) } }
            composable("csv") { LedgerContent(state, model::retry) { CsvImportScreen(state.ledger!!, model.csv, model::editTransaction) } }
            composable("recovery") { model.recovery?.let { RecoveryScreen(it) } }
        }
    }
    if (editor != null && state.ledger != null) key(editor!!.id) {
        LedgerForm(editor!!, state.ledger!!, formError, busy, model::updateEditor, model::saveEditor, model::closeEditor)
    }
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = model::cancelDelete,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(if (deleting.size > 1) R.string.delete_linked_help else R.string.delete_help)) },
        confirmButton = { TextButton(onClick = model::confirmDelete, enabled = !busy, modifier = Modifier.testTag("confirm_delete")) {
            Text(stringResource(if (deleting.size > 1) R.string.delete_linked_action else R.string.delete))
        } },
        dismissButton = { TextButton(onClick = model::cancelDelete, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    BudgetConfirmations(budgetCopy, budgetRemoval, state.ledger, budgetError, busy, model)
}

@Composable
fun LockedScreen(state: PrivacyUiState, unlock: () -> Unit) {
    ReportDrawnWhen { !state.authenticating }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 64.dp).testTag("locked_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.unlock_description))
        Button(onClick = unlock, enabled = !state.authenticating, modifier = Modifier.testTag("unlock")) {
            Text(stringResource(if (state.authenticating) R.string.authenticating else R.string.unlock))
        }
        state.error?.let { ErrorText(it) }
    }
}
