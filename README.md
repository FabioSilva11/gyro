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
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1
.\gradlew.bat :app:assembleDebug --no-daemon --max-workers=1
.\gradlew.bat :dragtest:assembleDebug --no-daemon --max-workers=1
```

O APK debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. O projeto já usa um perfil conservador de memória (`-Xmx1024m`, SerialGC, um worker e sem paralelismo), adequado a máquinas com 4 GB de RAM.

### APK de teste 3D independente

O módulo `dragtest` gera o aplicativo separado **Gyro DragTest** (`com.gyrobridge.dragtest`).
Ele contém localmente um cenário Three.js, funciona sem rede e reage somente a
arrastos recebidos pela tela. O círculo verde aceita o contato de movimento e o
restante do cenário aceita, simultaneamente, o contato da câmera. O HUD conta
`DOWN`, `MOVE` e `UP`, mostra IDs/estado dos contatos, maior intervalo, FPS,
yaw/pitch e vetor de movimento. Uma transmissão local protegida por permissão de
assinatura também mostra sensor, rotação e estado da sessão do GyroBridge.

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
SensorEngine → OrientationProcessor → referencial matricial único
        ↓                              ↓
MotionFilter → MotionPipeline       PhysicalMovementSensor → detector
        ↓                              ↓
        GestureScheduler → câmera + movimento no mesmo gesto persistente
        ↓
GyroAccessibilityService.dispatchGesture()
```

- `data`: DataStore, repositório e codec JSON validado;
- `domain`: perfis, limites e conversão de coordenadas normalizadas;
- `sensor`: seleção automática, referencial preservado entre rotações, deltas, filtros, pipeline e intenção física frente/trás;
- `gesture`: acumulação, fila limitada, métricas e dois contatos persistentes com callbacks protegidos por geração;
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
3. Ajuste sensibilidade horizontal/vertical, deadzone, filtro, taxa do sensor, taxa de gestos e inversão dos eixos.
4. Ative **Movimento físico** quando quiser converter a intenção de andar em um segundo contato no joystick. Ajuste frente/trás, limiar, tempo de parada e força.
5. Abra **Mapear controles**. Posicione o retângulo azul da câmera e o círculo verde do movimento sobre as áreas correspondentes do jogo. As duas áreas são salvas normalizadas junto com a rotação usada no mapeamento.
6. Salve. Perfis podem ser exportados/importados em JSON; valores não finitos ou fora dos limites são rejeitados/corrigidos.

## Calibrar

Mantenha o aparelho na posição natural em que será usado — em pé ou deitado — e toque em **Definir posição atual como centro**. A orientação completa vira um único referencial matricial; não são somados offsets de yaw/pitch/roll. Uma mudança de rotação da tela limpa apenas o delta de transição e preserva o centro. Durante o uso, recalibre pela notificação ou overlay.

## Iniciar uma sessão

Na home ou no perfil, toque em **Iniciar e abrir aplicativo**. O GyroBridge:

1. carrega o perfil;
2. inicia o foreground service por ação explícita do usuário;
3. seleciona o melhor sensor disponível;
4. permanece pausado, sem enviar toque algum;
5. abre o aplicativo associado;
6. somente depois do comando explícito **CALIBRAR + INICIAR** captura o centro e ativa os contatos;
7. acumula deltas enquanto um gesto está ocupado e envia câmera e movimento no mesmo `GestureDescription`.

Se a acessibilidade desconectar, os contatos são cancelados e a sessão passa
para **Aguardando acessibilidade**. Ela não fecha e não volta a controlar sozinha:
depois da reconexão continua pausada até outra ação explícita do usuário.

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
- o detector de movimento usa memória fixa e o sensor auxiliar não interrompe a câmera quando indisponível;
- a telemetria de teste é explícita, local, limitada a 5 Hz e protegida por permissão de assinatura;
- nenhum dado é enviado a servidores.

## Compatibilidade e limitações

- Compatível em código com Android 8 a Android 17; caminhos modernos são protegidos por verificação de API.
- A frequência real depende do aparelho. Solicitar 240 Hz não significa receber 240 Hz; confira **Diagnóstico**.
- Gestos de acessibilidade podem cancelar ou interferir em toques humanos.
- Câmera e movimento usam contatos persistentes via `continueStroke()`. O comportamento ainda varia por fabricante e aplicativo.
- Movimento físico é uma classificação de intenção baseada em aceleração linear e passo opcional. Não mede posição real, distância percorrida ou 6DoF e pode exigir ajuste de limiar conforme o aparelho e a forma de uso.
- O movimento físico assume que a direção calibrada do aparelho representa a direção do usuário; carregar o telefone solto ou mudar radicalmente sua posição reduz a confiabilidade.
- Alguns aplicativos bloqueiam overlays ou ignoram gestos sintéticos.
- Termos de jogos podem proibir ferramentas externas. O GyroBridge não tenta contornar essas regras, anti-cheat ou proteções do sistema.
- O Android pode impedir início de foreground service em background; por isso a sessão começa por ação explícita na UI/notificação, não clandestinamente.

## Testes

Os testes unitários cobrem normalização 359°/0°, referência e rotação, projeção da aceleração, inversão, deadzone independente da frequência, filtros, coordenadas, migração JSON, detecção frente/trás, estado explícito da sessão e planejamento dos dois contatos. Os testes instrumentados cobrem DataStore e navegação Compose sem automatizar aplicativos externos.
