// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes.imprt.factories

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.idea.base.analysis.api.utils.KtSymbolFromIndexProvider
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.imprt.CallableImportCandidatesProvider
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.imprt.ImportCandidate
import org.jetbrains.kotlin.idea.k2.codeinsight.fixes.imprt.ImportPositionTypeAndReceiver
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.util.OperatorNameConventions

internal object DelegateMethodImportQuickFixFactory : AbstractImportQuickFixFactory() {
    override fun detectPositionContext(diagnostic: KaDiagnosticWithPsi<*>): ImportPositionTypeAndReceiver<*, *>? {
        return when (diagnostic) {
            is KaFirDiagnostic.DelegateSpecialFunctionNoneApplicable,
            is KaFirDiagnostic.DelegateSpecialFunctionMissing -> {
                val delegateExpression = diagnostic.psi
                val propertyDelegate = delegateExpression.parent as? KtPropertyDelegate ?: return null

                ImportPositionTypeAndReceiver.Delegate(delegateExpression, propertyDelegate.expression)
            }

            else -> null
        }
    }

    override fun provideUnresolvedNames(diagnostic: KaDiagnosticWithPsi<*>, importPositionTypeAndReceiver: ImportPositionTypeAndReceiver<*, *>): Set<Name> {
        val expectedFunctionSignature = when (diagnostic) {
            is KaFirDiagnostic.DelegateSpecialFunctionNoneApplicable -> diagnostic.expectedFunctionSignature
            is KaFirDiagnostic.DelegateSpecialFunctionMissing -> diagnostic.expectedFunctionSignature
            else -> null
        }

        if (expectedFunctionSignature == null) return emptySet()

        val expectedDelegateFunctionName: Name? = listOf(
            OperatorNameConventions.GET_VALUE,
            OperatorNameConventions.SET_VALUE,
        ).singleOrNull { expectedFunctionSignature.startsWith(it.asString() + "(") }

        return setOfNotNull(
            expectedDelegateFunctionName,
            OperatorNameConventions.PROVIDE_DELEGATE,
        )
    }

    override fun KaSession.provideImportCandidates(
        unresolvedName: Name,
        importPositionTypeAndReceiver: ImportPositionTypeAndReceiver<*, *>,
        indexProvider: KtSymbolFromIndexProvider
    ): List<ImportCandidate> {
        if (importPositionTypeAndReceiver !is ImportPositionTypeAndReceiver.Delegate) return emptyList()
        val providers = CallableImportCandidatesProvider(importPositionTypeAndReceiver)
        return providers.collectCandidates(unresolvedName, indexProvider)
    }
}