package com.nexbytes.h7skertool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexbytes.h7skertool.model.CapturedRequest
import com.nexbytes.h7skertool.model.CapturedResponse
import com.nexbytes.h7skertool.ui.theme.*
import com.nexbytes.h7skertool.utils.DecodeUtils
import com.nexbytes.h7skertool.utils.HexUtils
import com.nexbytes.h7skertool.utils.ProtoField
import com.nexbytes.h7skertool.utils.ProtoModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ─── API response parser ─────────────────────────────────────────────────────
private fun parseDecodeApiResponse(raw: String?): String {
    if (raw.isNullOrBlank()) return "⚠️ Empty response from decode API"
    val t = raw.trim()
    return try {
        val j = JSONObject(t)
        listOf("decoded", "result", "data", "output", "text")
            .mapNotNull { k -> j.optString(k).ifEmpty { null } }
            .firstOrNull()?.let { DecodeUtils.prettyPrintJson(it).ifEmpty { it } }
            ?: DecodeUtils.prettyPrintJson(t).ifEmpty { t }
    } catch (_: Exception) { t }
}

// ─── Compare two JSON strings and return only changed fields ─────────────────
private fun extractChangedFields(original: String, modified: String): String {
    return try {
        val origObj = org.json.JSONObject(original)
        val modObj  = org.json.JSONObject(modified)
        val changed = org.json.JSONObject()
        val keys = modObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val origVal = if (origObj.has(k)) origObj.get(k).toString() else null
            val modVal  = modObj.get(k).toString()
            if (origVal == null || origVal != modVal) {
                changed.put(k, modObj.get(k))
            }
        }
        if (changed.length() == 0) modified // fallback: save all if nothing detected
        else changed.toString(2)
    } catch (_: Exception) { modified }
}

// ─── Count changed fields ─────────────────────────────────────────────────────
private fun countChangedFields(original: String, modified: String): Int {
    return try {
        val origObj = org.json.JSONObject(original)
        val modObj  = org.json.JSONObject(modified)
        var count = 0
        val keys = modObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val origVal = if (origObj.has(k)) origObj.get(k).toString() else null
            val modVal  = modObj.get(k).toString()
            if (origVal == null || origVal != modVal) count++
        }
        count
    } catch (_: Exception) { -1 }
}

// ─── JSON Field Editor ────────────────────────────────────────────────────────
@Composable
private fun JsonFieldEditor(
    decodedJson: String,
    originalDecodedJson: String,           // baseline for diff detection
    requestHeaders: Map<String, String>,
    onCreateMod: (name: String, fieldJson: String) -> Unit,
    onCreateHeaderMod: (name: String, headerJson: String) -> Unit
) {
    var editorTab by remember { mutableIntStateOf(0) }
    var jsonText by remember(decodedJson) { mutableStateOf(decodedJson) }
    var headersText by remember(requestHeaders) {
        mutableStateOf(requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    var jsonError by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showHeaderSaveDialog by remember { mutableStateOf(false) }
    var modName by remember { mutableStateOf("") }
    var headerModName by remember { mutableStateOf("") }

    val isJsonValid = remember(jsonText) {
        jsonText.isBlank() || DecodeUtils.isJson(jsonText.trim()) ||
        jsonText.trim().let { t -> t.startsWith("{") && runCatching { com.google.gson.JsonParser.parseString(t) }.isSuccess }
    }

    val changedCount = remember(jsonText, originalDecodedJson) {
        if (originalDecodedJson.isBlank() || jsonText.isBlank()) -1
        else countChangedFields(originalDecodedJson, jsonText)
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = editorTab, containerColor = CardBlack, contentColor = NeonGreen,
            indicator = { tp -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(tp[editorTab]), color = NeonGreen) }) {
            Tab(selected = editorTab == 0, onClick = { editorTab = 0 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.DataObject, null, modifier = Modifier.size(13.dp))
                    Text("DECODED FIELDS", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            })
            Tab(selected = editorTab == 1, onClick = { editorTab = 1 }, text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Http, null, modifier = Modifier.size(13.dp))
                    Text("HEADERS", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            })
        }

        when (editorTab) {
            0 -> {
                Column(Modifier.fillMaxSize()) {
                    // Info strip
                    Row(
                        Modifier.fillMaxWidth().background(NeonGreen.copy(0.06f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, null, tint = NeonGreen, modifier = Modifier.size(13.dp))
                        Text(
                            "Edit field values — only changes will be saved",
                            color = NeonGreen, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                jsonText = try {
                                    DecodeUtils.prettyPrintJson(jsonText).ifEmpty { jsonText }
                                } catch (_: Exception) { jsonText }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Format", color = Amber, fontSize = 10.sp)
                        }
                    }

                    // Changed fields indicator
                    AnimatedVisibility(visible = changedCount > 0) {
                        Row(
                            Modifier.fillMaxWidth().background(NeonGreen.copy(0.08f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = NeonGreen, modifier = Modifier.size(12.dp))
                            Text(
                                "$changedCount field(s) changed — only these will be saved in the mod",
                                color = NeonGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // JSON validity indicator
                    AnimatedVisibility(visible = jsonText.isNotBlank() && !isJsonValid) {
                        Row(
                            Modifier.fillMaxWidth().background(AlertRed.copy(0.08f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Error, null, tint = AlertRed, modifier = Modifier.size(12.dp))
                            Text("Invalid JSON — fix before saving", color = AlertRed,
                                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (decodedJson.isBlank()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.SearchOff, null, tint = TextDim, modifier = Modifier.size(36.dp))
                                Text("No decoded data", color = TextSecondary, fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = jsonText,
                            onValueChange = { jsonText = it; jsonError = null },
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                            textStyle = LocalTextStyle.current.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = if (isJsonValid) TextBright else AlertRed, lineHeight = 17.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (isJsonValid) NeonGreen else AlertRed,
                                unfocusedBorderColor = if (isJsonValid) DividerGray else AlertRed.copy(0.5f),
                                focusedTextColor = TextBright, unfocusedTextColor = TextPrimary,
                                cursorColor = NeonGreen,
                                focusedContainerColor = ElevatedBlack,
                                unfocusedContainerColor = ElevatedBlack
                            ),
                            shape = RoundedCornerShape(8.dp),
                            isError = !isJsonValid
                        )
                    }

                    jsonError?.let { err ->
                        Row(
                            Modifier.fillMaxWidth().background(AlertRed.copy(0.08f)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Error, null, tint = AlertRed, modifier = Modifier.size(12.dp))
                            Text(err, color = AlertRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f))
                        }
                    }

                    HorizontalDivider(color = DividerGray)
                    Row(
                        Modifier.fillMaxWidth().background(ElevatedBlack)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (changedCount > 0) "✓ $changedCount changed field(s)" else "Edit fields above",
                            color = if (changedCount > 0) NeonGreen else TextDim,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { jsonText = decodedJson },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp)); Text("Reset", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                if (!isJsonValid) {
                                    jsonError = "Cannot save: invalid JSON structure"; return@Button
                                }
                                modName = "mod_${System.currentTimeMillis()}"
                                showSaveDialog = true
                            },
                            enabled = isJsonValid && jsonText.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen,
                                disabledContainerColor = DividerGray
                            )
                        ) {
                            Icon(Icons.Default.Build, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "CREATE MOD",
                                color = Color.Black, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold, fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            1 -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().background(ElectricBlue.copy(0.06f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Http, null, tint = ElectricBlue, modifier = Modifier.size(13.dp))
                        Text("Edit HTTP headers only — never touches body",
                            color = ElectricBlue, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "Format: Header-Name: value  (one per line)",
                        color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = TextBright, lineHeight = 17.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue, unfocusedBorderColor = DividerGray,
                            focusedTextColor = TextBright, unfocusedTextColor = TextPrimary,
                            cursorColor = ElectricBlue,
                            focusedContainerColor = ElevatedBlack, unfocusedContainerColor = ElevatedBlack
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    HorizontalDivider(color = DividerGray)
                    Row(
                        Modifier.fillMaxWidth().background(ElevatedBlack)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Modifies HTTP headers only", color = TextDim, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                headerModName = "headers_${System.currentTimeMillis()}"
                                showHeaderSaveDialog = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Icon(Icons.Default.Http, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("SAVE HEADERS", color = Color.Black, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // ── Save mod name dialog ──────────────────────────────────────────────────
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = ElevatedBlack,
            shape = RoundedCornerShape(16.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Build, null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                    Text("Name this Mod", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (changedCount > 0) {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).background(NeonGreen.copy(0.08f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Info, null, tint = NeonGreen, modifier = Modifier.size(14.dp))
                            Text(
                                "Only $changedCount changed field(s) will be saved",
                                color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text("Give this modification a name:", color = TextSecondary, fontSize = 13.sp)
                    OutlinedTextField(
                        value = modName,
                        onValueChange = { modName = it },
                        singleLine = true,
                        placeholder = { Text("my_mod_name", color = TextDim, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen, unfocusedBorderColor = DividerGray,
                            focusedTextColor = TextBright, unfocusedTextColor = TextPrimary,
                            cursorColor = NeonGreen
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (modName.isBlank()) return@Button
                        // Only save changed fields vs original
                        val toSave = if (originalDecodedJson.isNotBlank() && jsonText.isNotBlank()) {
                            extractChangedFields(originalDecodedJson, jsonText)
                        } else {
                            jsonText
                        }
                        onCreateMod(modName.trim(), toSave)
                        showSaveDialog = false
                    },
                    enabled = modName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                ) {
                    Icon(Icons.Default.Save, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SAVE MOD", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showHeaderSaveDialog) {
        AlertDialog(
            onDismissRequest = { showHeaderSaveDialog = false },
            containerColor = ElevatedBlack,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text("Name this Header Mod", color = ElectricBlue, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = headerModName,
                    onValueChange = { headerModName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricBlue, unfocusedBorderColor = DividerGray,
                        focusedTextColor = TextBright, unfocusedTextColor = TextPrimary,
                        cursorColor = ElectricBlue
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            },
            confirmButton = {
                val json = headersTextToJson(headersText)
                Button(
                    onClick = {
                        if (headerModName.isBlank()) return@Button
                        onCreateHeaderMod(headerModName.trim(), json)
                        showHeaderSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("SAVE", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showHeaderSaveDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

private fun headersTextToJson(text: String): String {
    val map = mutableMapOf<String, String>()
    text.lines().forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotBlank()) map[key] = value
        }
    }
    return com.google.gson.Gson().toJson(map)
}

// ─── Pulsing animation for loading state ─────────────────────────────────────
@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ─── Main FloatingDecodeOverlay ───────────────────────────────────────────────

@Composable
fun FloatingDecodeOverlay(
    request: CapturedRequest,
    response: CapturedResponse?,
    onDismiss: () -> Unit,
    onSaveMod: (name: String, body: String) -> Unit,
    startInEditMode: Boolean = false          // when true: auto-decode → edit immediately
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val http = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    var tabIdx by remember { mutableIntStateOf(0) }
    // viewMode: 0=TEXT  1=HEX  2=DECODED  3=EDIT FIELDS
    var viewMode by remember { mutableIntStateOf(if (startInEditMode) 1 else 0) }
    var isDecoding by remember { mutableStateOf(false) }
    var isAutoDecodingForEdit by remember { mutableStateOf(startInEditMode) }
    var decodedResult by remember { mutableStateOf<String?>(null) }
    var originalDecodedSnapshot by remember { mutableStateOf("") }   // baseline for diff
    var decodeError by remember { mutableStateOf<String?>(null) }
    var protoFields by remember { mutableStateOf<List<ProtoField>>(emptyList()) }
    var isParsingFields by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }

    val currentBytes = if (tabIdx == 0) request.body else response?.body
    val currentText = if (tabIdx == 0) request.bodyText else response?.bodyText
    val currentHexDump = remember(tabIdx, request, response) {
        if (tabIdx == 0) HexUtils.toHexDump(request.body) else HexUtils.toHexDump(response?.body)
    }
    val currentHexClean = remember(tabIdx, request, response) {
        if (tabIdx == 0) request.body?.let { HexUtils.toCleanHex(it) } ?: ""
        else response?.body?.let { HexUtils.toCleanHex(it) } ?: ""
    }

    // Decode via API — returns decoded string or null
    suspend fun decodeViaApiSuspend(): String? {
        val hex = currentHexClean.ifEmpty { return null }
        return try {
            val url = if (tabIdx == 0) "http://node.mrkalpha.tech:19140/request"
                      else "http://node.mrkalpha.tech:19140/response"
            val jsonBody = JSONObject().put("hex", hex).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val resp = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).post(jsonBody).build()).execute()
            }
            val rawBody = withContext(Dispatchers.IO) { resp.body?.string() }
            parseDecodeApiResponse(rawBody)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeViaApi() {
        val hex = currentHexClean.ifEmpty { decodeError = "No hex data available"; return }
        isDecoding = true; decodeError = null; decodedResult = null
        scope.launch {
            try {
                val url = if (tabIdx == 0) "http://node.mrkalpha.tech:19140/request"
                          else "http://node.mrkalpha.tech:19140/response"
                val jsonBody = JSONObject().put("hex", hex).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val resp = withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url(url).post(jsonBody).build()).execute()
                }
                val rawBody = withContext(Dispatchers.IO) { resp.body?.string() }
                withContext(Dispatchers.Main) {
                    isDecoding = false
                    val decoded = parseDecodeApiResponse(rawBody)
                    decodedResult = decoded
                    originalDecodedSnapshot = decoded
                    viewMode = 2
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDecoding = false
                    decodeError = "Network error: ${e.message}"
                }
            }
        }
    }

    fun enterEditMode() {
        viewMode = 3
        val bytes = currentBytes
        if (bytes != null && bytes.isNotEmpty()) {
            isParsingFields = true
            scope.launch(Dispatchers.Default) {
                val fields = ProtoModifier.parseFields(bytes)
                val decoded = decodedResult ?: try {
                    val json = buildString {
                        append("{")
                        fields.forEachIndexed { i, f ->
                            val rawVal = f.rawValue.trim('"').trim()
                            val isNum = rawVal.toLongOrNull() != null
                            append("\"${f.fieldNum}\": ${if (isNum) rawVal else "\"$rawVal\""}")
                            if (i < fields.size - 1) append(", ")
                        }
                        append("}")
                    }
                    DecodeUtils.prettyPrintJson(json).ifEmpty { json }
                } catch (_: Exception) { "{}" }
                withContext(Dispatchers.Main) {
                    protoFields = fields
                    isParsingFields = false
                    if (decodedResult == null && decoded != "{}") {
                        decodedResult = decoded
                        originalDecodedSnapshot = decoded
                    }
                }
            }
        }
    }

    // ── Auto-decode then enter edit mode (for startInEditMode=true or HEX → Edit Fields) ──
    fun editFieldsFromHex() {
        if (decodedResult != null) {
            enterEditMode()
            return
        }
        isAutoDecodingForEdit = true
        decodeError = null
        scope.launch {
            try {
                val decoded = decodeViaApiSuspend()
                withContext(Dispatchers.Main) {
                    isAutoDecodingForEdit = false
                    if (decoded != null) {
                        decodedResult = decoded
                        originalDecodedSnapshot = decoded
                        enterEditMode()
                    } else {
                        // Fallback: enter edit mode without API decode (use proto parse)
                        enterEditMode()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAutoDecodingForEdit = false
                    enterEditMode()
                }
            }
        }
    }

    // Auto-trigger on open if startInEditMode
    LaunchedEffect(startInEditMode) {
        if (startInEditMode) {
            editFieldsFromHex()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.95f)
                .clip(RoundedCornerShape(18.dp))
                .background(SheetBlack)
                .border(1.dp, NeonGreen.copy(0.25f), RoundedCornerShape(18.dp))
        ) {
            // Drag handle
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(DividerGray))
            }

            // Top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                Arrangement.SpaceBetween, Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DECODE WINDOW", color = NeonGreen, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        request.endpoint, color = TextSecondary, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Back from edit mode
                    if (viewMode == 3) {
                        IconButton(onClick = { viewMode = 1 }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = NeonGreen,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = {
                            val txt = when (viewMode) {
                                1 -> currentHexDump; 2 -> decodedResult ?: ""; else -> currentText ?: ""
                            }
                            clipboard.setText(AnnotatedString(txt))
                            snack = "Copied!"
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = TextSecondary,
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextSecondary,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(color = DividerGray, thickness = 0.5.dp)

            // REQUEST / RESPONSE tabs
            TabRow(
                selectedTabIndex = tabIdx,
                containerColor = SheetBlack, contentColor = NeonGreen,
                indicator = { tp ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tp[tabIdx]), color = NeonGreen
                    )
                }
            ) {
                listOf("REQUEST", "RESPONSE").forEachIndexed { i, t ->
                    Tab(selected = tabIdx == i, onClick = {
                        tabIdx = i; decodedResult = null; decodeError = null
                        originalDecodedSnapshot = ""
                        if (viewMode == 3) viewMode = 1 else if (viewMode != 2) viewMode = 0
                    }, text = { Text(t, fontSize = 11.sp, fontFamily = FontFamily.Monospace) })
                }
            }

            // ── Auto-decode loading overlay ───────────────────────────────────
            AnimatedVisibility(
                visible = isAutoDecodingForEdit,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                Row(
                    Modifier.fillMaxWidth().background(NeonGreen.copy(0.08f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NeonGreen
                    )
                    Text(
                        "Decoding packet…", color = NeonGreen, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.weight(1f))
                    PulsingDot(NeonGreen)
                }
            }

            // View mode chips (not in edit mode, not auto-decoding)
            if (viewMode != 3 && !isAutoDecodingForEdit) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("TEXT", "HEX", "DECODED").forEachIndexed { i, m ->
                        val enabled = i != 2 || decodedResult != null
                        FilterChip(
                            selected = viewMode == i,
                            onClick = { if (enabled) viewMode = i },
                            enabled = enabled,
                            label = { Text(m, fontSize = 10.sp, fontFamily = FontFamily.Monospace) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonGreen.copy(0.12f),
                                selectedLabelColor = NeonGreen,
                                containerColor = ElevatedBlack, labelColor = TextSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = enabled, selected = viewMode == i,
                                selectedBorderColor = NeonGreen.copy(0.4f), borderColor = DividerGray
                            )
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    // Decode indicator (small) when loading
                    if (isDecoding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Amber
                        )
                    }

                    // Single "EDIT FIELDS" button — auto-decodes if needed
                    Button(
                        onClick = ::editFieldsFromHex,
                        enabled = !isDecoding,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonGreen, contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "EDIT FIELDS", color = Color.Black, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }
                }
                HorizontalDivider(color = DividerGray, thickness = 0.5.dp)
            } else if (viewMode == 3) {
                // Edit mode header
                Row(
                    Modifier.fillMaxWidth().background(NeonGreen.copy(0.06f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Edit, null, tint = NeonGreen, modifier = Modifier.size(13.dp))
                    Text(
                        "FIELD EDITOR", color = NeonGreen, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (isParsingFields) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = NeonGreen
                        )
                        Text("Parsing…", color = TextSecondary, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                    } else {
                        Text(
                            "${protoFields.size} proto fields",
                            color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                        if (decodedResult != null)
                            Text("• decoded", color = NeonGreen, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = NeonGreen.copy(0.15f))
            }

            // Error banner
            decodeError?.let { err ->
                Row(
                    Modifier.fillMaxWidth().background(AlertRed.copy(0.08f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Error, null, tint = AlertRed, modifier = Modifier.size(13.dp))
                    Text(err, color = AlertRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { decodeError = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = AlertRed, modifier = Modifier.size(11.dp))
                    }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when (viewMode) {
                3 -> {
                    val editorJson = decodedResult ?: if (protoFields.isNotEmpty()) {
                        try {
                            val json = buildString {
                                append("{")
                                protoFields.forEachIndexed { i, f ->
                                    val rawVal = f.rawValue.trim()
                                    val isNum = rawVal.toLongOrNull() != null
                                    if (i > 0) append(", ")
                                    append("\"${f.fieldNum}\": ${if (isNum) rawVal else "\"${rawVal.trim('"')}\"" }")
                                }
                                append("}")
                            }
                            DecodeUtils.prettyPrintJson(json).ifEmpty { json }
                        } catch (_: Exception) { "" }
                    } else ""

                    JsonFieldEditor(
                        decodedJson = editorJson,
                        originalDecodedJson = originalDecodedSnapshot.ifBlank { editorJson },
                        requestHeaders = if (tabIdx == 0) request.headers
                                         else response?.headers ?: emptyMap(),
                        onCreateMod = { name, json ->
                            onSaveMod(name, json)
                            snack = "✓ Mod '$name' saved!"
                            viewMode = 2
                        },
                        onCreateHeaderMod = { name, json ->
                            onSaveMod(name, json)
                            snack = "✓ Header mod '$name' saved!"
                            viewMode = 0
                        }
                    )
                }
                else -> {
                    val displayContent = when (viewMode) {
                        1 -> currentHexDump.ifEmpty { "(no hex data)" }
                        2 -> decodedResult ?: "(tap EDIT FIELDS to decode and edit)"
                        else -> currentText ?: "(empty body)"
                    }
                    val textColor = when {
                        viewMode == 1 -> Amber.copy(0.9f)
                        viewMode == 2 -> NeonGreen.copy(0.9f)
                        tabIdx == 0 -> NeonGreen.copy(0.85f)
                        else -> ElectricBlue.copy(0.85f)
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(12.dp)
                    ) {
                        Text(
                            displayContent, color = textColor, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        // Snackbar
        snack?.let { msg ->
            LaunchedEffect(msg) { kotlinx.coroutines.delay(1800); snack = null }
            Box(Modifier.fillMaxSize().padding(bottom = 8.dp), Alignment.BottomCenter) {
                Snackbar(containerColor = ElevatedBlack) {
                    Text(msg, color = NeonGreen, fontSize = 12.sp)
                }
            }
        }
    }
}
