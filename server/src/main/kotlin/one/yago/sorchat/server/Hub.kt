package one.yago.sorchat.server

import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import one.yago.sorchat.protocol.CallSignal
import one.yago.sorchat.protocol.ClientFrame
import one.yago.sorchat.protocol.EndReason
import one.yago.sorchat.protocol.ProtocolJson
import one.yago.sorchat.protocol.ServerFrame
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private const val MAX_BODY_LENGTH = 16 * 1024

/** How long a call to an offline user is held while their device wakes up. */
private const val CALL_HOLD_MS = 60_000L

/**
 * A call to someone who wasn't connected: their device has been pushed, and the signals meant
 * for it are buffered until it connects. Kept until the callee responds, the caller ends it,
 * or it expires, so signals sent while the callee briefly reconnects aren't lost either.
 */
private class HeldCall(val callId: String, val caller: User, val expiresAt: Long) {
    val signals = mutableListOf<CallSignal>()
}

/**
 * Routes messages between connected users. Delivery is at-least-once: every message is
 * stored first and only deleted when the recipient acks it, so clients dedupe by message id.
 */
class Hub(private val store: Store, private val media: MediaStore, private val notifier: Notifier) {
    private val log = LoggerFactory.getLogger(Hub::class.java)

    /** One live connection per user for now (no multi-device yet). */
    private val sessions = ConcurrentHashMap<String, WebSocketServerSession>()

    /** Keyed by callee id; one at a time. */
    private val heldCalls = ConcurrentHashMap<String, HeldCall>()

    suspend fun serve(user: User, session: WebSocketServerSession) {
        sessions.put(user.id, session)?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced by a newer connection"))
        log.info("{} connected", user.id)
        try {
            for (message in store.pendingFor(user.id)) session.sendFrame(message)
            deliverHeldCall(user.id, session)
            session.sendFrame(ServerFrame.Synced)
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val parsed = runCatching { ProtocolJson.decodeFromString(ClientFrame.serializer(), frame.readText()) }
                    .getOrElse {
                        session.sendFrame(ServerFrame.Error(null, "malformed frame"))
                        continue
                    }
                handle(user, session, parsed)
            }
        } finally {
            sessions.remove(user.id, session)
            log.info("{} disconnected", user.id)
        }
    }

    private suspend fun handle(user: User, session: WebSocketSession, frame: ClientFrame) {
        when (frame) {
            is ClientFrame.Send -> {
                if (frame.body.length > MAX_BODY_LENGTH) {
                    session.sendFrame(ServerFrame.Error(frame.id, "message too long"))
                    return
                }
                val recipient = store.findUser(frame.to)
                if (recipient == null) {
                    session.sendFrame(ServerFrame.Error(frame.id, "unknown recipient"))
                    return
                }
                val attachment = frame.attachment?.let { a ->
                    val uploaded = media.find(a.mediaId)?.takeIf { it.owner == user.id }
                    if (uploaded == null) {
                        session.sendFrame(ServerFrame.Error(frame.id, "unknown attachment"))
                        return
                    }
                    // Trust our own record of the upload over what the client claims.
                    a.copy(mimeType = uploaded.mimeType, size = uploaded.size)
                }
                val message = ServerFrame.Message(frame.id, user.id, user.name, frame.body, System.currentTimeMillis(), attachment)
                when (store.storeMessage(message, recipient.id)) {
                    StoreResult.STORED -> {
                        session.sendFrame(ServerFrame.Accepted(frame.id))
                        deliver(recipient.id, message)
                    }
                    StoreResult.DUPLICATE -> session.sendFrame(ServerFrame.Accepted(frame.id))
                    StoreResult.CONFLICT -> session.sendFrame(ServerFrame.Error(frame.id, "message id already in use"))
                }
            }
            is ClientFrame.Ack -> store.deleteMessage(frame.id, user.id)?.let { media.delete(it) }
            is ClientFrame.Call -> relayCall(user, session, frame)
        }
    }

    private suspend fun relayCall(user: User, session: WebSocketSession, frame: ClientFrame.Call) {
        // The callee answered or declined: nothing more needs holding for them.
        heldCalls[user.id]?.let { if (it.callId == frame.callId) heldCalls.remove(user.id, it) }

        val forwarded = sessions[frame.to]
            ?.let { runCatching { it.sendFrame(ServerFrame.Call(user.id, user.name, frame.callId, frame.signal)) }.isSuccess }
            ?: false
        if (forwarded) {
            if (frame.signal is CallSignal.End) heldCalls[frame.to]?.let { if (it.callId == frame.callId) heldCalls.remove(frame.to, it) }
            return
        }

        val signal = frame.signal
        if (signal is CallSignal.Invite) {
            val callee = store.findUser(frame.to)
            if (callee == null || !notifier.push(callee.id, Push.IncomingCall(frame.callId, user.id, user.name, signal.video))) {
                session.sendFrame(ServerFrame.Call(frame.to, callee?.name ?: frame.to, frame.callId, CallSignal.End(EndReason.UNAVAILABLE)))
                return
            }
            heldCalls[callee.id] = HeldCall(frame.callId, user, System.currentTimeMillis() + CALL_HOLD_MS)
                .also { it.signals += signal }
            return
        }
        val held = heldCalls[frame.to]?.takeIf { it.callId == frame.callId && it.caller.id == user.id } ?: return
        if (signal is CallSignal.End) {
            heldCalls.remove(frame.to, held)
            notifier.push(frame.to, Push.CallEnded(frame.callId))
        } else {
            synchronized(held) { held.signals += signal }
        }
    }

    private suspend fun deliverHeldCall(userId: String, session: WebSocketSession) {
        val held = heldCalls[userId] ?: return
        if (held.expiresAt < System.currentTimeMillis()) {
            heldCalls.remove(userId, held)
            return
        }
        val signals = synchronized(held) { held.signals.toList().also { held.signals.clear() } }
        for (signal in signals) session.sendFrame(ServerFrame.Call(held.caller.id, held.caller.name, held.callId, signal))
    }

    private suspend fun deliver(recipientId: String, message: ServerFrame.Message) {
        val delivered = sessions[recipientId]?.let { runCatching { it.sendFrame(message) }.isSuccess } ?: false
        // The message stays queued either way; the push just tells the device to come and get it.
        if (!delivered) notifier.push(recipientId, Push.MessagesWaiting)
    }
}

suspend fun WebSocketSession.sendFrame(frame: ServerFrame) {
    send(Frame.Text(ProtocolJson.encodeToString(ServerFrame.serializer(), frame)))
}
