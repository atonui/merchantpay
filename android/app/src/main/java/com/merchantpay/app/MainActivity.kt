package com.merchantpay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.merchantpay.app.data.DemoPaymentRepository
import com.merchantpay.app.ui.PaymentScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    PaymentScreen(repository = DemoPaymentRepository())
                }
            }
        }
    }
}
