package lesson5

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....

import java.io.File

enum class ItemType{
    POTION,
    QUEST_ITEM,
    MONEY
}

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

data class ItemStack(
    val item: Item,
    val count: Int
)

val HERB = Item(
    "Herb",
    "Herb",
    ItemType.QUEST_ITEM,
    16
)

val HEAL_POTION = Item(
    "potion_heal",
    "Heal Potion",
    ItemType.POTION,
    6
)

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val inventory = mutableStateOf(List<ItemStack?>(5) {null})
    // List<ItemStack?>(5) { null } - 5 слотов, каждый пустой (null) по умолчанию

    val selectedSlot = mutableStateOf(0)
    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.log.value = (game.log.value + text).takeLast(20)
    // takeLast - оставлять только последние 20 строк списка, удаляя старые
    // (Старый список + новая строка лога) -> новый список
}

// События - что происходит в игре

// sealed - иерархия классов, который содержит в себе дочерние классы (в данном примере события)
// interface - это набор правил, которые дочерний класс, обязательно должен перезаписать
sealed interface GameEvent{
    val playerId: String
}

// События квеста/диалога
data class TalkedNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class ItemGivenToNpc(
    override val playerId: String,
    val npcId: String,
    val itemId: String,
    val count: Int
): GameEvent

// Событие изменения состояния квеста (удобно для логов и для реакции на любые изменения)
data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

// Событие успешного сохранения прогресса
data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stateName: String
): GameEvent

// Система подписок и рассылок событий Event Bus
typealias Listener = (GameEvent) -> Unit
// typealias - псевдоним типа данных (переменная для типов данных)
// (GameEvent) -> Unit - означает, что по умолчанию функция которая принимает событие - ничего не возвращает (Unit)

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

// Графы состояний (State Graph) - для системы не линейного квеста
// QuestState - набор состояний (этапов) квеста
// Это и есть наши узлы графа (ноды)
enum class QuestState{
    START,
    OFFERED,
    ACCEPTED_HELP,
    ACCEPTED_THREAT,
    HERB_COLLECTED,
    GOOD_END,
    EVIL_END
}

// StateGraph - нынешнее состояние + событие -> новое состояние
// ЗДЕСЬ:
// S - Сокращение State (состояние)
// E - Сокращение Event (событие)
class StateGraph<S:Any, E: Any>(
    private val initial: S
){
    // Карта переходов (transitions)
    // Из состояния S -> (тип события -> функция, которая вычисляет новое состояние
    private val transitions = mutableMapOf<S, MutableMap<Class<out E>, (E) -> S>>()

    // Метод добавления перехода
    fun on(from: S, eventClass: Class<out E>, to: (E) -> S){
        val byEvent = transitions.getOrPut(from){mutableMapOf()}
        // getOrPut - если ключа в словаре не находит - положить то, что указали в {...}
        byEvent[eventClass] = to
    }

    fun next(current: S, event: E): S {
        // 1. берем карту переходов для текущего состояния
        val byEvent = transitions[current] ?: return current
        // ?: - если переходов для данного события нет (null) тогда остаемся в этом же состоянии

        // 2. Брем класс события
        // event::class.java - "Реальный тип события во время его выполнения"
        // Например: ChoiceSelected::class.java
        val eventClass = event::class.java

        // 3. Ищем обработчик(handler) для этого типа события
        val handler = byEvent[eventClass] ?: return current

        // 4. Передаем новое состояние после перехода
        return handler(event)
    }

    fun initialState(): S{
        return initial
    }
}

// Квестовая система
class QuestSystem(
    private val bus: EventBus
){
    val questId = "q_alchemist"

    // У каждого игрока фиксируется свой прогресс квеста
    val stateByPlayer = mutableStateOf<Map<String, QuestState>>(emptyMap())
    // Словарь состояний прохождения квеста для каждого игрока
    // Ключ playerId -> значение его состояние квеста

    // Граф квеста
    private val graph = StateGraph<QuestState, GameEvent>(QuestState.START)

    // Инициализация - действие происходит только при первом запуске системы
    init{
        // Настройка переходов и графов (узлы и стрелки из них)

        // Цепочка START -> (TalkedToNpc) -> OFFERED
        graph.on(QuestState.START, TalkedNpc::class.java){ _ ->
            QuestState.OFFERED
        }

        graph.on(QuestState.OFFERED, ChoiceSelected::class.java){ e ->
            val ev = e as ChoiceSelected
            if (ev.choiceId == "help") QuestState.ACCEPTED_HELP else QuestState.ACCEPTED_THREAT
        }

        graph.on(QuestState.ACCEPTED_HELP, ItemCollected::class.java){e ->
            val ev = e as ItemCollected
            if(ev.itemId == HERB.id) QuestState.HERB_COLLECTED else QuestState.ACCEPTED_HELP
        }

        graph.on(QuestState.HERB_COLLECTED, ChoiceSelected::class.java){ e ->
            val ev = e as ItemGivenToNpc
            if (ev.itemId == HERB.id) QuestState.GOOD_END else QuestState.HERB_COLLECTED
        }

        graph.on(QuestState.ACCEPTED_THREAT, ChoiceSelected::class.java){ e ->
            val ev = e as ChoiceSelected
            if (ev.choiceId == "threaten_confirm") QuestState.EVIL_END else QuestState.ACCEPTED_THREAT
        }

        bus.subscribe { event ->
            advance(event)
        }
    }

    fun getState(playerId: String): QuestState{
        return stateByPlayer.value[playerId] ?: graph.initialState()
    }

    fun setState(playerId: String, state: QuestState){
        val copy = stateByPlayer.value.toMutableMap()
        copy[playerId] = state
        stateByPlayer.value = copy.toMap()
    }

    private fun advance(event: GameEvent){
        val pid = event.playerId
        val current = getState(pid)
        val next = graph.next(current, event)

        if(next != current){
            setState(pid, next)

            // После перехода на новое состояние квеста (этап) запускаем авто-сохранение
            bus.publish(
                QuestStateChanged(
                    pid,
                    questId,
                    next.name
                )
            )

            bus.publish(
                PlayerProgressSaved(
                    pid,
                    questId,
                    next.name
                )
            )
        }
    }
}

class SaveSystem(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: QuestSystem
){
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved){
                save(event.playerId, event.questId, event.stateName)
            }
        }
    }

    private fun saveFile(playerId: String, questId: String): File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()

        return File(dir, "${playerId}_${questId}.save")
    }

    private fun save(playerId: String, questId: String, stateName: String){
        val f = saveFile(playerId, questId)

        // Подготовка формата сохранения данных
        // Простой формат ключ = значение
        val text =
            "playerId=$playerId\n" +
                    "questId=$questId\n" +
                    "state=$stateName\n" +
                    "hp=${game.hp.value}\n" +
                    "gold=${game.gold.value}\n"

        f.writeText(text)
    }

    fun load(playerId: String, questId: String){
        val f = saveFile(playerId, questId)
        if (!f.exists()) return
        // Если файла сохранения нет, ничего не загружать

        val map = mutableMapOf<String, String>()
        for (line in f.readLines()){
            val parts = line.split("=")
            // Сохраняем в виде списка значения которые разделены "="
            if (parts.size == 2) map[parts[0]] = parts[1]
        }

        val stateName = map["state"] ?: QuestState.START.name
        // Получаем название состояния в котором находится игрок в сохранении
        // Если в сохранении пока нет этого состояния - то по умолчанию START
        val hp = map["hp"]?.toIntOrNull() ?: 100
        // Достаем информацию о здоровье игрока из сохранения, и если оно null, то по умолчанию сделать 100
        val gold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = hp
        game.gold.value = gold

        // Превращение строки обратно в enum
        val loadedState = runCatching { QuestState.valueOf(stateName)}.getOrElse { QuestState.START }
        quests.setState(playerId, loadedState)
    }
}

// Методы работы с инвентарем (InventoryHelpers)

fun addItem(
    slots: List<ItemStack?>,
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int>{
    // Возвращает пару значений, а именно Новый список инвентаря и остаток предметов, что не поместились
    var left = addCount
    val newSlots = slots.toMutableList()

    // 1. Сначала пробуем стакать в уже существующие ячейки с предметами
    for (i in newSlots.indices){
        val s = newSlots[i] ?: continue
        if (s.item.id == item.id && item.maxStack > 1 && left > 0){
            val free = item.maxStack - s.count
            val toAdd = minOf(left, free)
            newSlots[i] = ItemStack(item, s.count + toAdd)
            left -= toAdd
        }
    }

    // Если слотов с предметами не найдено, либо они заполнены - кладем в пустые
    for (i in newSlots.indices){
        if(left <= 0) break
        // Если больше нет предметов, которые нужно класть - прерываем
        if (newSlots[i] == null){
            // Если слот пустой, класть в него
            val toPlace = minOf(left, item.maxStack)
            newSlots[i] = ItemStack(item, toPlace)
            left -= toPlace
        }
    }

    return Pair(newSlots, left)
}
fun removeItem(
    slots: List<ItemStack?>,
    itemId: String,
    count: Int
): Pair<List<ItemStack?>, Boolean>{
    var need = count
    val newSlots = slots.toMutableList()

    for (i in newSlots.indices){
        val s = newSlots[i] ?: continue
        if (s.item.id == itemId && need > 0){
            val take = minOf(need, s.count)
            val leftInStack = s.count - take
            need -= take

            newSlots[i] = if (leftInStack <= 0) null else ItemStack(s.item, leftInStack)
        }
    }

    val success = (need == 0)
    return Pair(newSlots, success)
}

// ДИАЛОГОВАЯ ЦЕПОЧКА
data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val id: String,
    val name: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        // Метод в котором нпс говорит разные фразы в зависимости от состояния квеста

        return when(state){
            QuestState.START -> DialogueView(
                "[$name] Привет! Подходи, перетрем о травах",
                listOf(
                    DialogueOption("talk", " Поговорить")
                )
            )

            QuestState.OFFERED -> DialogueView(
                "[$name] Мне нужна трава, поможешь?",
                listOf(
                    DialogueOption("help", "Помочь(принести траву)"),
                    DialogueOption("threat", "Угрожать(требовать золото)")
                )
            )

            QuestState.ACCEPTED_HELP -> DialogueView(
                "[$name] Отлично, буду ждать тебя здесь",
                listOf(
                    DialogueOption("collect_herb", "Собрать траву (симуляция, все совпадения лишь ошибка, ничего личного"),
                    DialogueOption("talk", "Поговорить еше раз")
                )
            )

            QuestState.HERB_COLLECTED -> DialogueView(
                "[$name] Ты принес траву? ДАЙ ЕЕ МНЕ",
                listOf(
                    DialogueOption("give_herb", "Отдать 1 траву")
                )
            )

            QuestState.ACCEPTED_THREAT -> DialogueView(
                "[$name] Ты уверен мабой?",
                listOf(
                    DialogueOption("threat_confirm", "Да, гони золото")
                )
            )

            QuestState.GOOD_END -> DialogueView(
                "[$name] Хорош! Знал что можно на тебя положиться, держи золото",
                emptyList()
                // Больше вариантов выбора нет
            )
            QuestState.EVIL_END -> DialogueView(
                "[$name] Ладно, забирай золото, но я тебя запомнил, братки подъедут",
                emptyList()
            )
        }
    }
}

fun main() = KoolApplication{

    val game = GameState()
    val bus = EventBus()
    val quests = QuestSystem(bus)
    val saves = SaveSystem(bus, game, quests)

    val npc = Npc("alchemist", "Alchemist")

    bus.subscribe { e ->
        val line = when(e){
            is QuestStateChanged -> "Состояние квеста ${e.questId} изменено -> ${e.newState}"
            is PlayerProgressSaved -> "Сохранился прогресс ${e.questId} игрока ${e.playerId} в состоянии ${e.stateName}"
            is TalkedNpc -> "Поговорил с Npc: ${e.npcId}"
            is ChoiceSelected -> "Выбран вариант: ${e.choiceId}"
            is ItemCollected -> "Получен предмет: ${e.itemId} x${e.count}"
            is ItemGivenToNpc -> "Предмет ${e.itemId} x${e.count} передан ${e.npcId}"
        }
        pushLog(game, "[${e.playerId}] $line")
    }

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
    }


    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .padding(12.dp)
                .background(RoundRectBackground(Color(0f,0f,0f, 0.6f), 14.dp))

            Column {
                // --------- СТАТЫ -----------
                Text("Игрок ${game.playerId.use()}"){}
                Text("HP ${game.hp.use()}"){}

                modifier.margin(bottom = sizes.gap)

                // --------- КВЕСТОВЫЕ СОСТОЯНИЯ -----------
                val state = quests.stateByPlayer.use()[game.playerId.use()] ?: QuestState.START
                Text("Состояние квеста: ${state.name}"){}

                modifier.margin(bottom = sizes.gap)

                // --------- Диалог NPC -----------
                val view = npc.dialogueFor(state)

                Text("${npc.name}"){}
                Text(view.text){}

                modifier.margin(bottom = sizes.gap)

                // --------- Кнопки выбора -----------
                Row {
                    for (opt in view.options){
                        Button(opt.text){
                            modifier
                                .margin(end = 8.dp)
                                .onClick{
                                    val pid = game.playerId.value

                                    // Специальная логика для кнопок
                                    when(opt.id){
                                        "talk" -> {
                                            bus.publish(TalkedNpc(pid, npc.id))
                                        }

                                        "collected_herb" -> {
                                            val (updated, left) = addItem(game.inventory.value, HERB, 1)
                                            game.inventory.value = updated

                                            bus.publish(ItemCollected(pid, HERB.id, 1))

                                            if (left > 0) game.gold.value += left
                                        }

                                        "give_herb" -> {
                                            val (updated, succes) = removeItem(game.inventory.value, HERB.id, 1)
                                            game.inventory.value = updated

                                            if (succes){
                                                bus.publish(ItemGivenToNpc(pid, npc.id, HERB.id, 1))

                                                // Награда за хорошую концовку
                                                val (inv2, left) = addItem(game.inventory.value, HEAL_POTION, 6)
                                                game.inventory.value = inv2
                                                if (left > 0) game.gold.value += left
                                            }else{
                                                pushLog(game, "[$pid] Нет травы, чтобы отдать")
                                            }
                                        }

                                        "threat" -> {
                                            bus.publish(ChoiceSelected(pid, npc.id, "threat"))
                                        }
                                        "help" -> {
                                            bus.publish(ChoiceSelected(pid, npc.id, "help"))
                                        }

                                        "threaten_confirm" -> {
                                            bus.publish(ChoiceSelected(pid, npc.id, "threaten_confirm"))

                                            game.gold.value += 10
                                        }

                                        else -> {
                                            bus.publish(ChoiceSelected(pid, npc.id, opt.id))
                                        }
                                    }
                                }
                        }
                    }
                }
                // --------- ПРОСТАЯ ВИЗУАЛИЗАЦИЯ ИНВЕНТАРЯ -----------
                Text("Инвентарь"){}
                modifier.margin(bottom = sizes.gap)

                val inv = game.inventory.use()
                for (i in inv.indices){
                    val s = inv[i]
                    val text = if (s == null) "[${i + 1}] (пуст)" else "[${i + 1}] ${s.item.name} x${s.count}"
                    Text(text) {modifier.font(sizes.smallText)}
                }
            }
        }
    }
}











