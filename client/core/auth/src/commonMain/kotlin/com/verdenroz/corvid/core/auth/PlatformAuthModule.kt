package com.verdenroz.corvid.core.auth

import org.koin.core.module.Module

/** Supplies the platform [org.publicvalue.multiplatform.oidc.flows.CodeAuthFlowFactory]. */
expect val platformAuthModule: Module
