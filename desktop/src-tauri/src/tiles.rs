// SPDX-License-Identifier: GPL-3.0-or-later
//! Map tile proxy behind the `tiles:` URI scheme: the webview never contacts a remote
//! origin (strict CSP); tiles are fetched by the backend with an identifying User-Agent, as
//! required by the OpenStreetMap tile policy, and cached on disk.

use std::path::Path;
use std::time::Duration;

use tauri::http::{Response, StatusCode, header};

const MAX_ZOOM: u32 = 19;
const CACHE_TTL: Duration = Duration::from_secs(30 * 24 * 3600);
const USER_AGENT: &str = concat!(
    "Bastion-Desktop/",
    env!("CARGO_PKG_VERSION"),
    " (+https://github.com/0x80070006/bastion)"
);

/// HTTP client used for tiles (public PKI, no pinning).
///
/// # Errors
/// TLS initialization errors.
pub fn http_client() -> reqwest::Result<reqwest::Client> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(Duration::from_secs(15))
        .https_only(true)
        .build()
}

/// Parses `/{z}/{x}/{y}.png` and checks the tile exists at that zoom.
#[must_use]
pub fn parse(path: &str) -> Option<(u32, u32, u32)> {
    let mut parts = path.trim_start_matches('/').split('/');
    let z: u32 = parts.next()?.parse().ok()?;
    let x: u32 = parts.next()?.parse().ok()?;
    let y: u32 = parts.next()?.strip_suffix(".png")?.parse().ok()?;
    if parts.next().is_some() || z > MAX_ZOOM {
        return None;
    }
    let size = 1u32 << z;
    (x < size && y < size).then_some((z, x, y))
}

fn respond(status: StatusCode, body: Vec<u8>) -> Response<Vec<u8>> {
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "max-age=86400")
        .body(body)
        .unwrap_or_else(|_| Response::new(Vec::new()))
}

/// Serves a tile from the cache, or downloads it when `online` is allowed.
pub async fn serve(
    cache: &Path,
    http: &reqwest::Client,
    path: &str,
    online: bool,
) -> Response<Vec<u8>> {
    let Some((z, x, y)) = parse(path) else {
        return respond(StatusCode::BAD_REQUEST, Vec::new());
    };
    let file = cache
        .join(z.to_string())
        .join(x.to_string())
        .join(format!("{y}.png"));
    let fresh = std::fs::metadata(&file)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|age| age < CACHE_TTL);
    if (fresh || !online)
        && let Ok(bytes) = std::fs::read(&file)
    {
        return respond(StatusCode::OK, bytes);
    }
    if !online {
        return respond(StatusCode::NOT_FOUND, Vec::new());
    }
    let url = format!("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    let fetched = async {
        let response = http.get(url).send().await.ok()?;
        if !response.status().is_success() {
            return None;
        }
        let bytes = response.bytes().await.ok()?;
        // PNG signature check: never cache or serve anything else.
        bytes
            .starts_with(b"\x89PNG\r\n\x1a\n")
            .then(|| bytes.to_vec())
    }
    .await;
    match fetched {
        Some(bytes) => {
            if let Some(dir) = file.parent()
                && std::fs::create_dir_all(dir).is_ok()
            {
                let _ = std::fs::write(&file, &bytes);
            }
            respond(StatusCode::OK, bytes)
        }
        None => match std::fs::read(&file) {
            Ok(stale) => respond(StatusCode::OK, stale),
            Err(_) => respond(StatusCode::BAD_GATEWAY, Vec::new()),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::parse;

    #[test]
    fn tile_paths_are_validated() {
        assert_eq!(parse("/0/0/0.png"), Some((0, 0, 0)));
        assert_eq!(parse("/3/7/7.png"), Some((3, 7, 7)));
        for bad in [
            "/3/8/0.png",
            "/20/0/0.png",
            "/1/0/0.jpg",
            "/../0/0.png",
            "/1/0/0.png/x",
            "/a/b/c.png",
        ] {
            assert_eq!(parse(bad), None, "{bad}");
        }
    }
}
