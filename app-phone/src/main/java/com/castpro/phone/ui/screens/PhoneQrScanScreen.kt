package com.castpro.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.phone.ui.theme.*

@Composable
fun PhoneQrScanScreen(
    onQrCodeDetected: (String) -> Unit
) {
    var manualQrInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GamerDarkBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Scan & Pair Instantly",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Point camera at the QR code on your TV or Web Receiver",
            color = GamerTextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        // Viewfinder Card
        Box(
            modifier = Modifier
                .size(280.dp)
                .background(Color(0xFF070B11), RoundedCornerShape(20.dp))
                .border(2.dp, GamerAccent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = GamerAccent,
                    modifier = Modifier.size(72.dp)
                )
                Text(
                    text = "ALIGN QR IN FRAME",
                    color = GamerAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }

        // Direct Pairing Input
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GamerCardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Or Enter Pairing Code / URL",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                OutlinedTextField(
                    value = manualQrInput,
                    onValueChange = { manualQrInput = it },
                    placeholder = { Text("e.g. castpro://connect?host=192.168.1.50", color = Color.Gray, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GamerAccent,
                        unfocusedBorderColor = GamerCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (manualQrInput.isNotBlank()) {
                            onQrCodeDetected(manualQrInput.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GamerNeonGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pair Display", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
