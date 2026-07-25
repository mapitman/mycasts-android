package com.bugzapperlabs.myfeeds.addfeed

sealed interface AddFeedUiState {
    data object Idle : AddFeedUiState
    data object Loading : AddFeedUiState
    data class Success(val message: String) : AddFeedUiState
    data class Error(val message: String) : AddFeedUiState
}
