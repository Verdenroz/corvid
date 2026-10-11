//! Passkey sign-in: the WebAuthn verifier and the OIDC issuer Stalwart's directory trusts.

mod cose;
#[cfg(feature = "soft-authenticator")]
pub mod soft;
mod token;
mod webauthn;

pub use token::{Audience, Claims, Invalid, Issuer};
pub use webauthn::{
    AssertionResponse, Policy, Refusal, Registration, RegistrationResponse, RelyingParty,
};
