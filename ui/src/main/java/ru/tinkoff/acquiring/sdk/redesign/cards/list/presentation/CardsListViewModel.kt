package ru.tinkoff.acquiring.sdk.redesign.cards.list.presentation

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ru.tinkoff.acquiring.sdk.AcquiringSdk
import ru.tinkoff.acquiring.sdk.models.Card
import ru.tinkoff.acquiring.sdk.models.enums.CardStatus
import ru.tinkoff.acquiring.sdk.redesign.cards.list.models.CardItemUiModel
import ru.tinkoff.acquiring.sdk.redesign.cards.list.ui.CardListEvent
import ru.tinkoff.acquiring.sdk.redesign.cards.list.ui.CardListMode
import ru.tinkoff.acquiring.sdk.redesign.cards.list.ui.CardsListState
import ru.tinkoff.acquiring.sdk.responses.GetCardListResponse
import ru.tinkoff.acquiring.sdk.utils.ConnectionChecker
import ru.tinkoff.acquiring.sdk.utils.CoroutineManager

/**
 * Created by Ivan Golovachev
 */
internal class CardsListViewModel(
    private val sdk: AcquiringSdk,
    private val connectionChecker: ConnectionChecker,
    private val manager : CoroutineManager = CoroutineManager()
) : ViewModel() {

    private var deleteJob: Job? = null

    @VisibleForTesting
    val stateFlow = MutableStateFlow<CardsListState>(CardsListState.Loading)

    val stateUiFlow = stateFlow.filter { it.isInternal.not() }

    val modeFlow = stateFlow.map { it.mode }

    val eventFlow = MutableStateFlow<CardListEvent?>(null)

    fun loadData(customerKey: String?, recurrentOnly: Boolean) {
        if (connectionChecker.isOnline().not()) {
            stateFlow.tryEmit(CardsListState.NoNetwork)
            return
        }
        stateFlow.tryEmit(CardsListState.Loading)
        manager.launchOnBackground {
            if (customerKey == null) {
                stateFlow.tryEmit(CardsListState.Error)
                return@launchOnBackground
            }

            sdk.getCardList { this.customerKey = customerKey }.executeFlow().collect { r ->
                r.process(
                    onSuccess = { handleGetCardListResponse(it, recurrentOnly) },
                    onFailure = ::handleGetCardListError
                )
            }
        }
    }

    fun deleteCard(model: CardItemUiModel, customerKey: String?) {
        if (deleteJob?.isActive == true) {
            return
        }

        deleteJob = manager.launchOnBackground {
            if (connectionChecker.isOnline().not()) {
                eventFlow.value = CardListEvent.ShowError
                return@launchOnBackground
            }
            if (customerKey == null) {
                eventFlow.value = CardListEvent.ShowError
                return@launchOnBackground
            }
            sdk.removeCard {
                this.cardId = model.id
                this.customerKey = customerKey
            }
                .executeFlow().collect {
                    it.process(
                        onSuccess = { r ->
                            handleDeleteCard(checkNotNull(r.cardId?.toString()))
                            deleteJob?.cancel()
                        },
                        onFailure = {
                            eventFlow.value = CardListEvent.ShowError
                            deleteJob?.cancel()
                        }
                    )
                }
        }
    }

    fun changeMode(mode: CardListMode) {
        val list = (stateFlow.value as? CardsListState.Content)?.cards ?: return
        val cards = list.map { it.copy(showDelete = mode == CardListMode.DELETE) }
        stateFlow.value = CardsListState.Content(mode, false, cards)
    }

    private fun handleGetCardListResponse(it: GetCardListResponse, recurrentOnly: Boolean) {
        try {
            val uiCards = filterCards(it.cards, recurrentOnly)
            stateFlow.value = if (uiCards.isEmpty()) {
                CardsListState.Empty
            } else {
                CardsListState.Content(CardListMode.ADD, false, uiCards)
            }
        } catch (e: Exception) {
            handleGetCardListError(e)
        }
    }

    private fun filterCards(it: Array<Card>, recurrentOnly: Boolean): List<CardItemUiModel> {
        var activeCards = it.filter { card ->
            card.status == CardStatus.ACTIVE
        }

        if (recurrentOnly) {
            activeCards = activeCards.filter { card -> !card.rebillId.isNullOrBlank() }
        }

        return activeCards.map(::CardItemUiModel)
    }

    private fun handleGetCardListError(it: Exception) {
        stateFlow.value = CardsListState.Error
    }

    private fun handleDeleteCard(deletedCardId: String) {
        val list = checkNotNull((stateFlow.value as? CardsListState.Content)?.cards).toMutableList()
        val indexAt = list.indexOfFirst { it.id == deletedCardId }
        list.removeAt(indexAt)

        if (list.isEmpty()) {
            stateFlow.value = CardsListState.Empty
        } else {
            stateFlow.update { CardsListState.Content(it.mode, true, list) }
            eventFlow.value = CardListEvent.RemoveCard(indexAt)
        }
    }

    override fun onCleared() {
        manager.cancelAll()
        super.onCleared()
    }
}