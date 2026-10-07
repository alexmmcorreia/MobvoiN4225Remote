package com.local.mobvoin4225remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Health Connect",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "A N4225 usa o Health Connect apenas para gravar as sessões de passadeira que escolheres sincronizar, incluindo duração, distância e calorias. Os dados ficam sob o controlo das permissões do Health Connect no teu telemóvel."
                    )
                    Text(
                        "A app não envia estes dados para um servidor próprio nem os usa para publicidade."
                    )
                    Button(onClick = { finish() }) {
                        Text("Voltar")
                    }
                }
            }
        }
    }
}
