package com.example.macconverter.engine

import android.content.Context
import com.example.macconverter.model.OperatingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Root Engine for modern Android 12 - 17+ Mainline APEX Wi-Fi subsystem.
 *
 * Directly manages:
 * - APEX Store: /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml
 * - Legacy Store: /data/misc/wifi/WifiConfigStore.xml
 */
class WifiConfigStoreManager(private val context: Context) {

    companion object {
        const val APEX_CONFIG_STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"
        const val LEGACY_CONFIG_STORE = "/data/misc/wifi/WifiConfigStore.xml"

        private val REGEX_RANDOMIZED_MAC = Regex("<string name=\"RandomizedMacAddress\">([0-9A-Fa-f:]{17})</string>")
        private val REGEX_RANDOMIZATION_SETTING = Regex("<int name=\"MacRandomizationSetting\" value=\"(\\d+)\" />")
    }

    data class ExecutionResult(
        val isSuccess: Boolean,
        val message: String,
        val modifiedCount: Int = 0
    )

    /**
     * Executes root commands safely in a single su session.
     */
    private suspend fun runRootCommands(commands: List<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        var process: Process? = null
        var outputStream: DataOutputStream? = null
        val output = StringBuilder()

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
                output.append(line).append("\n")
            }
            while (stdError.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            Pair(exitCode, output.toString().trim())
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Failed to execute root command")
        } finally {
            try {
                outputStream?.close()
                process?.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * Finds existing active WifiConfigStore.xml path on the device.
     */
    suspend fun resolveActiveStorePath(): String = withContext(Dispatchers.IO) {
        val checkApex = runRootCommands(listOf("ls $APEX_CONFIG_STORE"))
        if (checkApex.first == 0 && !checkApex.second.contains("No such file", ignoreCase = true)) {
            return@withContext APEX_CONFIG_STORE
        }
        val checkLegacy = runRootCommands(listOf("ls $LEGACY_CONFIG_STORE"))
        if (checkLegacy.first == 0 && !checkLegacy.second.contains("No such file", ignoreCase = true)) {
            return@withContext LEGACY_CONFIG_STORE
        }
        APEX_CONFIG_STORE // Default to modern APEX
    }

    /**
     * Injects custom spoofed MAC into WifiConfigStore.xml (Mode 2).
     */
    suspend fun applyCustomSpoof(
        targetMac: String,
        logCallback: suspend (String) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val storePath = resolveActiveStorePath()
            logCallback("[APEX Engine] Target Store: $storePath")

            // 1. Temporarily toggle Wi-Fi off to safely modify config store
            logCallback("[APEX Engine] Disabling Wi-Fi subsystem…")
            runRootCommands(listOf("cmd wifi set-wifi-enabled disabled || svc wifi disable"))
            kotlinx.coroutines.delay(1000)

            // 2. Read existing XML content
            val readResult = runRootCommands(listOf("cat $storePath"))
            if (readResult.first != 0 || readResult.second.isBlank()) {
                // Restore Wi-Fi before returning error
                runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
                return@withContext ExecutionResult(
                    isSuccess = false,
                    message = "Could not read $storePath (Exit: ${readResult.first}). Stderr: ${readResult.second}"
                )
            }

            var xmlContent = readResult.second
            var modifiedEntries = 0

            // 3. Replace all RandomizedMacAddress entries with target MAC
            val foundMatches = REGEX_RANDOMIZED_MAC.findAll(xmlContent).count()
            if (foundMatches > 0) {
                xmlContent = REGEX_RANDOMIZED_MAC.replace(xmlContent) {
                    modifiedEntries++
                    "<string name=\"RandomizedMacAddress\">$targetMac</string>"
                }
                logCallback("[APEX Engine] Replaced $modifiedEntries saved network MAC entries with $targetMac")
            }

            // 4. Ensure MacRandomizationSetting is enabled (value = 1)
            xmlContent = REGEX_RANDOMIZATION_SETTING.replace(xmlContent) {
                "<int name=\"MacRandomizationSetting\" value=\"1\" />"
            }

            // 5. Write back to store using local cache file transfer
            val writeResult = writeXmlContentSafely(storePath, xmlContent)
            if (!writeResult.isSuccess) {
                runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
                return@withContext writeResult
            }

            // 6. Re-enable Wi-Fi
            logCallback("[APEX Engine] Re-enabling Wi-Fi subsystem…")
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            kotlinx.coroutines.delay(1200)

            // 7. Make preferences readable for LSPosed hooks
            syncWorldReadablePrefs()

            ExecutionResult(
                isSuccess = true,
                message = "Successfully injected $targetMac into $storePath ($modifiedEntries networks updated).",
                modifiedCount = modifiedEntries
            )
        } catch (e: Exception) {
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            ExecutionResult(
                isSuccess = false,
                message = "Exception during applyCustomSpoof: ${e.message}"
            )
        }
    }

    /**
     * Restores Android default auto dynamic randomization (Mode 1: Normal Duty).
     */
    suspend fun restoreNormalDuty(
        logCallback: suspend (String) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val storePath = resolveActiveStorePath()
            logCallback("[APEX Engine] Restoring Android Normal Duty in: $storePath")

            // 1. Disable Wi-Fi
            logCallback("[APEX Engine] Disabling Wi-Fi for reset…")
            runRootCommands(listOf("cmd wifi set-wifi-enabled disabled || svc wifi disable"))
            kotlinx.coroutines.delay(1000)

            // 2. Read XML
            val readResult = runRootCommands(listOf("cat $storePath"))
            if (readResult.first == 0 && readResult.second.isNotBlank()) {
                var xmlContent = readResult.second
                // Reset MacRandomizationSetting to 1 (Standard Android dynamic auto randomization)
                xmlContent = REGEX_RANDOMIZATION_SETTING.replace(xmlContent) {
                    "<int name=\"MacRandomizationSetting\" value=\"1\" />"
                }
                writeXmlContentSafely(storePath, xmlContent)
            }

            // 3. Re-enable Wi-Fi
            logCallback("[APEX Engine] Enabling Wi-Fi with Android Auto Randomization active…")
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            kotlinx.coroutines.delay(1200)

            syncWorldReadablePrefs()

            ExecutionResult(
                isSuccess = true,
                message = "Android Normal Duty restored. System handles MAC randomization dynamically."
            )
        } catch (e: Exception) {
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            ExecutionResult(
                isSuccess = false,
                message = "Exception during restoreNormalDuty: ${e.message}"
            )
        }
    }

    /**
     * Forces the genuine physical factory hardware MAC across all Wi-Fi configurations (Mode 3).
     */
    suspend fun forceFactoryHardwareMac(
        logCallback: suspend (String) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val storePath = resolveActiveStorePath()
            logCallback("[APEX Engine] Setting Factory Hardware Mode (Device MAC) in: $storePath")

            // 1. Disable Wi-Fi
            runRootCommands(listOf("cmd wifi set-wifi-enabled disabled || svc wifi disable"))
            kotlinx.coroutines.delay(1000)

            // 2. Read XML
            val readResult = runRootCommands(listOf("cat $storePath"))
            if (readResult.first != 0 || readResult.second.isBlank()) {
                runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
                return@withContext ExecutionResult(
                    isSuccess = false,
                    message = "Could not read $storePath"
                )
            }

            var xmlContent = readResult.second
            var modifiedEntries = 0

            // 3. Set MacRandomizationSetting = 0 (RANDOMIZATION_NONE = Use Device MAC)
            xmlContent = REGEX_RANDOMIZATION_SETTING.replace(xmlContent) {
                modifiedEntries++
                "<int name=\"MacRandomizationSetting\" value=\"0\" />"
            }

            // 4. Write back
            val writeResult = writeXmlContentSafely(storePath, xmlContent)
            if (!writeResult.isSuccess) {
                runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
                return@withContext writeResult
            }

            // 5. Re-enable Wi-Fi
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            kotlinx.coroutines.delay(1200)

            syncWorldReadablePrefs()

            ExecutionResult(
                isSuccess = true,
                message = "Factory Hardware Mode applied. Android will use permanent device MAC.",
                modifiedCount = modifiedEntries
            )
        } catch (e: Exception) {
            runRootCommands(listOf("cmd wifi set-wifi-enabled enabled || svc wifi enable"))
            ExecutionResult(
                isSuccess = false,
                message = "Exception during forceFactoryHardwareMac: ${e.message}"
            )
        }
    }

    /**
     * Safely writes XML content using local cache file transfer with correct permissions.
     */
    private suspend fun writeXmlContentSafely(targetPath: String, content: String): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, "wifi_store_temp.xml")
            cacheFile.writeText(content, Charsets.UTF_8)
            val cachePath = cacheFile.absolutePath

            val commands = listOf(
                "cp $cachePath $targetPath",
                "rm -f $cachePath",
                "chown wifi:wifi $targetPath || chown 1010:1010 $targetPath",
                "chmod 600 $targetPath",
                "restorecon $targetPath || true"
            )

            val result = runRootCommands(commands)
            if (result.first == 0) {
                ExecutionResult(isSuccess = true, message = "Store updated successfully.")
            } else {
                ExecutionResult(isSuccess = false, message = "Failed to write $targetPath: ${result.second}")
            }
        } catch (e: Exception) {
            ExecutionResult(isSuccess = false, message = "File write error: ${e.message}")
        }
    }

    /**
     * Makes app SharedPreferences world-readable so LSPosed hooks can access preferences.
     */
    suspend fun syncWorldReadablePrefs() = withContext(Dispatchers.IO) {
        try {
            val pkgName = context.packageName
            val prefsDir = "/data/data/$pkgName/shared_prefs"
            val prefsFile = "$prefsDir/MacConverterPrefs.xml"
            runRootCommands(listOf(
                "chmod 755 /data/data/$pkgName",
                "chmod 755 $prefsDir",
                "chmod 664 $prefsFile || chmod 644 $prefsFile"
            ))
        } catch (_: Exception) {}
    }
}
