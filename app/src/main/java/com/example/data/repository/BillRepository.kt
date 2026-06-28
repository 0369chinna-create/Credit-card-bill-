package com.example.data.repository

import com.example.data.dao.BankAccountDao
import com.example.data.dao.BillDao
import com.example.data.entity.BankAccount
import com.example.data.entity.Bill
import kotlinx.coroutines.flow.Flow

class BillRepository(
    private val bankAccountDao: BankAccountDao,
    private val billDao: BillDao
) {
    val allAccounts: Flow<List<BankAccount>> = bankAccountDao.getAllAccounts()
    val allBills: Flow<List<Bill>> = billDao.getAllBills()
    val upcomingAutoDebits: Flow<List<Bill>> = billDao.getUpcomingAutoDebits()

    suspend fun insertAccount(account: BankAccount): Long {
        return bankAccountDao.insertAccount(account)
    }

    suspend fun updateAccount(account: BankAccount) {
        bankAccountDao.updateAccount(account)
    }

    suspend fun deleteAccount(account: BankAccount) {
        bankAccountDao.deleteAccount(account)
    }

    suspend fun insertBill(bill: Bill): Long {
        return billDao.insertBill(bill)
    }

    suspend fun updateBill(bill: Bill) {
        billDao.updateBill(bill)
    }

    suspend fun deleteBill(bill: Bill) {
        billDao.deleteBill(bill)
    }
}
