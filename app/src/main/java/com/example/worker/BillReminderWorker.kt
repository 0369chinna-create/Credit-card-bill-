package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import java.util.Calendar

class BillReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val bills = database.billDao().getAllBills().first()
        val accounts = database.bankAccountDao().getAllAccounts().first()

        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel(notificationManager)

        val today = Calendar.getInstance()

        // 1. Process Credit Card and other bill alerts
        bills.forEach { bill ->
            if (!bill.isPaid) {
                val diffTime = bill.dueDate - today.timeInMillis
                val diffDays = (diffTime / (1000 * 60 * 60 * 24)).toInt()

                // If bill is due in target lead days or on the day
                if (diffDays in 0..bill.alertLeadDays) {
                    val alertMessage = when (diffDays) {
                        0 -> "Your ${bill.name} bill is due TODAY! Pay now to avoid fees."
                        1 -> "Your ${bill.name} bill is due tomorrow. Ensure $${bill.amount} is ready."
                        else -> "Your ${bill.name} bill is due in $diffDays days ($${bill.amount})."
                    }
                    showNotification(
                        notificationManager,
                        bill.id.toInt() * 10 + 1,
                        "Bill Due Alert",
                        alertMessage
                    )
                }
            }
        }

        // 2. Process Auto-Debit and Bank Check Bounce Protection
        accounts.forEach { account ->
            // Calculate total upcoming auto-debits within next 7 days for this account
            val upcomingDebitsTotal = bills.filter { bill ->
                !bill.isPaid && bill.isAutoDebit && (bill.bankAccountId == account.id || bill.bankAccountId == null) &&
                        (bill.dueDate - today.timeInMillis) in 0..(7 * 24 * 60 * 60 * 1000L)
            }.sumOf { it.amount }

            val totalRequired = upcomingDebitsTotal + account.minimumRequiredBalance
            if (account.currentBalance < totalRequired) {
                val shortfall = totalRequired - account.currentBalance
                val alertMessage = "Bounce Risk! Account '${account.name}' has $${account.currentBalance}. " +
                        "Upcoming debits + min balance requires $${String.format("%.2f", totalRequired)}. Ensure $${String.format("%.2f", shortfall)} is deposited."
                showNotification(
                    notificationManager,
                    account.id.toInt() * 10 + 2,
                    "Auto-Debit Bounce Risk",
                    alertMessage
                )
            }
        }

        return Result.success()
    }

    private fun showNotification(
        notificationManager: NotificationManager,
        notificationId: Int,
        title: String,
        message: String
    ) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Payment Reminders & Bounce Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies users about upcoming bill payments and potential account overdraft risks"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "bill_reminder_channel"
    }
}
