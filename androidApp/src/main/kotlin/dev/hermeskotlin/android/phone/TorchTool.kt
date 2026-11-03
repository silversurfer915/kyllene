package dev.hermeskotlin.android.phone

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `phone_torch`: the flashlight. It takes no permission — a torch is not a camera — and needs no approval: the user can
 * see it, and "off" undoes it.
 *
 * Whether it is on comes from the camera's own callback rather than a flag this tool keeps, so a toggle still does the
 * right thing after the user flipped the flashlight from the quick settings tile.
 */
internal class TorchTool(context: Context) {

    private val camera = context.getSystemService(CameraManager::class.java)

    /** The lens that has a flash: the back one, on every phone with a torch. */
    private val cameraId: String? by lazy {
        val manager = camera ?: return@lazy null
        runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    private val lit = AtomicBoolean(false)

    init {
        runCatching {
            camera?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == this@TorchTool.cameraId) lit.set(enabled)
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == this@TorchTool.cameraId) lit.set(false)
                }
            }, null)
        }
    }

    fun run(args: JsonObject): JsonObject {
        val manager = camera ?: throw PhoneToolException("This phone has no camera service.")
        val id = cameraId ?: throw PhoneToolException("This phone has no flashlight.")
        val on = when (args.string("action")?.lowercase() ?: "toggle") {
            "on" -> true
            "off" -> false
            "toggle" -> !lit.get()
            else -> throw PhoneToolException("action is on, off or toggle.")
        }
        try {
            manager.setTorchMode(id, on)
        } catch (e: Exception) {
            // Another app can hold the camera; the torch then belongs to it.
            throw PhoneToolException("The flashlight didn't answer: ${e.message ?: e.javaClass.simpleName}")
        }
        lit.set(on)
        return buildJsonObject {
            put("torch", if (on) "on" else "off")
            put("note", if (on) "The flashlight is on." else "The flashlight is off.")
        }
    }
}
