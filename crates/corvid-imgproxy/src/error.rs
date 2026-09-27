use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};

#[derive(Debug)]
pub enum ProxyError {
    Unsigned,
    BadTarget,
    NotAnImage,
    TooLarge,
    Upstream,
}

impl IntoResponse for ProxyError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            Self::Unsigned => (StatusCode::FORBIDDEN, "not a minted url"),
            Self::BadTarget => (StatusCode::BAD_REQUEST, "not a fetchable url"),
            Self::NotAnImage => (
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                "upstream is not an image",
            ),
            Self::TooLarge => (StatusCode::PAYLOAD_TOO_LARGE, "upstream image is too large"),
            Self::Upstream => (StatusCode::BAD_GATEWAY, "upstream fetch failed"),
        };
        (status, message).into_response()
    }
}
