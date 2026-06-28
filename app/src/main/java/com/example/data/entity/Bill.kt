package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bills")
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Double,
    val dueDate: Long, // Epoch timestamp in milliseconds
    val isAutoDebit: Boolean,
    val isPaid: Boolean,
    val bankAccountId: Long?, // Nullable link to a BankAccount
    val category: String, // e.g. "Credit Card", "Subscription", "Rent", "Utility", "Loan"
    val alertLeadDays: Int = 3
)
