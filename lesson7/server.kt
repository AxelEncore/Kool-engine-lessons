package lesson7

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.modules.ksl.KslPbrSplatShader
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....
import lesson5.pushLog

import java.io.File

// В игре, которая зависит от общего игрового прогресса игроков - клиент не должен уметь менять квесты, золото, инвентарь
// Клиент иначе можно будет взломать, только сервер будет решать что можно, а что нельзя, и сервер сихронизирует все
// между игроками одинаково.

// Аннотации - разделение кусков кода на серверные и клиентские (мы сами говорим программе, что где должно работать)
// Правильная цепочка безопасного кода:
// 1. Клиент (через hud или кнопку) отправляет команду на сервер:
// "я поговорил с алхимиком"
// 2. Сервер принимает команду, проверяет правила, которые ему установили (соблюдено ли условие 5 золота)
// 3. Сервер рассылает событие (GameEvent) с информацией (Reward / Refuse)
// 4. Клиент получает информацию о том, можно ли пройти дальше

enum class QuestState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val id: String,
    val name: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        return when(state){
            QuestState.START -> DialogueView(
                name,
                "Привет! Нажми Talk чтобы начать диалог",
                listOf(
                    DialogueOption("talk", "Говорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                name,
                "Поможешь мне или будет драться?",
                listOf(
                    DialogueOption("help", "Помочь"),
                    DialogueOption("threat", "Давай драться")
                )
            )
            QuestState.HELP_ACCEPTED -> DialogueView(
                name,
                "СПАСИБО! ПОБЕДА",
                emptyList()
            )
            QuestState.THREAT_ACCEPTED -> DialogueView(
                name,
                "Не хочу драться, уходи",
                emptyList()
            )
        }
    }
}

// GameState (показывает только HUD)

class ClientUiState{
    // Состояния внутри него, будут обновляться от серверных данных

    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf(QuestState.START)
    val networkLagMs = mutableStateOf(350)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: ClientUiState, text: String){
    ui.log.value = (ui.log.value + text).takeLast(20)
}

sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: QuestState
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()
    // Список всех, кто реагирует на события (слушателей)
    // private - позволяет читать, вызывать и использовать список только внутри класса (сейчас только внутри GameEvent)
    fun subscribe(listener: Listener){
        listeners.add(listener)
        // .add - добавляет в конец списка
    }

    fun publish(event: GameEvent){
        // Метод рассылки событий для слушателей
        for (l in listeners){
            l(event)
        }
    }
}

// Команды - "запрос клиента на сервер"

sealed interface GameCommand{
    val playerId: String
}

data class CmdTalkToNpc(
    override val playerId: String,
    val npcId: String
) : GameCommand

data class CmdSelectChoice(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameCommand

data class CmdLoadPlayer(
    override val playerId: String,
): GameCommand

// SERVER WORLD - серверные данные и обработка команд

// PlayerData
data class PlayerData(
    var hp: Int,
    var gold: Int,
    var questState: QuestState
)
// Команда, которая ждет выполнения (симуляция пинга)
data class PendingCommand(
    val cmd: GameCommand,
    var delayLeftSec: Float
)

class ServerWorld(
    private val bus: EventBus
){
    private val questId = "q_alchemist"

    // Словарь всех игроков севрвера
    private val serverPlayers = mutableMapOf<String, PlayerData>()

    // inbox - чередь выполнения команд с учетом пинга
    private val inbox = mutableListOf<PendingCommand>()

    // Метод проверки, существования игрока в базе данных, и если его нет - создаем
    private fun  ensurePlayer(playerId: String): PlayerData{
        val existing = serverPlayers[playerId]
        if (existing != null) return existing
        // Если пользователь существует в базе данных, то вернуть его, если нет - идем дальше и создаем его

        val created = PlayerData(
            100,
            0,
            QuestState.START
        )
        serverPlayers[playerId] = created
        return created
    }
    // Снимок серверных данных
    fun getSnapshot (playerId: String) : PlayerData{
        val player = ensurePlayer(playerId)

        // Копия важна - так как мы в клиенте не может менять информацию об игроке
        // Мы отправляем (return) новый объект PlayerData, чтобы клиент не мог изменить, но мог прочесть и отобразить
        return PlayerData(
            player.hp,
            player.gold,
            player.questState
        )
    }

    // Метод для отправки команды на север от клиента
    fun sendCommand(cmd: GameCommand, networkLagMs: Int){
        val lagSec = networkLagMs / 1000f
        // Перевод миллисекунд в секунды

        // Добавляем в очередь выполнения команд
        inbox.add(
            PendingCommand(
                cmd,
                lagSec
            )
        )
    }

    // Метод update  вызывается каждый кадр, нужен для уменьшения задержки и выполнения команд, которые дошли
    fun update(deltaSec: Float){
        // delta - сколько прошло времени с прошлого кадра (Time.deltaT)

        // Уменьшаем таймер у каждой команды, за прошедшее delta время
        for (pending in inbox){
            pending.delayLeftSec -= deltaSec
        }

        // Отфильтруем очередь с отдельный список, с командами, готовыми к выполнению
        val ready = inbox.filter { it.delayLeftSec <= 0f }

        // Удаляем команды, которые надо выполнить из списка очереди
        inbox.removeAll(ready)

        for (pending in ready){
            applyCommand(pending.cmd)
        }
    }

    private fun applyCommand(cmd: GameCommand){
        val player = ensurePlayer(cmd.playerId)

        when(cmd){
            is CmdTalkToNpc -> {
                // Публикация события от сервера всей игре - это подтверждение сервера, что игрок поговрил
                bus.publish(TalkedToNpc(cmd.playerId, cmd.npcId))

                // После рассылки сервер меняет соответсвтенно правилам, которые прописанным в dialogueFor
                val newState = nextQuestState(player.questState, TalkedToNpc(cmd.playerId, cmd.npcId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }

            is CmdSelectChoice -> {
                bus.publish(ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId))

                val newState = nextQuestState(player.questState, ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }

            is CmdLoadPlayer -> {
                loadPlayerFromDisk(cmd.playerId, player)
                // После загрузки сохранения игрока - желательно тоже сохранить событием
                bus.publish(PlayerProgressSaved(cmd.playerId, "Игрок загрузил сохранения с диска"))
            }
        }
    }

    // Правила квеста (state machine)
    private fun nextQuestState(current: QuestState, event: GameEvent, npcId: String): QuestState{
        // npcId - нужен чтобы не реагировать на других нпс не связанных с этапом квеста

        if (npcId != "alchemist") return current

        return when (current){
            QuestState.START -> when (event){
                is TalkedToNpc -> QuestState.OFFERED
                else -> QuestState.START
                // Если состояние квеста START и происходит событие TalkedToNpc тогда поменять состояние квеста на OFFERED
            }
            QuestState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if(event.choiceId == "help") QuestState.HELP_ACCEPTED else QuestState.THREAT_ACCEPTED
                }
                else -> QuestState.OFFERED
            }

            QuestState.THREAT_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if(event.choiceId == "threat_confirm") QuestState.EVIL_END else QuestState.THREAT_ACCEPTED
                }
                else -> QuestState.THREAT_ACCEPTED
            }

            QuestState.HELP_ACCEPTED -> QuestState.GOOD_END
            QuestState.GOOD_END -> QuestState.GOOD_END
            QuestState.EVIL_END -> QuestState.EVIL_END
        }
    }
    private fun setQuestState(playerId: String, player: PlayerData, newState: QuestState){
        val old = player.questState
        if (newState == old) return

        player.questState = newState

        bus.publish(
            QuestStateChanged(
                playerId,
                questId,
                newState
            )
        )

        bus.publish(
            PlayerProgressSaved(
                playerId,
                "Игрок перешел на новый этап квеста ${newState.name}"
            )
        )
    }
    // Сохранение и загрузка на сервере

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}_server.save")
    }

    fun savePlayerToDisk(playerId: String){
        val player = ensurePlayer(playerId)
        val file = saveFile(playerId)

        val sb = StringBuilder()
        // Пустой сборщик строк

        sb.append("playerId=").append(playerId).append("\n")
        // append - добавление текста в конец списка
        sb.append("hp=").append(player.hp).append("\n")
        sb.append("gold=").append(player.gold).append("\n")
        sb.append("questState=").append(player.questState.name).append("\n")
        // name - превратить enum в строку например "START"

        val text = sb.toString()
        // toString - получить финальную строку из StringBuilder

        file.writeText(text)
    }

    private fun loadPlayerFromDisk(playerId: String, player: PlayerData){
        val file = saveFile(playerId)
        if (!file.exists()) return

        val map = mutableMapOf<String, String>()
        // словарь который будет в себе храть 2 части строки с учетом разделителя
        // hp=100 - в ключ занесем hp в значение 100

        for (line in file.readLines()){
            val parts = line.split("=")
            // Поделить цельную строку на 2 части с учетом разделителя =
            if (parts.size == 2){
                map[parts[0]] = parts[1]
            }
        }
        player.hp = map["hp"]?.toIntOrNull() ?: 100
        player.gold = map["gold"]?.toIntOrNull() ?: 0

        val stateName = map["questState"] ?: QuestState.START.name

        player.questState = try{
            QuestState.valueOf(stateName)
        } catch (e: Exception){
            QuestState.START
        }
    }
}

// SaveSystem - отдельная система, которая слушает события и вызывает save на сервере
class SaveSystem(
    private val bus: EventBus,
    private val server: ServerWorld
){
    init{
        bus.subscribe { event ->
            if(event is PlayerProgressSaved){
                server.savePlayerToDisk(event.playerId)
            }
        }
    }
}

class Client(
    private val ui: ClientUiState,
    private val server: ServerWorld
){
    fun send(cmd: GameCommand){
        // UI -> Server отправка команды с текущим пингом
        server.sendCommand(cmd, ui.networkLagMs.value)
    }

    fun syncFromServer(){
        // Берем снимок данных с сервера
        val snap = server.getSnapshot(ui.playerId.value)

        // После получения копии данных - обновляем клиентский UI state
        ui.hp.value = snap.hp
        ui.gold.value = snap.gold
        ui.questState.value = snap.questState
    }
}

fun main() = KoolApplication {
    val ui = ClientUiState()
    val bus = EventBus()
    val server = ServerWorld(bus)
    val saveSystem = SaveSystem(bus, server)
    val client = Client(ui, server)

    val npc = Npc("alchemist", "Алхимик")

    bus.subscribe { event ->
        val line = when(event){
            is TalkedToNpc -> "EVENT: игрок ${event.playerId} поговорил с ${event.npcId}"
            is ChoiceSelected -> "EVENT: игрок ${event.playerId} выбрал вариант ответа ${event.choiceId}"
            is QuestStateChanged -> "EVENT: квест ${event.questId} перешел на этап ${event.newState}"
            is PlayerProgressSaved -> "EVENT: Сохранено для ${event.playerId} причина - ${event.reason}"
        }
        pushLog(ui, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        onUpdate{ // Главный игровой цикл сервера
            server.update(Time.deltaT)  // сервер обрабатывает очередь команд
            client.syncFromServer() // клиент обновляет HUD из серверных данных
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            // modifier
            // выровнять по верхнему левому углу
            // отступить снаружи 16dp
            // сделать фон 0f 0f 0f 0.6f и скруглить вместе с фоном углы на 14.dp

            Column {
                // Выводите информацию о статах что за игрок, какой хп, сколько золота
                // Важно не просто получать значение .value а читать изменения состояний
                // Выводит на каком этапе квеста игрок

                // Отображаете нынешний пинг
                Row{
                    // Создаете 3 кнопки, с помощью которых вы сможете менять пинг
                    // 50ms 350ms 1200ms
                    // Так же важно сделать между кнопками отступ, чтобы они сливались
                }
                Row {
                    // Отсуп
                    // Сделать кнопку преключения игроков

                    // Отступ
                    // Кнопка загрузки сохранения игрока
                    // ВАЖНО клиент не должен загружать файл напрямую
                    // Клиент должен просить у сервера загрузить игрока
                }
            }
        }
    }


}









