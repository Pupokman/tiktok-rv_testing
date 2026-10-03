package app.revanced.patches.tiktok.misc.proxy

import app.revanced.patcher.*
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.tiktok.misc.extension.sharedExtensionPatch
import app.revanced.patches.tiktok.misc.settings.settingsStatusLoadMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SMART_PROXY_DESCRIPTOR =
    "Lapp/revanced/extension/tiktok/network/SmartProxy;->apply(Ljava/lang/Object;)V"

private val BytecodePatchContext.ttNetGetCronetHttpClientMethod by gettingFirstMethodDeclaratively {
    name("getCronetHttpClient")
    definingClass("Lcom/bytedance/ttnet/TTNetInit;")
}

@Suppress("unused")
val smartProxyPatch = bytecodePatch(
    name = "Smart proxy",
    description = "Routes TikTok API/control traffic through a proxy while keeping media traffic direct.",
    use = false,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically",
    )

    apply {
        // getCronetHttpClient is a stable TTNet SDK method. Call the extension immediately before
        // each return. The extension itself verifies that Cronet has a live engine and applies the
        // native TTNet proxy only once per engine/config.
        ttNetGetCronetHttpClientMethod.apply {
            val returnIndices = implementation!!.instructions
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }

            returnIndices.asReversed().forEach { index ->
                val resultRegister = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(index, "invoke-static {v$resultRegister}, $SMART_PROXY_DESCRIPTOR")
            }
        }

        settingsStatusLoadMethod.addInstruction(
            0,
            "invoke-static {}, Lapp/revanced/extension/tiktok/settings/SettingsStatus;->enableSmartProxy()V",
        )
    }
}
