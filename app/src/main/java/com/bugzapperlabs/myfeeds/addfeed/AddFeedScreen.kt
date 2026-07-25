package com.bugzapperlabs.myfeeds.addfeed

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.directory.FeedDirectoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(
    modifier: Modifier = Modifier,
    viewModel: AddFeedViewModel = hiltViewModel(),
    initialUrl: String? = null,
    onDone: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val opmlImportMessage by viewModel.opmlImportMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var url by remember { mutableStateOf(initialUrl.orEmpty()) }
    var opmlUrl by remember { mutableStateOf("") }
    var opmlText by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.let { viewModel.importOpml(it) }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is AddFeedUiState.Success) onDone()
    }

    LaunchedEffect(opmlImportMessage) {
        opmlImportMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeOpmlImportMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_feed_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.add_feed_search_heading), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                label = { Text(stringResource(R.string.add_feed_search_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (searchQuery.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    Text(
                        text = stringResource(R.string.add_feed_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        searchResults.forEach { entry ->
                            FeedDirectoryResultRow(
                                entry = entry,
                                enabled = uiState !is AddFeedUiState.Loading,
                                onAdd = { viewModel.addFromDirectory(entry) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            Text(stringResource(R.string.add_feed_by_url_heading), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.add_feed_url_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { viewModel.addFeedByUrl(url) },
                enabled = uiState !is AddFeedUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.add_feed_add_button))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

            Text(stringResource(R.string.add_feed_import_from_opml_heading), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { filePickerLauncher.launch("*/*") },
                enabled = uiState !is AddFeedUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.add_feed_choose_opml_file))
            }
            OutlinedTextField(
                value = opmlUrl,
                onValueChange = { opmlUrl = it },
                label = { Text(stringResource(R.string.add_feed_or_opml_url_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { viewModel.importOpmlFromUrl(opmlUrl) },
                enabled = uiState !is AddFeedUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.add_feed_import_from_url_button))
            }
            OutlinedTextField(
                value = opmlText,
                onValueChange = { opmlText = it },
                label = { Text(stringResource(R.string.add_feed_or_paste_opml_label)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = { viewModel.importOpmlFromText(opmlText) },
                enabled = uiState !is AddFeedUiState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.add_feed_import_from_text_button))
            }

            when (val state = uiState) {
                is AddFeedUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                is AddFeedUiState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun FeedDirectoryResultRow(entry: FeedDirectoryEntry, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entry.category,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!entry.description.isNullOrBlank()) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onAdd, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) {
            Text(stringResource(R.string.add_feed_search_add_button))
        }
    }
}
