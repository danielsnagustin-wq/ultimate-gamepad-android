package com.ultimate.gamepad.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ultimate.gamepad.core.GamepadButtons
import com.ultimate.gamepad.core.GamepadExtraButtons
import com.ultimate.gamepad.core.HidAvailability
import com.ultimate.gamepad.core.LayoutGeometry
import com.ultimate.gamepad.core.TransportMethod
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Mando estilo Xbox Series, AMOLED (negro puro) con acentos neón.
 * Toda la pantalla es el mando: sticks asimétricos (izquierdo
 * arriba, derecho abajo, como el mando Xbox real), cruceta sobre
 * su plato, A/B/X/Y con los colores oficiales de Xbox, LB/RB,
 * LT/RT analógicos, L3/R3 (tocando el stick), VIEW a la izquierda
 * del botón Xbox, MENU a la derecha y Compartir debajo. Nada es
 * decorativo: cada botón envía su bit real por el protocolo de
 * 32 bytes.
 *
 * Lo de arriba (estado, métodos, conexión) no estorba: vive en
 * Ajustes (⚙, arriba a la derecha) y en la píldora de estado del
 * centro, que también abre Ajustes al tocarla.
 *
 * Personalización: cada elemento se puede ocultar, agrandar,
 * achicar, mover o hacer transparente desde Ajustes >
 * Personalizar botones o desde el panel visual de botones
 * (LayoutConfig); el ⚙ siempre queda visible para poder volver.
 */
/** Cómo se interactúa con el cuerpo del mando. En juego, cada
 * botón envía su entrada real; en edición (panel de botones),
 * tocar un elemento abre su ajuste y arrastrarlo lo mueve: el
 * botón en sí no envía nada. La vista previa del panel usa el
 * MISMO cuerpo que la pantalla real (GamepadBody), así lo que se
 * ve en el editor es lo que se juega: mismos tamaños, mismas
 * posiciones, misma matemática (LayoutGeometry). */
sealed interface GamepadInteraction {
    class Game(val vm: GamepadViewModel, val ui: GamepadUiState) : GamepadInteraction
    class Edit(
        val onTapElement: (String) -> Unit,
        val onDragElement: (String, Int, Int) -> Unit,
        val onDragEnd: (String) -> Unit,
        val selectedId: String?,
    ) : GamepadInteraction
}

/** Un diseño guardado, como geometría pura de :core. */
internal fun LayoutConfig.toGeomLayout(): LayoutGeometry.GeomLayout = LayoutGeometry.GeomLayout(
    leftStick = element(LayoutConfig.LEFT_STICK).toGeom(),
    dpad = element(LayoutConfig.DPAD).toGeom(),
    abxy = element(LayoutConfig.ABXY).toGeom(),
    rightStick = element(LayoutConfig.RIGHT_STICK).toGeom(),
    view = element(LayoutConfig.VIEW).toGeom(),
    xbox = element(LayoutConfig.XBOX).toGeom(),
    menu = element(LayoutConfig.MENU).toGeom(),
    share = element(LayoutConfig.SHARE).toGeom(),
    shouldersLeft = element(LayoutConfig.SHOULDERS_LEFT).toGeom(),
    shouldersRight = element(LayoutConfig.SHOULDERS_RIGHT).toGeom(),
    l4 = element(LayoutConfig.L4).toGeom(),
    r4 = element(LayoutConfig.R4).toGeom(),
)

@Composable
fun GamepadScreen(vm: GamepadViewModel) {
    val ui by vm.ui.collectAsState()
    val layout by vm.layoutConfig.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    // Segundo plano: soltar todo y parar el giroscopio; al volver,
    // reactivar. Sin esto, el polling de ping y el giroscopio seguían
    // en segundo plano y un botón pulsado al apagar la pantalla se
    // quedaba atascado en el juego.
    LaunchedEffect(lifecycleState) {
        if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) vm.onAppForegrounded()
        else vm.onAppBackgrounded()
    }
    LaunchedEffect(ui.status, lifecycleState) {
        vm.refreshHidStatus()
        vm.refreshBtDataStatus()
        vm.refreshWifiState()
        // El ping se vigila también en CONECTANDO: si no, el estado
        // CONECTANDO nunca se resolvería y la pantalla se quedaría
        // tiesa esperando un eco que nadie comprueba.
        while (ui.status == ConnectionStatus.CONNECTED || ui.status == ConnectionStatus.CONNECTING) {
            // Solo con la app en primer plano: en segundo plano el
            // fallback llamaba a startService/startForegroundService
            // y el sistema lo rechaza (BackgroundServiceStartNotAllowed).
            if (lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) vm.refreshPing()
            delay(500)
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            // Fondo del tema (por defecto negro puro AMOLED: los
            // píxeles apagados no gastan batería y el neón resalta
            // más); "Gris muy oscuro" se ve aquí también.
            .background(MaterialTheme.colorScheme.background)
            .border(
                1.5.dp,
                Brush.linearGradient(listOf(Color(0xFF00E5FF).copy(alpha = 0.35f), MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), Color(0xFF00E5FF).copy(alpha = 0.35f))),
                RoundedCornerShape(24.dp),
            )
            .padding(12.dp),
    ) {
        GamepadBody(vm, ui, layout, GamepadInteraction.Game(vm, ui), Modifier.fillMaxSize())
        if (ui.showSettings) SettingsScreen(vm, ui)
        if (ui.showLayoutEditor) LayoutEditorScreen(vm) { vm.closeLayoutEditor() }
        if (ui.showQrScanner) QrScannerScreen(onResult = { vm.connectFromQr(it) }, onClose = { vm.closeQrScanner() }, vibrationEnabled = vm.vibrationEnabled.collectAsState().value)
        // Pantallas Fase 3 (se abren desde Ajustes, vuelven a Ajustes al cerrar).
        if (ui.showControllerType) ControllerTypeScreen(vm, ui) { vm.closeControllerType() }
        if (ui.showGameProfiles) GameProfilesScreen(vm) { vm.closeGameProfiles() }
        if (ui.showControllerTest) ControllerTestScreen(vm, ui) { vm.closeControllerTest() }
        if (ui.showPerformance) PerformanceScreen(vm, ui) { vm.closePerformance() }
        // Pantalla Fase 4 (Extras: turbo, macros, vibración, giroscopio, teclado, ratón).
        if (ui.showExtras) ExtrasScreen(vm, ui) { vm.closeExtras() }
        // Pantalla "Personalizar interfaz" (ítem 3: se abre desde Ajustes, vuelve a Ajustes al cerrar).
        if (ui.showUiCustomization) UiCustomizationScreen { vm.closeUiCustomization() }
    }
}

/** Cuerpo del mando (hombros + controles), compartido por la
 * pantalla real y por la vista previa del panel de botones: la
 * misma matemática (LayoutGeometry) y los mismos composables, así
 * lo que se ve en el editor es lo que se juega. En edición no se
 * muestran los avisos (son transitorios) y ningún botón envía
 * nada: tocar abre su ajuste y arrastrar lo mueve. */
@Composable
fun GamepadBody(
    vm: GamepadViewModel,
    ui: GamepadUiState,
    layout: LayoutConfig,
    interaction: GamepadInteraction,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        val isLandscape = maxWidth > maxHeight
        // Tamaño real del mando con la escala y el desplazamiento
        // del usuario: antes se escalaba por un fijo (500 dp) y el
        // contenido, más alto que su Row (408 dp en 301 dp), se
        // cortaba por abajo — la flecha ▼ de la cruceta quedaba
        // fuera de la vista. Ahora se mide el contenido de verdad
        // (cada columna con su escala y su desplazamiento) y el
        // ajuste se calcula para que quepa entero.
        // Toda la matemática del layout vive en LayoutGeometry
        // (:core, sin Compose) y se comprueba con tests en CI para
        // varios tamaños de pantalla; aquí solo se usa el resultado.
        val geom = layout.toGeomLayout()
        val columns = LayoutGeometry.landscapeColumns(geom)
        // En vertical la escala tiene en cuenta el ancho real del
        // contenido (incluida la fila central con L4/R4, 458 dp):
        // antes solo dependía del ancho de pantalla y la fila se
        // salía por la derecha en teléfonos de 390 dp (R4 fuera
        // de la pantalla). La pantalla es alta y sobra sitio en
        // alto; el ajuste lo manda el ancho.
        val fitScale = LayoutGeometry.portrait(geom, maxWidth.value, maxHeight.value).fitScale
        // Ancho de la fila de hombros: se captura aquí porque dentro del
        // Column el receptor es ColumnScope y maxWidth ya no se puede
        // usar con receptor implícito.
        val shoulderRowWidth = maxWidth - 24.dp
        NeonBackground(Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize()) {
            ShoulderRow(vm, ui, layout, shoulderRowWidth, interaction)
            if (interaction is GamepadInteraction.Game) {
                if (ui.error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(ui.error!!, color = Color(0xFFFF5252), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                if (ui.info != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(ui.info!!, color = Color(0xFF9BF00B), fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                if (ui.status == ConnectionStatus.CONNECTED && !ui.tvResponding && ui.attemptMethod != TransportMethod.BLUETOOTH_HID) {
                    // P1-7: por HID no hay eco de ping (el HID nunca
                    // devuelve eco): sin esta exclusión, cada
                    // conexión HID correcta mostraba "TV sin
                    // respuesta" aunque el mando funcionara.
                    Spacer(Modifier.height(6.dp))
                    Text("TV sin respuesta (sin eco de ping en 3 s). En modo Automático se probará el siguiente método.", color = Color(0xFFFFC107), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                // P0-4: cancelar un intento en curso (antes no había
                // forma de parar un CONECTANDO, ni siquiera el HID
                // esperando a que la TV empareje).
                if (ui.status == ConnectionStatus.CONNECTING) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.disconnect() }) {
                        Text("Cancelar", color = Color.White)
                    }
                }
                // La TV responde al ping pero no recibe paquetes de
                // mando: el camino funciona pero la entrada no llega
                // (red, IP vieja, receptor caído). Es distinto de "sin
                // respuesta" y distinto de "recibe pero no inyecta"
                // (eso es falta de Shizuku y lo dice la TV).
                if (ui.status == ConnectionStatus.CONNECTED && ui.tvResponding && !ui.tvReceivingPackets) {
                    Spacer(Modifier.height(6.dp))
                    Text("La TV responde al ping pero no recibe paquetes de mando. Revisa la Wi-Fi o reconecta.", color = Color(0xFFFF5252), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            if (isLandscape) {
                // La fila mide lo que mide el contenido (no toda la
                // pantalla): antes medía 301 dp con un contenido de
                // 408 dp y la capa escalada recortaba lo que
                // sobresalía por abajo (la ▼). Ahora la fila es tan
                // alta como el contenido y el ajuste se calcula con
                // la altura exacta que queda tras hombros y avisos.
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val fit = LayoutGeometry.fitFor(maxWidth.value, maxHeight.value, columns.maxSpanH, columns.contentW)
                    Row(
                        modifier = Modifier.height(columns.rowH.dp).fillMaxWidth()
                            .graphicsLayer { scaleX = fit; scaleY = fit },
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LeftControls(vm, layout, columns.left.shift, interaction)
                        CenterControls(vm, layout, horizontal = false, yShift = columns.center.shift, interaction)
                        RightControls(vm, layout, columns.right.shift, interaction)
                    }
                }
            } else {
                // En vertical la escala se ancla arriba: si se
                // anclara al centro, el mando quedaría flotando
                // en medio de la pantalla con huecos arriba y abajo.
                Box(
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = fitScale
                            scaleY = fitScale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LayoutElement(layout, LayoutConfig.LEFT_STICK, 180f, 208f, interaction) { StickWithLabel(vm, left = true, interaction) }
                            LayoutElement(layout, LayoutConfig.ABXY, 200f, 200f, interaction) { AbxyPad(vm, interaction) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LayoutElement(layout, LayoutConfig.DPAD, 196f, 196f, interaction) { DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) }, interactive = interaction is GamepadInteraction.Game) }
                            LayoutElement(layout, LayoutConfig.RIGHT_STICK, 180f, 208f, interaction) { StickWithLabel(vm, left = false, interaction) }
                        }
                        Spacer(Modifier.height(12.dp))
                        CenterControls(vm, layout, horizontal = true, interaction = interaction)
                    }
                }
            }
        }
    }
}

/** Fondo neón sobre negro puro: curvas luminosas + brillos
 * suaves, como los mockups, con menos intensidad para que el
 * negro siga siendo negro (AMOLED). */
@Composable
private fun NeonBackground(modifier: Modifier = Modifier) {
    // Ronda 2 P2-11: el acento del usuario (MaterialTheme) — se
    // iza aquí porque dentro del Canvas (DrawScope) no se puede
    // leer MaterialTheme (es @Composable).
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(w * 0.15f, h * 0.2f),
                radius = w * 0.35f,
            ),
            radius = w * 0.35f,
            center = Offset(w * 0.15f, h * 0.2f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.85f),
                radius = w * 0.35f,
            ),
            radius = w * 0.35f,
            center = Offset(w * 0.85f, h * 0.85f),
        )
        val curve1 = Path().apply {
            moveTo(0f, h * 0.78f)
            quadraticBezierTo(w * 0.5f, h * 0.35f, w, h * 0.62f)
        }
        drawPath(curve1, accent.copy(alpha = 0.16f), style = Stroke(width = 2.dp.toPx()))
        val curve2 = Path().apply {
            moveTo(0f, h * 0.22f)
            quadraticBezierTo(w * 0.5f, h * 0.62f, w, h * 0.18f)
        }
        drawPath(curve2, Color(0xFF00E5FF).copy(alpha = 0.10f), style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Un elemento guardado, como geometría pura de :core. */
private fun ButtonLayout.toGeom() = LayoutGeometry.GeomElement(visible, scale, offsetXdp, offsetYdp)

/** Un elemento personalizable: oculto no se dibuja; si no, se
 * mueve, se escala y se hace transparente según el diseño
 * guardado en Ajustes.
 *
 * La caja ocupa el tamaño YA escalado (base × escala): antes
 * la escala era solo visual (graphicsLayer) y el layout seguía
 * reservando el tamaño base, así que un stick a 1.4x (252 dp
 * visuales en 180 dp de hueco) tapaba la etiqueta de abajo y
 * la cruceta. Ahora el hueco crece con la escala y nada se tapa. */
@Composable
private fun LayoutElement(
    layout: LayoutConfig,
    id: String,
    widthDp: Float,
    heightDp: Float,
    interaction: GamepadInteraction,
    content: @Composable () -> Unit,
) {
    val el = layout.element(id)
    if (!el.visible) return
    val edit = interaction as? GamepadInteraction.Edit
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size((widthDp * el.scale).dp, (heightDp * el.scale).dp)
            .offset(el.offsetXdp.dp, el.offsetYdp.dp)
            .then(
                if (edit != null) {
                    Modifier
                        .pointerInput(id) {
                            detectTapGestures(onTap = { edit.onTapElement(id) })
                        }
                        .pointerInput(id) {
                            detectDragGestures(
                                onDragEnd = { edit.onDragEnd(id) },
                                onDragCancel = { edit.onDragEnd(id) },
                            ) { change, dragAmount ->
                                change.consume()
                                // El gesto llega en píxeles locales del
                                // elemento (Compose invierte la escala
                                // global del mando al entregarlo), así
                                // que el desplazamiento es 1:1 en dp,
                                // sin dividir por el ajuste: dividirlo
                                // duplicaba el movimiento.
                                val dx = with(density) { dragAmount.x.toDp().value }.roundToInt()
                                val dy = with(density) { dragAmount.y.toDp().value }.roundToInt()
                                edit.onDragElement(id, dx, dy)
                            }
                        }
                } else Modifier
            )
            .then(
                if (edit != null && edit.selectedId == id) {
                    Modifier.border(2.dp, Color(0xFF9BF00B), RoundedCornerShape(12.dp))
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = el.scale
                scaleY = el.scale
                alpha = el.opacity
            },
        ) { content() }
    }
}

/** Hombros: LT/LB a la izquierda, RB/RT a la derecha, estado y ⚙ al centro/derecha.
 *
 * La tuerca ⚙ es fija (siempre visible, es la vuelta a Ajustes) y está a 8 dp del grupo
 * RB/RT. Si el usuario mueve ese grupo a la derecha, RT se mete debajo de la tuerca y
 * LT se sale por el borde izquierdo. El desplazamiento se recorta al dibujar (lo
 * guardado en Ajustes no cambia): cada grupo puede moverse hacia el centro todo lo que
 * quiera, pero nunca hacia la tuerca ni fuera de la pantalla. */
@Composable
private fun ShoulderRow(vm: GamepadViewModel, ui: GamepadUiState, layout: LayoutConfig, contentW: Dp, interaction: GamepadInteraction) {
    val contentWf = contentW.value
    val leftEl = layout.element(LayoutConfig.SHOULDERS_LEFT)
    val rightEl = layout.element(LayoutConfig.SHOULDERS_RIGHT)
    // El recorte vive en LayoutGeometry (:core) y se comprueba con
    // tests en CI: cada grupo puede moverse hacia el centro todo lo
    // que quiera, pero nunca hacia la tuerca ni fuera de la pantalla.
    val (leftOffX, rightOffX) = LayoutGeometry.clampShoulders(contentWf, leftEl.toGeom(), rightEl.toGeom())
    val layoutClamped = layout
        .withElement(LayoutConfig.SHOULDERS_LEFT, leftEl.copy(offsetXdp = leftOffX.toInt()))
        .withElement(LayoutConfig.SHOULDERS_RIGHT, rightEl.copy(offsetXdp = rightOffX.toInt()))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutElement(layoutClamped, LayoutConfig.SHOULDERS_LEFT, 124f, 48f, interaction) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShoulderButton("LT", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setLeftTrigger(if (pressed) 1f else 0f) }
                ShoulderButton("LB", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.LB, pressed) }
            }
        }
        StatusPill(vm, ui, interaction)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LayoutElement(layoutClamped, LayoutConfig.SHOULDERS_RIGHT, 124f, 48f, interaction) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShoulderButton("RB", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.RB, pressed) }
                    ShoulderButton("RT", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setRightTrigger(if (pressed) 1f else 0f) }
                }
            }
            GearButton({ vm.openSettings() }, interaction is GamepadInteraction.Game)
        }
    }
}

/** Píldora de estado con borde neón (tocarla abre Ajustes). */
@Composable
private fun StatusPill(vm: GamepadViewModel, ui: GamepadUiState, interaction: GamepadInteraction) {
    // P1-3: si falta el permiso de Bluetooth, la píldora lo dice
    // en vez de un "Desconectado" genérico (sin pedir el permiso
    // al arrancar y sin contexto: el botón "Dar permiso" está en
    // el selector de método).
    val (dot, text) = when {
        ui.hidAvailability == HidAvailability.PERMISSION_DENIED && ui.status == ConnectionStatus.DISCONNECTED -> Color(0xFFFFC107) to "Bluetooth: falta permiso"
        ui.status == ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) to "Conectado${ui.pingMs?.let { " · $it ms" } ?: ""}"
        ui.status == ConnectionStatus.CONNECTING -> Color(0xFFFFC107) to "Conectando por ${ui.attemptMethod.label}…"
        ui.status == ConnectionStatus.ERROR -> Color(0xFFFF5252) to "Error"
        else -> Color(0xFF9AA0B0) to "Desconectado"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .then(if (interaction is GamepadInteraction.Game) Modifier.clickable { vm.openSettings() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Botón redondo ⚙ que abre Ajustes y conexión. Siempre visible. 48 dp: mínimo táctil (R-03). */
@Composable
private fun GearButton(onClick: () -> Unit, interactive: Boolean = true) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(48.dp)
            .pressEffect(pressed)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1D27), Color(0xFF000000))))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
            .then(if (interactive) Modifier.pointerPress("Ajustes") { p -> pressed = p; if (p) onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text("⚙", color = Color.White, fontSize = 20.sp) }
}

/** Izquierda, como el mando Xbox: stick arriba, cruceta abajo (asimétrico). */
@Composable
private fun LeftControls(vm: GamepadViewModel, layout: LayoutConfig, yShift: Float = 0f, interaction: GamepadInteraction) {
    Column(
        modifier = Modifier.offset(0.dp, yShift.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LayoutElement(layout, LayoutConfig.LEFT_STICK, 180f, 208f, interaction) { StickWithLabel(vm, left = true, interaction) }
        LayoutElement(layout, LayoutConfig.DPAD, 196f, 196f, interaction) { DpadView(onChange = { up, down, left, right -> vm.setDpad(up, down, left, right) }, interactive = interaction is GamepadInteraction.Game) }
    }
}

/** Derecha, como el mando Xbox: A/B/X/Y arriba, stick abajo (asimétrico). */
@Composable
private fun RightControls(vm: GamepadViewModel, layout: LayoutConfig, yShift: Float = 0f, interaction: GamepadInteraction) {
    Column(
        modifier = Modifier.offset(0.dp, yShift.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LayoutElement(layout, LayoutConfig.ABXY, 200f, 200f, interaction) { AbxyPad(vm, interaction) }
        LayoutElement(layout, LayoutConfig.RIGHT_STICK, 180f, 208f, interaction) { StickWithLabel(vm, left = false, interaction) }
    }
}

/** Stick con su etiqueta L3/R3 (tocar el stick = pulsar L3/R3, como en un mando real). */
@Composable
private fun StickWithLabel(vm: GamepadViewModel, left: Boolean, interaction: GamepadInteraction) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (left) {
            JoystickView(
                onMove = { x, y -> vm.setLeftStick(x, y) },
                onRelease = { vm.setLeftStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.L3) },
                description = "Stick izquierdo (pulsa L3)",
                interactive = interaction is GamepadInteraction.Game,
            )
            StickLabel("L3")
        } else {
            JoystickView(
                onMove = { x, y -> vm.setRightStick(x, y) },
                onRelease = { vm.setRightStick(0f, 0f) },
                onClick = { vm.pulseButton(GamepadButtons.R3) },
                description = "Stick derecho (pulsa R3)",
                interactive = interaction is GamepadInteraction.Game,
            )
            StickLabel("R3")
        }
    }
}

/** Etiqueta pequeña con píldora (como en los mockups). */
@Composable
private fun StickLabel(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF111111))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Text("Toca el stick", color = Color(0xFF9AA0B0), fontSize = 11.sp)
    }
}

/** Centro, como el mando Xbox Series: VIEW a la izquierda del
 * botón Xbox, MENU a la derecha y Compartir debajo, centrado. */
@Composable
private fun CenterControls(vm: GamepadViewModel, layout: LayoutConfig, horizontal: Boolean, yShift: Float = 0f, interaction: GamepadInteraction) {
    if (horizontal) {
        Row(
            // Centrada, no anclada a la izquierda: si la fila
            // desborda, el desbordamiento es simétrico y el ajuste
            // por ancho basta para que quepa; anclada a la
            // izquierda, R4 se saldría aunque el ajuste fuera
            // exacto, porque el escalado es alrededor del centro.
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.weight(1f))
            LayoutElement(layout, LayoutConfig.L4, 58f, 48f, interaction) { ShoulderButton("L4", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setExtraButton(GamepadExtraButtons.L4, pressed) } }
            LayoutElement(layout, LayoutConfig.VIEW, 76f, 48f, interaction) { SmallButton("VIEW", { ViewIcon() }, interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) } }
            LayoutElement(layout, LayoutConfig.XBOX, 64f, 64f, interaction) { XboxButton(vm, sizeDp = 64, interactive = interaction is GamepadInteraction.Game) }
            LayoutElement(layout, LayoutConfig.MENU, 76f, 48f, interaction) { SmallButton("MENU", { MenuIcon() }, interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.START, pressed) } }
            LayoutElement(layout, LayoutConfig.SHARE, 76f, 48f, interaction) { PulsePill("SHARE", { ShareIcon() }, interactive = interaction is GamepadInteraction.Game) { vm.pulseButton(GamepadButtons.CAPTURE) } }
            LayoutElement(layout, LayoutConfig.R4, 58f, 48f, interaction) { ShoulderButton("R4", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setExtraButton(GamepadExtraButtons.R4, pressed) } }
            Spacer(Modifier.weight(1f))
        }
    } else {
        Column(
            modifier = Modifier.offset(0.dp, yShift.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LayoutElement(layout, LayoutConfig.VIEW, 76f, 48f, interaction) { SmallButton("VIEW", { ViewIcon() }, interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.SELECT, pressed) } }
                LayoutElement(layout, LayoutConfig.XBOX, 72f, 72f, interaction) { XboxButton(vm, sizeDp = 72, interactive = interaction is GamepadInteraction.Game) }
                LayoutElement(layout, LayoutConfig.MENU, 76f, 48f, interaction) { SmallButton("MENU", { MenuIcon() }, interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setButton(GamepadButtons.START, pressed) } }
            }
            LayoutElement(layout, LayoutConfig.SHARE, 76f, 48f, interaction) { PulsePill("SHARE", { ShareIcon() }, interactive = interaction is GamepadInteraction.Game) { vm.pulseButton(GamepadButtons.CAPTURE) } }
            // Paletas traseras L4/R4 (Fase 4): píldoras como los
            // hombros, con su propio elemento de diseño (se pueden
            // mover, hacer transparentes o quitar desde el panel
            // de botones, como los demás).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LayoutElement(layout, LayoutConfig.L4, 58f, 48f, interaction) { ShoulderButton("L4", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setExtraButton(GamepadExtraButtons.L4, pressed) } }
                LayoutElement(layout, LayoutConfig.R4, 58f, 48f, interaction) { ShoulderButton("R4", interactive = interaction is GamepadInteraction.Game) { pressed -> vm.setExtraButton(GamepadExtraButtons.R4, pressed) } }
            }
        }
    }
}

/** Botón Xbox (HOME): esfera negra con brillo verde Xbox y el
 * logo dibujado (dos trazos cruzados, como el mando real), con
 * anillo verde luminoso. Un toque = pulso HOME. */
@Composable
private fun XboxButton(vm: GamepadViewModel, sizeDp: Int, interactive: Boolean = true) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(sizeDp.dp)
            .pressEffect(pressed)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF1E2A1E), Color.Black)))
            .border(2.5.dp, Brush.sweepGradient(listOf(Color(0xFF9BF00B), Color(0xFF107C10), Color(0xFF52D273), Color(0xFF107C10), Color(0xFF9BF00B))), CircleShape)
            .then(if (interactive) Modifier.pointerPress("Xbox") { p -> pressed = p; if (p) vm.pulseButton(GamepadButtons.HOME) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size((sizeDp * 0.52f).dp)) {
            val w = size.width
            val h = size.height
            val sw = (sizeDp * 0.09f).dp.toPx()
            // Logo Xbox: X de dos trazos gruesos con puntas redondas.
            drawLine(Color.White, Offset(w * 0.18f, h * 0.18f), Offset(w * 0.82f, h * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.82f, h * 0.18f), Offset(w * 0.18f, h * 0.82f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

/** A/B/X/Y en rombo con los colores oficiales de Xbox:
 * A verde #107C10, B rojo #E81123, X azul #0078D7, Y amarillo #FFB900. */
@Composable
private fun AbxyPad(vm: GamepadViewModel, interaction: GamepadInteraction) {
    Box(modifier = Modifier.size(200.dp)) {
        GamepadButton("Y", Color(0xFFFFB900), Modifier.align(Alignment.TopCenter), interactive = interaction is GamepadInteraction.Game) { p -> vm.setButton(GamepadButtons.Y, p) }
        GamepadButton("X", Color(0xFF0078D7), Modifier.align(Alignment.CenterStart), interactive = interaction is GamepadInteraction.Game) { p -> vm.setButton(GamepadButtons.X, p) }
        GamepadButton("B", Color(0xFFE81123), Modifier.align(Alignment.CenterEnd), interactive = interaction is GamepadInteraction.Game) { p -> vm.setButton(GamepadButtons.B, p) }
        GamepadButton("A", Color(0xFF107C10), Modifier.align(Alignment.BottomCenter), interactive = interaction is GamepadInteraction.Game) { p -> vm.setButton(GamepadButtons.A, p) }
    }
}

@Composable
fun GamepadButton(label: String, color: Color, modifier: Modifier = Modifier, interactive: Boolean = true, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(modifier = modifier.size(78.dp), contentAlignment = Alignment.Center) {
        // Halo neón del color del botón.
        Box(
            Modifier.size(78.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent))),
        )
        Box(
            modifier = Modifier.size(62.dp)
                .pressEffect(pressed)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (pressed) Brush.linearGradient(listOf(color, color))
                    else Brush.verticalGradient(listOf(Color(0xFF1E2026), Color(0xFF000000))),
                )
                .border(2.dp, color, CircleShape)
                .then(if (interactive) Modifier.pointerPress(label) { p -> pressed = p; onPress(p) } else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Botón de hombro (LB/RB) o gatillo (LT/RT): píldora brillante con borde neón. Alto 48 dp: mínimo táctil (R-03). */
@Composable
private fun ShoulderButton(label: String, interactive: Boolean = true, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 58.dp, height = 48.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                    else listOf(Color(0xFF1A1D27), Color(0xFF000000)),
                ),
            )
            .border(1.dp, Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF00E5FF))), RoundedCornerShape(19.dp))
            .then(if (interactive) Modifier.pointerPress(label) { p -> pressed = p; onPress(p) } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** Píldora con icono arriba y texto abajo (VIEW / MENU). */
@Composable
fun SmallButton(label: String, icon: @Composable () -> Unit, interactive: Boolean = true, onPress: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 76.dp, height = 48.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
                    else listOf(Color(0xFF1A1D27), Color(0xFF000000)),
                ),
            )
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .then(if (interactive) Modifier.pointerPress(label) { p -> pressed = p; onPress(p) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            icon()
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Píldora de un toque con icono (SHARE): pulso corto, no mantener. */
@Composable
private fun PulsePill(label: String, icon: @Composable () -> Unit, interactive: Boolean = true, onTap: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.size(width = 76.dp, height = 48.dp)
            .pressEffect(pressed)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1D27), Color(0xFF000000))))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .then(if (interactive) Modifier.pointerPress(label) { p -> pressed = p; if (p) onTap() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            icon()
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Icono VIEW: dos ventanas solapadas. */
@Composable
private fun ViewIcon() {
    Box(Modifier.size(16.dp, 14.dp)) {
        Box(
            Modifier.size(11.dp, 9.dp).align(Alignment.TopStart)
                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp)),
        )
        Box(
            Modifier.size(11.dp, 9.dp).align(Alignment.BottomEnd)
                .background(Color(0xFF232839), RoundedCornerShape(2.dp))
                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

/** Icono SHARE: flecha saliendo de una bandeja. */
@Composable
private fun ShareIcon() {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        drawLine(Color.White, Offset(w / 2, h - 2.dp.toPx()), Offset(w / 2, 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w / 2, 2.dp.toPx()), Offset(w / 2 - 4.dp.toPx(), 6.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w / 2, 2.dp.toPx()), Offset(w / 2 + 4.dp.toPx(), 6.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(2.dp.toPx(), h * 0.55f), Offset(2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(2.dp.toPx(), h - 2.dp.toPx()), Offset(w - 2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w - 2.dp.toPx(), h * 0.55f), Offset(w - 2.dp.toPx(), h - 2.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Icono MENU: tres líneas. */
@Composable
private fun MenuIcon() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Box(Modifier.size(14.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
        }
    }
}
