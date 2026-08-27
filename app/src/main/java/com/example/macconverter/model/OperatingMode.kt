package com.example.macconverter.model

/**
 * Operating modes for Wi-Fi MAC Address behavior.
 */
enum class OperatingMode(val key: String, val displayName: String, val description: String) {
    /**
     * Mode 1: Default Android OS Mode (Normal Duty).
     * Android handles MAC randomization dynamically per-SSID without third-party interference.
     */
    NORMAL_DUTY(
        "NORMAL_DUTY",
        "Normal Duty (Android Auto)",
        "Standard Android OS behavior. Dynamic per-network MAC randomization enabled."
    ),

    /**
     * Mode 2: Custom / Spoofed MAC Address.
     * Overrides the Wi-Fi framework and APEX ConfigStore with user-defined or generated MAC.
     */
    CUSTOM_SPOOF(
        "CUSTOM_SPOOF",
        "Custom MAC Spoofed",
        "Custom MAC address injected directly into Android APEX Wi-Fi Framework."
    ),

    /**
     * Mode 3: Hardware Factory Original MAC.
     * Disables randomization completely and forces device to use genuine factory MAC.
     */
    FACTORY_HARDWARE(
        "FACTORY_HARDWARE",
        "Factory Hardware MAC",
        "Randomization disabled. Device connects using permanent hardware MAC."
    );

    companion object {
        fun fromKey(key: String?): OperatingMode {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: NORMAL_DUTY
        }
    }
}
