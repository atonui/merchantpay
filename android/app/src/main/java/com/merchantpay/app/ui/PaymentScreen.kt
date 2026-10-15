package com.merchantpay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.merchantpay.app.data.DemoPaymentRepository
import com.merchantpay.app.data.PaymentIntent
import com.merchantpay.app.data.PaymentRepository
import com.merchantpay.app.domain.KesAmount
import java.util.UUID
import kotlinx.coroutines.launch

private const val DEMO_MERCHANT_ID = "demo-merchant"

@Composable
fun PaymentScreen(repository: PaymentRepository) {
    var amount by rememberSaveable { mutableStateOf("500.00") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var intent by remember { mutableStateOf<PaymentIntent?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("MerchantPay", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("SANDBOX • OFFLINE DEMONSTRATION", color = MaterialTheme.colorScheme.primary)
            Text("This app cannot charge a card, initiate M-Pesa, or confirm that anyone has paid.")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Merchant", style = MaterialTheme.typography.labelLarge)
                    Text("Demo merchant (not a real account)")
                    Text("Payment amount", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = amount,
                        onValueChange = {
                            amount = it
                            message = null
                            intent = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Amount in KES") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text("Enter up to two decimal places") }
                    )
                    Button(
                        onClick = {
                            val minor = KesAmount.parseMinorUnits(amount)
                            if (minor == null) {
                                message = "Enter a positive KES amount with at most two decimals."
                                return@Button
                            }
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    intent = repository.createIntent(
                                        DEMO_MERCHANT_ID, minor, UUID.randomUUID().toString()
                                    )
                                } catch (_: Exception) {
                                    message = "Unable to create the simulated intent."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(if (busy) "Creating…" else "Create simulated intent")
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            intent?.let { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Simulated intent created", style = MaterialTheme.typography.titleLarge)
                        Text(KesAmount.format(result.amountMinor))
                        Text("Status: ${result.status} — NOT PAID", fontWeight = FontWeight.Bold)
                        Text("Reference: ${result.id}", style = MaterialTheme.typography.bodySmall)
                        Text("No payment was attempted. This reference exists only in this app.")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPaymentScreen() {
    MaterialTheme { PaymentScreen(repository = DemoPaymentRepository()) }
}
