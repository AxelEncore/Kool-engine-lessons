package questJournal2

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)
import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд
import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами
import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*

import kotlinx.coroutines.launch                    // запускает корутину
import kotlinx.coroutines.Job                       // контроллер запущенной корутины
import kotlinx.coroutines.isActive                  // проверка жива ли еще корутина - полезно для циклов
import kotlinx.coroutines.delay

import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения состояний
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.flow.collect              // слушать поток

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest
import lesson5.QuestState
import lesson9.GameState

// QuestJournal 2.0 - список активных квестов, цели, маркеры, подсказки
// QuestSystem будет обрабатывать иформацию о нынешнем квесте активного игрока
// и на UI выводить активнульную информацию

// МАРКЕРЫ И ТИПЫ КВЕСТОВ

enum class QuestStatus{
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class QuestMarker{
    NEW,
    PINNED,
    COMPLETED,
    NONE
}

// --------- Подготовка журнала квестов - то что будет отрисовывать UI -------- //
data class QuestJournalEntry(
    val questId: String,
    val title: String,              // Отображаемое название квеста
    val status: QuestStatus,
    val objectiveText: String,       // подсказка "что делать дальше"
    val marker: QuestMarker,
    val markerHint: String
)

// ------------ События, что будут влиять на UI и другие системы ---------- //

sealed interface GameEvent{
    val playerId: String
}

data class QuestJournalUpdated(
    override val playerId: String
): GameEvent

// Игрок открыл квест - поменять маркер NEW
data class QuestOpened(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestPinned(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestProgressed(
    override val playerId: String,
    val questId: String
): GameEvent

// ----------------- Команды UI -> Сервер ------------------ //

sealed interface GameCommand{
    val playerId: String
}

// Игрок открыл квест - поменять маркер NEW
data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdPinQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdProgressQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdSwitchPlayer(
    override val playerId: String,
    val newPlayerId: String
) : GameCommand

// ------- Серверные данные квеста ------ //
data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val step: Int,
    val status: QuestStatus,
    val isNew: Boolean,
    val isPinned: Boolean
)


class QuestSystem{
    // Здесь прописываем текст целей квестов по шагам ка каждого квеста
    private fun objectiveFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step){
                0 -> "Поговорить с алхимиком"
                1 -> "Собери траву"
                2 -> "Принеси траву"
                else -> "Квест завершен"
            }

            "q_guard" -> when(step){
                0 -> "Поговорить со стражем этой двери"
                1 -> "Заплатить 10 золота"
                else -> "Проход открыт"
            }

            else -> "Неизвестный квест"
        }
    }

    // Подсказки куда идти - в будущем используем для карты и компаса
    private fun markerHintFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step){
                0 -> "Идти к NPC: Алхимик"
                1 -> "Собрать Herb x2"
                2 -> "Вернись к NPC: Алхимик"
                else -> "Готово"
            }

            "q_guard" -> when(step){
                0 -> "Идти к NPC: Страж"
                1 -> "Найди чем расплатиться со стражником"
                else -> "Готово"
            }

            else -> ""
        }
    }

    // Превращаем QuestStateOnServer в то, что отобразится на UI
    fun toJournalEntry(quest: QuestStateOnServer): QuestJournalEntry{
        val objective = objectiveFor(quest.questId, quest.step)
        val hint = markerHintFor(quest.questId, quest.step)

        val marker = when {
            quest.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            quest.isPinned -> QuestMarker.PINNED
            quest.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }

        return QuestJournalEntry(
            quest.questId,
            quest.title,
            quest.status,
            objective,
            marker,
            hint
        )
    }
}

// ----------- Сервер - обработка квестов, приняте команд и рассылка событий ----------- //
class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean{
        // tryEmit - попытка быстро положить команду в поток
        return _commands.tryEmit(cmd)
    }

    // состояние квестов для каждого игрока

    private val _questByPlayer = MutableStateFlow<Map<String, List<QuestStateOnServer>>>(
        mapOf(
            "Oleg" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            ),
            "Stas" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            )
        )
    )

    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questByPlayer.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect{ cmd ->
                process(cmd)
            }
        }
    }

    private suspend fun process(cmd: GameCommand){
        when (cmd){
            is CmdOpenQuest -> openQuest(cmd.playerId, cmd.questId)
            is CmdPinQuest -> pinQuest(cmd.playerId, cmd.questId)
            is CmdProgressQuest -> progressQuest(cmd.playerId, cmd.questId)
            is CmdSwitchPlayer -> {}
        }
    }

    private fun getPlayerQuests(playerId: String) : List<QuestStateOnServer>{
        return _questByPlayer.value[playerId] ?: emptyList()
    }

    private fun setPlayerQuests(playerId: String, quests: List<QuestStateOnServer>){
        val oldMap = _questByPlayer.value.toMutableMap()
        oldMap[playerId] = quests
        _questByPlayer.value = oldMap.toMap()
    }

    private suspend fun openQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isNew = false)
            }
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun pinQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isPinned = (q.questId == questId))
            }
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun progressQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                val newStep = q.step + 1

                val completed = when(q.questId){
                    "q_alchemist" -> newStep >= 3
                    "q_guard" -> newStep >= 2
                    else -> false
                }

                val newStatus = if (completed) QuestStatus.COMPLETED else QuestStatus.ACTIVE

                quests[i] = q.copy(isNew = false, step = newStep, status = newStatus)
            }
        }
        setPlayerQuests(playerId, quests)
        _events.emit(QuestJournalUpdated(playerId))
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuestId = mutableStateOf<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, text: String){
    hud.log.value = (hud.log.value + text).takeLast(20)
}

fun markerSymbol(m: QuestMarker): String{
    return when(m){
        QuestMarker.NEW -> "!"
        QuestMarker.PINNED -> "->"
        QuestMarker.COMPLETED -> "✓"
        QuestMarker.NONE -> "o"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()
    val quests = QuestSystem()

    addScene {
        defaultOrbitCamera()
        addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)
    }

    addScene {
        setupUiScene(ClearColorLoad)

        // Подписка на события квестов
        coroutineScope.launch {
            server.questByPlayer.collect{ map ->
                val pid = hud.activePlayerIdFlow.value
                val serverList = map[pid] ?: emptyList()

                val entries = serverList.map { quests.toJournalEntry(it) }

                hud.questEntries.value = entries

                if (hud.selectedQuestId.value == null){
                    val pinned = entries.firstOrNull{it.marker == QuestMarker.PINNED }
                    if (pinned != null) hud.selectedQuestId.value = pinned.questId
                }
            }
        }

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map{ e -> "[${e.playerId}] ${e::class.simpleName}"}
            .onEach { line -> hudLog(hud, line) }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Player: ${hud.activePlayerIdUi.use()}"){}
                modifier.margin(bottom = sizes.gap)

                Row {
                    Button("Switch PLayer"){
                        modifier.margin(end=8.dp).onClick{
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId

                            hud.selectedQuestId.value = null
                        }
                    }
                }

                Text("Активные квесты:") {modifier.margin(top=sizes.gap)}

                val entries = hud.questEntries.use()
                val selectedId = hud.selectedQuestId.use()

                for (q in entries){
                    val symbol = markerSymbol(q.marker)

                    val line = "$symbol ${q.title}"
                    // Кнопка открытия квеста
                    Button(line){
                        modifier.margin(bottom = sizes.smallGap).onClick{
                            hud.selectedQuestId.value = q.questId
                            // Если квест открыт отправить серверу команду, что он уже не новый
                            server.trySend(CmdOpenQuest(hud.activePlayerIdUi.value, q.questId))
                        }
                    }
                    Text(" - ${q.objectiveText}"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                    }

                    // Если квест выбранный, то показываем, маркер-подсказку
                    if (selectedId == q.questId){
                        Text("  marker: ${q.markerHint}"){
                            modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                        }

                        // Кнопки действия над выбранным квестом
                        Row {
                            Button("Pin"){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(CmdPinQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }

                            Button("Progress"){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(CmdProgressQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}















