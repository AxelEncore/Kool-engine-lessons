package lesson11

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

import kotlinx.serialization.Serializable           // Аннотация, что можно сохранять
import kotlinx.serialization.json.Json              // Формат файла Json
import java.awt.Button
import java.io.File

// Когда событий становится слишком много в игре, появляется проблема:
// 1. Если все системы слушают все события, код быстро превратится в кашу
// 2. Будет сложно понять, кто на что реагирует из систем
// 3. Такие системы сложно дебажить (например почему квест не изменил состояние)
// 4. И так же надо жестко разделять, события игрока Oleg от событий игрока Stas

// Для исправления данных проблем, надо использовать flow-операторы
// filter - оставляет в потоке только то, что подходит по условию
// map - преобразует каждый элемент потока (например GameEvent -> String ля логирования)
// onEach - делает нужное действие для каждого элемента в потоке, но не изменяет сам поток
// launchIn (scope) - запускает слушателя на фоне в нужным пространстве работы корутин
// flatMapLatest - нужно для переключения игроков.
// Существет поток playerId, каждый раз, когда мы меняем игрока, будет переключаться на новый поток событий
// при этом забыв старый

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val dummyHp: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val questState: String
)

sealed interface GameEvent{
    val playerId: String
}

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
) : GameEvent

data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTick: Int,
    val intervalMs: Long
): GameEvent

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class SaveRequested(
    override val playerId: String
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    // Дополнительный небольшой буфер, что Emit при рассылке событий чаще проходил не упираясь в ограничение буфера

    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 50, 0, 0L, "START"),
            "Stas" to PlayerSave("Stas", 100, 0, 50, 0, 0L, "START")
        )
    )

    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean{
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent){
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave){
        // change - функция, которая берет старый PlayerSave и возращает новый

        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer

        _players.value = newMap.toMutableMap()
    }

    fun getPlayer(playerId: String): PlayerSave{
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0,50, 0, 0L, "START")
    }
}

class CooldownSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val cooldownJobs = mutableMapOf<String, Job>()

    fun startCooldown(playerId: String, totalMs: Long){
        cooldownJobs[playerId]?.cancel()

        server.updatePlayer(playerId) {player -> player.copy(attackCooldownMsLeft = totalMs)}

        val job = scope.launch {
            val step = 100L
            while(isActive && server.getPlayer(playerId).attackCooldownMsLeft > 0L){
                delay(step)
                server.updatePlayer(playerId) { player ->
                    val left = (player.attackCooldownMsLeft - step).coerceAtLeast(0L)
                    player.copy(attackCooldownMsLeft = left)
                }
            }
        }
        cooldownJobs[playerId] = job
    }

    fun canAttack(playerId: String): Boolean{
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }
}

class PoisonSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val poisonJobs = mutableMapOf<String, Job>()

    fun startPoison(e: PoisonApplied, publishDamage: (GameEvent) -> Unit){
            poisonJobs[e.playerId]?.cancel()

            server.updatePlayer(e.playerId) { player ->
                player.copy(poisonTicksLeft = player.poisonTicksLeft + e.ticks)
            }

            val job = scope.launch {
                while (isActive && server.getPlayer(e.playerId).poisonTicksLeft > 0){
                    delay(e.intervalMs)

                    server.updatePlayer(e.playerId) { player ->
                        player.copy(poisonTicksLeft = (player.poisonTicksLeft - 1).coerceAtLeast(0))
                    }

                    publishDamage(DamageDealt(e.playerId, "self", e.damagePerTick))
                }
            }
            poisonJobs[e.playerId] = job
    }
}

class DamageSystem(private val server: GameServer){
    fun handleDamage(e: DamageDealt){
        server.updatePlayer(e.playerId){player ->
            if(e.targetId == "self"){
                val newHp = (player.hp - e.amount).coerceAtLeast(0)
                player.copy(hp = newHp)
            }else{
                val newDummy = (player.dummyHp - e.amount).coerceAtLeast(0)
                player.copy(dummyHp = newDummy)
            }
        }
    }
}

class QuestSystem(private val server: GameServer){
    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun handleTalk(e: TalkedToNpc, publish: (GameEvent) -> Unit){
        if (e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if (player.questState == "START"){
            server.updatePlayer(e.playerId) {it.copy(questState = "OFFERED")}
            publish(QuestStateChanged(e.playerId, questId, "OFFERED"))
        }
    }

    fun handleChoice(e: ChoiceSelected, publish: (GameEvent) -> Unit){
        if (e.npcId != npcId) return

        val player = server.getPlayer(e.playerId)
        if(player.questState == "OFFERED"){
            val newState = if(e.choiceId == "help") "GOOD_END" else "EVIL_END"
            server.updatePlayer(e.playerId) {it.copy(questState = newState)}
            publish(QuestStateChanged(e.playerId, questId, newState))
        }
    }
}

class SaveSystem {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private fun file(playerId: String): File {
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave) {
        val text = json.encodeToString(PlayerSave.serializer(), player)

        file(player.playerId).writeText(text)
    }
}

class HudState{
    val activePLayerIdFlow = MutableStateFlow("Oleg")

    val activePlayerIdUi = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val dummyHp = mutableStateOf(50)
    val poisonTicksLeft = mutableStateOf(0)
    val questState = mutableStateOf("START")
    val attackCooldownMsLeft = mutableStateOf(0L)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun main() = KoolApplication {
    val hud = HudState()

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

        val server = GameServer()
        val saver = SaveSystem()
        val damage = DamageSystem(server)
        val cooldowns = CooldownSystem(server, coroutineScope)
        val poison = PoisonSystem(server, coroutineScope)
        val quests = QuestSystem(server)

        // Подписка на события 1:
        // Получает событие только AttackPressed -> проверяем кулдаун -> вызываем DamageDealt + запуск кулдауна по новой
        server.events
            .filter { it is AttackPressed }
            .onEach{ event ->
                val e = event as AttackPressed
                // as - приведение типа (мы уверены, что событием здесь будет AttackPressed так как выше - мы их отфильтровали

                if(!cooldowns.canAttack(e.playerId)){
                    val msg = ServerMessage(e.playerId, "Нельзя атаковать: кулдаун")
                    if (!server.tryPublish(msg)){
                        coroutineScope.launch { server.publish(msg) }
                    }
                    return@onEach
                    // выход из onEach блока, но не из всех функции
                }

                val dmg = DamageDealt(e.playerId, e.targetId, 10)

                if (!server.tryPublish(dmg)){
                    coroutineScope.launch { server.publish(dmg) }
                }

                cooldowns.startCooldown(e.playerId, 1200L)
            }
            .launchIn(coroutineScope)
            // Собирает и запускает поток в области корутин что указали coroutineScope

        // Подписка 2: DamageDealt вызывает DamageSystem для смены HP
        server.events
            .filter{ it is DamageDealt }
            .onEach { event ->
                val e = event as DamageDealt
                damage.handleDamage(e)
            }
            .launchIn(coroutineScope)

        // Подписка 3: PoisonApplied -> корутина тиков -> DamageDealt

        server.events
            .filter { it is PoisonApplied }
            .onEach { event ->
                val e = event as PoisonApplied

                poison.startPoison(e){ dmg ->
                    if (!server.tryPublish(dmg)){
                        coroutineScope.launch { server.publish(dmg) }
                    }
                }
            }
            .launchIn(coroutineScope)

        // Подписчик 4: TalkedToNpc и ChoiceSelected -> квест
        server.events
            .filter { it is TalkedToNpc }
            .onEach { event ->
                val e = event as TalkedToNpc
                quests.handleTalk(e){ newEvent ->
                    if (!server.tryPublish(newEvent)){
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is ChoiceSelected }
            .onEach { event ->
                val e = event as ChoiceSelected
                quests.handleChoice(e){ newEvent ->
                    if (!server.tryPublish(newEvent)){
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter { it is QuestStateChanged }
            .onEach { event ->
                val e = event as QuestStateChanged
                val save = SaveRequested(e.playerId)

                if (!server.tryPublish(save)){
                    coroutineScope.launch { server.publish(save) }
                }
            }
            .launchIn(coroutineScope)

        server.events
            .filter{ it is SaveRequested }
            .onEach { event ->
                val e = event as SaveRequested
                val snapShot = server.getPlayer(e.playerId)
                saver.save(snapShot)
            }
            .launchIn(coroutineScope)

        Shared.server = server
        // Учебный мост между сервером и UI
        // Связывает данные между двумя сценами
    }

    addScene {
        setupUiScene(ClearColorLoad)

        val server = Shared.server

        if (server != null){
            coroutineScope.launch {
                server.players.collect{ playersMap ->
                    val pid = hud.activePLayerIdFlow.value
                    val p = playersMap[pid] ?: return@collect

                    hud.hp.value = p.hp
                    hud.gold.value = p.gold
                    hud.dummyHp.value = p.dummyHp
                    hud.poisonTicksLeft.value = p.poisonTicksLeft
                    hud.questState.value = p.questState
                    hud.attackCooldownMsLeft.value = p.attackCooldownMsLeft
                }
            }

            // Маршрутизация событий по активному игроку
            // activePLayerIdFlow -> flatMapLatest -> поток событий только для этого игрока

            hud.activePLayerIdFlow
                .flatMapLatest { pid ->
                    // flatMapLatest означает - "каждый раз когда pid меняется -> переключиться на новый поток"
                    // и перестать слушать старый
                    server.events.filter { it.playerId == pid}
                    // Теперь фильтруем только по событиям игрока
                }
                .map{ event ->
                    // Превращает событие в строку лога
                    eventToText(event)
                }
                .onEach { line ->
                    hudLog(hud, line)
                }
                .launchIn(coroutineScope)
        }

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Player: ${hud.activePlayerIdUi.use()}") { }
                Text("HP: ${hud.hp.use()}   Gold: ${hud.gold.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("DummyHp: ${hud.dummyHp.use()}"){}
                Text("QuestState: ${hud.questState.use()}") { }
                Text("Poison ticks left: ${hud.poisonTicksLeft.use()}") { }
                Text("Attack cooldown: ${hud.attackCooldownMsLeft.use()} ms") {
                    modifier.margin(bottom = sizes.gap)
                }

                Row {
                    Button("Смена игрока"){
                        // получить новый id пользователя
                        // обновляем в hud состояния activePlayerIdUi и activePlayerIdFlow
                    }
                }
            }

        }
    }
}

fun eventToText(e: GameEvent): String{
    return when (e){
        is AttackPressed -> "[${e.playerId}] AttackPressed по ${e.targetId}"
        is DamageDealt -> "[${e.playerId}] DamageDealt ${e.amount} по ${e.targetId}"
        is PoisonApplied -> "[${e.playerId}] PoisonApplied ${e.ticks}"
        is SaveRequested -> "[${e.playerId}] SaveRequested"
        is ChoiceSelected -> "[${e.playerId}] ChoiceSelected ${e.choiceId}"
        is ServerMessage -> "[${e.playerId}] ServerMessage ${e.text}"
        is TalkedToNpc -> "[${e.playerId}] TalkedToNpc ${e.npcId}"
        is QuestStateChanged -> "[${e.playerId}] QuestStateChanged ${e.newState}"
    }
}

object Shared{
    var server: GameServer? = null
}
































