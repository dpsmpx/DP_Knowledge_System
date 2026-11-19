package com.dpsmpx.dpks.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dpsmpx.dpks.R
import com.dpsmpx.dpks.ui.assistant.AskScreen
import com.dpsmpx.dpks.ui.journal.JournalScreen
import com.dpsmpx.dpks.ui.more.MoreScreen
import com.dpsmpx.dpks.ui.notes.NotesScreen
import com.dpsmpx.dpks.ui.review.InboxScreen

enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Notes("notes", R.string.nav_notes, Icons.Outlined.Notes),
    Inbox("inbox", R.string.nav_inbox, Icons.Outlined.Inbox),
    Journal("journal", R.string.nav_journal, Icons.Outlined.AutoStories),
    Ask("ask", R.string.nav_ask, Icons.Outlined.QuestionAnswer),
    More("more", R.string.nav_more, Icons.Outlined.MoreHoriz),
}

@Composable
fun DpksApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                ProcessStrip()
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(dest.labelRes),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Notes.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.Notes.route) { NotesScreen() }
            composable(TopLevelDestination.Inbox.route) { InboxScreen() }
            composable(TopLevelDestination.Journal.route) { JournalScreen() }
            composable(TopLevelDestination.Ask.route) { AskScreen() }
            composable(TopLevelDestination.More.route) { MoreScreen() }
        }
    }
}

/** Non-blocking status strip above the bottom bar (jobs in 22.5). */
@Composable
private fun ProcessStrip() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = stringResource(R.string.process_strip_idle),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
