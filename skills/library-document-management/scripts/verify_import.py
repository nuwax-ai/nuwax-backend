#!/usr/bin/env python3
"""
4sandbox Import Verification Script

Checks whether an imported page was successfully parsed by reading its contentSize.

Usage:
    python verify_import.py <slugId>            # by slug (default)
    python verify_import.py <pageId> --by-id    # by numeric page id

Exit codes:
    0 - Parsed successfully (contentSize > 0)
    1 - Parsed failed (contentSize == 0) / usage error
    3 - Network / connection error
    4 - API error (non-200 response or business failure)
"""

import argparse
import os
import sys

PLATFORM_BASE_URL = os.environ.get("PLATFORM_BASE_URL")
SANDBOX_ACCESS_KEY = os.environ.get("SANDBOX_ACCESS_KEY")

try:
    import requests
except ImportError:
    print("Error: 'requests' library is required. Install it with: pip install requests")
    sys.exit(1)


def verify_import(identifier: str, by_id: bool, base_url: str, access_key: str):
    """GET the page metadata and return the response."""
    if by_id:
        path = "pages/{0}".format(identifier)
    else:
        path = "pages/by-slug/{0}".format(identifier)
    url = base_url.rstrip("/") + "/api/v1/4sandbox/repo/" + path
    headers = {"Authorization": "Bearer " + access_key}
    return requests.get(url, headers=headers, timeout=30)


def main():
    parser = argparse.ArgumentParser(
        description="Verify an imported page was parsed (contentSize > 0).",
    )
    parser.add_argument("id", metavar="ID", help="slugId (default) or pageId (with --by-id)")
    parser.add_argument(
        "--by-id",
        action="store_true",
        help="Treat ID as numeric pageId instead of slugId",
    )
    parser.add_argument(
        "--base-url",
        dest="base_url",
        default=PLATFORM_BASE_URL,
        help="Platform base URL (default: $PLATFORM_BASE_URL)",
    )
    parser.add_argument(
        "--access-key",
        dest="access_key",
        default=SANDBOX_ACCESS_KEY,
        help="Bearer access key (default: $SANDBOX_ACCESS_KEY)",
    )

    args = parser.parse_args()

    if not args.base_url or not args.access_key:
        print("Error: PLATFORM_BASE_URL and SANDBOX_ACCESS_KEY must be set "
              "(or passed via --base-url / --access-key)")
        sys.exit(1)

    try:
        resp = verify_import(args.id, args.by_id, args.base_url, args.access_key)
    except requests.ConnectionError as e:
        print("Error: Cannot connect to {0}".format(args.base_url))
        print("Details: {0}".format(e))
        sys.exit(3)
    except requests.Timeout:
        print("Error: Request timed out after 30 seconds")
        sys.exit(3)
    except requests.RequestException as e:
        print("Error: Network request failed: {0}".format(e))
        sys.exit(3)

    try:
        data = resp.json()
    except ValueError:
        print("Error: Invalid JSON response (HTTP {0})".format(resp.status_code))
        print("Response body: {0}".format(resp.text[:500]))
        sys.exit(4)

    if resp.status_code != 200:
        print("Error: HTTP {0}".format(resp.status_code))
        print("API response: {0}".format(data))
        sys.exit(4)

    code = data.get("code", "")
    if code != "0000":
        print("Error: API returned failure (code={0}, message={1})".format(
            code, data.get("message", "Unknown error")))
        sys.exit(4)

    page = data.get("data", {})
    size = page.get("contentSize", 0)
    print("contentSize: {0}".format(size))

    if isinstance(size, int) and size > 0:
        print("✅ 解析成功，可预览")
        sys.exit(0)
    else:
        print("❌ 解析失败（contentSize == 0），需用 content(base64) 重新导入")
        sys.exit(1)


if __name__ == "__main__":
    main()
