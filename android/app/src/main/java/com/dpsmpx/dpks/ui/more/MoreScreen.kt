package com.dpsmpx.dpks.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpks.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_more)) })
        Text(
            text = stringResource(R.string.more_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.process_idle)) },
            supportingContent = { Text("Background index / analysis status") },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Backup & export") },
            supportingContent = { Text("Encrypted journal — phase 22.5") },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Theme") },
            supportingContent = { Text("System / light / dark — dynamic color on") },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("About") },
            supportingContent = { Text(stringResource(R.string.shell_banner)) },
        )
    }
}
