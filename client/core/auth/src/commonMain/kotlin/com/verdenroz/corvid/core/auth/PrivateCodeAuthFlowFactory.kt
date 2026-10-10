package com.verdenroz.corvid.core.auth

import org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory

/**
 * A [CodeAuthFlowFactory] whose browser holds no session from the daily sign-in. Pocket-ID keeps
 * one account per browser, and ending its session revokes the tokens it issued, so the admin
 * step-up cannot share the daily browser context.
 */
class PrivateCodeAuthFlowFactory(
    delegate: CodeAuthFlowFactory,
) : CodeAuthFlowFactory by delegate
