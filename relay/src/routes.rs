// SPDX-License-Identifier: AGPL-3.0-or-later
//! HTTP API routes. Milestone 1 exposes only the health endpoint; the mailbox and
//! enrollment endpoints land in milestone 3 (see `docs/PROTOCOL.md` §8).

use axum::{Json, Router, routing::get};
use serde::Serialize;

/// Health response. Deliberately minimal: no version string beyond the protocol version,
/// no host information.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// Always `"ok"` when the process is serving requests.
    pub status: &'static str,
    /// Supported application protocol version.
    pub protocol_version: u32,
}

async fn health() -> Json<Health> {
    Json(Health {
        status: "ok",
        protocol_version: bastion_proto::PROTOCOL_VERSION,
    })
}

/// Builds the relay API router.
pub fn router() -> Router {
    Router::new().route("/v1/health", get(health))
}

#[cfg(test)]
mod tests {
    use super::router;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_reports_ok() {
        let response = router()
            .oneshot(Request::get("/v1/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = response.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(&body[..], br#"{"status":"ok","protocol_version":1}"#);
    }

    #[tokio::test]
    async fn unknown_routes_are_404() {
        let response = router()
            .oneshot(Request::get("/admin").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}
