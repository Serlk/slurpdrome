package dev.slurpdrome.ui.components

import androidx.compose.ui.res.stringResource
import dev.slurpdrome.R
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import dev.slurpdrome.ui.LocalNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, back: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(dev.slurpdrome.ui.LocalTopInset.current))
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (back) FIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) { nav.popBackStack() } },
        actions = actions,
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
