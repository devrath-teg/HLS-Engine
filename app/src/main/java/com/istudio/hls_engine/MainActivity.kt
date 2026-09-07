package com.istudio.hls_engine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.istudio.hls_engine.ui.HlsDemoScreen
import com.istudio.hls_engine.ui.theme.HLSEngineTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HlsEngineRoot()
        }
    }
}

@Composable
private fun HlsEngineRoot() {
    HLSEngineTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            HlsDemoScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
