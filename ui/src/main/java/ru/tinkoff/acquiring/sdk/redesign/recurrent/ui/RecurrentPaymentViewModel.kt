package ru.tinkoff.acquiring.sdk.redesign.recurrent.ui

import android.app.Application
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.tinkoff.acquiring.sdk.TinkoffAcquiring
import ru.tinkoff.acquiring.sdk.TinkoffAcquiring.Companion.EXTRA_REBILL_ID
import ru.tinkoff.acquiring.sdk.models.options.screen.PaymentOptions
import ru.tinkoff.acquiring.sdk.models.paysources.AttachedCard
import ru.tinkoff.acquiring.sdk.models.result.PaymentResult
import ru.tinkoff.acquiring.sdk.payment.PaymentByCardState
import ru.tinkoff.acquiring.sdk.payment.PaymentByCardState
import ru.tinkoff.acquiring.sdk.payment.RecurrentPaymentProcess
import ru.tinkoff.acquiring.sdk.redesign.recurrent.nav.RecurrentPaymentNavigation
import ru.tinkoff.acquiring.sdk.redesign.recurrent.ui.RecurrentPaymentActivity.Companion.EXTRA_CARD
import ru.tinkoff.acquiring.sdk.threeds.ThreeDsDataCollector
import ru.tinkoff.acquiring.sdk.threeds.ThreeDsHelper
import ru.tinkoff.acquiring.sdk.utils.BankCaptionResourceProvider
import ru.tinkoff.acquiring.sdk.utils.CoroutineManager
import ru.tinkoff.acquiring.sdk.utils.getExtra

internal class RecurrentPaymentViewModel(
    private val recurrentPaymentProcess: RecurrentPaymentProcess,
    private val recurrentProcessMapper: RecurrentProcessMapper,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val recurrentPaymentNavigation: RecurrentPaymentNavigation.Impl,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() , RecurrentPaymentNavigation by recurrentPaymentNavigation  {

    // started
    private val paymentOptions: PaymentOptions = savedStateHandle.getExtra()
    private val rebillId: String = checkNotNull(savedStateHandle.get<String>(EXTRA_REBILL_ID))
    private val eventChannel = Channel<RecurrentPaymentEvent>()
    private val card: Card = checkNotNull(savedStateHandle.get<Card>(EXTRA_CARD))
    // state
    val state = recurrentPaymentProcess.state.map {
        recurrentProcessMapper(it)
    }
    val events = eventChannel.receiveAsFlow()

    fun pay() {
        viewModelScope.launch {
            recurrentPaymentProcess.start(
                AttachedCard(rebillId),
                paymentOptions,
                paymentOptions.customer.email
                AttachedCard(card.rebillId),
                paymentOptions,
                paymentOptions.customer.email
            )
        }
    }

    fun onClose() {
        viewModelScope.launch {
            when (val paymentState = recurrentPaymentProcess.state.value) {
                PaymentByCardState.Created -> Unit
                PaymentByCardState.CvcUiInProcess -> RecurrentPaymentEvent.CloseWithCancel()
                is PaymentByCardState.CvcUiNeeded -> RecurrentPaymentEvent.CloseWithCancel()
                is PaymentByCardState.Error -> eventChannel.send(
                    RecurrentPaymentEvent.CloseWithError(
                        paymentState
                    )
                )
                is PaymentByCardState.Started -> Unit
                is PaymentByCardState.Success -> eventChannel.send(
                    RecurrentPaymentEvent.CloseWithSuccess(paymentState)
                )
                PaymentByCardState.ThreeDsInProcess -> eventChannel.send(
                    RecurrentPaymentEvent.CloseWithCancel()
                )
                is PaymentByCardState.ThreeDsUiNeeded -> eventChannel.send(
                    RecurrentPaymentEvent.CloseWithCancel()
                )
            }
        }
    }

    fun set3dsResult(error: Throwable?) {
        recurrentPaymentProcess.set3dsResult(error)
    }

    fun set3dsResult(paymentResult: PaymentResult) {
        recurrentPaymentProcess.set3dsResult(paymentResult)
    }

    fun goTo3ds() {
        recurrentPaymentProcess.onThreeDsUiInProcess()
    }


    fun onClose() {
        viewModelScope.launch {
            when (val paymentState = recurrentPaymentProcess.state.value) {
                PaymentByCardState.Created -> Unit
                is PaymentByCardState.CvcUiNeeded -> RecurrentPaymentEvent.CloseWithCancel()
                is PaymentByCardState.Error -> recurrentPaymentNavigation.eventChannel.send(
                    RecurrentPaymentEvent.CloseWithError(
                        paymentState
                    )
                )
                is PaymentByCardState.Started -> Unit
                is PaymentByCardState.Success -> recurrentPaymentNavigation.eventChannel.send(
                    RecurrentPaymentEvent.CloseWithSuccess(paymentState)
                )
                PaymentByCardState.ThreeDsInProcess ->  recurrentPaymentNavigation.eventChannel.send(
                    RecurrentPaymentEvent.CloseWithCancel()
                )
                is PaymentByCardState.ThreeDsUiNeeded ->  recurrentPaymentNavigation.eventChannel.send(
                    RecurrentPaymentEvent.CloseWithCancel()
                )
            }
        }
    }
}

fun RecurrentViewModelsFactory(
    application: Application,
    paymentOptions: PaymentOptions,
    threeDsDataCollector: ThreeDsDataCollector = ThreeDsHelper.CollectData
)  =  viewModelFactory {
    RecurrentPaymentProcess.init(
        TinkoffAcquiring(
            application,
            paymentOptions.terminalKey,
            paymentOptions.publicKey
        ).sdk,
        application,
        threeDsDataCollector
    )
    val recurrentPaymentNavigation = RecurrentPaymentNavigation.Impl()
    val recurrentProcessMapper = RecurrentProcessMapper(recurrentPaymentNavigation)
    val bankCaptionResourceProvider = BankCaptionResourceProvider(application)
    val coroutineManager = CoroutineManager()
    initializer {
        RecurrentPaymentViewModel(
            RecurrentPaymentProcess.get(),
            recurrentProcessMapper,
            recurrentPaymentNavigation,
            createSavedStateHandle()
        )
    }
    initializer {
        RejectedViewModel(
            RecurrentPaymentProcess.get(),
            bankCaptionResourceProvider,
            coroutineManager,
            createSavedStateHandle()
        )
    }
}