# GyroBridge: rotação confiável, movimento físico e DragTest

Data: 2026-08-25
Status: aprovado em conversa, aguardando revisão deste documento

## Objetivo

Atualizar o GyroBridge para controlar simultaneamente a câmera e o joystick de
um jogo por gestos persistentes de acessibilidade. A câmera continuará sendo
controlada pela orientação do aparelho. O joystick passará a responder à
intenção de caminhada física para frente ou para trás, sem tentar calcular
posição 3D ou distância percorrida.

Um segundo aplicativo, `dragtest`, fornecerá uma cena Three.js local para
validar câmera, joystick, multitouch, rotação e continuidade dos pointers sem
depender de um jogo de terceiros.

## Restrições

- O GyroBridge e o DragTest serão APKs e aplicativos independentes.
- Os dois serão módulos do mesmo projeto Gradle: `:app` e `:dragtest`.
- O GyroBridge continuará com sua Activity principal em portrait.
- O DragTest aceitará portrait, landscape e suas orientações reversas.
- A sessão sempre começará pausada. Nenhum toque será enviado antes de uma
  ação explícita do usuário no overlay ou na notificação.
- A funcionalidade Auto Detect não faz parte desta atualização.
- Não serão utilizados root, injeção, leitura de memória de outros processos
  ou APIs privadas.
- Não será executado Gradle, teste, build ou instalação enquanto o usuário não
  autorizar. Até lá, testes novos permanecerão escritos, mas não executados.

## Estrutura do projeto

```text
GyroBridge (repositório)
├── app/                  aplicativo principal com AccessibilityService
├── dragtest/             aplicativo Three.js independente
├── gradle/
└── settings.gradle.kts   inclui :app e :dragtest
```

O `dragtest` terá `applicationId=com.gyrobridge.dragtest`, não dependerá do
módulo `:app` e não precisará de internet. Os arquivos recuperados do APK
(`index.html`, `main.js`, `game.bundle.js`, `three.core.min.js` e
`three.module.min.js`) serão usados como base local. A regra `/dragtest/` será
removida do `.gitignore` para que o novo módulo passe a ser versionado.

## Situação atual confirmada

- `SensorEngine` mantém `lockedDisplayRotation` depois da captura e
  `OrientationProcessor.onDisplayRotationChanged()` apaga a referência.
- `OrientationProcessor.referenceMatrix` e `SensorCalibration.zeroYaw/Pitch/Roll`
  centralizam a orientação duas vezes.
- O período de estabilidade usa um `elapsed` calculado antes de reiniciar o
  relógio de settling.
- `autoCalibrate` é persistido, mas não governa o início da sessão.
- `DEGREES` aplica deadzone a cada amostra, portanto depende da frequência.
- O mapper converte normalizados diretamente para a resolução atual e não
  conhece a rotação na qual o perfil foi mapeado.
- O modo contínuo do scheduler mantém apenas a câmera. O joystick só é
  adicionado ao gesto segmentado e não mantém um pointer.
- `cancelAll()` não invalida callbacks antigos por geração ou identificador.
- Configurações como `driftCompensation`, `biasCorrection`,
  `degreesForMaxMovement`, `queueCapacity`, `autoRecenterThreshold`,
  `invertRoll`, `tiltThreshold`, sensibilidade e curva do joystick não estão
  integralmente ligadas ao motor.
- O README documenta `:dragtest`, mas o módulo não existe no checkout.

## Arquitetura de sensores e orientação

### Referência única

`OrientationProcessor` será a única fonte de verdade para o centro da sessão.
Ele manterá uma matriz ortonormal dispositivo-para-mundo como representação
canônica da orientação e calculará a pose relativa em relação a essa
referência. No fallback puro, a integração poderá usar quaternion normalizado
internamente, mas o resultado será convertido para a mesma matriz canônica.

`SensorCalibration` deixará de subtrair `zeroYaw`, `zeroPitch` e `zeroRoll` de
cada amostra. Ele passará a coordenar somente a máquina de estados de captura:

```text
IDLE -> WAITING_SENSOR -> WAITING_STABLE -> CAPTURED
                                |              |
                                +-- movimento -+
```

Ao capturar, `SensorCalibration` solicitará a `OrientationProcessor` que grave
a pose atual como referência. A referência será da sessão e não será persistida
entre usos, pois a posição física neutra muda.

### Rotação do display

A rotação usada durante settling poderá ser temporariamente congelada para
evitar capturar uma transição. Assim que a referência for capturada, o bloqueio
será removido sem apagar a referência.

Quando o display mudar entre `ROTATION_0`, `90`, `180` e `270`:

1. a referência de sessão será preservada;
2. a pose atual e a referência serão interpretadas no mesmo sistema de eixos;
3. a primeira amostra após a troca atualizará a base anterior sem emitir delta;
4. as amostras seguintes usarão os eixos da nova orientação.

Isso evita um salto de câmera e também evita que a calibração seja secretamente
substituída pela primeira amostra após a rotação.

### Fallback de giroscópio

A prioridade continuará:

```text
GAME_ROTATION_VECTOR -> ROTATION_VECTOR -> GYROSCOPE
```

No fallback puro, a velocidade angular será integrada em orientação de
dispositivo, não diretamente em yaw/pitch/roll de tela. A mudança de rotação
passará pelo mesmo remapeamento da orientação absoluta. A correção de bias será
aplicada somente quando o detector de repouso confirmar estabilidade.

Se nenhum sensor iniciar, a sessão ficará em estado de erro e não será exibida
como operacional.

## Calibração e início explícito

A sessão será criada em `PAUSED`. O estado de execução será explícito:

```text
STOPPED
PAUSED
WAITING_ACCESSIBILITY
WAITING_SENSOR
CALIBRATING
ACTIVE
ERROR
```

- Com `autoCalibrate=true`, a ação explícita de iniciar executará settling,
  capturará a referência e somente então ativará gestos.
- Com `autoCalibrate=false`, a ação explícita de iniciar não fará calibração
  escondida; usará a referência já estabelecida na sessão ou solicitará uma
  calibração manual quando ainda não houver referência.
- A ação explícita `CALIBRAR` sempre realizará nova captura.
- Reiniciar o settling recalculará o tempo imediatamente; decisões posteriores
  não usarão o `elapsed` antigo.
- A desconexão do AccessibilityService colocará a sessão em
  `WAITING_ACCESSIBILITY`, cancelará os contatos e explicará o estado na UI.

O schema de perfil será incrementado. Perfis antigos continuarão sendo lidos,
mas os ângulos `zeroYaw/Pitch/Roll` antigos não serão tratados como uma
calibração válida de sessão.

As configurações auditadas terão destino explícito:

- `autoCalibrate` controlará a ação de início descrita acima;
- `biasCorrection` e `driftCompensation` antigos migrarão para uma única opção
  funcional de correção de bias do giroscópio puro;
- `degreesForMaxMovement` normalizará as curvas do pipeline;
- `queueCapacity` será removido, pois o novo planejador manterá somente estado
  atual e um dispatch em voo, sem fila variável;
- `autoRecenterThreshold` será removido; `boundaryMargin` continuará sendo a
  única regra de limite da área da câmera;
- `invertRoll` será removido; inversão final continuará em `invertX/invertY`;
- `tiltThreshold`, sensibilidade e curva do joystick de inclinação serão
  substituídos pela configuração de movimento físico;
- `interactionMode` deixará de selecionar implementações concorrentes: câmera
  permanece sempre disponível e o movimento é governado por `enabled` no
  perfil físico.

## Pipeline de câmera independente da frequência

O pipeline acumulará deslocamento angular entre frames de gesto. A deadzone em
graus será aplicada ao deslocamento acumulado da janela, não individualmente a
cada sample. Limites de velocidade e pixels por segundo continuarão usando
`dt`.

Curvas não lineares serão normalizadas por `degreesForMaxMovement` e depois
convertidas novamente para pixels, tornando esse campo efetivo. Frequências
simuladas de 50, 100 e 200 Hz para o mesmo movimento deverão produzir
deslocamentos totais aproximadamente equivalentes.

## Coordenadas e áreas mapeadas

A transformação ficará centralizada em `ScreenCoordinateMapper`. Nenhuma tela,
overlay ou scheduler manterá fórmulas próprias de rotação.

As duas áreas terão a rotação original do mapeamento:

```text
CameraZone
  centerX, centerY, width, height
  mappedDisplayRotation

MovementZone
  centerX, centerY, radius
  mappedDisplayRotation
```

O mapper transformará pontos e dimensões pela diferença entre a rotação
mapeada e a rotação atual. Em diferenças de 90 ou 270 graus, largura e altura
serão trocadas. Perfis legados sem rotação registrada manterão inicialmente o
comportamento normalizado antigo, evitando deslocamento inesperado; a próxima
edição da área gravará a rotação real.

## Movimento físico

`PhysicalMovementDetector` será separado de `OrientationProcessor` e não fará
dispatch de gestos. Sua saída será:

```kotlin
enum class PhysicalMovementState {
    STATIONARY,
    FORWARD,
    BACKWARD
}
```

A API permitirá adicionar `LEFT` e `RIGHT` futuramente sem alterar o
scheduler, mas esses estados não serão detectados nesta versão.

### Entradas e referencial

- `TYPE_LINEAR_ACCELERATION` será a entrada principal quando disponível.
- A orientação atual transformará a aceleração do aparelho para o referencial
  frontal capturado na calibração.
- `TYPE_STEP_DETECTOR`, quando presente, aumentará a confiança de caminhada,
  mas nunca será requisito único nem fonte de direção.
- A ausência dos sensores auxiliares desabilitará apenas movimento físico; a
  câmera continuará funcionando.

### Classificação

O detector utilizará filtro, energia de janela, impulso projetado no eixo
frontal, hysteresis, duração mínima, confiança e timeout de parada. Tremidas
curtas e movimentos usados apenas para olhar não deverão vencer o tempo mínimo.

O objetivo é reconhecer intenção contínua, não integrar aceleração em posição:

```text
STATIONARY -> FORWARD/BACKWARD -> mantém joystick
sem evidência até stopTimeout -> STATIONARY -> libera joystick
```

Inicialmente, `FORWARD` e `BACKWARD` usarão força fixa configurável. Intensidade
variável só será usada se os testes mostrarem estabilidade.

O perfil receberá uma configuração equivalente a:

```text
enabled
forwardEnabled
backwardEnabled
threshold
sensitivity
minimumActiveMs
stopTimeoutMs
joystickStrength
movementZone
```

O default para perfis antigos será movimento físico desativado.

## Gestos persistentes e multitouch

O scheduler será dividido em duas partes:

1. um planejador puro e testável que decide contatos e transições;
2. um adaptador Android que cria `GestureDescription` e chama
   `dispatchGesture()`.

Haverá dois canais lógicos:

```text
CAMERA_POINTER
MOVEMENT_POINTER
```

Cada canal manterá estado, posição lógica e `StrokeDescription` anterior. A
cada segmento, o compositor poderá incluir os dois strokes no mesmo
`GestureDescription`:

```text
câmera:    DOWN -> MOVE -> continueStroke -> ... -> UP
movimento: DOWN -> MOVE até alvo -> mantém -> ... -> UP
```

O pointer de movimento começa no centro do `MovementZone` e aponta para cima
ou para baixo pela força configurada. Ele só termina quando o detector voltar a
`STATIONARY`, quando a sessão for pausada ou quando ocorrer erro.

O pointer da câmera permanece contínuo enquanto há movimento. Ao atingir o
limite da área, termina de forma controlada e reinicia no centro em um novo
segmento, sem transformar cada atualização em clique.

Somente um dispatch ficará em voo. Cada configuração, pause, cancelamento ou
nova sessão incrementará um token de geração. Callbacks carregarão geração e
identificador do dispatch; callbacks antigos serão ignorados e não poderão
alterar contatos novos.

## UI e overlay

O mapeador sobre o aplicativo alvo terá dois elementos independentes,
inspirados na referência visual fornecida:

- retângulo azul translúcido com cruz e setas para a câmera;
- círculo verde com centro, frente e trás para o movimento.

As áreas serão selecionáveis, arrastáveis e redimensionáveis. O overlay normal
continuará compacto. Seus estados indicarão claramente `OFF`, `CALIBRANDO`,
`AGUARDANDO ACESSIBILIDADE`, `ERRO` e `ON`.

A tela de perfil permitirá habilitar movimento físico, configurar força,
sensibilidade, threshold e timeout. Opções antigas sem efeito serão conectadas
ao motor ou removidas com migração segura; não permanecerão controles mortos.

## DragTest Three.js

O `dragtest` será uma Activity Android leve com WebView acelerada por hardware
e assets locais. Não haverá engine Android 3D adicional.

A cena mostrará:

- ambiente 3D com grade, horizonte, objetos e crosshair;
- região grande para yaw/pitch da câmera;
- joystick grande à esquerda;
- markers distintos para pointer de câmera e pointer de movimento;
- contadores e estado de `DOWN`, `MOVE`, `UP` por pointer;
- FPS, yaw, pitch, posição do joystick e maior intervalo entre movimentos.

Pointer events controlarão a câmera e o joystick simultaneamente. O DragTest
não produzirá o movimento do Gyro por conta própria: ele somente reagirá aos
toques recebidos, garantindo que o teste valide o GyroBridge real.

Telemetria interna do GyroBridge será enviada ao DragTest por broadcast
explícito, protegido por permissão `signature` e limitado a baixa frequência.
O DragTest mostrará sensor, display rotation, estado da sessão,
AccessibilityService e classificação física quando o GyroBridge estiver
presente, sem internet nem interface exportada para aplicativos com outra
assinatura. A validação de pointers não dependerá desse broadcast.

## Persistência e compatibilidade

- O JSON de perfil terá nova versão e defaults seguros.
- Perfis antigos continuarão carregando câmera e overlay como hoje.
- Áreas antigas sem rotação registrada usarão interpretação legada.
- Configurações antigas de joystick poderão fornecer centro e raio iniciais
  para a nova área, mas movimento físico permanecerá desativado até o usuário
  habilitá-lo.
- Campos removidos serão aceitos na leitura e omitidos em novas gravações.
- Importações com números inválidos continuarão sanitizadas.

## Logs e desempenho

Logs serão emitidos em transições, não por sample:

```text
Gyro/Sensor
Gyro/Calibration
Gyro/Orientation
Gyro/Gesture
Gyro/Movement
Gyro/Accessibility
```

Callbacks de sensor não criarão listas ou coroutines por amostra. Janelas do
detector terão armazenamento fixo. UI e telemetria serão amostradas em taxa
menor. O scheduler manterá estado limitado e apenas um dispatch em voo.

## Estratégia de testes

Os testes serão escritos junto com cada componente, mesmo que sua execução
fique adiada até autorização.

- `OrientationProcessor`: quatro rotações, transições adjacentes e 90/270,
  durante e depois da calibração, sem salto.
- `SensorCalibration`: estabilidade, reinício do relógio, timeout, recenter e
  `autoCalibrate` ligado/desligado.
- `ScreenCoordinateMapper`: transformação de CameraZone e MovementZone entre
  todas as rotações relevantes, incluindo dimensões.
- `MotionPipeline`: equivalência aproximada em 50, 100 e 200 Hz.
- `PhysicalMovementDetector`: repouso, frente, trás, ruído, tremida, impulso
  curto, sequência, parada e timeout.
- planejador de gestos: apenas câmera, apenas movimento, ambos, pausa,
  cancelamento, retomada, limite e callback obsoleto.
- codec: migração de perfis antigos, novos campos e sanitização.
- instrumentados: criação/continuação/finalização dos strokes quando a API
  Android não puder ser isolada em teste local.
- DragTest: validação visual de dois pointers, continuidade e rotação.

Testes existentes não serão apagados para esconder regressões.

## Sequência de implementação

1. Adicionar testes de orientação, calibração e mapper.
2. Corrigir referência, rotação e fallback de sensor.
3. Remover a dupla centralização e corrigir estados da sessão.
4. Tornar deadzone e pipeline independentes da frequência.
5. Adicionar rotação às zonas e migrar perfis.
6. Criar o planejador de contatos e o scheduler persistente.
7. Criar e testar `PhysicalMovementDetector`.
8. Integrar MovementZone, perfil, persistência e UI.
9. Criar o mapeador visual duplo.
10. Criar `:dragtest` com os assets Three.js recuperados.
11. Atualizar a cena, telemetria e visualização multitouch.
12. Atualizar README e remover somente código comprovadamente morto.
13. Após autorização, executar testes, builds de baixa memória, instalação com
    `adb install -r` e validação no aparelho sem desinstalar os aplicativos.

## Critérios de aceitação

- Calibração tem uma única referência e não é perdida ao girar a tela.
- Mudanças de rotação não geram delta brusco de câmera.
- Áreas de câmera e movimento continuam sobre os controles mapeados.
- A sessão nunca envia gestos antes da ação explícita do usuário.
- Falha de sensor ou acessibilidade produz estado visível e nenhum falso `ON`.
- Câmera e movimento físico funcionam isoladamente e ao mesmo tempo.
- Ambos os pointers são persistentes e callbacks antigos não corrompem estado.
- Movimento detecta `FORWARD`, `BACKWARD` e `STATIONARY` sem tracking 6DoF.
- Perfis antigos continuam carregando com movimento desativado por padrão.
- DragTest é outro aplicativo e permite validar câmera, joystick, multitouch e
  as quatro orientações.
- Resultados finais distinguem código escrito, testes executados, build,
  instalação e comportamento observado no aparelho.
