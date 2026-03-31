package realGameScene

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

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

// Типы объектов игрового мира
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE
}

// Описание объекта в игровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String,
    val gold: Int
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START ->{
            val greeting =
                if (!memory.hasMet){
                    "О привет, ты кто"
                }else{
                    "Снова ты, я тебя знаю, ты же ${player.playerId}"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nХочешь помочь - тащи траву",
                listOf(
                    DialogueOption("accept_help", "Я принесу траву"),
                    DialogueOption("threat", "Травы не будет, давай товар")
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if(herbs < 3){
                DialogueView(
                    "Алхимик",
                    "Недостаточно, тебе надо $herbs/4 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "ОООООО Это отличный стафф мужик, давай сюда",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }

        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо спасибо спасибо"
                }else{
                    "Ты завершил квест, но память нпс не обновилась, иди чини код"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "Уходи, я не хочу с тобой разговаривать",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand


sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
) : GameEvent
data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

class GameServer{
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        )
    )

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _commands.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
    }

    private fun setPlayerData(playerId: String, data: PlayerState){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    fun getPlayerData(playerId: String): PlayerState{
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState){
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return
        
        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z)
        }
        // minBy = берет ближайший объект по расстоянию до игрока
        // OrNull - если таких объектов не найдено - соответственно вернуть null
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAraId = nearest?.id

        if (oldAreaId == newAraId){
            val newHint =
                when (newAraId){
                    "alchemist" -> "Подойти и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAraId != null){
            _events.emit(EnteredArea(playerId, newAraId))
        }

        val newHint =
            when (newAraId){
                "alchemist" -> "Подойти и нажми по алхимику"
                "herb_source" -> "Собери траву"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) { p ->
            p.copy(
                hintText = newHint,
                currentAreaId = newAraId
            )
        }
    }

    private suspend fun processCommand(cmd: GameCommand){
        when(cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerData(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val  oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе сейчас не нужна - сначала возьми квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val newCount = oldCount + 1
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }
                }
            }

            is CmdChooseDialogueOption -> {
                val player = getPlayerData(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if(player.questState != QuestState.START) {
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать только в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросить собрать 3 травы"))
                    }
                    "give_herb" -> {
                        if (player.questState != QuestState.WAIT_HERB) {
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }

                    else -> {
                        _events.emit(ServerMessage(cmd.playerId, "Неизвестный формат диалога"))
                    }
                }
            }

            is CmdSwitchActivePlayer -> {
                //
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) { _ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")

    val activePlayerIdUi = mutableStateOf("Oleg")

    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState) : String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory: " + player.inventory.entries.joinToString { "${it.key} x${it.value}" }
    }
}

fun currentObjective(player: PlayerState) : String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. Сейчас $herbs / 3"
            else "Вернись к алхимику и отдай 3 Травы"
        }
        QuestState.GOOD_END -> "Квест завершен по хорошей ветке"
        QuestState.EVIL_END -> "Квест завершен по плохой ветке"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "Зона: Алхимик"
        "herb_source" -> "Зона Источника травы"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился = ${memory.hasMet}, Сколько раз поговорил = ${memory.timesTalked}, Отдал траву = ${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea ${e.areaId}"
        is LeftArea -> "LeftArea ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился = ${e.memory.hasMet}, Сколько раз поговорил = ${e.memory.timesTalked}, Отдал траву = ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        herbNode.transform.translate(3f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerData(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

        herbNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map { map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map{ event ->
                eventToText(event)
            }
            .onEach{line ->
                hudLog(hud, "[${hud.activePlayerIdUi.value}] $line")
            }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Позиция: x=${"%.1f".format(player.posX)}  z=${"%.1f".format(player.posZ)}") {}
                Text("Quest State: ${player.questState}") {
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)) {
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)) {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Gold: ${player.gold}") {
                    modifier.font(sizes.smallText)
                }
                Text("Hint: ${player.hintText}") {
                    modifier.font(sizes.smallText)
                }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить игрока") {
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }
                Text("Движение в мире:") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Лево") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = -0.5f, dz = 0f))
                        }
                    }
                    Button("Право") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперед") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = -0.5f))
                        }
                    }
                    Button("Назад") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdMovePlayer(player.playerId, dx = 0f, dz = 0.5f))
                        }
                    }
                }

                Text("Взаимодействия:") { modifier.margin(top = sizes.gap) }

                Row {
                    Button("Потрогать ближайшего"){
                        modifier.margin(end = 8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                }

                Text(dialogue.npcId){ modifier.margin(top = sizes.gap) }

                Text(dialogue.text){ modifier.margin(bottom = sizes.smallGap)}

                if(dialogue.options.isEmpty()){
                    Text("Нет доступный вариантов ответа"){
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row{
                        for (option in dialogue.options){
                            Button(option.text){
                                modifier.margin(end = 8.dp).onClick{
                                    server.trySend(
                                        CmdChooseDialogueOption(
                                            player.playerId,
                                            option.id
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Лог: "){ modifier.margin(top = sizes.gap)}

                for (line in hud.log.use()){
                    Text(line){ modifier.font(sizes.smallText)}
                }

                // 1. Сделать фиксированную траекторию движения NPC
                //    Если с ним взаимодействует игрок - он останавливается

                // 2. При сборе травы - сделать кнопку не подбора, а кнопку поиска травы, где с шансом мы найдем от 1 до 3 трав
            }
        }
    }
}





