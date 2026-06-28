package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.BuildConfig
import com.example.data.api.Content
import com.example.data.api.GeminiClient
import com.example.data.api.GeminiRequest
import com.example.data.api.GenerationConfig
import com.example.data.api.Part
import com.example.data.api.ParsedBill
import com.example.data.api.ResponseSchema
import com.example.data.api.SchemaProperty
import com.example.data.database.AppDatabase
import com.example.data.entity.BankAccount
import com.example.data.entity.Bill
import com.example.data.repository.BillRepository
import com.example.worker.BillReminderWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class BillViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: BillRepository
    val allAccounts: StateFlow<List<BankAccount>>
    val allBills: StateFlow<List<Bill>>
    val upcomingAutoDebits: StateFlow<List<Bill>>

    // Gemini Parsing UI States
    val geminiParsingState = MutableStateFlow<GeminiState>(GeminiState.Idle)
    
    // Shared Flow for toast or UI notifications
    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BillRepository(database.bankAccountDao(), database.billDao())

        allAccounts = repository.allAccounts.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        allBills = repository.allBills.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        upcomingAutoDebits = repository.upcomingAutoDebits.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        // Seed with sample data if database is empty
        seedDatabaseIfEmpty()

        // Schedule periodic background reminders via WorkManager
        schedulePeriodicReminders()
    }

    private fun seedDatabaseIfEmpty() {
        viewModelScope.launch {
            val accounts = repository.allAccounts.first()
            if (accounts.isEmpty()) {
                val primaryAccount = BankAccount(
                    name = "Primary Checking (Chase)",
                    currentBalance = 1200.00,
                    minimumRequiredBalance = 500.00
                )
                val savingsAccount = BankAccount(
                    name = "High-Yield Savings",
                    currentBalance = 4500.00,
                    minimumRequiredBalance = 0.00
                )
                val primaryId = repository.insertAccount(primaryAccount)
                repository.insertAccount(savingsAccount)

                // 1. Credit Card due in 3 days (Amount: $450.00) - Not Auto Debit
                val cal1 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 3) }
                repository.insertBill(
                    Bill(
                        name = "Chase Sapphire CC",
                        amount = 450.00,
                        dueDate = cal1.timeInMillis,
                        isAutoDebit = false,
                        isPaid = false,
                        bankAccountId = null,
                        category = "Credit Card",
                        alertLeadDays = 5
                    )
                )

                // 2. Rent due in 5 days (Amount: $850.00) - Auto Debit from Checking
                // (This will trigger a bounce warning since 1200 balance - 850 rent < 500 min balance!)
                val cal2 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 5) }
                repository.insertBill(
                    Bill(
                        name = "Apartment Rent",
                        amount = 850.00,
                        dueDate = cal2.timeInMillis,
                        isAutoDebit = true,
                        isPaid = false,
                        bankAccountId = primaryId,
                        category = "Rent",
                        alertLeadDays = 3
                    )
                )

                // 3. Subscription due in 1 day (Amount: $15.49) - Auto Debit
                val cal3 = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                repository.insertBill(
                    Bill(
                        name = "Netflix Standard",
                        amount = 15.49,
                        dueDate = cal3.timeInMillis,
                        isAutoDebit = true,
                        isPaid = false,
                        bankAccountId = primaryId,
                        category = "Subscription",
                        alertLeadDays = 3
                    )
                )
            }
        }
    }

    private fun schedulePeriodicReminders() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<BillReminderWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "BillReminderPeriodicWork",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    fun triggerSimulation() {
        viewModelScope.launch {
            try {
                // Instantly trigger a one-time execution of the worker so the user gets actual status bar notifications!
                val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<BillReminderWorker>().build()
                WorkManager.getInstance(getApplication()).enqueue(oneTimeRequest)

                // Check alerts immediately for UI display toast
                val database = AppDatabase.getDatabase(getApplication())
                val bills = database.billDao().getAllBills().first()
                val accounts = database.bankAccountDao().getAllAccounts().first()

                var alertCount = 0
                val today = Calendar.getInstance()

                bills.forEach { bill ->
                    if (!bill.isPaid) {
                        val diffDays = ((bill.dueDate - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
                        if (diffDays in 0..bill.alertLeadDays) {
                            alertCount++
                        }
                    }
                }

                accounts.forEach { account ->
                    val upcomingDebitsTotal = bills.filter { bill ->
                        !bill.isPaid && bill.isAutoDebit && (bill.bankAccountId == account.id || bill.bankAccountId == null) &&
                                (bill.dueDate - today.timeInMillis) in 0..(7 * 24 * 60 * 60 * 1000L)
                    }.sumOf { it.amount }

                    if (account.currentBalance < (upcomingDebitsTotal + account.minimumRequiredBalance)) {
                        alertCount++
                    }
                }

                _uiEvent.emit(UiEvent.ShowToast("Simulation ran successfully! Generated $alertCount alerts in system tray."))
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowToast("Simulation failed: ${e.localizedMessage}"))
            }
        }
    }

    fun addBankAccount(name: String, balance: Double, minBalance: Double) {
        viewModelScope.launch {
            repository.insertAccount(BankAccount(name = name, currentBalance = balance, minimumRequiredBalance = minBalance))
            _uiEvent.emit(UiEvent.ShowToast("Bank Account '$name' added!"))
        }
    }

    fun updateBankAccount(account: BankAccount) {
        viewModelScope.launch {
            repository.updateAccount(account)
        }
    }

    fun deleteBankAccount(account: BankAccount) {
        viewModelScope.launch {
            repository.deleteAccount(account)
            _uiEvent.emit(UiEvent.ShowToast("Bank Account deleted."))
        }
    }

    fun addBill(name: String, amount: Double, dueDate: Long, isAutoDebit: Boolean, bankAccountId: Long?, category: String, alertLeadDays: Int) {
        viewModelScope.launch {
            repository.insertBill(
                Bill(
                    name = name,
                    amount = amount,
                    dueDate = dueDate,
                    isAutoDebit = isAutoDebit,
                    isPaid = false,
                    bankAccountId = bankAccountId,
                    category = category,
                    alertLeadDays = alertLeadDays
                )
            )
            _uiEvent.emit(UiEvent.ShowToast("Bill '$name' scheduled!"))
        }
    }

    fun toggleBillPaid(bill: Bill) {
        viewModelScope.launch {
            val updated = bill.copy(isPaid = !bill.isPaid)
            repository.updateBill(updated)
            
            // If it is paid and auto-debit, we deduct the amount from the associated bank account to simulate the payment!
            if (updated.isPaid && updated.isAutoDebit && updated.bankAccountId != null) {
                val accounts = allAccounts.value
                val account = accounts.find { it.id == updated.bankAccountId }
                if (account != null) {
                    val newBalance = account.currentBalance - updated.amount
                    repository.updateAccount(account.copy(currentBalance = newBalance))
                    _uiEvent.emit(UiEvent.ShowToast("Bill marked as paid. Deducted $${updated.amount} from '${account.name}'."))
                    return@launch
                }
            }
            
            _uiEvent.emit(UiEvent.ShowToast(if (updated.isPaid) "Bill marked as paid!" else "Bill marked as unpaid."))
        }
    }

    fun deleteBill(bill: Bill) {
        viewModelScope.launch {
            repository.deleteBill(bill)
            _uiEvent.emit(UiEvent.ShowToast("Bill removed."))
        }
    }

    fun parseBillSms(smsContent: String) {
        viewModelScope.launch {
            geminiParsingState.value = GeminiState.Loading
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isBlank()) {
                    geminiParsingState.value = GeminiState.Error("Gemini API key is not configured. Please add GEMINI_API_KEY in Secrets.")
                    return@launch
                }

                val prompt = """
                    Analyze the following bank notification or transaction SMS and extract the bill payment details. 
                    Text: "$smsContent"
                """.trimIndent()

                val request = GeminiRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = prompt)))
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        responseSchema = ResponseSchema(
                            type = "OBJECT",
                            properties = mapOf(
                                "name" to SchemaProperty("STRING", "Name of the biller, card, or merchant, e.g. Chase Credit Card, Netflix, Rent"),
                                "amount" to SchemaProperty("NUMBER", "Outstanding bill or debit amount"),
                                "dueDate" to SchemaProperty("STRING", "Due date of payment in YYYY-MM-DD format (infer relative to today: 2026-06-28 if not explicitly stated)"),
                                "isAutoDebit" to SchemaProperty("BOOLEAN", "True if notification mentions auto-debit, autopay, NACH, ECS, or standing instructions, false otherwise"),
                                "category" to SchemaProperty("STRING", "Category of the bill. Options: Credit Card, Subscription, Rent, Utility, Loan, Other")
                            ),
                            required = listOf("name", "amount", "dueDate", "isAutoDebit", "category")
                        )
                    )
                )

                val response = GeminiClient.api.generateContent(apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                if (responseText != null) {
                    val parsed = GeminiClient.billAdapter.fromJson(responseText)
                    if (parsed != null) {
                        geminiParsingState.value = GeminiState.Success(parsed)
                    } else {
                        geminiParsingState.value = GeminiState.Error("Failed to parse Gemini model output.")
                    }
                } else {
                    geminiParsingState.value = GeminiState.Error("Gemini returned an empty response.")
                }
            } catch (e: Exception) {
                Log.e("GeminiParsing", "Error parsing SMS", e)
                geminiParsingState.value = GeminiState.Error(e.localizedMessage ?: "Unknown API error occurred.")
            }
        }
    }

    fun clearParsingState() {
        geminiParsingState.value = GeminiState.Idle
    }
}

sealed interface GeminiState {
    object Idle : GeminiState
    object Loading : GeminiState
    data class Success(val parsedBill: ParsedBill) : GeminiState
    data class Error(val message: String) : GeminiState
}

sealed interface UiEvent {
    data class ShowToast(val message: String) : UiEvent
}
