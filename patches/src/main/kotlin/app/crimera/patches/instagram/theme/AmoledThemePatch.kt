/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.theme

import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.misc.settings.Categories
import app.crimera.patches.instagram.misc.settings.instagramToggle
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.THEME_DESCRIPTOR
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element
import org.w3c.dom.NodeList

private const val STYLES_FILE = "res/values/styles.xml"
private val AMOLED_OVERLAY_ATTRIBUTES =
    listOf(
        "igds_color_primary_background",
        "status_bar_background",
        "igds_color_elevated_background",
        "igds_color_media_background",
        "igds_color_clips_tab_bar_background",
    )
private const val BDS_BLACK_COLOR = "bds_black"
private const val OVERLAY_STYLE_NAME = "PikoAmoledOverlay"
private const val GRAY_OVERRIDES_STYLE_NAME = "IgdsPrismGrayOverrides"
private const val PRISM_DARK_STYLE_NAME = "IgdsPrismSemanticColorsExperimentDark"
private const val CONTEXT_DESCRIPTOR = "Landroid/content/Context;"
private const val CONTEXT_THEME_DESCRIPTOR = "Landroid/content/res/Resources\$Theme;"
private const val CONTEXT_THEME_WRAPPER_DESCRIPTOR = "Landroid/view/ContextThemeWrapper;"
private const val APPLY_OVERLAY_DESCRIPTOR =
    "Lapp/morphe/extension/instagram/patches/theme/AmoledTheme;->applyOverlay($CONTEXT_DESCRIPTOR)V"
private const val BASE_PRISM_COLORS_DESCRIPTOR = "Lcom/instagram/compose/core/theme/BasePrismColors;"
private const val BASE_PRISM_COLORS_V2_DESCRIPTOR = "Lcom/instagram/compose/core/theme/BasePrismColorsV2;"
private const val BASE_PRISM_BLACK_LITERAL = 0x00000000ff0c1014L
private const val COMPOSE_COLOR_SHIFT = 0x20L

/** Adds the runtime-applied overlay style; the original Prism styles and palette remain unchanged. */
@Suppress("unused")
internal val amoledThemeResources =
    resourcePatch(
        description = "Adds a pure-black overlay style for dark-mode View surfaces.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            document(STYLES_FILE).use { document ->
                addAmoledOverlayStyle(document.documentElement)
            }
        }
    }

/**
 * The runtime toggle controls both the Compose palette hooks and the View-theme overlay. Original Prism resource
 * values remain unchanged, so disabling the setting and restarting restores Instagram's stock colors.
 */
@Suppress("unused")
val amoledThemePatch =
    bytecodePatch(
        name = "AMOLED theme",
        description = "Uses pure black for dark-mode Compose and View surfaces when enabled.",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(sharedExtensionPatch, amoledThemeResources, resourceMappingPatch)

        instagramToggle(
            id = "instagram.theme.amoled_black",
            category = Categories.THEME,
            strings = settingStrings("piko_ig_amoled_black"),
            order = 100,
            defaultValue = true,
            rebootApp = true,
        )

        execute {
            validateAmoledOverlayResources()
            blackenComposePalette(BASE_PRISM_COLORS_DESCRIPTOR, "BLACK")
            blackenComposePalette(BASE_PRISM_COLORS_V2_DESCRIPTOR, "GRAY_1600")
            hookViewThemeOverlay()
        }
    }

private fun addAmoledOverlayStyle(root: Element) {
    val existingStyles =
        root.getElementsByTagName("style").elements().filter { style ->
            style.getAttribute("name") == OVERLAY_STYLE_NAME
        }
    if (existingStyles.isNotEmpty()) {
        fail("Style $OVERLAY_STYLE_NAME already exists in $STYLES_FILE")
    }

    val document = root.ownerDocument
    val style = document.createElement("style")
    style.setAttribute("name", OVERLAY_STYLE_NAME)
    AMOLED_OVERLAY_ATTRIBUTES.forEach { attributeName ->
        val item = document.createElement("item")
        item.setAttribute("name", attributeName)
        item.textContent = "@color/$BDS_BLACK_COLOR"
        style.appendChild(item)
    }
    root.appendChild(style)
}

context(_: BytecodePatchContext)
private fun validateAmoledOverlayResources() {
    AMOLED_OVERLAY_ATTRIBUTES.forEach { attributeName ->
        requireMappedResource(ResourceType.ATTR, "attribute", attributeName)
    }
    requireMappedResource(ResourceType.COLOR, "color", BDS_BLACK_COLOR)
}

context(_: BytecodePatchContext)
private fun requireMappedResource(
    type: ResourceType,
    typeName: String,
    name: String,
): Long {
    val resourceId = getResourceId(type, name)
    if (resourceId == 0L) fail("Missing $typeName resource $name")
    return resourceId
}

context(patchContext: BytecodePatchContext)
private fun hookViewThemeOverlay() {
    val grayOverridesStyleId =
        requireMappedResource(ResourceType.STYLE, "style", GRAY_OVERRIDES_STYLE_NAME)
    val contextVoidMethods =
        Fingerprint(
            parameters = listOf(CONTEXT_DESCRIPTOR),
            returnType = "V",
        ).matchAll().map { match -> match.method }
    val methodsWithGrayOverridesStyle =
        contextVoidMethods.filter { method -> method.containsLiteral(grayOverridesStyleId) }
    val grayOverridesApplier =
        requireExactlyOne(
            "Context method applying $GRAY_OVERRIDES_STYLE_NAME (resource id=0x${grayOverridesStyleId.toString(16)}, " +
                "signature matches=${contextVoidMethods.size}, literal matches=${methodsWithGrayOverridesStyle.size})",
            methodsWithGrayOverridesStyle.filter { method ->
                method.containsInvoke { reference ->
                    reference.definingClass == CONTEXT_THEME_DESCRIPTOR &&
                        reference.name == "applyStyle" &&
                        reference.parameterTypes.map { type -> type.toString() } == listOf("I", "Z") &&
                        reference.returnType == "V"
                }
            },
        )
    val grayOverridesInstructions = grayOverridesApplier.instructionsOrThrow("$GRAY_OVERRIDES_STYLE_NAME applier")
    val grayOverridesReturn =
        requireExactlyOne(
            "$GRAY_OVERRIDES_STYLE_NAME applier return-void",
            grayOverridesInstructions.indices.filter { index ->
                grayOverridesInstructions[index].opcode == Opcode.RETURN_VOID
            },
        )
    val contextRegister = grayOverridesApplier.contextParameterRegister()
    val applyOverlay = methodReference(APPLY_OVERLAY_DESCRIPTOR)
    grayOverridesApplier.insertHook(index = grayOverridesReturn, relocateBranchTargets = true) {
        invokeStatic(applyOverlay, contextRegister)
    }

    val prismDarkStyleId =
        requireMappedResource(ResourceType.STYLE, "style", PRISM_DARK_STYLE_NAME)
    val contextReturningMethods =
        Fingerprint(
            parameters = listOf(CONTEXT_DESCRIPTOR),
            returnType = CONTEXT_DESCRIPTOR,
        ).matchAll().map { match -> match.method }
    val methodsWithPrismDarkStyle =
        contextReturningMethods.filter { method -> method.containsLiteral(prismDarkStyleId) }
    val darkContextWrapper =
        requireExactlyOne(
            "Context method wrapping with $PRISM_DARK_STYLE_NAME (resource id=0x${prismDarkStyleId.toString(16)}, " +
                "signature matches=${contextReturningMethods.size}, literal matches=${methodsWithPrismDarkStyle.size})",
            methodsWithPrismDarkStyle.filter { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.isNewInstanceOf(CONTEXT_THEME_WRAPPER_DESCRIPTOR)
                } == true
            },
        )
    val darkContextInstructions = darkContextWrapper.instructionsOrThrow("$PRISM_DARK_STYLE_NAME context wrapper")
    val contextReturns =
        darkContextInstructions.indices.filter { index ->
            darkContextInstructions[index].opcode == Opcode.RETURN_OBJECT
        }
    if (contextReturns.isEmpty()) fail("$PRISM_DARK_STYLE_NAME context wrapper has no return-object")
    contextReturns.sortedDescending().forEach { index ->
        val returnedContext =
            (darkContextInstructions[index] as? OneRegisterInstruction)?.registerA
                ?: fail("$PRISM_DARK_STYLE_NAME context wrapper return-object has no register")
        darkContextWrapper.insertHook(index = index, relocateBranchTargets = true) {
            invokeStatic(applyOverlay, returnedContext)
        }
    }
}

/** Hooks an exact palette value that supplies the Compose near-black to the theme. */
context(patchContext: BytecodePatchContext)
private fun blackenComposePalette(
    classDescriptor: String,
    fieldName: String,
) {
    val paletteField = "$classDescriptor->$fieldName:J"
    val colorsClass = patchContext.mutableClassDefBy(classDescriptor)
    val initializer =
        requireExactlyOne(
            "$paletteField static initializer",
            colorsClass.methods.filter { method ->
                method.name == "<clinit>" && method.returnType == "V" && method.parameterTypes.isEmpty()
            },
        )
    val instructions = initializer.implementation?.instructions?.toList()
        ?: throw PatchException("$classDescriptor has no <clinit> implementation")

    val blackStoreIndex =
        requireExactlyOne(
            "$paletteField sput-wide",
            instructions.indices.filter { index ->
                val instruction = instructions[index]
                instruction.opcode == Opcode.SPUT_WIDE &&
                    instruction.fieldReference()?.let { field ->
                        field.definingClass == classDescriptor &&
                            field.name == fieldName &&
                            field.type == "J"
                    } == true
            },
        )
    val blackStore = instructions[blackStoreIndex] as? OneRegisterInstruction
        ?: throw PatchException("$paletteField sput-wide has no source register")
    val colorRegister = blackStore.registerA

    val colorConstantIndex =
        requireExactlyOne(
            "$paletteField near-black const-wide",
            instructions.indices.filter { index ->
                val instruction = instructions[index]
                index < blackStoreIndex &&
                    instruction.opcode == Opcode.CONST_WIDE &&
                    (instruction as? WideLiteralInstruction)?.wideLiteral == BASE_PRISM_BLACK_LITERAL &&
                    (instruction as? OneRegisterInstruction)?.registerA == colorRegister
            },
        )
    val colorShiftIndex =
        requireExactlyOne(
            "$paletteField color shift",
            (colorConstantIndex + 1 until blackStoreIndex).filter { index ->
                val instruction = instructions[index]
                instruction.opcode == Opcode.SHL_LONG_2ADDR &&
                    (instruction as? TwoRegisterInstruction)?.registerA == colorRegister
            },
        )
    val colorShift = instructions[colorShiftIndex] as TwoRegisterInstruction

    val shiftCountConstantIndex =
        requireExactlyOne(
            "$paletteField 32-bit shift count",
            instructions.indices.filter { index ->
                val instruction = instructions[index]
                index < colorShiftIndex &&
                    instruction.opcode == Opcode.CONST_16 &&
                    (instruction as? NarrowLiteralInstruction)?.narrowLiteral == COMPOSE_COLOR_SHIFT.toInt() &&
                    (instruction as? OneRegisterInstruction)?.registerA == colorShift.registerB
            },
        )

    if (instructions.anyRegisterADefinition(shiftCountConstantIndex + 1, colorShiftIndex, colorShift.registerB)) {
        fail("$paletteField shift-count register is overwritten before the color shift")
    }
    if (instructions.anyWideRegisterDefinition(colorConstantIndex + 1, colorShiftIndex, colorRegister)) {
        fail("$paletteField color register is overwritten before the color shift")
    }
    if (instructions.anyWideRegisterDefinition(colorShiftIndex + 1, blackStoreIndex, colorRegister)) {
        fail("$paletteField shifted color is overwritten before its sput-wide")
    }

    val composeBlack = methodReference("$THEME_DESCRIPTOR;->composeBlack(J)J")
    // The insertion carries labels, so branches to the original sput execute the conversion as well.
    initializer.insertHook(index = blackStoreIndex, relocateBranchTargets = true) {
        invokeStatic(composeBlack, colorRegister, colorRegister + 1)
        moveResult(colorRegister, "J")
    }
}

private fun List<BuilderInstruction>.anyRegisterADefinition(
    startIndex: Int,
    endIndex: Int,
    register: Int,
): Boolean =
    (startIndex until endIndex).any { index ->
        val instruction = this[index]
        (instruction as? OneRegisterInstruction)?.registerA == register ||
            (instruction as? TwoRegisterInstruction)?.registerA == register
    }

private fun List<BuilderInstruction>.anyWideRegisterDefinition(
    startIndex: Int,
    endIndex: Int,
    register: Int,
): Boolean =
    anyRegisterADefinition(startIndex, endIndex, register) ||
        anyRegisterADefinition(startIndex, endIndex, register + 1)

private fun Instruction.fieldReference(): FieldReference? =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.isNewInstanceOf(descriptor: String): Boolean =
    opcode == Opcode.NEW_INSTANCE &&
        ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type == descriptor

private fun Instruction.matchesLiteral(value: Long): Boolean =
    when (this) {
        is NarrowLiteralInstruction -> narrowLiteral.toLong() == value
        is WideLiteralInstruction -> wideLiteral == value
        else -> false
    }

private fun MutableMethod.containsLiteral(value: Long): Boolean =
    implementation?.instructions?.any { instruction -> instruction.matchesLiteral(value) } == true

private fun MutableMethod.containsInvoke(matches: (MethodReference) -> Boolean): Boolean =
    implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let(matches) == true
    } == true

private fun MutableMethod.contextParameterRegister(): Int {
    val methodImplementation = implementation ?: fail("$this has no implementation")
    val parameters = parameterTypes.map { type -> type.toString() }
    if (parameters != listOf(CONTEXT_DESCRIPTOR)) {
        fail("$this does not have exactly one Context parameter: $parameters")
    }
    val register = methodImplementation.registerCount - 1
    if (register < 0) fail("$this has no register for its Context parameter")
    return register
}

private fun MutableMethod.instructionsOrThrow(label: String): List<BuilderInstruction> =
    implementation?.instructions?.toList() ?: fail("$label has no implementation")

private fun fail(message: String): Nothing = throw PatchException(message)

private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { index -> item(index) as? Element }
