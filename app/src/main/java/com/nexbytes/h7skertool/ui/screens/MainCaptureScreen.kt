package com.nexbytes.h7skertool.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.ui.components.LogViewer
import com.nexbytes.h7skertool.ui.components.UnifiedCaptureItem
import com.nexbytes.h7skertool.ui.theme.*
import com.nexbytes.h7skertool.utils.ExportUtils
import com.nexbytes.h7skertool.utils.ModFile
import com.nexbytes.h7skertool.viewmodel.AppUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainCaptureScreen(
    state: AppUiState,
    savedMods: List<ModFile>,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onSearch: (String) -> Unit,
    onFilterEndpoint: (String?) -> Unit,
    onClearCaptures: () -> Unit,
    onSaveMod: (String, String) -> Unit,
    onClearLogs: () -> Unit,
    onNavigateToDetail: (CapturedRequest) -> Unit,
    onSelectMod: (ModFile?) -> Unit,
    selectedMod: ModFile?,
    onToggleModEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onEnableAllMods: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    var tab by remember { mutableIntStateOf(0) }
    var snack by remember { mutableStateOf<String?>(null) }
    var showModPanel by remember { mutableStateOf(false) }

    val filteredRequests by remember(state.filteredRequests) { derivedStateOf { state.filteredRequests } }
    val enabledModCount = remember(savedMods) { savedMods.count { it.enabled } }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Pulsing indicator
                            val dotColor by animateColorAsState(
                                if (state.isCapturing) NeonGreen else TextDim,
                                animationSpec = tween(300), label = "dot"
                            )
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(dotColor)
                            )
                            Text(
                                "H7skER TOOL", color = NeonGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                                letterSpacing = 1.sp
                            )
                            Text("v1.0", color = TextDim, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                        }
                    },
                    actions = {
                        if (filteredRequests.isNotEmpty()) {
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(NeonGreen.copy(0.1f))
                                    .border(1.dp, NeonGreen.copy(0.2f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "${filteredRequests.size}", color = NeonGreen, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = onClearCaptures) {
                            Icon(Icons.Default.DeleteSweep, null, tint = Amber)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardBlack)
                )

                // Search bar
                OutlinedTextField(
                    value = state.searchQuery, onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        .height(46.dp),
                    placeholder = {
                        Text("Search endpoints, bodies…", color = TextDim, fontSize = 12.sp)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, tint = TextSecondary,
                            modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty())
                            IconButton(
                                onClick = { onSearch("") },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Clear, null, tint = TextSecondary,
                                    modifier = Modifier.size(16.dp))
                            }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonGreen, unfocusedBorderColor = DividerGray,
                        focusedTextColor = TextBright, unfocusedTextColor = TextPrimary,
                        cursorColor = NeonGreen
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // Endpoint filter chips
                if (state.allEndpoints.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().background(CardBlack),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = state.endpointFilter == null,
                                onClick = { onFilterEndpoint(null) },
                                label = {
                                    Text("ALL", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonGreen.copy(0.15f),
                                    selectedLabelColor = NeonGreen,
                                    containerColor = ElevatedBlack, labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = state.endpointFilter == null,
                                    selectedBorderColor = NeonGreen.copy(0.4f),
                                    borderColor = DividerGray
                                )
                            )
                        }
                        items(state.allEndpoints) { ep ->
                            FilterChip(
                                selected = state.endpointFilter == ep,
                                onClick = {
                                    onFilterEndpoint(if (state.endpointFilter == ep) null else ep)
                                },
                                label = {
                                    Text(ep.trimStart('/'), fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace)
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricBlue.copy(0.15f),
                                    selectedLabelColor = ElectricBlue,
                                    containerColor = ElevatedBlack, labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = state.endpointFilter == ep,
                                    selectedBorderColor = ElectricBlue.copy(0.4f),
                                    borderColor = DividerGray
                                )
                            )
                        }
                    }
                }

                // ── Expandable mod panel ───────────────────────────────────────
                AnimatedVisibility(
                    visible = showModPanel && savedMods.isNotEmpty(),
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(tween(200)),
                    exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(tween(150))
                ) {
                    ModPanel(
                        mods = savedMods,
                        onToggle = onToggleModEnabled,
                        onEnableAll = {
                            onEnableAllMods()
                            snack = "✓ All ${savedMods.size} mods enabled"
                        },
                        onDisableAll = {
                            savedMods.forEach { onToggleModEnabled(it.name, false) }
                            snack = "All mods disabled"
                        }
                    )
                }

                // Capture strip
                CaptureStrip(
                    capturing = state.isCapturing,
                    onStart = onStartCapture,
                    onStop = onStopCapture,
                    clientUrl = state.clientUrl,
                    enabledModCount = enabledModCount,
                    showModPanel = showModPanel,
                    onToggleModPanel = { showModPanel = !showModPanel },
                    onEnableAllMods = {
                        onEnableAllMods()
                        snack = "✓ All ${savedMods.size} mods enabled"
                    }
                )

                // Tabs
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = CardBlack, contentColor = NeonGreen,
                    indicator = { tp ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tp[tab]), color = NeonGreen
                        )
                    }
                ) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("CAPTURE", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            if (filteredRequests.isNotEmpty())
                                Badge(containerColor = NeonGreen.copy(0.1f)) {
                                    Text("${filteredRequests.size}", color = NeonGreen, fontSize = 9.sp)
                                }
                        }
                    })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("LOGS", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            if (state.logs.isNotEmpty())
                                Badge(containerColor = ElectricBlue.copy(0.1f)) {
                                    Text("${state.logs.size}", color = ElectricBlue, fontSize = 9.sp)
                                }
                        }
                    })
                }
            }
        }
    ) { pv ->
        Box(Modifier.fillMaxSize().padding(pv)) {
            when (tab) {
                0 -> CaptureList(
                    requests = filteredRequests,
                    responses = state.responses,
                    onTapItem = onNavigateToDetail,
                    onCopyReq = { req ->
                        clipboard.setText(AnnotatedString(ExportUtils.buildRequestText(req)))
                        snack = "Request copied!"
                    },
                    onCopyRes = { req ->
                        state.responses[req.id]?.let {
                            clipboard.setText(AnnotatedString(ExportUtils.buildResponseText(req, it)))
                            snack = "Response copied!"
                        }
                    },
                    onSaveMod = { req ->
                        onSaveMod(req.endpoint, req.bodyText ?: "")
                        snack = "Mod saved!"
                    }
                )
                1 -> LogViewer(logs = state.logs, onClear = onClearLogs)
            }
        }
    }

    snack?.let { msg ->
        LaunchedEffect(msg) { kotlinx.coroutines.delay(2000); snack = null }
        Box(
            Modifier.fillMaxSize().padding(bottom = 90.dp),
            Alignment.BottomCenter
        ) {
            Snackbar(containerColor = ElevatedBlack) {
                Text(msg, color = NeonGreen, fontSize = 12.sp)
            }
        }
    }
}

// ── Mod Panel — shows ALL mods with individual toggles ───────────────────────
@Composable
private fun ModPanel(
    mods: List<ModFile>,
    onToggle: (String, Boolean) -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit
) {
    val enabledCount = mods.count { it.enabled }
    Column(
        Modifier.fillMaxWidth().background(ElevatedBlack)
            .border(BorderStroke(0.5.dp, DividerGray), RoundedCornerShape(0.dp))
    ) {
        // Header row
        Row(
            Modifier.fillMaxWidth().background(CardBlack)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Build, null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                Text(
                    "MODS", color = NeonGreen, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(NeonGreen.copy(0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "$enabledCount/${mods.size} active",
                        color = NeonGreen, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onEnableAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = NeonGreen)
                ) {
                    Text("ALL ON", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDisableAll,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("ALL OFF", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Scrollable mod list
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(mods, key = { it.name }) { mod ->
                ModToggleChip(mod = mod, onToggle = { onToggle(mod.name, !mod.enabled) })
            }
        }
    }
}

@Composable
private fun ModToggleChip(mod: ModFile, onToggle: () -> Unit) {
    val enabledColor by animateColorAsState(
        if (mod.enabled) NeonGreen else TextDim,
        animationSpec = tween(250), label = "chipColor"
    )
    val bgColor by animateColorAsState(
        if (mod.enabled) NeonGreen.copy(0.12f) else CardBlack,
        animationSpec = tween(250), label = "chipBg"
    )
    val borderColor by animateColorAsState(
        if (mod.enabled) NeonGreen.copy(0.5f) else DividerGray,
        animationSpec = tween(250), label = "chipBorder"
    )

    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (mod.enabled) Icons.Default.CheckCircle else Icons.Default.Circle,
                null, tint = enabledColor, modifier = Modifier.size(14.dp)
            )
            Text(
                mod.name, color = enabledColor, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (mod.enabled) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 90.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type badge
            Box(
                Modifier.clip(RoundedCornerShape(4.dp))
                    .background(enabledColor.copy(0.12f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    mod.type.take(3), color = enabledColor, fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
            if (mod.endpoint.isNotBlank()) {
                Text(
                    mod.endpoint.trimStart('/').take(12),
                    color = TextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Capture strip with MODS button ───────────────────────────────────────────
@Composable
private fun CaptureStrip(
    capturing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    clientUrl: String,
    enabledModCount: Int,
    showModPanel: Boolean,
    onToggleModPanel: () -> Unit,
    onEnableAllMods: () -> Unit
) {
    val modBtnScale by animateDpAsState(
        if (showModPanel) 0.95.dp else 1.dp,
        animationSpec = spring(), label = "modBtn"
    )

    Row(
        Modifier.fillMaxWidth().background(CardBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        // Status info
        Column(Modifier.weight(1f)) {
            Text(
                if (capturing) "● CAPTURING" else "○ IDLE",
                color = if (capturing) NeonGreen else TextSecondary,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                clientUrl.take(38), color = TextDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            if (enabledModCount > 0)
                Text(
                    "$enabledModCount mod(s) active",
                    color = NeonGreen, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
                )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // MODS toggle button (next to Start/Stop)
            if (!capturing) {
                Box {
                    OutlinedButton(
                        onClick = onToggleModPanel,
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (showModPanel) NeonGreen else TextSecondary
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (showModPanel) NeonGreen.copy(0.6f) else DividerGray
                        )
                    ) {
                        Icon(
                            Icons.Default.Build, null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("MODS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    // Badge showing enabled count
                    if (enabledModCount > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                                .size(16.dp).clip(CircleShape)
                                .background(NeonGreen),
                            Alignment.Center
                        ) {
                            Text(
                                "$enabledModCount", color = Color.Black, fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            // Start / Stop button
            if (capturing) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRed),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, AlertRed.copy(0.5f)
                    )
                ) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (enabledModCount > 0) "Start + $enabledModCount Mod${if (enabledModCount > 1) "s" else ""}"
                        else "Start",
                        color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureList(
    requests: List<CapturedRequest>,
    responses: Map<String, CapturedResponse>,
    onTapItem: (CapturedRequest) -> Unit,
    onCopyReq: (CapturedRequest) -> Unit,
    onCopyRes: (CapturedRequest) -> Unit,
    onSaveMod: (CapturedRequest) -> Unit
) {
    if (requests.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.SignalWifiOff, null, tint = TextDim,
                    modifier = Modifier.size(48.dp))
                Text("No requests captured", color = TextDim, fontSize = 14.sp)
                Text("Start capture to intercept traffic",
                    color = TextDim.copy(0.6f), fontSize = 12.sp)
            }
        }
    } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(requests, key = { it.id }) { req ->
                UnifiedCaptureItem(
                    request = req,
                    response = responses[req.id],
                    onTap = { onTapItem(req) },
                    onCopyRequest = { onCopyReq(req) },
                    onCopyResponse = { onCopyRes(req) },
                    onOpenDecode = { onTapItem(req) },
                    onSaveMod = { onSaveMod(req) }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
