package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.UnitUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_control_character
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_invalid_key
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_missing_secret
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_not_a_number
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_not_an_option
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_not_in_scope
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_out_of_range
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_owned
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_secret_in_project
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_secret_literal
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_too_long
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_unknown_document
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_unknown_variant
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_unparsed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_wrong_format
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_problem_wrong_type
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_unit_ms
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_unit_s
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_unit_tokens
import org.jetbrains.compose.resources.StringResource

/** Message of every configuration problem; the panel tests check that none is missing. */
internal val ConfigProblemTexts: Map<ConfigProblemReasonUi, StringResource> = mapOf(
    ConfigProblemReasonUi.UnknownDocument to Res.string.control_problem_unknown_document,
    ConfigProblemReasonUi.WrongFormat to Res.string.control_problem_wrong_format,
    ConfigProblemReasonUi.NotInScope to Res.string.control_problem_not_in_scope,
    ConfigProblemReasonUi.TooLong to Res.string.control_problem_too_long,
    ConfigProblemReasonUi.WrongType to Res.string.control_problem_wrong_type,
    ConfigProblemReasonUi.NotAnOption to Res.string.control_problem_not_an_option,
    ConfigProblemReasonUi.OutOfRange to Res.string.control_problem_out_of_range,
    ConfigProblemReasonUi.InvalidKey to Res.string.control_problem_invalid_key,
    ConfigProblemReasonUi.ControlCharacter to Res.string.control_problem_control_character,
    ConfigProblemReasonUi.UnknownVariant to Res.string.control_problem_unknown_variant,
    ConfigProblemReasonUi.Owned to Res.string.control_problem_owned,
    ConfigProblemReasonUi.SecretInProject to Res.string.control_problem_secret_in_project,
    ConfigProblemReasonUi.MissingSecret to Res.string.control_problem_missing_secret,
    ConfigProblemReasonUi.SecretLiteral to Res.string.control_problem_secret_literal,
    ConfigProblemReasonUi.NotANumber to Res.string.control_problem_not_a_number,
    ConfigProblemReasonUi.Unparsed to Res.string.control_problem_unparsed,
)

/** Label of a number unit. */
internal fun UnitUi.label(): StringResource = when (this) {
    UnitUi.Tokens -> Res.string.control_unit_tokens
    UnitUi.Milliseconds -> Res.string.control_unit_ms
    UnitUi.Seconds -> Res.string.control_unit_s
}
