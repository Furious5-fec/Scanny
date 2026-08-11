package fr.mescourses.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// ---------- Modèle ----------

data class CartItem(
    val barcode: String,
    val name: String,
    val unitPrice: Double,
    val quantity: Int
)

// ---------- Activité principale ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CoursesApp()
                }
            }
        }
    }
}

// ---------- Sauvegarde locale (SharedPreferences, sur le téléphone uniquement) ----------

private const val PREFS_NAME = "mes_courses_prefs"
private const val KEY_CART = "cart_json"
private const val KEY_MEMORY = "price_memory_json"

private fun loadCart(context: Context): List<CartItem> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_CART, null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        val list = mutableListOf<CartItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CartItem(
                    barcode = o.getString("barcode"),
                    name = o.getString("name"),
                    unitPrice = o.getDouble("unitPrice"),
                    quantity = o.getInt("quantity")
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveCart(context: Context, items: List<CartItem>) {
    val arr = JSONArray()
    for (item in items) {
        val o = JSONObject()
        o.put("barcode", item.barcode)
        o.put("name", item.name)
        o.put("unitPrice", item.unitPrice)
        o.put("quantity", item.quantity)
        arr.put(o)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CART, arr.toString())
        .apply()
}

private fun loadPriceMemory(context: Context): Map<String, Pair<String, Double>> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_MEMORY, null) ?: return emptyMap()
    return try {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Pair<String, Double>>()
        val keysIterator = obj.keys()
        for (k in keysIterator) {
            val entry = obj.getJSONObject(k)
            map[k] = Pair(entry.getString("name"), entry.getDouble("price"))
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

private fun savePriceMemory(context: Context, memory: Map<String, Pair<String, Double>>) {
    val obj = JSONObject()
    for ((barcode, pair) in memory) {
        val entry = JSONObject()
        entry.put("name", pair.first)
        entry.put("price", pair.second)
        obj.put(barcode, entry)
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_MEMORY, obj.toString())
        .apply()
}

// ---------- Recherche du nom du produit (Open Food Facts, base ouverte et gratuite) ----------
// NB : cette base ne connaît pas les prix Leclerc, seulement les noms de produits.
// Le prix reste toujours saisi/confirmé par toi.

private suspend fun lookupProductName(barcode: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "MesCoursesApp/1.0 (Android perso)")
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        if (connection.responseCode != 200) return@withContext null
        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        if (json.optInt("status", 0) != 1) return@withContext null
        val product = json.getJSONObject("product")
        val name = product.optString("product_name", "")
        val brand = product.optString("brands", "")
        when {
            name.isBlank() -> null
            brand.isNotBlank() -> "$name ($brand)"
            else -> name
        }
    } catch (e: Exception) {
        null
    }
}

private fun formatPrice(value: Double): String = String.format(Locale.FRANCE, "%.2f", value)

// ---------- Écran principal ----------

@Composable
fun CoursesApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cartItems by remember { mutableStateOf(loadCart(context)) }
    var priceMemory by remember { mutableStateOf(loadPriceMemory(context)) }

    var showDialog by remember { mutableStateOf(false) }
    var editingBarcode by remember { mutableStateOf<String?>(null) }
    var dialogBarcode by remember { mutableStateOf("") }
    var dialogName by remember { mutableStateOf("") }
    var dialogPrice by remember { mutableStateOf("") }
    var dialogQuantity by remember { mutableStateOf(1) }
    var dialogIsLoading by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    fun persistAll(items: List<CartItem>, memory: Map<String, Pair<String, Double>>) {
        saveCart(context, items)
        savePriceMemory(context, memory)
    }

    fun openScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: return@addOnSuccessListener
                editingBarcode = null
                dialogBarcode = code
                dialogQuantity = 1
                dialogError = null
                val remembered = priceMemory[code]
                if (remembered != null) {
                    dialogName = remembered.first
                    dialogPrice = formatPrice(remembered.second)
                    dialogIsLoading = false
                    showDialog = true
                } else {
                    dialogName = ""
                    dialogPrice = ""
                    dialogIsLoading = true
                    showDialog = true
                    scope.launch {
                        val found = lookupProductName(code)
                        if (dialogBarcode == code) {
                            dialogName = found ?: ""
                            dialogIsLoading = false
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Scanner indisponible pour le moment, réessaie.", Toast.LENGTH_SHORT).show()
            }
    }

    fun openEditDialog(item: CartItem) {
        editingBarcode = item.barcode
        dialogBarcode = item.barcode
        dialogName = item.name
        dialogPrice = formatPrice(item.unitPrice)
        dialogQuantity = item.quantity
        dialogIsLoading = false
        dialogError = null
        showDialog = true
    }

    fun confirmDialog() {
        val price = dialogPrice.replace(",", ".").trim().toDoubleOrNull()
        if (price == null || price < 0) {
            dialogError = "Indique un prix valide (ex. 2,50)"
            return
        }
        if (dialogName.isBlank()) {
            dialogError = "Indique un nom pour l'article"
            return
        }
        val newItems: List<CartItem>
        if (editingBarcode != null) {
            val finalName = dialogName
            val finalPrice = price
            val finalQty = dialogQuantity
            newItems = cartItems.map {
                if (it.barcode == editingBarcode) it.copy(name = finalName, unitPrice = finalPrice, quantity = finalQty) else it
            }
        } else {
            val existing = cartItems.find { it.barcode == dialogBarcode }
            newItems = if (existing != null) {
                cartItems.map {
                    if (it.barcode == dialogBarcode)
                        it.copy(quantity = it.quantity + dialogQuantity, unitPrice = price, name = dialogName)
                    else it
                }
            } else {
                cartItems + CartItem(dialogBarcode, dialogName, price, dialogQuantity)
            }
        }
        val newMemory = priceMemory + (dialogBarcode to (dialogName to price))
        cartItems = newItems
        priceMemory = newMemory
        persistAll(newItems, newMemory)
        showDialog = false
    }

    val total = cartItems.sumOf { it.unitPrice * it.quantity }
    val totalArticles = cartItems.sumOf { it.quantity }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Total du panier", fontSize = 15.sp)
                Text("${formatPrice(total)} €", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("$totalArticles article(s)", fontSize = 13.sp)
            }
        }

        if (cartItems.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Panier vide.\nAppuie sur Scanner pour ajouter un article.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(cartItems, key = { it.barcode }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${item.quantity} × ${formatPrice(item.unitPrice)} € = ${formatPrice(item.unitPrice * item.quantity)} €",
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(onClick = { openEditDialog(item) }) { Text("Modifier") }
                            TextButton(onClick = {
                                val newItems = cartItems.filter { it.barcode != item.barcode }
                                cartItems = newItems
                                persistAll(newItems, priceMemory)
                            }) { Text("✕") }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    cartItems = emptyList()
                    persistAll(emptyList(), priceMemory)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Vider le panier") }

            Button(onClick = { openScanner() }, modifier = Modifier.weight(1f)) {
                Text("📷 Scanner")
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(if (editingBarcode != null) "Modifier l'article" else "Nouvel article") },
            text = {
                Column {
                    Text("Code-barres : $dialogBarcode", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogName,
                        onValueChange = { dialogName = it },
                        label = { Text(if (dialogIsLoading) "Nom (recherche en cours…)" else "Nom de l'article") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogPrice,
                        onValueChange = { dialogPrice = it },
                        label = { Text("Prix unitaire vu en rayon (€)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Quantité : ")
                        OutlinedButton(
                            onClick = { if (dialogQuantity > 1) dialogQuantity-- },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("−") }
                        Text("  $dialogQuantity  ", fontSize = 18.sp)
                        OutlinedButton(
                            onClick = { dialogQuantity++ },
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("+") }
                    }
                    if (dialogError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(dialogError ?: "", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDialog() }) {
                    Text(if (editingBarcode != null) "Enregistrer" else "Ajouter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Annuler") }
            }
        )
    }
}
