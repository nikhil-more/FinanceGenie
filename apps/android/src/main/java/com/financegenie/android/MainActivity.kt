package com.financegenie.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.financegenie.android.ui.FinanceGenieApp
import com.financegenie.android.ui.theme.FinanceGenieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinanceGenieTheme {
                FinanceGenieApp()
            }
        }
    }
}
