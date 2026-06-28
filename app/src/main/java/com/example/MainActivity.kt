package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.api.ParsedBill
import com.example.data.entity.BankAccount
import com.example.data.entity.Bill
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.BillViewModel
import com.example.ui.viewmodel.GeminiState
import com.example.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: BillViewModel by viewModels()

    // Activity launcher for requesting POST_NOTIFICATIONS permission
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notifications permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Reminders may not appear in system status bar.", Toast.LENGTH_LONG).show()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkNotificationPermission()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    "SafePay Reminder & Protection",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: BillViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Dashboard", "My Bills", "AI SMS Extractor")

    val accounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val bills by viewModel.allBills.collectAsStateWithLifecycle()
    val upcomingAutoDebits by viewModel.upcomingAutoDebits.collectAsStateWithLifecycle()
    val geminiParsingState by viewModel.geminiParsingState.collectAsStateWithLifecycle()

    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showAddBillDialog by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = true) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                is UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        when (selectedTab) {
            0 -> DashboardTab(
                accounts = accounts,
                bills = bills,
                onAddAccountClick = { showAddAccountDialog = true },
                onAddBillClick = { showAddBillDialog = true },
                onTriggerSimulation = { viewModel.triggerSimulation() },
                onDeleteAccount = { viewModel.deleteBankAccount(it) }
            )
            1 -> BillsTab(
                bills = bills,
                accounts = accounts,
                onAddBillClick = { showAddBillDialog = true },
                onTogglePaid = { viewModel.toggleBillPaid(it) },
                onDeleteBill = { viewModel.deleteBill(it) }
            )
            2 -> AiExtractorTab(
                parsingState = geminiParsingState,
                accounts = accounts,
                onParseClick = { smsText -> viewModel.parseBillSms(smsText) },
                onAddParsedBill = { parsed ->
                    // Set due date to extracted relative days
                    val calendar = Calendar.getInstance()
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        val date = sdf.parse(parsed.dueDate)
                        if (date != null) {
                            calendar.time = date
                        }
                    } catch (e: Exception) {
                        calendar.add(Calendar.DAY_OF_YEAR, 3) // Default offset
                    }
                    
                    val preferredAccount = accounts.firstOrNull()?.id // Link to default account
                    viewModel.addBill(
                        name = parsed.name,
                        amount = parsed.amount,
                        dueDate = calendar.timeInMillis,
                        isAutoDebit = parsed.isAutoDebit,
                        bankAccountId = if (parsed.isAutoDebit) preferredAccount else null,
                        category = parsed.category,
                        alertLeadDays = 3
                    )
                    viewModel.clearParsingState()
                },
                onClearState = { viewModel.clearParsingState() }
            )
        }
    }

    // Dialogs
    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onSave = { name, balance, minRequired ->
                viewModel.addBankAccount(name, balance, minRequired)
                showAddAccountDialog = false
            }
        )
    }

    if (showAddBillDialog) {
        AddBillDialog(
            accounts = accounts,
            onDismiss = { showAddBillDialog = false },
            onSave = { name, amount, daysOffset, isAutoDebit, bankId, category, leadDays ->
                val cal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, daysOffset)
                }
                viewModel.addBill(name, amount, cal.timeInMillis, isAutoDebit, bankId, category, leadDays)
                showAddBillDialog = false
            }
        )
    }
}

@Composable
fun DashboardTab(
    accounts: List<BankAccount>,
    bills: List<Bill>,
    onAddAccountClick: () -> Unit,
    onAddBillClick: () -> Unit,
    onTriggerSimulation: () -> Unit,
    onDeleteAccount: (BankAccount) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning Banner for Auto-Debit / Check Bounce Risks
        val bounceAlerts = getBounceAlerts(accounts, bills)
        if (bounceAlerts.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE),
                        contentColor = Color(0xFFC62828)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Overdraft Warning",
                                tint = Color(0xFFD32F2F)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DEBIT BOUNCE WARNINGS DETECTED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        bounceAlerts.forEach { alert ->
                            Text(
                                text = "• $alert",
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ensure deposits are made before auto-debit schedule hits to prevent bounce penalties.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9),
                        contentColor = Color(0xFF2E7D32)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Safe",
                            tint = Color(0xFF388E3C)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Auto-Debit Status: Fully Funded",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "No bounce risks found for the next 7 days based on current balances.",
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Summary Statistics Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Protection Summary",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val unpaidBills = bills.filter { !it.isPaid }
                        val upcomingDebits = unpaidBills.filter { it.isAutoDebit }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$${String.format("%.2f", unpaidBills.sumOf { it.amount })}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(text = "Total Owed", fontSize = 11.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$${String.format("%.2f", upcomingDebits.sumOf { it.amount })}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(text = "Auto-Debits", fontSize = 11.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            val activeAlertsCount = bounceAlerts.size + bills.filter { !it.isPaid && ((it.dueDate - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)) <= it.alertLeadDays }.size
                            Text(
                                text = "$activeAlertsCount",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeAlertsCount > 0) Color(0xFFD32F2F) else Color(0xFF388E3C)
                            )
                            Text(text = "Active Alerts", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }

        // Action Toolbar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onAddBillClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Bill")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Bill", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = onAddAccountClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Star, contentDescription = "Add Account")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Account", fontSize = 13.sp)
                }
            }
        }

        // Bank Accounts Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Monitored Accounts",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Long-press to delete",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        if (accounts.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No bank accounts added for protection monitoring.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(accounts) { account ->
                BankAccountCard(
                    account = account,
                    onDelete = { onDeleteAccount(account) }
                )
            }
        }

        // Simulation tools
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Notification Simulator Tools",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Runs our background WorkManager scheduler rules instantly and pushes live, clickable reminders to your Android status bar.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onTriggerSimulation,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Run Checks")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Simulate Checks & Push Alerts", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BankAccountCard(
    account: BankAccount,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDeleteConfirm = true }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = account.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ID: #${account.id}",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                }
                Text(
                    text = "$${String.format("%.2f", account.currentBalance)}",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = if (account.currentBalance < account.minimumRequiredBalance) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color.LightGray.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Auto-Debit Min Floor Buffer:",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "$${String.format("%.2f", account.minimumRequiredBalance)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account") },
            text = { Text("Are you sure you want to stop tracking and delete '${account.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun BillsTab(
    bills: List<Bill>,
    accounts: List<BankAccount>,
    onAddBillClick: () -> Unit,
    onTogglePaid: (Bill) -> Unit,
    onDeleteBill: (Bill) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Upcoming Bills",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onAddBillClick) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add New Bill", tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (bills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No scheduled payments or bills found.", color = Color.Gray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onAddBillClick) {
                        Text("Add Your First Bill")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(bills) { bill ->
                    val linkedAccount = accounts.find { it.id == bill.bankAccountId }
                    BillRowItem(
                        bill = bill,
                        linkedAccountName = linkedAccount?.name,
                        onTogglePaid = { onTogglePaid(bill) },
                        onDelete = { onDeleteBill(bill) }
                    )
                }
            }
        }
    }
}

@Composable
fun BillRowItem(
    bill: Bill,
    linkedAccountName: String?,
    onTogglePaid: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val daysLeft = ((bill.dueDate - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (bill.isPaid) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDeleteConfirm = true }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Circular Indicator icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (bill.isPaid) Color(0xFFE8F5E9)
                            else if (daysLeft <= 1) Color(0xFFFFEBEE)
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (bill.isPaid) Icons.Default.Done
                        else if (bill.isAutoDebit) Icons.Default.Refresh
                        else Icons.Default.Info,
                        contentDescription = "Status Icon",
                        tint = if (bill.isPaid) Color(0xFF2E7D32)
                        else if (daysLeft <= 1) Color(0xFFC62828)
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = bill.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (bill.isPaid) Color.Gray else MaterialTheme.colorScheme.onSurface
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bill.category,
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (bill.isAutoDebit) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "• Auto-Debit",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Due: ${formatLongToDate(bill.dueDate)} (${getRelativeDaysText(daysLeft)})",
                        fontSize = 11.sp,
                        color = if (!bill.isPaid && daysLeft <= bill.alertLeadDays) Color(0xFFC62828) else Color.DarkGray
                    )
                    
                    if (bill.isAutoDebit && linkedAccountName != null) {
                        Text(
                            text = "Linked to: $linkedAccountName",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "$${String.format("%.2f", bill.amount)}",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = if (bill.isPaid) Color.Gray else MaterialTheme.colorScheme.onSurface
                )
                
                Button(
                    onClick = onTogglePaid,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bill.isPaid) Color.Gray else Color(0xFF2E7D32)
                    ),
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (bill.isPaid) "Mark Unpaid" else "Mark Paid",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Manage Bill") },
            text = { Text("Would you like to delete the scheduled bill for '${bill.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Delete Bill", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun AiExtractorTab(
    parsingState: GeminiState,
    accounts: List<BankAccount>,
    onParseClick: (String) -> Unit,
    onAddParsedBill: (ParsedBill) -> Unit,
    onClearState: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }
    val templates = listOf(
        "CC Statement Alert" to "Dear Customer, HDFC Bank Credit Card ending 1209 Statement is generated. Amount Due: Rs 24,950. Due Date: 2026-07-06. Minimum Amount Due: Rs 1,247.",
        "Streaming Auto-Debit" to "Auto-debit of $15.49 for your Netflix subscription is scheduled from your primary checking account on 2026-07-01. Ensure sufficient funds to avoid decline charges.",
        "Electricity Standing Order" to "NACH alert: Utility Bill of Rs 4,120.00 will be debited automatically from your Chase account XXXXX5432 on 2026-07-03. Maintain required minimum balance."
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = "AI Feature", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Gemini Smart SMS Parser",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Paste a bank transaction alert, credit card statement email excerpt, or auto-debit SMS. Gemini will extract bill dates, totals, auto-debit triggers, and help you configure alerts instantly.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Paste Input text field
        item {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text("Paste Bank / Bill SMS notification", fontSize = 12.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp),
                placeholder = { Text("e.g. Dear Customer, your credit card bill of Rs 4,500 is due on...", fontSize = 12.sp) },
                shape = RoundedCornerShape(8.dp)
            )
        }

        // Templates section for quick testing
        item {
            Text("Try template notifications:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { (title, fullText) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { textInput = fullText }
                    ) {
                        Box(
                            modifier = Modifier.padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Extract action button
        item {
            Button(
                onClick = { onParseClick(textInput) },
                enabled = textInput.isNotBlank() && parsingState !is GeminiState.Loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (parsingState is GeminiState.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Parsing details with Gemini...", fontSize = 13.sp)
                } else {
                    Icon(imageVector = Icons.Default.Check, contentDescription = "Parse")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Parse Bill with Gemini", fontSize = 13.sp)
                }
            }
        }

        // Extraction Result Panel
        item {
            when (parsingState) {
                is GeminiState.Loading -> {
                    // Handled inside button but let's show an ambient box
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Extracting parameters...", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                is GeminiState.Success -> {
                    val parsed = parsingState.parsedBill
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                        border = BorderStroke(1.dp, Color(0xFF8BC34A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Extracted Parameters:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF33691E))
                                IconButton(onClick = onClearState, modifier = Modifier.size(24.dp)) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear", tint = Color.Gray)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Biller Name:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text(parsed.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Amount Extracted:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text("$${String.format("%.2f", parsed.amount)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Interpreted Date:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text(parsed.dueDate, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Category Tag:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text(parsed.category, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Auto-Debit Configured:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text(if (parsed.isAutoDebit) "Yes (Safe Account Monitored)" else "No (Manual Reminder)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (parsed.isAutoDebit) Color(0xFF0288D1) else Color(0xFF757575))
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onAddParsedBill(parsed) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                            ) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = "Schedule")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Approve & Schedule Reminder", fontSize = 13.sp)
                            }
                        }
                    }
                }
                is GeminiState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        border = BorderStroke(1.dp, Color(0xFFEF9A9A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Parsing Error:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFC62828))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(parsingState.message, fontSize = 12.sp, color = Color(0xFFB71C1C))
                        }
                    }
                }
                is GeminiState.Idle -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Provide text or tap a template above to generate bill reminders automatically.",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

// Dialogs code
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, balance: Double, minRequired: Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var minRequired by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monitor New Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name (e.g., Chase Checking)") }
                )
                OutlinedTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Current Balance ($)") }
                )
                OutlinedTextField(
                    value = minRequired,
                    onValueChange = { minRequired = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Floor Buffer Limit ($)") },
                    placeholder = { Text("e.g. 500.00") }
                )
                Text(
                    text = "Floor Buffer Limit is the minimum balance required to be kept in the account (e.g. to prevent overdraft fees or daily limits).",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bal = balance.toDoubleOrNull() ?: 0.0
                    val minR = minRequired.toDoubleOrNull() ?: 0.0
                    if (name.isNotBlank()) {
                        onSave(name, bal, minR)
                    }
                }
            ) {
                Text("Monitor Account")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddBillDialog(
    accounts: List<BankAccount>,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, daysOffset: Int, isAutoDebit: Boolean, bankId: Long?, category: String, leadDays: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var daysOffset by remember { mutableStateOf("3") }
    var isAutoDebit by remember { mutableStateOf(false) }
    var selectedBankId by remember { mutableStateOf<Long?>(null) }
    var category by remember { mutableStateOf("Credit Card") }
    var leadDays by remember { mutableStateOf("3") }

    var nameError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }
    var daysOffsetError by remember { mutableStateOf<String?>(null) }

    val categories = listOf("Credit Card", "Subscription", "Rent", "Utility", "Loan", "Other")

    LaunchedEffect(accounts) {
        if (accounts.isNotEmpty()) {
            selectedBankId = accounts.first().id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Bill Reminder") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { 
                            name = it 
                            if (nameError != null) nameError = null
                        },
                        label = { Text("Biller / Card Name") },
                        isError = nameError != null,
                        supportingText = {
                            if (nameError != null) {
                                Text(nameError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { 
                            amount = it 
                            if (amountError != null) amountError = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Bill Amount ($)") },
                        isError = amountError != null,
                        supportingText = {
                            if (amountError != null) {
                                Text(amountError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = daysOffset,
                        onValueChange = { 
                            daysOffset = it 
                            if (daysOffsetError != null) daysOffsetError = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Due In How Many Days?") },
                        isError = daysOffsetError != null,
                        supportingText = {
                            if (daysOffsetError != null) {
                                Text(daysOffsetError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = leadDays,
                        onValueChange = { leadDays = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("Alert Leads Days (Days to warn before)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("Category:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        categories.take(3).forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat, fontSize = 10.sp) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        categories.drop(3).forEach { cat ->
                            FilterChip(
                                selected = category == cat,
                                onClick = { category = cat },
                                label = { Text(cat, fontSize = 10.sp) }
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Auto-Debit Activated", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Switch(checked = isAutoDebit, onCheckedChange = { isAutoDebit = it })
                    }
                }
                if (isAutoDebit && accounts.isNotEmpty()) {
                    item {
                        Text("Monitored Bank Account:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        accounts.forEach { acc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBankId = acc.id }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedBankId == acc.id,
                                    onClick = { selectedBankId = acc.id }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(acc.name, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    val amt = amount.toDoubleOrNull()
                    val days = daysOffset.toIntOrNull()

                    val isNameValid = trimmedName.isNotEmpty()
                    val isAmountValid = amt != null && amt > 0.0
                    val isDaysValid = days != null && days > 0

                    nameError = if (isNameValid) null else "Biller name cannot be empty"
                    amountError = when {
                        amount.trim().isEmpty() -> "Amount cannot be empty"
                        amt == null -> "Please enter a valid number"
                        amt <= 0.0 -> "Amount must be greater than 0"
                        else -> null
                    }
                    daysOffsetError = when {
                        daysOffset.trim().isEmpty() -> "Due days cannot be empty"
                        days == null -> "Please enter a valid integer"
                        days <= 0 -> "Due date must be in the future (at least 1 day)"
                        else -> null
                    }

                    if (isNameValid && isAmountValid && isDaysValid) {
                        val lead = leadDays.toIntOrNull() ?: 3
                        onSave(
                            trimmedName,
                            amt!!,
                            days!!,
                            isAutoDebit,
                            if (isAutoDebit) selectedBankId else null,
                            category,
                            lead
                        )
                    }
                }
            ) {
                Text("Schedule Bill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// Helpers
fun getBounceAlerts(accounts: List<BankAccount>, bills: List<Bill>): List<String> {
    val alerts = mutableListOf<String>()
    val today = Calendar.getInstance()

    accounts.forEach { account ->
        // Calculate upcoming auto-debit payments inside the next 7 days linked to this account
        val upcomingAutoDebits = bills.filter { bill ->
            !bill.isPaid && bill.isAutoDebit && (bill.bankAccountId == account.id || bill.bankAccountId == null) &&
                    (bill.dueDate - today.timeInMillis) in 0..(7 * 24 * 60 * 60 * 1000L)
        }

        val upcomingDebitsTotal = upcomingAutoDebits.sumOf { it.amount }
        val minimumBuffer = account.minimumRequiredBalance
        val totalRequired = upcomingDebitsTotal + minimumBuffer

        if (account.currentBalance < totalRequired) {
            val shortfall = totalRequired - account.currentBalance
            val debitListText = upcomingAutoDebits.joinToString { "'${it.name}' ($${it.amount})" }
            alerts.add(
                "Overdraft Risk on '${account.name}': Current balance of $${account.currentBalance} will drop below minimum requirement of $${minimumBuffer} due to upcoming auto-debits within 7 days: $debitListText. shortfall of $${String.format("%.2f", shortfall)}."
            )
        }
    }
    return alerts
}

fun formatLongToDate(timeInMillis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timeInMillis))
}

fun getRelativeDaysText(daysLeft: Int): String {
    return when {
        daysLeft < 0 -> "Overdue"
        daysLeft == 0 -> "Today"
        daysLeft == 1 -> "Tomorrow"
        else -> "$daysLeft days left"
    }
}

// Helpers
