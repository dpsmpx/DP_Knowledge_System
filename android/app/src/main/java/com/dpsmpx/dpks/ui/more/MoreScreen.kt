package com.dpsmpx.dpks.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dpsmpx.dpks.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.more_title)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.more_processes)) },
            supportingContent = { Text(stringResource(R.string.more_processes_idle)) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.more_backup)) },
            supportingContent = { Text(stringResource(R.string.more_backup_sub)) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.more_theme)) },
            supportingContent = { Text(stringResource(R.string.more_theme_sub)) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.more_about)) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.more_about_sub),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
