package com.gyrobridge.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gyrobridge.app.accessibility.GyroAccessibilityService
import com.gyrobridge.app.core.AppGraph
import com.gyrobridge.app.domain.model.*
import com.gyrobridge.app.gesture.GestureDispatcherRegistry
import com.gyrobridge.app.overlay.OverlayController
import com.gyrobridge.app.profile.InstalledApp
import com.gyrobridge.app.sensor.MotionOutput
import com.gyrobridge.app.sensor.OrientationSample
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Route(val label: String) {
    HOME("Início"), PROFILES("Perfis"), PROFILE("Editar perfil"), APPS("Selecionar aplicativo"), CALIBRATION("Calibração"),
    MAPPER("Mapear controles"), DIAGNOSTICS("Diagnóstico"), GYRO_PLAYGROUND("Gyro Playground"), GESTURE_PLAYGROUND("Gesture Playground"),
    PERMISSIONS("Permissões"), SETTINGS("Configurações"), LIMITATIONS("Compatibilidade")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GyroBridgeApp(vm: AppViewModel) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    fun navigateBack() { route = when(route) { Route.APPS -> Route.PROFILE; Route.PROFILE -> Route.PROFILES; else -> Route.HOME } }
    BackHandler(enabled = route != Route.HOME) { navigateBack() }
    val profiles by vm.profiles.collectAsStateWithLifecycle(); val editing by vm.editingProfile.collectAsStateWithLifecycle()
    val active by vm.runtime.sessionActive.collectAsStateWithLifecycle(); val paused by vm.runtime.sessionPaused.collectAsStateWithLifecycle()
    val current by vm.runtime.activeProfile.collectAsStateWithLifecycle()
    val mainRoutes = setOf(Route.HOME, Route.PROFILES, Route.DIAGNOSTICS, Route.SETTINGS)
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (route == Route.HOME) "GyroBridge" else route.label) }, navigationIcon = { if (route !in mainRoutes) IconButton(onClick = { navigateBack() }) { Icon(Icons.Default.ArrowBack, "Voltar") } }, actions = { if (active) IconButton(onClick = vm::stop) { Icon(Icons.Default.StopCircle, "Parar", tint = MaterialTheme.colorScheme.error) } }) },
        bottomBar = { if (route in mainRoutes) NavigationBar { listOf(Route.HOME to Icons.Default.Home, Route.PROFILES to Icons.Default.Tune, Route.DIAGNOSTICS to Icons.Default.MonitorHeart, Route.SETTINGS to Icons.Default.Settings).forEach { (r, icon) -> NavigationBarItem(selected = route == r, onClick = { route = r }, icon = { Icon(icon, r.label) }, label = { Text(r.label) }) } } },
    ) { padding ->
        AnimatedContent(route, modifier = Modifier.padding(padding), label = "navigation") { target ->
            when (target) {
                Route.HOME -> HomeScreen(vm, profiles, current, active, paused) { route = it }
                Route.PROFILES -> ProfilesScreen(vm, profiles, onEdit = { vm.edit(it); route = Route.PROFILE }, onCreate = { vm.createProfile(); route = Route.PROFILE }, onImport = { })
                Route.PROFILE -> editing?.let { ProfileEditor(vm, it, onPickApp = { vm.loadApps(); route = Route.APPS }, onMapper = { route = Route.MAPPER }, onSaved = { route = Route.PROFILES }) } ?: EmptyState("Nenhum perfil selecionado")
                Route.APPS -> AppPicker(vm) { route = Route.PROFILE }
                Route.CALIBRATION -> CalibrationScreen(vm, editing ?: current ?: ControlProfile()) 
                Route.MAPPER -> editing?.let { MapperScreen(vm, it) } ?: EmptyState("Abra um perfil primeiro")
                Route.DIAGNOSTICS -> DiagnosticsScreen(vm) { route = it }
                Route.GYRO_PLAYGROUND -> GyroPlayground(vm, editing ?: current ?: ControlProfile())
                Route.GESTURE_PLAYGROUND -> GesturePlayground()
                Route.PERMISSIONS -> PermissionsScreen()
                Route.SETTINGS -> SettingsScreen { route = it }
                Route.LIMITATIONS -> LimitationsScreen()
            }
        }
    }
}

@Composable private fun HomeScreen(vm: AppViewModel, profiles: List<ControlProfile>, current: ControlProfile?, active: Boolean, paused: Boolean, navigate: (Route) -> Unit) {
    val orientation by vm.runtime.orientation.collectAsStateWithLifecycle(); val sensor by vm.runtime.sensorInfo.collectAsStateWithLifecycle()
    val overlay by vm.runtime.overlayStatus.collectAsStateWithLifecycle()
    val a11yOk by vm.runtime.a11yAvailable.collectAsStateWithLifecycle()
    val sessionStatus by vm.runtime.sessionStatus.collectAsStateWithLifecycle()
    val movementState by vm.runtime.physicalMovementState.collectAsStateWithLifecycle()
    val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current; var a11yRefresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) a11yRefresh++ }; lifecycleOwner.lifecycle.addObserver(observer); onDispose { lifecycleOwner.lifecycle.removeObserver(observer) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard(active, paused, current?.name, sensor.name, overlay.visible, overlay.message, sessionStatus, movementState, active && !a11yOk) }
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Text("Orientação ao vivo", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Angle("Yaw", orientation.yaw); Angle("Pitch", orientation.pitch); Angle("Roll", orientation.roll) }
            } }
        }
        item {
            val selected = current ?: profiles.firstOrNull()
            val context = LocalContext.current
            val a11yEnabled = remember(a11yRefresh) { isAccessibilityEnabled(context) }
            Button(onClick = { selected?.let { vm.startProfile(it, launchApp = true) } }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = selected != null && !active) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (selected?.packageName == null) "INICIAR MODO MANUAL" else "INICIAR E ABRIR APLICATIVO") }
            if (!a11yEnabled && !active) { Spacer(Modifier.height(6.dp)); Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text("AccessibilityService desativado", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall); Text("Ative em Configurações para que os gestos funcionem.", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("ATIVAR") } } } }
            if (active) { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Text(" PARAR IMEDIATAMENTE") } }
        }
        item { Text("Ferramentas", style = MaterialTheme.typography.titleMedium) }
        items(listOf(
            Triple("Permissões", "Acessibilidade, overlay e notificações", Route.PERMISSIONS),
            Triple("Calibração", "Defina a posição neutra", Route.CALIBRATION), Triple("Mapear controles", "Centro e limites da câmera", Route.MAPPER),
            Triple("Gyro Playground", "Teste o pipeline sem outro app", Route.GYRO_PLAYGROUND), Triple("Gesture Playground", "Valide swipes sintéticos", Route.GESTURE_PLAYGROUND),
        )) { (title, subtitle, r) -> NavigationCard(title, subtitle) { navigate(r) } }
    }
}

@Composable private fun StatusCard(active: Boolean, paused: Boolean, profile: String?, sensor: String, overlayVisible: Boolean, overlayMessage: String?, sessionStatus: SessionStatus, movementState: PhysicalMovementState, a11yMissing: Boolean = false) {
    val color = when { sessionStatus == SessionStatus.ERROR || a11yMissing -> MaterialTheme.colorScheme.error; paused -> MaterialTheme.colorScheme.tertiary; active -> Color(0xFF22C55E); else -> MaterialTheme.colorScheme.outline }
    val statusLabel = when (sessionStatus) {
        SessionStatus.STOPPED -> "Gyro desligado"
        SessionStatus.PAUSED -> "Gyro pronto — toque em iniciar"
        SessionStatus.WAITING_ACCESSIBILITY -> "Aguardando acessibilidade"
        SessionStatus.WAITING_SENSOR -> "Aguardando sensor"
        SessionStatus.CALIBRATING -> "Calibrando — mantenha o aparelho parado"
        SessionStatus.ACTIVE -> "Gyro funcionando"
        SessionStatus.ERROR -> "Sensor indisponível"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(color, CircleShape)); Spacer(Modifier.width(8.dp)); Text(statusLabel, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp)); Text("Perfil: ${profile ?: "Nenhum perfil"}"); Text("Sensor: $sensor", style = MaterialTheme.typography.bodySmall)
        if (movementState != PhysicalMovementState.STATIONARY) Text("Movimento ativo: ${if (movementState == PhysicalMovementState.FORWARD) "Frente" else "Trás"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF16A34A))
        if (a11yMissing) Text("Ative o AccessibilityService para enviar gestos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (active) Text("Overlay: ${if (overlayVisible) "visível" else overlayMessage ?: "iniciando"}", style = MaterialTheme.typography.bodySmall, color = if (overlayVisible) Color(0xFF16A34A) else MaterialTheme.colorScheme.error)
    } }
}

@Composable private fun Angle(label: String, value: Float) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, style = MaterialTheme.typography.labelMedium); Text("%+.2f°".format(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) } }
@Composable private fun NavigationCard(title: String, subtitle: String, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Default.ChevronRight, null) } } }

@Composable private fun ProfilesScreen(vm: AppViewModel, profiles: List<ControlProfile>, onEdit: (ControlProfile) -> Unit, onCreate: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current; var importMessage by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importProfile(context, it) { ok -> importMessage = if (ok) "Perfil importado" else "JSON inválido" } } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { importer.launch(arrayOf("application/json")) }) { Icon(Icons.Default.FileOpen, null); Text(" Importar JSON") }; importMessage?.let { Text(it, Modifier.align(Alignment.CenterVertically)) } } }
            if (profiles.isEmpty()) item { EmptyState("Crie um perfil para começar") }
            items(profiles, key = { it.id }) { profile -> Card(Modifier.fillMaxWidth().clickable { onEdit(profile) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SportsEsports, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(profile.name, fontWeight = FontWeight.Bold); Text(profile.appLabel ?: profile.packageName ?: "Sem aplicativo", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { vm.delete(profile) }) { Icon(Icons.Default.DeleteOutline, "Excluir") } } } }
        }
        ExtendedFloatingActionButton(onClick = onCreate, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text("Novo perfil") })
    }
}

@Composable private fun ProfileEditor(vm: AppViewModel, profile: ControlProfile, onPickApp: () -> Unit, onMapper: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current; var advanced by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportProfile(context, it, profile) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("GERAL") }
        item { OutlinedTextField(value = profile.name, onValueChange = { value -> vm.updateEditing { it.copy(name = value) } }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { NavigationCard("Aplicativo", profile.appLabel ?: profile.packageName ?: "Selecionar") { onPickApp() } }
        if (profile.packageName == null) item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Text("Modo manual: ao iniciar, o GyroBridge vai para a tela inicial. Abra o jogo desejado e este perfil permanecerá ativo.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall) } }
        item { SettingSwitch("Ativação automática", "Ativa ao detectar este pacote", profile.autoActivate) { value -> vm.updateEditing { it.copy(autoActivate = value) } } }
        item { SectionTitle("CÂMERA") }
        item { PrecisionSlider("Sensibilidade horizontal", profile.sensitivityConfig.horizontal, .01f..50f) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(horizontal = v, vertical = if (p.sensitivityConfig.linked) v else p.sensitivityConfig.vertical)) } } }
        item { PrecisionSlider("Sensibilidade vertical", profile.sensitivityConfig.vertical, .01f..50f) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(vertical = v)) } } }
        item { SettingSwitch("Usar mesma sensibilidade X/Y", null, profile.sensitivityConfig.linked) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(linked = v, vertical = if (v) p.sensitivityConfig.horizontal else p.sensitivityConfig.vertical)) } } }
        item { PrecisionSlider("Deadzone horizontal (°)", profile.sensitivityConfig.horizontalDeadzone, 0f..5f) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(horizontalDeadzone = v)) } } }
        item { PrecisionSlider("Deadzone vertical (°)", profile.sensitivityConfig.verticalDeadzone, 0f..5f) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(verticalDeadzone = v)) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = profile.axisConfig.invertX, onClick = { vm.updateEditing { p -> p.copy(axisConfig = p.axisConfig.copy(invertX = !p.axisConfig.invertX)) } }, label = { Text("Inverter X") }); FilterChip(selected = profile.axisConfig.invertY, onClick = { vm.updateEditing { p -> p.copy(axisConfig = p.axisConfig.copy(invertY = !p.axisConfig.invertY)) } }, label = { Text("Inverter Y") }) } }
        item { NavigationCard("Região da câmera", "${(profile.cameraZone.centerX*100).roundToInt()}% × ${(profile.cameraZone.centerY*100).roundToInt()}%") { onMapper() } }
        item { SectionTitle("MOVIMENTO FÍSICO") }
        item { SettingSwitch("Movimento físico", "Mantém um segundo toque no joystick enquanto você anda", profile.physicalMovement.enabled) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(enabled = v)) } } }
        if (profile.physicalMovement.enabled) {
            item { SettingSwitch("Frente", null, profile.physicalMovement.forwardEnabled) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(forwardEnabled = v)) } } }
            item { SettingSwitch("Trás", null, profile.physicalMovement.backwardEnabled) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(backwardEnabled = v)) } } }
            item { PrecisionSlider("Limiar do passo", profile.physicalMovement.threshold, .05f..3f) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(threshold = v)) } } }
            item { PrecisionSlider("Sensibilidade do movimento", profile.physicalMovement.sensitivity, .1f..4f) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(sensitivity = v)) } } }
            item { PrecisionSlider("Tempo para parar (ms)", profile.physicalMovement.stopTimeoutMs.toFloat(), 100f..1500f, 0) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(stopTimeoutMs = v.roundToInt().toLong())) } } }
            item { PrecisionSlider("Força do joystick", profile.physicalMovement.joystickStrength * 100f, 10f..100f, 0) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(joystickStrength = v / 100f)) } } }
            item { NavigationCard("Área de movimento", "${(profile.physicalMovement.zone.centerX*100).roundToInt()}% × ${(profile.physicalMovement.zone.centerY*100).roundToInt()}%") { onMapper() } }
        }
        item { SectionTitle("FILTRAGEM") }
        item { ChoiceChips(FilterType.entries, profile.filterConfig.xFilter, { it.name.replace('_',' ') }) { v -> vm.updateEditing { p -> p.copy(filterConfig = p.filterConfig.copy(xFilter = v, yFilter = v)) } } }
        item { PrecisionSlider("Suavização", profile.filterConfig.smoothing * 100f, 0f..100f) { v -> vm.updateEditing { p -> p.copy(filterConfig = p.filterConfig.copy(smoothing = v/100f)) } }; if (profile.filterConfig.smoothing > .75f) Text("Suavização alta aumenta a latência.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        item { SectionTitle("SENSOR") }
        item { ChoiceChips(listOf(SensorRate.AUTOMATIC,SensorRate.GAME,SensorRate.FASTEST,SensorRate.CUSTOM),profile.sensorConfig.rate,{it.name}) { v -> vm.updateEditing { p -> p.copy(sensorConfig=p.sensorConfig.copy(rate=v)) } } }
        if(profile.sensorConfig.rate==SensorRate.CUSTOM) item { PrecisionSlider("Taxa solicitada (Hz)",profile.sensorConfig.customHz.toFloat(),50f..400f,0) { v -> vm.updateEditing { p -> p.copy(sensorConfig=p.sensorConfig.copy(customHz=v.roundToInt())) } } }
        item { SectionTitle("GESTOS") }
        item { PrecisionSlider("Gestos por segundo", profile.gestureConfig.targetRate.toFloat(), 15f..120f, decimals = 0) { v -> vm.updateEditing { p -> p.copy(gestureConfig = p.gestureConfig.copy(targetRate = v.roundToInt())) } } }
        item { SectionTitle("OVERLAY") }
        item { SettingSwitch("Mostrar overlay", "Requer permissão sobre outros apps", profile.overlayConfig.enabled) { v -> vm.updateEditing { p -> p.copy(overlayConfig = p.overlayConfig.copy(enabled = v)) } } }
        item { TextButton(onClick = { advanced = !advanced }) { Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null); Text(" Opções avançadas") } }
        if (advanced) {
            item { ChoiceChips(AxisSource.entries, profile.axisConfig.xSource, { "$it → X" }) { v -> vm.updateEditing { p -> p.copy(axisConfig = p.axisConfig.copy(xSource = v)) } } }
            item { ChoiceChips(AxisSource.entries, profile.axisConfig.ySource, { "$it → Y" }) { v -> vm.updateEditing { p -> p.copy(axisConfig = p.axisConfig.copy(ySource = v)) } } }
            item { PrecisionSlider("Gamma", profile.sensitivityConfig.gamma, .2f..4f) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig = p.sensitivityConfig.copy(gamma = v, curve = ResponseCurve.POWER)) } } }
            item { ChoiceChips(ResponseCurve.entries,profile.sensitivityConfig.curve,{it.name.replace('_',' ')}) { v -> vm.updateEditing { p -> p.copy(sensitivityConfig=p.sensitivityConfig.copy(curve=v)) } } }
            item { PrecisionSlider("Máx. movimento por atualização", profile.gestureConfig.maxXPerUpdate, 1f..300f) { v -> vm.updateEditing { p -> p.copy(gestureConfig = p.gestureConfig.copy(maxXPerUpdate = v, maxYPerUpdate = v)) } } }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { exporter.launch("${profile.name}.gyrobridge.json") }, modifier = Modifier.weight(1f)) { Text("Exportar") }; Button(onClick = { vm.saveEditing(onSaved) }, modifier = Modifier.weight(1f)) { Text("Salvar") } } }
        item { Button(onClick = { vm.saveEditing { vm.startProfile(profile, true) } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Text(if (profile.packageName == null) " INICIAR MODO MANUAL" else " INICIAR") } }
    }
}

@Composable private fun AppPicker(vm: AppViewModel, onSelected: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle(); var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(16.dp), label = { Text("Pesquisar") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        if (apps.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn { items(filtered, key = { it.packageName }) { app -> Row(Modifier.fillMaxWidth().clickable { vm.selectApp(app); onSelected() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { AppIcon(app.icon); Spacer(Modifier.width(12.dp)); Column { Text(app.label, fontWeight = FontWeight.SemiBold); Text(app.packageName, style = MaterialTheme.typography.bodySmall) } } } }
    }
}

@Composable private fun AppIcon(drawable: Drawable) {
    val bitmap = remember(drawable) { drawable.toBitmap(64, 64).asImageBitmap() }
    androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = Modifier.size(44.dp))
}

@Composable private fun CalibrationScreen(vm: AppViewModel, profile: ControlProfile?) {
    val sample by if (vm.runtime.sessionActive.collectAsStateWithLifecycle().value) vm.runtime.orientation.collectAsStateWithLifecycle() else vm.previewSample.collectAsStateWithLifecycle()
    DisposableEffect(profile?.id) { profile?.let(vm::startPreview); onDispose(vm::stopPreview) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card { Column(Modifier.padding(20.dp)) { Text("Posição atual", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(16.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { Angle("Yaw", sample.yaw); Angle("Pitch", sample.pitch); Angle("Roll", sample.roll) } } }
        Button(onClick = { if (vm.runtime.sessionActive.value) vm.calibrate() else { vm.calibrateEditingFromPreview(); vm.saveEditing() } }, enabled = profile != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.CenterFocusStrong, null); Text(" DEFINIR POSIÇÃO ATUAL COMO CENTRO") }
        profile?.let { p ->
            Text("Offsets salvos", style = MaterialTheme.typography.titleMedium)
            Text("Yaw ${"%.2f".format(p.calibrationConfig.zeroYaw)}°   Pitch ${"%.2f".format(p.calibrationConfig.zeroPitch)}°   Roll ${"%.2f".format(p.calibrationConfig.zeroRoll)}°")
            Text("Ao tocar CALIBRAR + INICIAR, a posição atual do celular sempre se torna o centro e a primeira amostra não movimenta a câmera.", style = MaterialTheme.typography.bodySmall)
            SettingSwitch("Auto Recenter", "Intervalo: ${p.calibrationConfig.autoRecenterSeconds}s", p.calibrationConfig.autoRecenter) { v -> vm.updateEditing { it.copy(calibrationConfig = it.calibrationConfig.copy(autoRecenter = v)) } }
            SettingSwitch("Compensação de drift", null, p.calibrationConfig.driftCompensation) { v -> vm.updateEditing { it.copy(calibrationConfig = it.calibrationConfig.copy(driftCompensation = v)) } }
        }
        Text("Mantenha o aparelho imóvel na posição natural antes de calibrar. O giroscópio puro acumula drift; rotation vector é preferido quando disponível.", style = MaterialTheme.typography.bodySmall)
    }
}

private enum class MappingTarget { CAMERA, MOVEMENT }

@Composable private fun MapperScreen(vm: AppViewModel, profile: ControlProfile) {
    val cameraZone = profile.cameraZone
    val movementZone = profile.physicalMovement.zone
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val currentRotation = DisplayRotation.fromSurface((context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0)
    var target by rememberSaveable { mutableStateOf(MappingTarget.CAMERA) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Escolha uma área e arraste seu centro. Azul controla a câmera; verde mantém o toque do joystick.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = target == MappingTarget.CAMERA, onClick = { target = MappingTarget.CAMERA }, label = { Text("Área da câmera") })
            FilterChip(selected = target == MappingTarget.MOVEMENT, onClick = { target = MappingTarget.MOVEMENT }, label = { Text("Área de movimento") })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF07101E), RoundedCornerShape(18.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))) {
            Canvas(Modifier.fillMaxSize().pointerInput(profile.id, target) { detectDragGestures { change, _ ->
                change.consume()
                val x = (change.position.x / size.width).coerceIn(0f,1f)
                val y = (change.position.y / size.height).coerceIn(0f,1f)
                vm.updateEditing { p ->
                    if (target == MappingTarget.CAMERA) p.copy(cameraZone = p.cameraZone.copy(centerX = x, centerY = y, mappedDisplayRotation = currentRotation))
                    else p.copy(physicalMovement = p.physicalMovement.copy(zone = p.physicalMovement.zone.copy(centerX = x, centerY = y, mappedDisplayRotation = currentRotation)))
                }
            } }) {
                val left = (cameraZone.centerX-cameraZone.width/2f)*size.width; val top = (cameraZone.centerY-cameraZone.height/2f)*size.height
                drawRoundRect(Color(0x3322D3EE), Offset(left, top), Size(cameraZone.width*size.width, cameraZone.height*size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f), style = androidx.compose.ui.graphics.drawscope.Fill)
                drawRoundRect(Color(0xFF38BDF8), Offset(left, top), Size(cameraZone.width*size.width, cameraZone.height*size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f), style = Stroke(if (target == MappingTarget.CAMERA) 5f else 3f))
                drawLine(Color(0xFF38BDF8), Offset(left, cameraZone.centerY*size.height), Offset(left+cameraZone.width*size.width, cameraZone.centerY*size.height), strokeWidth = 2f)
                drawLine(Color(0xFF38BDF8), Offset(cameraZone.centerX*size.width, top), Offset(cameraZone.centerX*size.width, top+cameraZone.height*size.height), strokeWidth = 2f)
                val movementCenter = Offset(movementZone.centerX*size.width, movementZone.centerY*size.height)
                val movementRadius = movementZone.radius * minOf(size.width, size.height)
                drawCircle(Color(0x3334D399), movementRadius, movementCenter)
                drawCircle(Color(0xFF4ADE80), movementRadius, movementCenter, style = Stroke(if (target == MappingTarget.MOVEMENT) 5f else 3f))
                drawLine(Color(0xFF4ADE80), Offset(movementCenter.x, movementCenter.y-movementRadius*.85f), Offset(movementCenter.x, movementCenter.y+movementRadius*.85f), strokeWidth = 3f)
                drawCircle(Color.White, 10f, if (target == MappingTarget.CAMERA) Offset(cameraZone.centerX*size.width, cameraZone.centerY*size.height) else movementCenter)
            }
        }
        if (target == MappingTarget.CAMERA) {
            Text("Câmera X ${(cameraZone.centerX*100).roundToInt()}%   Y ${(cameraZone.centerY*100).roundToInt()}%")
            PrecisionSlider("Largura da área", cameraZone.width*100f, 5f..100f, 0) { v -> vm.updateEditing { it.copy(cameraZone = it.cameraZone.copy(width = v/100f, mappedDisplayRotation = currentRotation)) } }
            PrecisionSlider("Altura da área", cameraZone.height*100f, 5f..100f, 0) { v -> vm.updateEditing { it.copy(cameraZone = it.cameraZone.copy(height = v/100f, mappedDisplayRotation = currentRotation)) } }
        } else {
            Text("Movimento X ${(movementZone.centerX*100).roundToInt()}%   Y ${(movementZone.centerY*100).roundToInt()}%")
            PrecisionSlider("Raio da área", movementZone.radius*100f, 3f..40f, 0) { v -> vm.updateEditing { p -> p.copy(physicalMovement = p.physicalMovement.copy(zone = p.physicalMovement.zone.copy(radius = v/100f, mappedDisplayRotation = currentRotation))) } }
        }
        Button(onClick = { vm.saveEditing() }, modifier = Modifier.fillMaxWidth()) { Text("SALVAR ÁREAS") }
    }
}

@Composable private fun DiagnosticsScreen(vm: AppViewModel, navigate: (Route) -> Unit) {
    val sensor by vm.runtime.sensorInfo.collectAsStateWithLifecycle(); val orientation by vm.runtime.orientation.collectAsStateWithLifecycle()
    val motion by vm.runtime.motion.collectAsStateWithLifecycle(); val gestures by vm.runtime.gestureMetrics.collectAsStateWithLifecycle()
    val profile by vm.runtime.activeProfile.collectAsStateWithLifecycle(); val context = LocalContext.current
    val samples = remember { mutableStateListOf<MotionOutput>() }
    LaunchedEffect(Unit) { while (true) { val m = vm.runtime.motion.value; if (samples.size >= 300) samples.removeAt(0); samples.add(m); delay(33) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DiagnosticGraph(samples) }
        item { DiagnosticCard("Sensor", listOf("Selecionado" to sensor.name, "Vendor" to sensor.vendor, "Versão" to sensor.version, "Potência" to "${sensor.power} mA", "Resolução" to sensor.resolution, "Min delay" to "${sensor.minDelayMicros} µs", "Solicitado" to "${"%.1f".format(sensor.requestedHz)} Hz", "Real" to "${"%.1f".format(sensor.actualHz)} Hz")) }
        item { DiagnosticCard("Processamento", listOf("Raw yaw" to "${"%.3f".format(orientation.yaw)}°", "Raw pitch" to "${"%.3f".format(orientation.pitch)}°", "Delta X" to "${"%+.4f".format(motion.rawX)}°", "Delta Y" to "${"%+.4f".format(motion.rawY)}°", "dx" to "${"%+.2f".format(motion.dx)} px", "dy" to "${"%+.2f".format(motion.dy)} px", "Latência" to "${motion.processingLatencyNanos/1_000_000f} ms")) }
        item { DiagnosticCard("Gesture engine", listOf("Estado" to gestures.state, "Na fila" to gestures.queued, "Enviados" to gestures.sent, "Concluídos" to gestures.completed, "Cancelados" to gestures.cancelled, "Descartados" to gestures.dropped, "Frequência efetiva" to "${"%.1f".format(gestures.effectiveHz)} Hz", "Cancelamento" to "${"%.1f".format(gestures.cancellationPercent)}%", "Latência P50/P95" to "${"%.1f".format(gestures.p50LatencyMs)} / ${"%.1f".format(gestures.p95LatencyMs)} ms")) }
        item { val dm = context.resources.displayMetrics; DiagnosticCard("Display e aplicativo", listOf("Resolução" to "${dm.widthPixels} × ${dm.heightPixels}", "Densidade" to dm.density, "Perfil" to (profile?.name ?: "—"), "Pacote" to (profile?.packageName ?: "—"), "Acessibilidade" to if (isAccessibilityEnabled(context)) "Ativa" else "Desativada", "Overlay" to if (OverlayController.canDraw(context)) "Autorizado" else "Negado")) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { navigate(Route.GYRO_PLAYGROUND) }, modifier = Modifier.weight(1f)) { Text("Gyro Playground") }; OutlinedButton(onClick = { navigate(Route.GESTURE_PLAYGROUND) }, modifier = Modifier.weight(1f)) { Text("Gesture Playground") } } }
    }
}

@Composable private fun DiagnosticGraph(samples: List<MotionOutput>) {
    Card { Column(Modifier.padding(16.dp)) { Text("Últimos 10 segundos", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(12.dp)); Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        if (samples.size < 2) return@Canvas
        fun plot(selector: (MotionOutput)->Float, color: Color) { val path = Path(); samples.forEachIndexed { index, sample -> val x = index.toFloat()/(samples.size-1)*size.width; val y = size.height/2f - selector(sample).coerceIn(-10f,10f)/10f*size.height/2f; if(index==0) path.moveTo(x,y) else path.lineTo(x,y) }; drawPath(path,color,style=Stroke(2f)) }
        drawLine(Color.Gray, Offset(0f,size.height/2), Offset(size.width,size.height/2)); plot({it.rawX},Color(0xFF38BDF8)); plot({it.filteredX},Color(0xFFA5F3FC)); plot({it.rawY},Color(0xFFF472B6)); plot({it.filteredY},Color(0xFFFBCFE8))
    }; Text("Azul X • Rosa Y • claro filtrado", style = MaterialTheme.typography.bodySmall) } }
}

@Composable private fun DiagnosticCard(title: String, entries: List<Pair<String, Any>>) { Card { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp)); entries.forEach { (key,value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(key, style = MaterialTheme.typography.bodySmall); Text(value.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) } } } } }

@Composable private fun GyroPlayground(vm: AppViewModel, profile: ControlProfile?) {
    val runtimeActive by vm.runtime.sessionActive.collectAsStateWithLifecycle(); val sample by if (runtimeActive) vm.runtime.orientation.collectAsStateWithLifecycle() else vm.previewSample.collectAsStateWithLifecycle()
    var x by remember { mutableFloatStateOf(.5f) }; var y by remember { mutableFloatStateOf(.5f) }
    val pipeline = remember(profile) { profile?.let { com.gyrobridge.app.sensor.MotionPipeline(it) } }
    DisposableEffect(profile?.id) { profile?.let(vm::startPreview); onDispose(vm::stopPreview) }
    LaunchedEffect(sample.sensorTimestampNanos) { pipeline?.process(sample)?.let { x = (x + it.dx/1000f).coerceIn(0f,1f); y = (y + it.dy/1000f).coerceIn(0f,1f) } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Usa o mesmo pipeline do controle externo: sensor → filtro → deadzone → curva → sensibilidade.")
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF07101E), RoundedCornerShape(20.dp))) { val p=Offset(x*size.width,y*size.height); drawCircle(Color(0x3338BDF8),36f,p); drawLine(Color.White,Offset(p.x-22,p.y),Offset(p.x+22,p.y),strokeWidth=4f); drawLine(Color.White,Offset(p.x,p.y-22),Offset(p.x,p.y+22),strokeWidth=4f) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { Angle("Yaw",sample.yaw); Angle("Pitch",sample.pitch); Angle("Roll",sample.roll) }
        OutlinedButton(onClick = { x=.5f;y=.5f }, modifier = Modifier.fillMaxWidth()) { Text("RECENTRALIZAR MIRA") }
    }
}

@Composable private fun GesturePlayground() {
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }; var message by remember { mutableStateOf("Arraste ou use TESTAR GESTO") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        Canvas(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF07101E), RoundedCornerShape(20.dp)).pointerInput(Unit) { detectDragGestures(onDragStart = { points=listOf(it); message="Gesto recebido" }, onDrag = { change,_ -> points=points+change.position }, onDragEnd = { message="Caminho: ${points.size} pontos" }) }) {
            if(points.size>1){ val path=Path().apply{moveTo(points.first().x,points.first().y);points.drop(1).forEach{lineTo(it.x,it.y)}};drawPath(path,Color(0xFF38BDF8),style=Stroke(6f));drawCircle(Color.Green,12f,points.first());drawCircle(Color.Red,12f,points.last()) }
        }
        Button(onClick = { val ok=GestureDispatcherRegistry.enqueue(100f,0f,System.nanoTime(),AppGraph.runtime.orientation.value); message=if(ok) "Gesto sintético enviado ao serviço" else "Ative o AccessibilityService e uma sessão primeiro" }, modifier=Modifier.fillMaxWidth()) { Text("TESTAR GESTO") }
        Text("Verde = início • azul = caminho • vermelho = fim", style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PermissionsScreen() {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current; var refresh by remember { mutableIntStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    DisposableEffect(owner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) } }
    val accessibility = remember(refresh) { isAccessibilityEnabled(context) }; val overlay = remember(refresh) { OverlayController.canDraw(context) }
    val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PermissionCard("Sensores", "Não exige permissão em tempo de execução. Taxas acima de 200 Hz usam HIGH_SAMPLING_RATE_SENSORS.", true, "Disponível") {} }
        item { PermissionCard("AccessibilityService", "Necessário para enviar swipes. O GyroBridge não lê conteúdo, senhas nem textos da tela.", accessibility, if(accessibility) "Ativo" else "Configurar") { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        item { PermissionCard("Sobrepor outros apps", "Necessário apenas para o painel flutuante e o mapeamento sobre outro aplicativo.", overlay, if(overlay) "Autorizado" else "Configurar") { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) } }
        if (overlay) item { OutlinedButton(onClick = { OverlayController.show(context) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PictureInPictureAlt, null); Text(" TESTAR OVERLAY AGORA") } }
        item { PermissionCard("Notificações", "Exibe o status e os botões Pausar, Calibrar e Parar durante a sessão.", notifications, if(notifications) "Autorizado" else "Permitir") { if(Build.VERSION.SDK_INT>=33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer)) { Text("O serviço de acessibilidade atua somente durante uma sessão ativa. Gestos sintéticos podem interferir em toques humanos e alguns aplicativos podem recusá-los.", Modifier.padding(16.dp)) } }
    }
}

@Composable private fun PermissionCard(title:String, description:String, granted:Boolean, button:String, action:()->Unit) { Card { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Row(verticalAlignment=Alignment.CenterVertically) { Icon(if(granted) Icons.Default.CheckCircle else Icons.Default.Cancel,null,tint=if(granted) Color(0xFF22C55E) else MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text(title,fontWeight=FontWeight.Bold) }; Text(description,style=MaterialTheme.typography.bodySmall); OutlinedButton(onClick=action,enabled=!granted) { Text(button) } } } }

@Composable private fun SettingsScreen(navigate:(Route)->Unit) {
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Desempenho",style=MaterialTheme.typography.titleMedium) }
        item { DiagnosticCard("Presets",listOf("Battery Saver" to "Sensor 60 Hz • gestos 30 Hz","Balanced" to "Sensor 120 Hz • gestos 45 Hz","Performance" to "Sensor 200 Hz • gestos 60 Hz","Extreme" to "Máximo solicitado, taxa real medida")) }
        item { Text("Ferramentas",style=MaterialTheme.typography.titleMedium) }
        item { NavigationCard("Permissões","Revise todos os acessos") { navigate(Route.PERMISSIONS) } }
        item { NavigationCard("Compatibilidade e limitações","APIs públicas, políticas e restrições") { navigate(Route.LIMITATIONS) } }
        item { Card { Column(Modifier.padding(16.dp)) { Text("Privacidade",fontWeight=FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("Todo o processamento é local. O app não possui permissão de internet e não envia telemetria ou conteúdo da tela.",style=MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable private fun LimitationsScreen() {
    val items=listOf(
        "Alguns aplicativos ignoram ou tratam gestos sintéticos de forma diferente.",
        "Jogos e aplicativos podem proibir ferramentas externas em seus termos; verifique as regras aplicáveis.",
        "O AccessibilityService possui finalidades e políticas específicas e nunca deve ser usado para burlar proteções.",
        "Um gesto de acessibilidade pode cancelar ou interferir em um toque humano simultâneo.",
        "A taxa de sensor solicitada não garante a taxa entregue pelo hardware; o diagnóstico mede a taxa real.",
        "Overlays podem ser bloqueados por aplicativos sensíveis ou pelo sistema.",
        "O GyroBridge não modifica memória, APK, rede, anti-cheat ou outro processo e não exige root.",
    )
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { item { Text("GyroBridge usa apenas APIs públicas do Android. Compatibilidade depende do fabricante, do aplicativo controlado e da versão do sistema.") }; items(items) { text -> Card { Row(Modifier.padding(14.dp)) { Icon(Icons.Default.Info,null,tint=MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(text,style=MaterialTheme.typography.bodyMedium) } } } }
}

@Composable private fun SectionTitle(text:String) { Text(text,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold) }

@Composable private fun SettingSwitch(title:String,subtitle:String?,checked:Boolean,onChecked:(Boolean)->Unit) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); subtitle?.let { Text(it,style=MaterialTheme.typography.bodySmall) } }; Switch(checked,onChecked) } }

@Composable private fun PrecisionSlider(label:String,value:Float,range:ClosedFloatingPointRange<Float>,decimals:Int=2,onValue:(Float)->Unit) {
    var text by remember(value) { mutableStateOf("%.${decimals}f".format(value)) }
    Column { Text(label,fontWeight=FontWeight.SemiBold); Slider(value.coerceIn(range.start,range.endInclusive),onValueChange={ onValue(it); text="%.${decimals}f".format(it) },valueRange=range); Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { IconButton(onClick={onValue((value-(range.endInclusive-range.start)/100f).coerceIn(range.start,range.endInclusive))}) { Icon(Icons.Default.Remove,null) }; OutlinedTextField(text,{input->text=input;input.replace(',','.').toFloatOrNull()?.let{onValue(it.coerceIn(range.start,range.endInclusive))}},modifier=Modifier.weight(1f),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal)); IconButton(onClick={onValue((value+(range.endInclusive-range.start)/100f).coerceIn(range.start,range.endInclusive))}) { Icon(Icons.Default.Add,null) } } }
}

@Composable private fun <T> ChoiceChips(values:List<T>,selected:T,label:(T)->String,onSelected:(T)->Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { values.forEach { value -> FilterChip(selected=selected==value,onClick={onSelected(value)},label={Text(label(value))}) } } }

@Composable private fun EmptyState(message:String) { Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center) { Text(message,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant) } }

private fun isAccessibilityEnabled(context:Context):Boolean {
    val manager=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName==context.packageName && it.resolveInfo.serviceInfo.name==GyroAccessibilityService::class.java.name }
}
