package com.aiphotostudio

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.aiphotostudio.editgraph.EditGraph
import com.aiphotostudio.export.ExportEngine
import com.aiphotostudio.imaging.PreviewBitmapDecoder
import com.aiphotostudio.pipeline.AnalysisResult
import com.aiphotostudio.pipeline.MultiCandidateAdaptiveEditingEngine
import com.aiphotostudio.pipeline.LocalGradingRenderer
import com.aiphotostudio.pipeline.RenderGraphExecutor
import com.aiphotostudio.pipeline.RenderQualityJudge
import com.aiphotostudio.pipeline.VisualIntelligenceAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudioTheme {
                var state by remember { mutableStateOf<StudioUiState>(StudioUiState.Empty) }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        lifecycleScope.launch {
                            state = StudioUiState.Loading("Opening photo…")
                            state = loadAndEdit(uri) { progressMessage, previewBitmap ->
                                state = if (previewBitmap != null) {
                                    StudioUiState.StagePreview(progressMessage, previewBitmap)
                                } else {
                                    StudioUiState.Loading(progressMessage)
                                }
                            }
                        }
                    }
                }

                StudioScreen(
                    state = state,
                    onOpen = {
                        val ready = state as? StudioUiState.Ready
                        if (ready == null || !ready.isBusy) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    onCompareToggle = {
                        val ready = state as? StudioUiState.Ready
                        if (ready != null) {
                            state = ready.copy(showBefore = !ready.showBefore)
                        }
                    },
                    onSave = {
                        val ready = state as? StudioUiState.Ready
                        if (ready != null && !ready.isBusy) {
                            state = ready.copy(status = "Preparing full-quality save…", isBusy = true)
                            lifecycleScope.launch {
                                try {
                                    withContext(Dispatchers.Default) {
                                        val full = withContext(Dispatchers.IO) { PreviewBitmapDecoder.decodeForExport(contentResolver, ready.uri) }
                                        val rendered = LocalGradingRenderer.render(full, ready.graph)
                                        withContext(Dispatchers.IO) { ExportEngine.saveToMediaStore(contentResolver, rendered) }
                                    }
                                    state = ready.copy(status = "Saved full quality", isBusy = false)
                                } catch (t: Throwable) {
                                    state = ready.copy(status = "Save failed: ${t.message ?: "Unknown error"}", isBusy = false)
                                }
                            }
                        }
                    },
                    onShare = {
                        val ready = state as? StudioUiState.Ready
                        if (ready != null && !ready.isBusy) {
                            state = ready.copy(status = "Preparing full-quality share…", isBusy = true)
                            lifecycleScope.launch {
                                try {
                                    val uri = withContext(Dispatchers.Default) {
                                        val full = withContext(Dispatchers.IO) { PreviewBitmapDecoder.decodeForExport(contentResolver, ready.uri) }
                                        val rendered = LocalGradingRenderer.render(full, ready.graph)
                                        withContext(Dispatchers.IO) { ExportEngine.saveToShareCache(this@MainActivity, rendered) }
                                    }
                                    state = ready.copy(status = "Ready to share", isBusy = false)
                                    shareImage(uri)
                                } catch (t: Throwable) {
                                    state = ready.copy(status = "Share failed: ${t.message ?: "Unknown error"}", isBusy = false)
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun loadAndEdit(
        uri: Uri,
        onProgress: suspend (String, Bitmap?) -> Unit
    ): StudioUiState {
        val startedAt = System.currentTimeMillis()
        return try {
            onProgress("Opening photo…", null)
            delay(180)

            val original = withContext(Dispatchers.IO) { PreviewBitmapDecoder.decode(contentResolver, uri) }

            onProgress("Scanning dense pixel intelligence…", null)
            delay(220)
            val analysis = withContext(Dispatchers.Default) { VisualIntelligenceAnalyzer.analyze(original) }

            onProgress("Understanding subject and background…", original)
            delay(260)

            onProgress("Testing professional edit patterns…", original)
            val selection = withContext(Dispatchers.Default) { MultiCandidateAdaptiveEditingEngine.chooseBest(original, analysis) }
            val graph = selection.graph

            onProgress("Choosing the best natural result…", null)
            delay(260)

            val beforePreview = withContext(Dispatchers.Default) { RenderGraphExecutor.renderOriginalFrame(original, graph) }
            onProgress("Framing", beforePreview)
            delay(220)

            val lightStage = withContext(Dispatchers.Default) { RenderGraphExecutor.render(original, graph.copy(color = com.aiphotostudio.editgraph.ColorOperation(), detail = com.aiphotostudio.editgraph.DetailOperation(), local = com.aiphotostudio.editgraph.LocalLightOperation())) }
            onProgress("Shaping light", lightStage)
            delay(260)

            val localStage = withContext(Dispatchers.Default) { RenderGraphExecutor.render(original, graph.copy(color = com.aiphotostudio.editgraph.ColorOperation(), detail = com.aiphotostudio.editgraph.DetailOperation())) }
            onProgress("Enhancing subject and calming background", localStage)
            delay(300)

            val gradeStage = withContext(Dispatchers.Default) { RenderGraphExecutor.render(original, graph.copy(detail = com.aiphotostudio.editgraph.DetailOperation())) }
            onProgress("Grading color", gradeStage)
            delay(260)

            val qualityResult = withContext(Dispatchers.Default) { RenderQualityJudge.renderWithSafety(original, graph) }
            onProgress("Final polish", qualityResult.bitmap)
            val finalBeforePreview = withContext(Dispatchers.Default) { RenderGraphExecutor.renderOriginalFrame(original, qualityResult.graph) }
            delay(280)

            StudioUiState.Ready(
                uri = uri,
                original = original,
                beforePreview = finalBeforePreview,
                edited = qualityResult.bitmap,
                analysis = analysis,
                graph = qualityResult.graph,
                showBefore = false,
                status = "Tested ${selection.candidateCount} edits · ${selection.candidateName}",
                isBusy = false
            )
        } catch (t: Throwable) {
            StudioUiState.Error(t.message ?: "Could not open this photo")
        }
    }

    private fun shareImage(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share edited photo"))
    }
}

sealed interface StudioUiState {
    data object Empty : StudioUiState
    data class Loading(val message: String) : StudioUiState
    data class StagePreview(val message: String, val preview: Bitmap) : StudioUiState
    data class Error(val message: String) : StudioUiState
    data class Ready(
        val uri: Uri,
        val original: Bitmap,
        val beforePreview: Bitmap,
        val edited: Bitmap,
        val analysis: AnalysisResult,
        val graph: EditGraph,
        val showBefore: Boolean,
        val status: String,
        val isBusy: Boolean
    ) : StudioUiState
}

@Composable
private fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            surface = Color(0xFF070707),
            primary = Color.White,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
private fun StudioScreen(
    state: StudioUiState,
    onOpen: () -> Unit,
    onCompareToggle: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Header()
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF040404)),
                contentAlignment = Alignment.Center
            ) {
                Crossfade(targetState = state, label = "studio-state") { s ->
                    when (s) {
                        StudioUiState.Empty -> EmptyState(onOpen)
                        is StudioUiState.Loading -> LoadingState(s.message)
                        is StudioUiState.StagePreview -> StagePreviewState(s)
                        is StudioUiState.Error -> ErrorState(s.message, onOpen)
                        is StudioUiState.Ready -> ReadyPreview(s)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Controls(state, onOpen, onCompareToggle, onSave, onShare)
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("AI Photo Studio", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Offline automatic editor", color = Color(0xFF9A9A9A), fontSize = 12.sp)
        }
        Text("V1.3.1", color = Color(0xFF696969), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Open a photo", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Automatic professional edit. No generative AI. Original stays untouched.",
            color = Color(0xFFA8A8A8),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
        Spacer(Modifier.height(22.dp))
        PrimaryButton("Open Photo", onOpen)
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color(0xFFD0D0D0))
    }
}

@Composable
private fun ErrorState(message: String, onOpen: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn’t open photo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Color(0xFFB0B0B0), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Try Another", onOpen)
    }
}


@Composable
private fun StagePreviewState(state: StudioUiState.StagePreview) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = state.preview.asImageBitmap(),
            contentDescription = state.message,
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Fit
        )
        Text(
            state.message.uppercase(),
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                .background(Color(0xCC000000), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ReadyPreview(state: StudioUiState.Ready) {
    val bitmap = if (state.showBefore) state.beforePreview else state.edited
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = if (state.showBefore) "Before" else "After",
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Fit
        )
        Text(
            if (state.showBefore) "BEFORE" else "AFTER",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                .background(Color(0xCC000000), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun Controls(
    state: StudioUiState,
    onOpen: () -> Unit,
    onCompareToggle: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    when (state) {
        StudioUiState.Empty -> Unit
        is StudioUiState.Loading -> Text(state.message, color = Color(0xFF9A9A9A), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        is StudioUiState.StagePreview -> Text(state.message, color = Color(0xFFD0D0D0), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        is StudioUiState.Error -> Unit
        is StudioUiState.Ready -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF050505))
                    .padding(9.dp)
            ) {
                Text("Professional edit applied", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(state.graph.intentReason, color = Color(0xFF8E8E8E), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.status, color = Color(0xFFB0B0B0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.showBefore) "Original" else "Edited", color = Color(0xFF777777), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Open", onOpen, Modifier.weight(1f), enabled = !state.isBusy)
                    SecondaryButton(if (state.showBefore) "After" else "Before", onCompareToggle, Modifier.weight(1f), enabled = !state.isBusy)
                    SecondaryButton("Save", onSave, Modifier.weight(1f), enabled = !state.isBusy)
                    SecondaryButton("Share", onShare, Modifier.weight(1f), enabled = !state.isBusy)
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616), contentColor = Color.White)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center
        )
    }
}
