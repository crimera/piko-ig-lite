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
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.w3c.dom.Element
import org.w3c.dom.NodeList

private const val STYLES_FILE = "res/values/styles.xml"

/**
 * The dark Prism styles that paint the feed, header, tab bar and status bar. Each sets the primary background and
 * the status bar to `igds_prism_black` (#0C1014), a blue-grey near black. Styles are matched by name because
 * resource ids change between releases.
 */
private val PRISM_DARK_STYLES = listOf("IgdsPrismSemanticColorsExperimentDark", "IgdsPrismGrayOverridesDark")

/** Items in those styles that paint the background. Tab bars and the action bar follow the primary background. */
private val BACKGROUND_ITEMS = listOf("igds_color_primary_background", "status_bar_background")

private const val BLUE_GREY_BLACK = "@color/igds_prism_black"
private const val PURE_BLACK = "@color/bds_black"

/**
 * Palette colour that views and code use directly, outside the styles above. Those references do not follow a style
 * item, so the colour gets a night value of black instead. Colour literals in code are not affected.
 */
private const val PALETTE_COLOR = "igds_prism_black"
private const val PALETTE_NIGHT_VALUE = "#000000"
private const val NIGHT_COLORS = "values-night/colors.xml"
private const val BUNDLED_THEME_DIR = "instagram/theme"
private const val BASE_PRISM_COLORS_DESCRIPTOR = "Lcom/instagram/compose/core/theme/BasePrismColors;"
private const val BASE_PRISM_COLORS_V2_DESCRIPTOR = "Lcom/instagram/compose/core/theme/BasePrismColorsV2;"
private const val BASE_PRISM_BLACK_LITERAL = 0x00000000ff0c1014L
private const val COMPOSE_COLOR_SHIFT = 0x20L

/**
 * Makes the dark theme background pure black (#000000) for OLED screens.
 *
 * Instagram paints the feed, header, tab bar and status bar from the `igds_color_primary_background` attribute, so
 * changing that item (and the status bar item) in the Prism dark styles changes all of them. Code and views that read
 * `igds_prism_black` directly are covered by a night value for that colour. Light mode keeps its colour.
 *
 * The patch fails closed. Each style must exist exactly once, and each item must still read `@color/igds_prism_black`.
 * A release that renames or repaints these fails the patch rather than being skipped.
 */
/** Resource edits behind the AMOLED theme: the dark Prism styles and the dark-mode palette colour. */
@Suppress("unused")
internal val amoledThemeResources =
    resourcePatch(
        description = "Makes the dark theme background pure black (#000000) instead of blue-grey.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            document(STYLES_FILE).use { document ->
                val styles = document.getElementsByTagName("style").elements()
                PRISM_DARK_STYLES.forEach { styleName ->
                    val style =
                        requireExactlyOne(
                            "style $styleName in $STYLES_FILE",
                            styles.filter { element -> element.getAttribute("name") == styleName },
                            describe = { element -> element.getAttribute("name") },
                        )
                    BACKGROUND_ITEMS.forEach { itemName ->
                        val item =
                            requireExactlyOne(
                                "$itemName item in $styleName",
                                style.childElements("item").filter { element -> element.getAttribute("name") == itemName },
                                describe = { element -> element.textContent },
                            )
                        val value = item.textContent.trim()
                        if (value != BLUE_GREY_BLACK) {
                            throw PatchException("$itemName in $styleName is $value, expected $BLUE_GREY_BLACK")
                        }
                        item.textContent = PURE_BLACK
                    }
                }
            }
            blackenNightPalette()
        }
    }

/**
 * The AMOLED theme as the user selects it. Its resource edits are a dependency. The toggle controls the Compose
 * palette hook; the style and night-palette edits remain in the APK whenever this patch is selected.
 */
@Suppress("unused")
val amoledThemePatch =
    bytecodePatch(
        name = "AMOLED theme",
        description = "Makes the dark theme background pure black (#000000) instead of blue-grey, for OLED screens.",
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
        )

        execute {
            blackenComposePalette(BASE_PRISM_COLORS_DESCRIPTOR, "BLACK")
            blackenComposePalette(BASE_PRISM_COLORS_V2_DESCRIPTOR, "GRAY_1600")
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

private fun fail(message: String): Nothing = throw PatchException(message)

private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { index -> item(index) as? Element }

private fun Element.childElements(tagName: String): List<Element> =
    childNodes.elements().filter { element -> element.tagName == tagName }

/**
 * Gives [PALETTE_COLOR] a night value. Edits the decoded `values-night/colors.xml` when Instagram already ships one,
 * which keeps its other night colours. Otherwise copies the bundled file, which only holds this colour.
 */
private fun ResourcePatchContext.blackenNightPalette() {
    if (get("res").resolve(NIGHT_COLORS).exists()) {
        document("res/$NIGHT_COLORS").use { document -> setNightPaletteColor(document.documentElement) }
    } else {
        copyResources(BUNDLED_THEME_DIR, ResourceGroup("values-night", "colors.xml"))
    }
}

private fun setNightPaletteColor(root: Element) {
    val existing =
        root.getElementsByTagName("color").elements().filter { element ->
            element.getAttribute("name") == PALETTE_COLOR
        }
    val color =
        if (existing.isEmpty()) {
            root.ownerDocument.createElement("color").also { element ->
                element.setAttribute("name", PALETTE_COLOR)
                root.appendChild(element)
            }
        } else {
            requireExactlyOne("night value of $PALETTE_COLOR", existing)
        }
    color.textContent = PALETTE_NIGHT_VALUE
}
