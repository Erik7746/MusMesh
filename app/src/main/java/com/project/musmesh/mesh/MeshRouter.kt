package com.project.musmesh.mesh

import android.content.Context
import android.os.SystemClock
import com.project.musmesh.ble.*
import com.project.musmesh.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class RoomSnapshot(val room: UUID = Ids.EMPTY_ROOM, val root: Long = 0, val peers: Map<Long, MeshPeer> = emptyMap(),
    val tree: DistributionTree = DistributionTree(0, emptyMap()), val direct: Map<Long, LinkMetrics> = emptyMap(),
    val rssi: Map<Long, Int> = emptyMap(), val admitted: Boolean = false)

class MeshRouter(context: Context, private val scope: CoroutineScope, val nodeId: Long, val session: Long,
    val isServer: Boolean, private val code: String, initialRoom: UUID, initialRoot: Long,
    private val onPacket: (MeshPacket, Long) -> Unit, private val onRoom: (UUID, Long) -> Unit,
    private val onError: (String) -> Unit, savedPeers: List<MeshPeer> = emptyList()) {
    private val codec = PacketCodec(RoomAuthenticator(code))
    private val deduplicator = PacketDeduplicator()
    private val _state = MutableStateFlow(RoomSnapshot(initialRoom, initialRoot))
    val state = _state.asStateFlow()
    private var sequence = 0L
    private var topologyVersion = 0L
    private val peers = mutableMapOf<Long, MeshPeer>()
    private val neighbors = mutableMapOf<Long, List<MeshEdge>>()
    private val seenAt = mutableMapOf<Long, Long>()
    private val linkSessions = mutableMapOf<Long, Long>()
    private val provisionalRoutes = mutableMapOf<Long, Long>()
    private val helloChallenges = mutableMapOf<UUID, Long>()
    private val admission = AdmissionGuard()
    private val joinAttempt = JoinAttempt()
    private var rosterSequence = 0L
    private var rootSession = if (isServer) session else 0L
    private var topologyJob: Job? = null
    private var ticker: Job? = null
    private var stopped = false
    private val transport = BleLinkManager(context, scope, nodeId, ::connected, ::disconnected, ::receive, onError)
    init {
        if (isServer) {
            peers.putAll(savedPeers.take(8).associateBy { it.id })
            peers[nodeId] = MeshPeer(nodeId, session)
            _state.value = RoomSnapshot(initialRoom, nodeId, peers.toMap(), DistributionTree(nodeId, emptyMap()), admitted = true)
        }
    }
    fun start() {
        transport.start(code)
        ticker = scope.launch {
            var count = 0
            while (isActive) {
                delay(2000)
                // Reissue admission and state: control messages are idempotent and bounded to eight peers.
                if (_state.value.root != 0L && !isServer && !_state.value.admitted) requestJoin()
                if (++count % 3 == 0) {
                    sendPeerInfo()
                    if (isServer) { broadcastRoster(); rebuild() }
                    transport.links.values.filter { it.nodeId == null && SystemClock.elapsedRealtime() - it.connectedAt > 12000 }.toList().forEach { it.disconnect() }
                }
                publish()
            }
        }
    }
    private fun packet(type: PacketType, destination: Long, payload: ByteArray) = MeshPacket(type, Ids.node(), nodeId,
        destination, _state.value.room, session, ++sequence, payload = payload)
    suspend fun send(type: PacketType, destination: Long, payload: ByteArray = byteArrayOf()): Boolean =
        route(packet(type, destination, payload))
    private suspend fun sendLink(link: BleLink, packet: MeshPacket) = runCatching { link.send(codec.encode(packet), packet.type.priority) }.getOrDefault(false)
    private suspend fun route(packet: MeshPacket, exclude: Long? = null): Boolean {
        if (stopped) return false
        val available = transport.links.values.filter { it.nodeId != null && it.nodeId != exclude }
        if (packet.destination == ProtocolConstants.BROADCAST) {
            if (packet.type !in BROADCAST_TYPES) return false
            return coroutineScope { available.map { async { sendLink(it, packet) } }.awaitAll().any { it } }
        }
        // Control packets can use a direct shortcut; data never travels outside its assigned tree edge.
        val direct = available.firstOrNull { it.nodeId == packet.destination }
        if (packet.type == PacketType.BLOCK_DATA && direct == null) return false
        val next = direct
            ?: available.firstOrNull { it.nodeId == _state.value.tree.nextHop(nodeId, packet.destination) }
            ?: available.firstOrNull { it.nodeId == provisionalRoutes[packet.destination] }
            ?: return false
        return sendLink(next, packet)
    }
    private fun hello(link: BleLink, echo: Long = 0) = WireWriter().long(_state.value.root).long(rootSession)
        .long(helloChallenges.getOrPut(link.generation) { Ids.node() }).long(echo).build()
    private fun connected(link: BleLink) { scope.launch { sendLink(link, packet(PacketType.HELLO, 0, hello(link))) } }
    private fun disconnected(link: BleLink) {
        helloChallenges.remove(link.generation)
        val id = link.nodeId ?: return
        linkSessions.remove(id); neighbors.remove(id); provisionalRoutes.entries.removeAll { it.value == id }
        neighbors[nodeId] = localEdges()
        if (!stopped) { scope.launch { sendPeerInfo() }; if (isServer) rebuild(); publish() }
    }
    private fun receive(link: BleLink, bytes: ByteArray) {
        val receivedAt = SystemClock.elapsedRealtimeNanos()
        val packet = codec.decode(bytes) ?: return
        if (packet.sender == nodeId) return
        if (packet.type == PacketType.HELLO || packet.type == PacketType.HELLO_ACK) {
            val proof = decodePayload(packet.payload) { List(4) { long().also { require(it >= 0) } } } ?: return
            val root = proof[0]
            if ((root == 0L) != (proof[1] == 0L) || proof[2] == 0L) return
            if (_state.value.room != Ids.EMPTY_ROOM && packet.room != Ids.EMPTY_ROOM && packet.room != _state.value.room) { link.disconnect(); return }
            if (_state.value.root != 0L && root != 0L && root != _state.value.root) { link.disconnect(); return }
            if (packet.type == PacketType.HELLO) {
                scope.launch {
                    sendLink(link, packet(PacketType.HELLO_ACK, packet.sender, hello(link, proof[2])))
                    if (link.nodeId == null || (_state.value.root == 0L && root > 0)) sendLink(link, packet(PacketType.HELLO, packet.sender, hello(link)))
                }
                return
            }
            if (proof[3] != helloChallenges[link.generation]) return
            val wasBound = link.nodeId != null
            var roomChanged = false
            if ((_state.value.room == Ids.EMPTY_ROOM || rootSession == 0L || packet.sender == root) && root > 0 && packet.room != Ids.EMPTY_ROOM) {
                roomChanged = rootSession != proof[1] || _state.value.room != packet.room || _state.value.root != root
                rootSession = proof[1]
                if (roomChanged) {
                    _state.value = _state.value.copy(room = packet.room, root = root, tree = DistributionTree(root, emptyMap()))
                    onRoom(packet.room, root)
                }
            }
            if (link.nodeId != null && link.nodeId != packet.sender) { link.disconnect(); return }
            val duplicate = transport.links.values.firstOrNull { it !== link && it.nodeId == packet.sender }
            if (duplicate != null) { link.disconnect(); return }
            link.nodeId = packet.sender; linkSessions[packet.sender] = packet.session
            seenAt[packet.sender] = SystemClock.elapsedRealtime()
            scope.launch {
                if (!wasBound) sendLink(link, packet(PacketType.HELLO_ACK, packet.sender, hello(link, proof[2])))
                // Peers may have connected before either knew the room. Bootstrap their existing links too.
                if (roomChanged) announceRoom(except = link)
                if (_state.value.root > 0) {
                    if (!isServer) requestJoin()
                    sendPeerInfo()
                    if (isServer) broadcastRoster()
                }
            }
            publish(); return
        }
        if (link.nodeId == null || packet.room != _state.value.room || _state.value.room == Ids.EMPTY_ROOM) return
        val membership = peers[packet.sender]
        val bootstrap = packet.type in setOf(PacketType.ROOM_JOIN, PacketType.ROOM_ACCEPT, PacketType.ROOM_REJECT)
        if (!bootstrap && (membership == null || membership.session != packet.session)) return
        if (!deduplicator.accept(packet.sender, packet.session, packet.sequence)) return
        if (provisionalRoutes.size < 16 || packet.sender in provisionalRoutes) provisionalRoutes[packet.sender] = requireNotNull(link.nodeId)
        seenAt[packet.sender] = SystemClock.elapsedRealtime()
        if (packet.destination != nodeId && packet.destination != 0L) {
            if (packet.ttl > 0 && packet.type != PacketType.BLOCK_DATA) scope.launch { route(packet.copy(ttl = packet.ttl - 1), link.nodeId) }
            return
        }
        when (packet.type) {
            PacketType.ROOM_JOIN -> {
                val join = decodePayload(packet.payload) { val phase = byte(); val nonce = long(); val targetSession = long(); val echo = long(); require(phase in 0..2 && nonce >= 0 && targetSession > 0 && echo > 0); JoinMessage(phase, nonce, targetSession, echo) } ?: return
                if (isServer) {
                    if (packet.sender !in peers && peers.size >= ProtocolConstants.MAX_NODES) scope.launch { send(PacketType.ROOM_REJECT, packet.sender) }
                    else if (join.phase == 0) {
                        admission.challenge(packet.sender, packet.session, SystemClock.elapsedRealtime())?.let { nonce ->
                            scope.launch { send(PacketType.ROOM_JOIN, packet.sender, WireWriter().byte(1).long(nonce).long(packet.session).long(join.echo).build()) }
                        }
                    } else if (join.phase == 2 && admission.confirm(packet.sender, packet.session, join.nonce, SystemClock.elapsedRealtime())) {
                        peers[packet.sender] = MeshPeer(packet.sender, packet.session, peers[packet.sender]?.required ?: true)
                        deduplicator.installSession(packet.sender, packet.session)
                        scope.launch { broadcastRoster(); sendPeerInfo() }; rebuild()
                    }
                } else if (packet.sender == _state.value.root && join.phase == 1 && join.targetSession == session && joinAttempt.matches(join.echo, SystemClock.elapsedRealtime())) {
                    // Fresh challenge proves the live root epoch, including across a root process restart.
                    val epochChanged = rootSession != packet.session
                    rootSession = packet.session
                    scope.launch {
                        send(PacketType.ROOM_JOIN, packet.sender, WireWriter().byte(2).long(join.nonce).long(session).long(join.echo).build())
                        if (epochChanged) announceRoom()
                    }
                }
            }
            PacketType.ROOM_ACCEPT -> if (!isServer && packet.sender == _state.value.root && packet.session == rootSession) {
                val oldRootSession = peers[_state.value.root]?.session
                if (oldRootSession == packet.session && packet.sequence <= rosterSequence) return
                val roster = decodePayload(packet.payload) {
                    val count = byte(); require(count in 1..8)
                    List(count) { val id = long(); val session = long(); val required = byte(); require(id > 0 && session > 0 && required in 0..1); MeshPeer(id, session, required == 1) }
                        .also { require(it.map { peer -> peer.id }.distinct().size == count) }
                } ?: return
                if (roster.none { it.id == _state.value.root && it.session == packet.session }) return
                if (oldRootSession != null && oldRootSession != packet.session) topologyVersion = 0
                rosterSequence = packet.sequence
                peers.clear(); peers.putAll(roster.associateBy { it.id })
                roster.forEach { deduplicator.installSession(it.id, it.session) }
                _state.value = _state.value.copy(admitted = peers[nodeId]?.session == session)
                if (_state.value.admitted) joinAttempt.complete()
                publish(); scope.launch { sendPeerInfo() }
            }
            PacketType.ROOM_REJECT -> onError("La sala ya tiene ocho dispositivos")
            PacketType.PEER_INFO -> {
                val edges = decodePayload(packet.payload) {
                    val count = byte(); require(count <= 7)
                    List(count) { val target = long(); val rssi = int(); val errors = short(); val stable = int()
                        require(target > 0 && target != packet.sender && rssi in -127..0 && stable >= 0)
                        MeshEdge(packet.sender, target, rssi, errors, stable.toLong()) }
                } ?: return
                neighbors[packet.sender] = edges
                if (isServer) rebuild()
            }
            PacketType.TOPOLOGY_UPDATE, PacketType.PARENT_ASSIGN -> if (!isServer && packet.sender == _state.value.root) {
                val tree = decodePayload(packet.payload) {
                    val version = long(); require(version >= topologyVersion)
                    val count = byte(); require(count <= 7)
                    val parents = mutableMapOf<Long, Long>(); val alternatives = mutableMapOf<Long, Long>()
                    repeat(count) { val child = long(); val parent = long(); val alt = long()
                        require(child in peers && parent in peers && child != _state.value.root && child !in parents)
                        parents[child] = parent; if (alt != 0L) alternatives[child] = alt }
                    val tree = DistributionTree(_state.value.root, parents, alternatives)
                    require(parents.keys.all { tree.path(it).size in 2..5 } && peers.keys.all { tree.children(it).size <= 2 })
                    version to tree
                } ?: return
                topologyVersion = tree.first; _state.value = _state.value.copy(tree = tree.second); publish()
            }
            else -> if (_state.value.admitted) onPacket(packet, receivedAt)
        }
        if (packet.destination == 0L && packet.ttl > 0 && packet.type in BROADCAST_TYPES) scope.launch { route(packet.copy(ttl = packet.ttl - 1), link.nodeId) }
    }
    private fun localEdges() = transport.links.values.mapNotNull { link -> link.nodeId?.let {
        MeshEdge(nodeId, it, link.rssi.coerceIn(-127, 0), link.queue.metrics.errors.toInt(), (SystemClock.elapsedRealtime() - link.connectedAt) / 1000)
    } }
    private suspend fun announceRoom(except: BleLink? = null) {
        for (link in transport.links.values.toList()) if (link !== except) sendLink(link, packet(PacketType.HELLO, link.nodeId ?: 0, hello(link)))
    }
    private data class JoinMessage(val phase: Int, val nonce: Long, val targetSession: Long, val echo: Long)
    private suspend fun requestJoin() {
        send(PacketType.ROOM_JOIN, 0, WireWriter().byte(0).long(0).long(session).long(joinAttempt.begin(SystemClock.elapsedRealtime())).build())
    }
    private suspend fun sendPeerInfo() {
        if (!_state.value.admitted) return
        val edges = localEdges(); neighbors[nodeId] = edges
        val payload = WireWriter().byte(edges.size).apply { edges.forEach { long(it.to).int(it.rssi).short(it.errors.coerceAtMost(65535)).int(it.stableSeconds.toInt()) } }.build()
        send(PacketType.PEER_INFO, 0, payload)
    }
    private suspend fun broadcastRoster() {
        if (!isServer) return
        send(PacketType.ROOM_ACCEPT, 0, WireWriter().byte(peers.size).apply { peers.values.forEach { long(it.id).long(it.session).byte(if (it.required) 1 else 0) } }.build())
        publish()
    }
    private fun rebuild() {
        if (!isServer || topologyJob?.isActive == true) return
        topologyJob = scope.launch {
            delay(150)
            val now = SystemClock.elapsedRealtime()
            val fresh = neighbors.filterKeys { it == nodeId || now - (seenAt[it] ?: 0) < 18000 }
            val edges = fresh.values.flatten().filter { edge ->
                edge.from in peers && edge.to in peers && fresh[edge.to]?.any { it.to == edge.from } == true
            }
            val members = peers.keys.toSet(); val previous = _state.value.tree
            val tree = withContext(Dispatchers.Default) { MeshTopology.build(nodeId, members, edges, previous) }
            if (stopped) return@launch
            _state.value = _state.value.copy(tree = tree); topologyVersion++
            val payload = WireWriter().long(topologyVersion).byte(tree.parents.size).apply {
                tree.parents.forEach { (child, parent) -> long(child).long(parent).long(tree.alternatives[child] ?: 0) }
            }.build()
            send(PacketType.TOPOLOGY_UPDATE, 0, payload); publish()
        }
    }
    fun setRequired(id: Long, required: Boolean) {
        if (!isServer || id == nodeId) return
        peers[id]?.let { peers[id] = it.copy(required = required); scope.launch { broadcastRoster() } }
    }
    fun fragmentSize(id: Long) = transport.links.values.firstOrNull { it.nodeId == id }?.let { MtuManager.fragmentPayload(it.mtu) }
    fun cancelData() = transport.links.values.forEach { it.queue.cancelData() }
    fun setTransferring(active: Boolean) = transport.setPowerProfile(if (active) PowerProfile.TRANSFER else PowerProfile.IDLE)
    private fun publish() {
        _state.value = _state.value.copy(peers = peers.toMap(), direct = transport.links.values.mapNotNull { l -> l.nodeId?.let { it to l.queue.metrics.copy(mtu = l.mtu) } }.toMap(),
            rssi = transport.links.values.mapNotNull { l -> l.nodeId?.let { it to l.rssi } }.toMap())
    }
    fun stop() { stopped = true; ticker?.cancel(); topologyJob?.cancel(); transport.stop() }
    companion object {
        private val BROADCAST_TYPES = setOf(PacketType.ROOM_JOIN, PacketType.ROOM_ACCEPT, PacketType.PEER_INFO,
            PacketType.TOPOLOGY_UPDATE, PacketType.PARENT_ASSIGN, PacketType.TRANSFER_CANCEL)
    }
}
