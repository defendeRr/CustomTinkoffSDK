package ru.tinkoff.acquiring.sdk.redesign.sbp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import ru.tinkoff.acquiring.sdk.models.options.screen.PaymentOptions
import ru.tinkoff.acquiring.sdk.payment.SbpPaymentProcess
import ru.tinkoff.acquiring.sdk.redesign.dialog.PaymentStatusSheetState
import ru.tinkoff.acquiring.sdk.redesign.sbp.util.SbpStateMapper
import ru.tinkoff.acquiring.sdk.utils.ConnectionChecker
import ru.tinkoff.acquiring.sdk.utils.CoroutineManager
import ru.tinkoff.acquiring.sdk.utils.updateIfNotNull

internal class SbpPaymentViewModel(
    private val connectionChecker: ConnectionChecker,
    private val sbpPaymentProcess: SbpPaymentProcess,
    private val manager: CoroutineManager = CoroutineManager(),
    private val stateMapper: SbpStateMapper = SbpStateMapper()
) : ViewModel() {

    val stateUiFlow = MutableStateFlow<SpbBankListState>(SpbBankListState.Shimmer)
    val paymentStateFlow = MutableStateFlow<PaymentStatusSheetState>(PaymentStatusSheetState.NotYet)

    init {
        manager.launchOnBackground {
            sbpPaymentProcess.state.collect {
                stateUiFlow.updateIfNotNull(stateMapper.mapUiState(it))
                paymentStateFlow.updateIfNotNull(stateMapper.mapStatusForm(it))
            }
        }
    }

    fun loadData(paymentOptions: PaymentOptions, paymentId: Long?) {
        if (connectionChecker.isOnline().not()) {
            stateUiFlow.value = SpbBankListState.NoNetwork
            return
        }
        stateUiFlow.value = SpbBankListState.Shimmer
        sbpPaymentProcess.start(paymentOptions, paymentId)
    }

    fun onGoingToBankApp() {
        sbpPaymentProcess.goingToBankApp()
    }

    fun startCheckingStatus() {
        sbpPaymentProcess.startCheckingStatus()
    }

    fun cancelPayment() {
        sbpPaymentProcess.stop()
    }

    override fun onCleared() {
        manager.cancelAll()
        super.onCleared()
    }

    companion object {
        fun factory(
            connectionChecker: ConnectionChecker,
        ) = viewModelFactory {
            initializer { SbpPaymentViewModel(connectionChecker, SbpPaymentProcess.get()) }
        }
    }
}