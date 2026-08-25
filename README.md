# GyroBridge

GyroBridge é um aplicativo Android local que transforma a orientação física do aparelho em gestos de toque usando exclusivamente APIs públicas: `SensorManager`, `AccessibilityService.dispatchGesture()`, `GestureDescription`, overlay e foreground service. Ele não modifica outros aplicativos, não usa root, não lê memória de processos, não injeta código e não possui permissão de internet.

## Requisitos

- Android Studio/SDK 37;
- JDK 17;
- Android 8.0 (API 26) ou posterior;
- aparelho com `TYPE_GAME_ROTATION_VECTOR`, `TYPE_ROTATION_VECTOR` ou giroscópio;
- AccessibilityService autorizado pelo usuário para o controle externo.

## Compilar

No Windows/PowerShell:

```powershell
.\gradlew.bat test --no-daemon --max-workers=1
.\gradlew.bat assembleDebug --no-daemon --max-workers=1
```

O APK debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. O projeto já usa um perfil conservador de memória (`-Xmx1024m`, SerialGC, um worker e sem paralelismo), adequado a máquinas com 4 GB de RAM.

### APK de teste 3D independente

O módulo `dragtest` gera o aplicativo **Gyro VR Test** (`com.gyrobridge.dragtest`).
Ele contém localmente um cenário Three.js, funciona sem rede e reage somente a
arrastos recebidos pela tela. O HUD conta `DOWN`, `MOVE` e `UP`, mostra o maior
intervalo entre movimentos e registra a mesma telemetria no log `GyroDragTest`.
Assim é possível distinguir um arrasto contínuo de uma sequência de cliques sem
depender de um jogo de terceiros.

```powershell
.\gradlew.bat :dragtest:assembleDebug --no-daemon --max-workers=1
adb install -r dragtest\build\outputs\apk\debug\dragtest-debug.apk
adb logcat -s GyroDragTest:I
```

Para o teste integrado, inicie um perfil do GyroBridge ainda pausado, abra o
Gyro VR Test, posicione a região da câmera no centro do cenário e só então use
`GYRO OFF` → `CALIBRAR + INICIAR`.

## Arquitetura

```text
UI Compose / ViewModel
        ↓ StateFlow
ProfileRepository + DataStore + JSON import/export
        ↓
SensorEngine → OrientationProcessor → SensorCalibration
        ↓
MotionFilter → MotionPipeline → dx/dy
        ↓
GestureAccumulator → GestureScheduler
        ↓
GyroAccessibilityService.dispatchGesture()
```

- `data`: DataStore, repositório e codec JSON validado;
- `domain`: perfis, limites e conversão de coordenadas normalizadas;
- `sensor`: seleção automática de sensor, rotação de display, deltas, filtros e pipeline;
- `gesture`: acumulação, fila limitada, métricas, gesto segmentado/contínuo e composição multitouch;
- `accessibility`: despacho real e detecção do pacote em primeiro plano;
- `service`: sessão, notificação, lifecycle e botão de emergência;
- `overlay`: painel flutuante movível com calibrar, pausar e parar;
- `ui`: home, perfis, app picker, calibração, mapeador, diagnóstico, permissões e playgrounds.

## Configuração inicial

1. Abra **Permissões**.
2. Em **AccessibilityService**, toque em Configurar, selecione GyroBridge e confirme. O serviço declara `canPerformGestures=true` e não recupera conteúdo da janela.
3. Autorize **Sobrepor outros apps** somente se desejar o painel flutuante.
4. No Android 13 ou posterior, autorize notificações para ver os controles da sessão.

Sensores não exigem permissão em tempo de execução. `HIGH_SAMPLING_RATE_SENSORS` está declarado para solicitações acima de 200 Hz.

## Criar e usar um perfil

1. Abra **Perfis** e escolha **Novo perfil**.
2. Dê um nome e use **Selecionar aplicativo** para associar um pacote instalado.
3. Ajuste sensibilidade horizontal/vertical, deadzone, filtro, taxa do sensor, taxa/modo de gestos e inversão dos eixos.
4. Abra **Região da câmera**, arraste o centro e ajuste largura/altura. Tudo é salvo entre `0.0` e `1.0`, não em pixels absolutos.
5. Salve. Perfis podem ser exportados/importados em JSON; valores não finitos ou fora dos limites são rejeitados/corrigidos.

## Calibrar

Mantenha o aparelho imóvel na posição neutra e toque em **Definir posição atual como centro**. A sessão também pode calibrar automaticamente ao iniciar. Durante o uso, recalibre pela notificação ou overlay.

## Iniciar uma sessão

Na home ou no perfil, toque em **Iniciar e abrir aplicativo**. O GyroBridge:

1. carrega o perfil;
2. inicia o foreground service por ação explícita do usuário;
3. seleciona o melhor sensor disponível;
4. calibra quando configurado;
5. abre o aplicativo associado;
6. acumula deltas enquanto um gesto está ocupado e envia apenas um `dispatchGesture()` por vez.

Use **PARAR** na home, notificação ou overlay para remover listeners, limpar a fila, remover o overlay e encerrar a sessão.

## Playgrounds e diagnóstico

- **Gyro Playground** move uma mira com o mesmo pipeline de sensor, filtro, deadzone, curva e sensibilidade usado externamente.
- **Gesture Playground** desenha início, caminho e fim de swipes. Com o serviço autorizado e uma sessão ativa, **Testar gesto** solicita um swipe real.
- **Diagnóstico** mostra sensor/vendor/delays, Hz solicitado e real, valores raw/filtrados, `dx/dy`, gráfico dos últimos 10 segundos, contadores de gesto, cancelamentos, Hz efetivo e percentis de latência.

## Segurança e eficiência

- parâmetros são limitados antes da persistência e antes do uso;
- `NaN`, infinito, durações inválidas e coordenadas fora da tela não chegam ao motor;
- a taxa do sensor é independente da taxa de gesto;
- eventos são processados em um único collector, sem criar uma coroutine por sample;
- a UI recebe `StateFlow` e o gráfico é amostrado em cerca de 30 Hz;
- o listener é removido quando a sessão/playground termina;
- a fila acumula movimentos e descarta eventos com mais de 500 ms;
- nenhum dado é enviado a servidores.

## Compatibilidade e limitações

- Compatível em código com Android 8 a Android 17; caminhos modernos são protegidos por verificação de API.
- A frequência real depende do aparelho. Solicitar 240 Hz não significa receber 240 Hz; confira **Diagnóstico**.
- Gestos de acessibilidade podem cancelar ou interferir em toques humanos.
- Multitouch sintético combina joystick e câmera em um único `GestureDescription` no modo segmentado; aparelhos/aplicativos podem recusá-lo, e a métrica indica indisponibilidade.
- O modo contínuo usa `continueStroke()` em dois segmentos encadeados. O comportamento ainda varia por fabricante e aplicativo.
- Alguns aplicativos bloqueiam overlays ou ignoram gestos sintéticos.
- Termos de jogos podem proibir ferramentas externas. O GyroBridge não tenta contornar essas regras, anti-cheat ou proteções do sistema.
- O Android pode impedir início de foreground service em background; por isso a sessão começa por ação explícita na UI/notificação, não clandestinamente.

## Testes

Os testes unitários cobrem normalização 359°/0°, inversão, deadzone, sensibilidade, clamps, curvas, EMA, One Euro, filtro adaptativo, coordenadas portrait/landscape, serialização e acumulação/overflow. Os testes instrumentados cobrem DataStore e navegação Compose sem automatizar aplicativos externos.
