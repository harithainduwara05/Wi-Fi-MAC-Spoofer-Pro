package com.example.macconverter

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.macconverter.databinding.ActivityMainBinding
import com.example.macconverter.engine.WifiConfigStoreManager
import com.example.macconverter.model.OperatingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var configStoreManager: WifiConfigStoreManager

    private val interfaceName = "wlan0"
    private var originalHardwareMac: String? = null
    private var isRootGranted: Boolean = false
    private var currentMode: OperatingMode = OperatingMode.NORMAL_DUTY

    companion object {
        private const val PREFS_NAME = "MacConverterPrefs"
        private const val KEY_ORIGINAL_MAC = "original_hardware_mac"
        private const val KEY_OPERATING_MODE = "operating_mode"
        private const val KEY_SPOOFED_MAC = "spoofed_mac"
        private const val MAC_UNAVAILABLE = "Unavailable"
        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            configStoreManager = WifiConfigStoreManager(this)

            setupListeners()
            setupMacInputAutoFormatter()
            initializeSystem()
        } catch (e: Exception) {
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Initializes root check, hardware MAC detection, and stored operating mode.
     */
    private fun initializeSystem() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                logConsole("Initializing system check for Android 12-17+ environment…")

                // 1. Verify Root (su) Access
                isRootGranted = checkRootAccess()
                updateRootBadge(isRootGranted)

                if (!isRootGranted) {
                    logConsole("[ERROR] Root permission denied or unavailable. SuperUser (su) required.")
                    Toast.makeText(
                        this@MainActivity,
                        "Root access is required! Please grant SuperUser permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    logConsole("[SUCCESS] Root (su) access verified successfully.")
                }

                // 2. Fetch Permanent Hardware MAC
                val savedOriginalMac = sharedPreferences.getString(KEY_ORIGINAL_MAC, null)
                val currentMac = fetchCurrentMacAddress()

                if (savedOriginalMac.isNullOrEmpty()) {
                    val permanentMac = fetchPermanentMacAddress()
                    if (isValidMac(permanentMac)) {
                        originalHardwareMac = permanentMac
                        sharedPreferences.edit().putString(KEY_ORIGINAL_MAC, permanentMac).apply()
                        logConsole("[INFO] Saved permanent factory hardware MAC: $permanentMac")
                    } else if (isValidMac(currentMac)) {
                        originalHardwareMac = currentMac
                        sharedPreferences.edit().putString(KEY_ORIGINAL_MAC, currentMac).apply()
                        logConsole("[WARN] Saved active MAC as original: $currentMac")
                    } else {
                        logConsole("[WARN] Could not retrieve hardware MAC address.")
                    }
                } else {
                    originalHardwareMac = savedOriginalMac
                    logConsole("[INFO] Loaded stored factory hardware MAC: $savedOriginalMac")
                }

                // 3. Load Operating Mode
                val savedModeKey = sharedPreferences.getString(KEY_OPERATING_MODE, OperatingMode.NORMAL_DUTY.key)
                currentMode = OperatingMode.fromKey(savedModeKey)
                updateModeBadge(currentMode)

                // 4. Update UI Fields
                binding.tvOriginalMac.text = originalHardwareMac ?: MAC_UNAVAILABLE
                binding.tvCurrentMac.text = currentMac

                val activeStore = configStoreManager.resolveActiveStorePath()
                logConsole("[INFO] Active Wi-Fi Storage Store: $activeStore")
                logConsole("[READY] Active Mode: ${currentMode.displayName}")

            } catch (e: Exception) {
                logConsole("[ERROR] Initialization failed: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Attaches click listeners to action buttons.
     */
    private fun setupListeners() {
        // Mode 1: Normal Duty (Android Auto Dynamic Randomization)
        binding.btnApplyNormalDuty.setOnClickListener {
            applyNormalDutyMode()
        }

        // Generate Random MAC
        binding.btnGenerateRandom.setOnClickListener {
            try {
                val randomMac = generateLocallyAdministeredUnicastMac()
                binding.etMacInput.setText(randomMac)
                binding.etMacInput.setSelection(randomMac.length)
                logConsole("Generated RFC 7042 Unicast MAC: $randomMac")
            } catch (e: Exception) {
                logConsole("[ERROR] Random MAC generation error: ${e.message}")
            }
        }

        // Mode 2: Apply Custom Spoof
        binding.btnApplyCustomSpoof.setOnClickListener {
            val targetMac = binding.etMacInput.text?.toString()?.trim()?.uppercase(Locale.ROOT) ?: ""
            if (!isValidMac(targetMac)) {
                binding.tilMacInput.error = "Please enter a valid MAC (XX:XX:XX:XX:XX:XX)"
                Toast.makeText(this, "Invalid MAC Address format!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.tilMacInput.error = null
            applyCustomSpoofMode(targetMac)
        }

        // Mode 3: Factory Hardware MAC
        binding.btnApplyFactoryHardware.setOnClickListener {
            applyFactoryHardwareMode()
        }

        // Refresh Status Button
        binding.btnRefreshStatus.setOnClickListener {
            refreshStatus()
        }

        // Clear Console Logs Button
        binding.btnClearLogs.setOnClickListener {
            binding.tvConsoleLogs.text = "[Console cleared]\n"
        }
    }

    /**
     * Mode 1: Restores Android Default Dynamic Randomization (Normal Duty).
     */
    private fun applyNormalDutyMode() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                logConsole("========================================")
                logConsole("[MODE 1] Activating Android Normal Duty (Auto Randomization)…")

                val result = withContext(Dispatchers.IO) {
                    configStoreManager.restoreNormalDuty { msg -> logConsole(msg) }
                }

                if (result.isSuccess) {
                    currentMode = OperatingMode.NORMAL_DUTY
                    sharedPreferences.edit()
                        .putString(KEY_OPERATING_MODE, currentMode.key)
                        .remove(KEY_SPOOFED_MAC)
                        .apply()

                    updateModeBadge(currentMode)
                    logConsole("[SUCCESS] ${result.message}")
                    delay(1500)

                    val activeMac = fetchCurrentMacAddress()
                    binding.tvCurrentMac.text = activeMac
                    logConsole("[STATUS] Current Active MAC: $activeMac")

                    Toast.makeText(
                        this@MainActivity,
                        "Android Normal Duty restored! OS handles MAC auto-randomization.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    logConsole("[FAILED] ${result.message}")
                    Toast.makeText(this@MainActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
                logConsole("========================================")
            } catch (e: Exception) {
                logConsole("[CRASH PREVENTED] Exception: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Mode 2: Injects Custom Spoofed MAC into APEX Wi-Fi Store and syncs LSPosed hooks.
     */
    private fun applyCustomSpoofMode(targetMac: String) {
        lifecycleScope.launch {
            setLoading(true)
            try {
                logConsole("========================================")
                logConsole("[MODE 2] Ingesting Custom Spoofed MAC: $targetMac")

                val result = withContext(Dispatchers.IO) {
                    configStoreManager.applyCustomSpoof(targetMac) { msg -> logConsole(msg) }
                }

                if (result.isSuccess) {
                    currentMode = OperatingMode.CUSTOM_SPOOF
                    sharedPreferences.edit()
                        .putString(KEY_OPERATING_MODE, currentMode.key)
                        .putString(KEY_SPOOFED_MAC, targetMac)
                        .apply()

                    updateModeBadge(currentMode)
                    logConsole("[SUCCESS] ${result.message}")

                    // Secondary attempt: Apply via kernel shell as fallback
                    executeRootCommands(listOf(
                        "ip link set $interfaceName address $targetMac 2>/dev/null || true"
                    ))

                    delay(1500)
                    val verifiedMac = fetchCurrentMacAddress()
                    binding.tvCurrentMac.text = verifiedMac
                    logConsole("[STATUS] Current Active MAC: $verifiedMac")

                    Toast.makeText(
                        this@MainActivity,
                        "Custom MAC ($targetMac) applied via APEX Wi-Fi Framework!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    logConsole("[FAILED] ${result.message}")
                    Toast.makeText(this@MainActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
                logConsole("========================================")
            } catch (e: Exception) {
                logConsole("[CRASH PREVENTED] Exception: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Mode 3: Forces genuine physical hardware MAC across all network configurations.
     */
    private fun applyFactoryHardwareMode() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                logConsole("========================================")
                logConsole("[MODE 3] Activating Factory Hardware Mode (Device MAC)…")

                val result = withContext(Dispatchers.IO) {
                    configStoreManager.forceFactoryHardwareMac { msg -> logConsole(msg) }
                }

                if (result.isSuccess) {
                    currentMode = OperatingMode.FACTORY_HARDWARE
                    sharedPreferences.edit()
                        .putString(KEY_OPERATING_MODE, currentMode.key)
                        .putString(KEY_SPOOFED_MAC, originalHardwareMac ?: "")
                        .apply()

                    updateModeBadge(currentMode)
                    logConsole("[SUCCESS] ${result.message}")
                    delay(1500)

                    val activeMac = fetchCurrentMacAddress()
                    binding.tvCurrentMac.text = activeMac
                    logConsole("[STATUS] Current Active MAC: $activeMac")

                    Toast.makeText(
                        this@MainActivity,
                        "Factory Hardware MAC applied. Randomization disabled.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    logConsole("[FAILED] ${result.message}")
                    Toast.makeText(this@MainActivity, "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
                logConsole("========================================")
            } catch (e: Exception) {
                logConsole("[CRASH PREVENTED] Exception: ${e.message}")
                Toast.makeText(this@MainActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Refreshes the active MAC address from the interface.
     */
    private fun refreshStatus() {
        lifecycleScope.launch {
            setLoading(true)
            try {
                logConsole("Refreshing active MAC status…")
                val activeMac = fetchCurrentMacAddress()
                binding.tvCurrentMac.text = activeMac
                logConsole("Active MAC address (wlan0): $activeMac")
                Toast.makeText(this@MainActivity, "Active MAC: $activeMac", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logConsole("[ERROR] Refresh status failed: ${e.message}")
            } finally {
                setLoading(false)
            }
        }
    }

    /**
     * Updates the Active Mode Badge in Card 1.
     */
    private fun updateModeBadge(mode: OperatingMode) {
        runOnUiThread {
            when (mode) {
                OperatingMode.NORMAL_DUTY -> {
                    binding.tvActiveModeBadge.text = "NORMAL DUTY (ANDROID AUTO)"
                    binding.tvActiveModeBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                    binding.tvActiveModeBadge.setBackgroundColor(Color.parseColor("#2610B981"))
                }
                OperatingMode.CUSTOM_SPOOF -> {
                    binding.tvActiveModeBadge.text = "CUSTOM MAC SPOOFED"
                    binding.tvActiveModeBadge.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
                    binding.tvActiveModeBadge.setBackgroundColor(Color.parseColor("#266366F1"))
                }
                OperatingMode.FACTORY_HARDWARE -> {
                    binding.tvActiveModeBadge.text = "FACTORY HARDWARE MAC"
                    binding.tvActiveModeBadge.setTextColor(ContextCompat.getColor(this, R.color.status_info))
                    binding.tvActiveModeBadge.setBackgroundColor(Color.parseColor("#2638BDF8"))
                }
            }
        }
    }

    /**
     * Executes an array of commands in a single root (`su`) shell session.
     */
    private suspend fun executeRootCommands(commands: List<String>): RootResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        var outputStream: DataOutputStream? = null
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        try {
            process = Runtime.getRuntime().exec("su")
            outputStream = DataOutputStream(process.outputStream)

            for (cmd in commands) {
                outputStream.writeBytes("$cmd\n")
                outputStream.flush()
            }

            outputStream.writeBytes("exit\n")
            outputStream.flush()

            val stdInput = BufferedReader(InputStreamReader(process.inputStream))
            val stdError = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (stdInput.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
            while (stdError.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                RootResult(isSuccess = true, output = stdout.toString().trim())
            } else {
                val err = if (stderr.isNotBlank()) stderr.toString().trim() else "Exit code: $exitCode"
                RootResult(isSuccess = false, errorMessage = err)
            }
        } catch (e: Exception) {
            RootResult(
                isSuccess = false,
                errorMessage = e.message ?: "Failed to execute root command"
            )
        } finally {
            try {
                outputStream?.close()
                process?.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if Root (su) permission is available and accessible.
     */
    private suspend fun checkRootAccess(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Reads the PERMANENT (factory) hardware MAC address.
     */
    private suspend fun fetchPermanentMacAddress(): String = withContext(Dispatchers.IO) {
        // Strategy 1: perm_address sysfs node
        try {
            val result = executeRootCommands(listOf("cat /sys/class/net/$interfaceName/perm_address"))
            if (result.isSuccess && isValidMac(result.output?.trim() ?: "")) {
                return@withContext result.output!!.trim().uppercase(Locale.ROOT)
            }
        } catch (_: Exception) {}

        // Strategy 2: ethtool permanent address
        try {
            val result = executeRootCommands(listOf("ethtool -P $interfaceName"))
            if (result.isSuccess && result.output != null) {
                val match = Regex("Permanent address:\\s+([0-9a-fA-F:]{17})").find(result.output)
                if (match != null) {
                    return@withContext match.groupValues[1].uppercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {}

        return@withContext MAC_UNAVAILABLE
    }

    /**
     * Reads the CURRENT active MAC address from the wlan0 interface.
     */
    private suspend fun fetchCurrentMacAddress(): String = withContext(Dispatchers.IO) {
        // Strategy 1: Direct sysfs address file via root
        try {
            val sysfsResult = executeRootCommands(listOf("cat /sys/class/net/$interfaceName/address"))
            if (sysfsResult.isSuccess && isValidMac(sysfsResult.output?.trim() ?: "")) {
                return@withContext sysfsResult.output!!.trim().uppercase(Locale.ROOT)
            }
        } catch (_: Exception) {}

        // Strategy 2: ip link command via root
        try {
            val ipResult = executeRootCommands(listOf("ip link show $interfaceName"))
            if (ipResult.isSuccess && ipResult.output != null) {
                val match = Regex("link/ether\\s+([0-9a-fA-F:]{17})").find(ipResult.output)
                if (match != null) {
                    return@withContext match.groupValues[1].uppercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {}

        // Strategy 3: Standard Java NetworkInterface (Fallback)
        try {
            val networkInterface = NetworkInterface.getByName(interfaceName)
            val macBytes = networkInterface?.hardwareAddress
            if (macBytes != null && macBytes.isNotEmpty()) {
                val sb = StringBuilder()
                for (b in macBytes) {
                    sb.append(String.format("%02X:", b))
                }
                if (sb.isNotEmpty()) {
                    sb.deleteCharAt(sb.length - 1)
                }
                val formatted = sb.toString()
                if (isValidMac(formatted)) {
                    return@withContext formatted.uppercase(Locale.ROOT)
                }
            }
        } catch (_: Exception) {}

        return@withContext MAC_UNAVAILABLE
    }

    /**
     * Generates a valid randomized Locally Administered Unicast MAC (RFC 7042).
     */
    private fun generateLocallyAdministeredUnicastMac(): String {
        val random = SecureRandom()
        val macBytes = ByteArray(6)
        random.nextBytes(macBytes)

        val validFirstNibbles = intArrayOf(0x02, 0x06, 0x0A, 0x0E)
        val firstByte = validFirstNibbles[random.nextInt(validFirstNibbles.size)]
        macBytes[0] = firstByte.toByte()

        val sb = StringBuilder()
        for (i in macBytes.indices) {
            sb.append(String.format("%02X", macBytes[i]))
            if (i < macBytes.size - 1) {
                sb.append(":")
            }
        }
        return sb.toString()
    }

    /**
     * Validates whether a MAC address conforms to standard notation.
     */
    private fun isValidMac(mac: String): Boolean {
        if (mac.isBlank() || mac.equals(MAC_UNAVAILABLE, ignoreCase = true)) {
            return false
        }
        return MAC_REGEX.matches(mac)
    }

    /**
     * Updates the status badge at top right.
     */
    private fun updateRootBadge(granted: Boolean) {
        runOnUiThread {
            if (granted) {
                binding.tvRootBadge.text = getString(R.string.root_status_granted)
                binding.tvRootBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                binding.tvRootBadge.setBackgroundResource(R.drawable.bg_badge)
            } else {
                binding.tvRootBadge.text = getString(R.string.root_status_denied)
                binding.tvRootBadge.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                binding.tvRootBadge.setBackgroundColor(Color.parseColor("#33EF4444"))
            }
        }
    }

    /**
     * Appends timestamped log text to the terminal log view in a 100% thread-safe manner.
     */
    private fun logConsole(message: String) {
        runOnUiThread {
            try {
                val timeStamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val logEntry = "[$timeStamp] $message\n"
                binding.tvConsoleLogs.append(logEntry)

                binding.tvConsoleLogs.post {
                    val layout = binding.tvConsoleLogs.layout ?: return@post
                    val lineCount = binding.tvConsoleLogs.lineCount
                    if (lineCount > 0) {
                        val scrollAmount = layout.getLineTop(lineCount) - binding.tvConsoleLogs.height
                        if (scrollAmount > 0) {
                            binding.tvConsoleLogs.scrollTo(0, scrollAmount)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Shows/hides indeterminate progress bar and toggles button states safely.
     */
    private fun setLoading(isLoading: Boolean) {
        runOnUiThread {
            try {
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.btnApplyNormalDuty.isEnabled = !isLoading
                binding.btnApplyCustomSpoof.isEnabled = !isLoading
                binding.btnApplyFactoryHardware.isEnabled = !isLoading
                binding.btnGenerateRandom.isEnabled = !isLoading
                binding.btnRefreshStatus.isEnabled = !isLoading
            } catch (_: Exception) {}
        }
    }

    /**
     * Auto-formats input to insert colons ':' every 2 characters and convert to uppercase.
     */
    private fun setupMacInputAutoFormatter() {
        binding.etMacInput.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return

                val raw = s.toString().replace(":", "").uppercase(Locale.ROOT)
                if (raw.length > 12) return

                isFormatting = true
                val formatted = StringBuilder()
                for (i in raw.indices) {
                    formatted.append(raw[i])
                    if ((i % 2 == 1) && (i < raw.length - 1) && (i < 11)) {
                        formatted.append(":")
                    }
                }

                s.replace(0, s.length, formatted.toString())
                isFormatting = false
            }
        })
    }

    data class RootResult(
        val isSuccess: Boolean,
        val output: String? = null,
        val errorMessage: String? = null
    )
}
