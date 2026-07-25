package com.bugzapperlabs.myfeeds.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bugzapperlabs.myfeeds.ui.components.ListItemRow
import com.bugzapperlabs.myfeeds.ui.components.ReaderText

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun DesignSystemPreview() {
    MyFeedsTheme {
        Surface {
            Column(modifier = Modifier.padding(8.dp)) {
                ListItemRow(title = "MyFeeds for Windows Phone", subtitle = "News and updates", unreadCount = 20)
                ListItemRow(title = "Windows Phone Blog", subtitle = "Official Windows Phone Blog", unreadCount = 15)
                ListItemRow(title = "Already read article", isRead = true, unreadCount = 0)
                ReaderText(text = "This is sample article body text rendered in the reader typography.", modifier = Modifier.padding(16.dp))
            }
        }
    }
}
