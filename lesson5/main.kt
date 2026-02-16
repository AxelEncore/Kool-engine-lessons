package lesson5

import de.fabmax.kool.modules.ui2.*
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
    // 1 новые слоты после удалчения 2 значение получилось ли удалить все
    // - Создать новый список, и создать переменную счетчик которая по умолчанию = count
    // перебераете все слоты
        // находите слот по индексу
        // проверяете на id предмета и что чисто предметов, которые надо удалить > 0
            // считаем манимальное от нужного удаления и числа в стаке
            // считаем сколько осталось в стаке после удаления
            // и отнимаем от счетчика отстаток (need)
            // в новый список слотов[по индексу] кладем с проверкой:
            // если остаток в слоте <= 0 то положить null
            // В любом другом случае положить ItemStack(предмет, остаток в слоте)
    // Если в итоге need == 0 то сохранить в переменную success true или false
    // Вернуть пару значений с новыми слотами и успехом или провалом
}















