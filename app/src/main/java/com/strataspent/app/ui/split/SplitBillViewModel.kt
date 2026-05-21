package com.strataspent.app.ui.split

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.strataspent.app.data.OcrRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pure in-memory state for one-shot bill splits. Nothing here touches
 * Firestore — by design, the use case is "split a bar tab tonight",
 * not "track a recurring shared expense".
 */
data class BillItem(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val amount: Double,
    /** Names assigned to this item; the per-item amount is divided evenly
     *  among them. Empty = item not assigned yet. */
    val assignedTo: Set<String> = emptySet(),
)

data class SplitBillUi(
    val names: List<String> = emptyList(),
    val items: List<BillItem> = emptyList(),
    val newName: String = "",
    val newItemDesc: String = "",
    val newItemAmount: String = "",
    val ocrLoading: Boolean = false,
    val message: String? = null,
)

class SplitBillViewModel(
    private val ocrRepo: OcrRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SplitBillUi())
    val state: StateFlow<SplitBillUi> = _state.asStateFlow()

    /** Per-name total. Each item amount is split evenly across its assignees. */
    val perPersonTotals: StateFlow<Map<String, Double>> = state.map { ui ->
        val totals = LinkedHashMap<String, Double>().apply {
            ui.names.forEach { put(it, 0.0) }
        }
        for (item in ui.items) {
            if (item.assignedTo.isEmpty()) continue
            val share = item.amount / item.assignedTo.size
            for (name in item.assignedTo) {
                totals[name] = (totals[name] ?: 0.0) + share
            }
        }
        totals
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val grandTotal: StateFlow<Double> = state.map { it.items.sumOf { item -> item.amount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val unassignedTotal: StateFlow<Double> = state.map { ui ->
        ui.items.filter { it.assignedTo.isEmpty() }.sumOf { it.amount }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    // -- Name management ---------------------------------------------------

    fun onNewName(v: String) = _state.update { it.copy(newName = v) }

    fun addName() = _state.update { s ->
        val n = s.newName.trim()
        if (n.isEmpty() || n in s.names) s.copy(newName = "")
        else s.copy(names = s.names + n, newName = "")
    }

    fun removeName(name: String) = _state.update { s ->
        s.copy(
            names = s.names - name,
            items = s.items.map { it.copy(assignedTo = it.assignedTo - name) },
        )
    }

    // -- Item management ---------------------------------------------------

    fun onNewItemDesc(v: String) = _state.update { it.copy(newItemDesc = v) }
    fun onNewItemAmount(v: String) = _state.update {
        it.copy(newItemAmount = v.filter { c -> c.isDigit() || c == '.' })
    }

    fun addItem() = _state.update { s ->
        val desc = s.newItemDesc.trim()
        val amt = s.newItemAmount.toDoubleOrNull() ?: return@update s
        if (desc.isEmpty() || amt <= 0) return@update s
        s.copy(
            items = s.items + BillItem(description = desc, amount = amt),
            newItemDesc = "",
            newItemAmount = "",
        )
    }

    fun removeItem(id: String) = _state.update { s ->
        s.copy(items = s.items.filterNot { it.id == id })
    }

    fun toggleAssignment(itemId: String, name: String) = _state.update { s ->
        s.copy(items = s.items.map { item ->
            if (item.id != itemId) item
            else item.copy(
                assignedTo = if (name in item.assignedTo) item.assignedTo - name
                else item.assignedTo + name
            )
        })
    }

    fun assignAllToEveryone() = _state.update { s ->
        s.copy(items = s.items.map { it.copy(assignedTo = s.names.toSet()) })
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // -- Receipt scan ------------------------------------------------------

    fun scanReceipt(image: Bitmap) {
        _state.update { it.copy(ocrLoading = true, message = null) }
        viewModelScope.launch {
            val result = ocrRepo.extractLineItems(image)
            _state.update { current ->
                when (result) {
                    is OcrRepository.LineItemsResult.NotConfigured -> current.copy(
                        ocrLoading = false,
                        message = "Set geminiApiKey in local.properties to enable receipt scanning.",
                    )
                    is OcrRepository.LineItemsResult.Failure -> current.copy(
                        ocrLoading = false,
                        message = "Scan failed: ${result.message}",
                    )
                    is OcrRepository.LineItemsResult.Success -> {
                        if (result.items.isEmpty()) current.copy(
                            ocrLoading = false,
                            message = "No items found on the receipt.",
                        ) else current.copy(
                            ocrLoading = false,
                            message = "Added ${result.items.size} items from the receipt.",
                            items = current.items + result.items.map { raw ->
                                BillItem(description = raw.description, amount = raw.amount)
                            },
                        )
                    }
                }
            }
        }
    }
}
