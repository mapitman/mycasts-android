package com.bugzapperlabs.myfeeds.addfeed

sealed interface AddFeedUiState {
    data object Idle : AddFeedUiState
    data object Loading : AddFeedUiState
    data class Success(val message: String) : AddFeedUiState
    data class Error(val message: String) : AddFeedUiState
}

/** One-shot OPML import result (issue #267). [success] means the OPML parsed -- whatever came of
 * the entries in it -- so the screen should close; otherwise it stays put so the user can retry. */
data class OpmlImportFeedback(val message: String, val success: Boolean)
