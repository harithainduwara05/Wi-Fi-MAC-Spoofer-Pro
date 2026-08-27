package com.example.macconverter.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Modern LSPosed / Xposed Hook for Android 12 - 17+
 *
 * Hooks into Android `system_server` and `com.android.wifi` Mainline APEX module
 * to intercept Wi-Fi MAC configuration requests at the Java Framework level.
 */
class XposedHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "MACConverter-Xposed"
        private const val PACKAGE_NAME = "com.example.macconverter"
        private const val PREFS_NAME = "MacConverterPrefs"

        private const val KEY_OPERATING_MODE = "operating_mode"
        private const val KEY_SPOOFED_MAC = "spoofed_mac"

        private const val MODE_NORMAL_DUTY = "NORMAL_DUTY"
        private const val MODE_CUSTOM_SPOOF = "CUSTOM_SPOOF"
        private const val MODE_FACTORY_HARDWARE = "FACTORY_HARDWARE"
    }

    private var prefs: XSharedPreferences? = null

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        try {
            prefs = XSharedPreferences(PACKAGE_NAME, PREFS_NAME)
            prefs?.makeWorldReadable()
            XposedBridge.log("[$TAG] Initialized XSharedPreferences from Zygote.")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Failed to init Zygote prefs: ${t.message}")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Target system_server or Android mainline Wi-Fi APEX packages
        val isSystemServer = lpparam.packageName == "android" || lpparam.processName == "system_server"
        val isWifiApex = lpparam.packageName.contains("wifi", ignoreCase = true)

        if (!isSystemServer && !isWifiApex) {
            return
        }

        XposedBridge.log("[$TAG] Hooking package: ${lpparam.packageName} (process: ${lpparam.processName})")

        hookWifiConfiguration(lpparam.classLoader)
        hookWifiNative(lpparam.classLoader)
        hookWifiInfo(lpparam.classLoader)
    }

    /**
     * Reloads preferences dynamically to reflect changes made from the app UI.
     */
    private fun reloadPrefs() {
        try {
            prefs?.reload()
        } catch (_: Throwable) { }
    }

    /**
     * Checks if a custom spoofed MAC should be returned.
     */
    private fun getTargetSpoofedMac(): String? {
        reloadPrefs()
        val mode = prefs?.getString(KEY_OPERATING_MODE, MODE_NORMAL_DUTY) ?: MODE_NORMAL_DUTY
        if (mode == MODE_CUSTOM_SPOOF) {
            val customMac = prefs?.getString(KEY_SPOOFED_MAC, null)
            if (!customMac.isNullOrBlank()) {
                return customMac.trim().uppercase()
            }
        }
        return null
    }

    /**
     * Checks if Factory Hardware MAC is forced.
     */
    private fun isFactoryHardwareMode(): Boolean {
        reloadPrefs()
        val mode = prefs?.getString(KEY_OPERATING_MODE, MODE_NORMAL_DUTY) ?: MODE_NORMAL_DUTY
        return mode == MODE_FACTORY_HARDWARE
    }

    /**
     * Hooks android.net.wifi.WifiConfiguration to override randomized MAC address.
     */
    private fun hookWifiConfiguration(classLoader: ClassLoader) {
        try {
            val wifiConfigClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiConfiguration", classLoader)
                ?: return

            // Hook getRandomizedMacAddress()
            XposedHelpers.findAndHookMethod(
                wifiConfigClass,
                "getRandomizedMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val targetMac = getTargetSpoofedMac()
                        if (targetMac != null) {
                            try {
                                val macAddressClass = XposedHelpers.findClassIfExists("android.net.MacAddress", classLoader)
                                if (macAddressClass != null) {
                                    val macObj = XposedHelpers.callStaticMethod(macAddressClass, "fromString", targetMac)
                                    param.result = macObj
                                    XposedBridge.log("[$TAG] Overrode WifiConfiguration.getRandomizedMacAddress() -> $targetMac")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("[$TAG] Error converting MAC string: ${t.message}")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error hooking WifiConfiguration: ${t.message}")
        }
    }

    /**
     * Hooks WifiNative.setMacAddress to inject target MAC when configuring the Wi-Fi interface.
     */
    private fun hookWifiNative(classLoader: ClassLoader) {
        val candidateClasses = listOf(
            "com.android.server.wifi.WifiNative",
            "com.android.server.wifi.ClientModeImpl",
            "com.android.server.wifi.ConcreteClientModeManager"
        )

        for (className in candidateClasses) {
            try {
                val targetClass = XposedHelpers.findClassIfExists(className, classLoader) ?: continue

                // Hook setMacAddress methods with varying parameters across Android versions
                val methods = targetClass.declaredMethods
                for (method in methods) {
                    if (method.name == "setMacAddress" || method.name == "changeMacAddress") {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val targetMac = getTargetSpoofedMac() ?: return
                                    val macAddressClass = XposedHelpers.findClassIfExists("android.net.MacAddress", classLoader)
                                        ?: return

                                    for (i in param.args.indices) {
                                        val arg = param.args[i]
                                        if (arg != null && arg.javaClass.name == "android.net.MacAddress") {
                                            try {
                                                val newMacObj = XposedHelpers.callStaticMethod(macAddressClass, "fromString", targetMac)
                                                param.args[i] = newMacObj
                                                XposedBridge.log("[$TAG] Injected $targetMac into ${method.name} arg[$i]")
                                            } catch (t: Throwable) {
                                                XposedBridge.log("[$TAG] Failed to inject custom MacAddress: ${t.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Error hooking class $className: ${t.message}")
            }
        }
    }

    /**
     * Hooks WifiInfo.getMacAddress() for consistency across system queries.
     */
    private fun hookWifiInfo(classLoader: ClassLoader) {
        try {
            val wifiInfoClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo", classLoader)
                ?: return

            XposedHelpers.findAndHookMethod(
                wifiInfoClass,
                "getMacAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val targetMac = getTargetSpoofedMac()
                        if (targetMac != null) {
                            param.result = targetMac
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error hooking WifiInfo: ${t.message}")
        }
    }
}
