// "Remove suppression" "true"

@Suppress("<caret>RedundantUnitReturnType")
fun foo() {
}

// K1_TOOL: org.jetbrains.kotlin.idea.inspections.RedundantUnitReturnTypeInspection
// K2_TOOL: org.jetbrains.kotlin.idea.k2.codeinsight.inspections.declarations.RedundantUnitReturnTypeInspection
// TOOL: com.intellij.codeInspection.RedundantSuppressInspection