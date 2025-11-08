package com.kitoko.packer.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kitoko.packer.data.PackedOrderStore
import com.kitoko.packer.model.OrderProgress
import com.kitoko.packer.model.PackedItem
import com.kitoko.packer.model.PackedOrder
import com.kitoko.packer.model.parseInvoicePayload
import com.kitoko.packer.model.parsePacketSku
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String
)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val email: String?, val uid: String) : AuthState
}

data class PackedOverlayState(
    val orderId: String,
    val message: String,
    val speakMessage: String
)

data class PackingUiState(
    val authState: AuthState = AuthState.Loading,
    val currentOrder: OrderProgress? = null,
    val history: List<PackedOrder> = emptyList(),
    val blockedOrders: Set<String> = emptySet(),
    val snackbarMessage: UiMessage? = null,
    val overlayState: PackedOverlayState? = null,
    val isUploading: Boolean = false
) {
    val isSignedIn: Boolean get() = authState is AuthState.SignedIn
}

class PackingViewModel(
    private val store: PackedOrderStore,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : ViewModel() {

    private val _state = MutableStateFlow(PackingUiState())
    val state: StateFlow<PackingUiState> = _state.asStateFlow()

    private val messageEvents = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val overlayEvents = MutableSharedFlow<PackedOverlayState>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _state.updateState { current ->
            current.copy(
                authState = if (user != null) {
                    AuthState.SignedIn(user.email, user.uid)
                } else {
                    AuthState.SignedOut
                }
            )
        }
    }

    private var historyJob: Job? = null
    private var blockedJob: Job? = null

    init {
        auth.addAuthStateListener(authListener)
        historyJob = viewModelScope.launch {
            store.observeHistory().collect { history ->
                _state.updateState { current ->
                    current.copy(history = history)
                }
            }
        }
        blockedJob = viewModelScope.launch {
            store.observeBlocked().collect { blocked ->
                _state.updateState { current ->
                    current.copy(blockedOrders = blocked)
                }
            }
        }
        viewModelScope.launch {
            messageEvents.collect { message ->
                _state.updateState { current -> current.copy(snackbarMessage = message) }
            }
        }
        viewModelScope.launch {
            overlayEvents.collect { overlay ->
                _state.updateState { current -> current.copy(overlayState = overlay) }
            }
        }

        val currentUser = auth.currentUser
        if (currentUser != null) {
            _state.updateState {
                it.copy(authState = AuthState.SignedIn(currentUser.email, currentUser.uid))
            }
        } else {
            _state.updateState { it.copy(authState = AuthState.SignedOut) }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            sendMessage("Email and password required.")
            return
        }
        _state.updateState { it.copy(authState = AuthState.Loading) }
        viewModelScope.launch {
            runCatching {
                auth.signInWithEmailAndPassword(email, password).await()
            }.onFailure { error ->
                sendMessage(error.localizedMessage ?: "Authentication failed.")
                _state.updateState { it.copy(authState = AuthState.SignedOut) }
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _state.updateState {
            it.copy(
                authState = AuthState.SignedOut,
                currentOrder = null
            )
        }
    }

    fun consumeMessage() {
        _state.updateState { it.copy(snackbarMessage = null) }
    }

    fun clearOverlay() {
        _state.updateState { it.copy(overlayState = null) }
    }

    fun handleBarcode(rawValue: String) {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return
        val currentState = _state.value
        if (!currentState.isSignedIn) {
            sendMessage("Sign in required.")
            return
        }

        if (trimmed.startsWith("PKG1:", ignoreCase = true)) {
            processInvoice(trimmed)
        } else {
            processPacket(trimmed)
        }
    }

    private fun processInvoice(raw: String) {
        val authState = _state.value.authState
        val signedIn = authState as? AuthState.SignedIn ?: run {
            sendMessage("Sign in required.")
            return
        }
        parseInvoicePayload(raw.removePrefix("PKG1:")).onSuccess { payload ->
            val blocked = _state.value.blockedOrders
            if (payload.orderId in blocked) {
                sendMessage("Order ${payload.orderId} already packed.")
                return@onSuccess
            }
            val currentOrder = _state.value.currentOrder
            if (currentOrder != null && !currentOrder.isComplete) {
                sendMessage("Finish current order first.")
                return@onSuccess
            }
            _state.updateState {
                it.copy(
                    currentOrder = OrderProgress(order = payload),
                    snackbarMessage = null,
                    overlayState = null
                )
            }
            sendMessage("Invoice ${payload.orderId} loaded.")
        }.onFailure {
            sendMessage("Invalid invoice payload.")
        }
    }

    private fun processPacket(raw: String) {
        val order = _state.value.currentOrder ?: run {
            sendMessage("Scan invoice first.")
            return
        }
        parsePacketSku(raw).onSuccess { sku ->
            val line = order.order.lines.firstOrNull { it.sku.equals(sku, ignoreCase = true) }
            if (line == null) {
                sendMessage("SKU $sku not in order.")
                return@onSuccess
            }
            val currentCount = order.scanned[ line.sku ] ?: 0
            if (currentCount >= line.quantity) {
                sendMessage("SKU ${line.sku} already complete.")
                return@onSuccess
            }
            val updatedCount = currentCount + 1
            val updated = order.copy(
                scanned = order.scanned + (line.sku to updatedCount)
            )
            _state.updateState { it.copy(currentOrder = updated) }

            if (updated.isComplete) {
                finalizeOrder(updated)
            }
        }.onFailure {
            sendMessage("Unsupported code.")
        }
    }

    private fun finalizeOrder(progress: OrderProgress) {
        val authState = _state.value.authState as? AuthState.SignedIn ?: return
        val completedAt = System.currentTimeMillis()
        val packedOrder = PackedOrder(
            orderId = progress.order.orderId,
            packedAt = completedAt,
            operatorEmail = authState.email,
            items = progress.order.lines.map { line ->
                PackedItem(line.sku, line.quantity)
            }
        )

        viewModelScope.launch {
            _state.updateState { it.copy(isUploading = true) }
            store.addOrder(packedOrder)
            _state.updateState {
                it.copy(
                    currentOrder = progress.copy(completedAt = completedAt),
                    isUploading = false
                )
            }
            overlayEvents.tryEmit(
                PackedOverlayState(
                    orderId = packedOrder.orderId,
                    message = "Order ${packedOrder.orderId} packed",
                    speakMessage = "Order ${packedOrder.orderId} packed"
                )
            )
            pushToFirestore(packedOrder, authState.uid)
        }
    }

    private suspend fun pushToFirestore(order: PackedOrder, uid: String) {
        runCatching {
            val doc = firestore.collection("users")
                .document(uid)
                .collection("packedOrders")
                .document("${order.orderId}_${order.packedAt}")
            val payload = mapOf(
                "orderId" to order.orderId,
                "packedAt" to order.packedAt,
                "operatorEmail" to order.operatorEmail,
                "items" to order.items.map { item ->
                    mapOf(
                        "sku" to item.sku,
                        "quantity" to item.quantity
                    )
                }
            )
            doc.set(payload).await()
        }.onFailure {
            sendMessage("Synced locally. Firestore pending.")
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            store.clear()
            sendMessage("History cleared.")
        }
    }

    suspend fun buildCsv(): String {
        val history = store.snapshot().sortedBy { it.packedAt }
        val builder = StringBuilder()
        builder.appendLine("order_id,packed_at,operator_email,sku,quantity")
        history.forEach { order ->
            order.items.forEach { item ->
                builder.append(order.orderId).append(',')
                    .append(order.formattedTimestamp).append(',')
                    .append(order.operatorEmail ?: "")
                    .append(',')
                    .append(item.sku)
                    .append(',')
                    .append(item.quantity)
                    .appendLine()
            }
        }
        return builder.toString()
    }

    private fun sendMessage(message: String) {
        messageEvents.tryEmit(UiMessage(text = message))
    }

    override fun onCleared() {
        super.onCleared()
        auth.removeAuthStateListener(authListener)
        historyJob?.cancel()
        blockedJob?.cancel()
    }

    companion object {
        fun factory(
            store: PackedOrderStore,
            auth: FirebaseAuth,
            firestore: FirebaseFirestore
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PackingViewModel(store, auth, firestore) as T
            }
        }
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    return kotlinx.coroutines.tasks.await(this)
}

private fun <T> MutableStateFlow<T>.updateState(transform: (T) -> T) {
    this.value = transform(this.value)
}
