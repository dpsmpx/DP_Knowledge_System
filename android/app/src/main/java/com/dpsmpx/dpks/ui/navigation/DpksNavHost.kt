package com.dpsmpx.dpks.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dpsmpx.dpks.R
import com.dpsmpx.dpks.ui.assistant.AssistantScreen
import com.dpsmpx.dpks.ui.knowledge.KnowledgeScreen
import com.dpsmpx.dpks.ui.library.LibraryScreen
import com.dpsmpx.dpks.ui.more.MoreScreen
import com.dpsmpx.dpks.ui.review.ReviewScreen

enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Library("library", R.string.nav_library, Icons.Outlined.LibraryBooks),
    Review("review", R.string.nav_review, Icons.Outlined.FactCheck),
    Knowledge("knowledge", R.string.nav_knowledge, Icons.Outlined.AutoStories),
    Assistant("assistant", R.string.nav_assistant, Icons.Outlined.Forum),
    More("more", R.string.nav_more, Icons.Outlined.MoreHoriz),
}

@Composable
fun DpksApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
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
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Library.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.Library.route) { LibraryScreen() }
            composable(TopLevelDestination.Review.route) { ReviewScreen() }
            composable(TopLevelDestination.Knowledge.route) { KnowledgeScreen() }
            composable(TopLevelDestination.Assistant.route) { AssistantScreen() }
            composable(TopLevelDestination.More.route) { MoreScreen() }
        }
    }
}
