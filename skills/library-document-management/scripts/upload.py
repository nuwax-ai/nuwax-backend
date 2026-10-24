#!/usr/bin/env python3
"""
4sandbox File Upload Script

Uploads a file to the 4sandbox platform File Management API.

Usage:
    python upload.py <file_path> [--target-type TYPE] [--target-id ID] [--metadata JSON]

Exit codes:
    0 - Success
    1 - Usage / argument error
    2 - File not found or not readable
    3 - Network / connection error
    4 - API error (non-200 response or business failure)
"""

import argparse
import json
import mimetypes
import os
import sys
from pathlib import Path

# API credentials — read from built-in environment at runtime
PLATFORM_BASE_URL = os.environ["PLATFORM_BASE_URL"]
SANDBOX_ACCESS_KEY = os.environ["SANDBOX_ACCESS_KEY"]

try:
    import requests
except ImportError:
    print("Error: 'requests' library is required. Install it with: pip install requests")
    sys.exit(1)


def build_url(base_url: str, target_type: str | None, target_id: int, metadata: str | None) -> str:
    """Build the full API URL with query parameters."""
    import urllib.parse

    url = base_url.rstrip("/") + "/api/v1/4sandbox/file/upload"
    params = {}

    if target_type is not None:
        params["targetType"] = target_type
    if target_id != -1:
        params["targetId"] = str(target_id)
    if metadata is not None:
        params["metadata"] = metadata

    if params:
        url += "?" + urllib.parse.urlencode(params)

    return url


def upload_file(
    file_path: str,
    base_url: str,
    access_key: str,
    target_type: str | None = None,
    target_id: int = -1,
    metadata: str | None = None,
) -> dict:
    """
    Upload a file to the 4sandbox API.

    Returns the parsed JSON response.
    Raises requests.RequestException on network errors.
    Raises ValueError on API business errors.
    """
    url = build_url(base_url, target_type, target_id, metadata)
    headers = {"Authorization": f"Bearer {access_key}"}

    # Validate file
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"File not found: {file_path}")
    if not path.is_file():
        raise IsADirectoryError(f"Path is a directory, not a file: {file_path}")
    if not os.access(file_path, os.R_OK):
        raise PermissionError(f"File is not readable: {file_path}")

    file_size = path.stat().st_size
    file_name = path.name
    file_ext = path.suffix.lower()
    mime_type, _ = mimetypes.guess_type(file_path)
    if mime_type is None:
        mime_type = "application/octet-stream"

    print(f"Uploading: {file_name} ({file_size:,} bytes, {mime_type})")

    with open(file_path, "rb") as f:
        files = {"file": (file_name, f, mime_type)}
        response = requests.post(url, headers=headers, files=files, timeout=120)

    return response


def refresh_auth_url(file_url: str, base_url: str, access_key: str) -> str | None:
    """
    Get an authorized access URL for an uploaded file.

    Calls: GET /api/file/ak/refresh?url=<URL-encoded file URL>
    Returns the authorized URL string, or None on failure.
    """
    import urllib.parse

    refresh_url = base_url.rstrip("/") + "/api/v1/4sandbox/file/ak/refresh"
    params = {"url": file_url}
    headers = {"Authorization": f"Bearer {access_key}"}

    try:
        resp = requests.get(refresh_url, headers=headers, params=params, timeout=30)

        # Try JSON response first (standard API response format)
        content_type = resp.headers.get("content-type", "")
        if "json" in content_type or resp.text.strip().startswith("{"):
            data = resp.json()
            if isinstance(data, dict):
                code = data.get("code", "")
                if code == "0000":
                    # Success — extract URL from data field
                    inner = data.get("data")
                    if isinstance(inner, str):
                        return inner
                    if isinstance(inner, dict) and inner.get("url"):
                        return inner["url"]
                else:
                    msg = data.get("message", "Unknown error")
                    print(f"  [warn] Auth refresh failed: [{code}] {msg}")
                    return None
            return str(data)
        else:
            # Plain text response
            resp.raise_for_status()
            return resp.text.strip()

    except requests.RequestException as e:
        print(f"  [warn] Failed to get authorized URL: {e}")
        return None


def handle_response(response) -> dict:
    """Process the API response. Returns parsed data on success, exits on failure."""
    try:
        data = response.json()
    except (ValueError, json.JSONDecodeError):
        print(f"Error: Invalid JSON response (HTTP {response.status_code})")
        print(f"Response body: {response.text[:500]}")
        sys.exit(4)

    if response.status_code != 200:
        print(f"Error: HTTP {response.status_code}")
        print(f"API response: {json.dumps(data, indent=2, ensure_ascii=False)}")
        sys.exit(4)

    code = data.get("code", "")
    success = data.get("success", False)

    if code == "0000" and success:
        return data

    # Business error
    message = data.get("message", "Unknown error")
    print(f"Error: API returned failure (code={code}, message={message})")
    print(f"Full response: {json.dumps(data, indent=2, ensure_ascii=False)}")
    sys.exit(4)


def main():
    parser = argparse.ArgumentParser(
        description="Upload a file to the 4sandbox platform",
    )
    parser.add_argument("file", metavar="FILE", help="Path to the file to upload")
    parser.add_argument(
        "--target-type",
        dest="target_type",
        default=None,
        help="Source object type (e.g., task, project)",
    )
    parser.add_argument(
        "--target-id",
        dest="target_id",
        type=int,
        default=-1,
        help="Source object ID (default: -1)",
    )
    parser.add_argument(
        "--metadata",
        dest="metadata",
        default=None,
        help="File metadata as a JSON string",
    )

    args = parser.parse_args()

    # Validate metadata is valid JSON if provided
    if args.metadata is not None:
        try:
            json.loads(args.metadata)
        except json.JSONDecodeError as e:
            print(f"Error: --metadata must be valid JSON: {e}")
            sys.exit(1)

    # Upload
    try:
        response = upload_file(
            file_path=args.file,
            base_url=PLATFORM_BASE_URL,
            access_key=SANDBOX_ACCESS_KEY,
            target_type=args.target_type,
            target_id=args.target_id,
            metadata=args.metadata,
        )

        data = handle_response(response)

        # Success output
        file_record = data.get("data", {})
        file_url = file_record.get("url", "")
        print(f"Upload successful!")
        print(f"  File ID   : {file_record.get('id')}")
        print(f"  File Name : {file_record.get('fileName')}")
        print(f"  MIME Type : {file_record.get('mimeType', 'N/A')}")
        print(f"  Extension : {file_record.get('fileExtension', 'N/A')}")
        print(f"  Size      : {file_record.get('size', 0):,} bytes")
        print(f"  Key       : {file_record.get('key') or 'N/A'}   # fileKey（用于 /import）")
        print(f"  URL       : {file_url or 'N/A'}")

        # Get authorized access URL
        if file_url:
            authorized_url = refresh_auth_url(file_url, PLATFORM_BASE_URL, SANDBOX_ACCESS_KEY)
            if authorized_url:
                print(f"  Auth URL  : {authorized_url}")

        print()
        print(json.dumps(data, indent=2, ensure_ascii=False))

    except FileNotFoundError as e:
        print(f"Error: {e}")
        sys.exit(2)
    except (IsADirectoryError, PermissionError) as e:
        print(f"Error: {e}")
        sys.exit(2)
    except requests.ConnectionError as e:
        print(f"Error: Cannot connect to {base_url}")
        print(f"Details: {e}")
        sys.exit(3)
    except requests.Timeout:
        print(f"Error: Request timed out after 120 seconds")
        sys.exit(3)
    except requests.RequestException as e:
        print(f"Error: Network request failed: {e}")
        sys.exit(3)


if __name__ == "__main__":
    main()
