#!/usr/bin/env python3
"""
Data Table for Page API Client (sandbox)

This client calls platform data-table REST endpoints under:
  Table Definition: $PLATFORM_BASE_URL/api/v1/4sandbox/compose/db/table/...
  Table SQL API:    $PLATFORM_BASE_URL/api/v1/4sandbox/table/sql/...

Two groups of operations:
  1. Table Definition (表结构配置): add, updateTableName, updateTableDefinition,
     delete, list, detailById, existTableData, copyTableDefinition
  2. Table SQL API (数据表SQL操作API): tableNewSql, tableUpdateSql

Auth:
  Authorization: Bearer $SANDBOX_ACCESS_KEY
  X-Sandbox-Id: $SANDBOX_ID (optional, sent when provided)
"""

import os
import sys
import json
import argparse
from typing import Optional, List, Dict, Any

import requests


TABLE_PREFIX = "/api/v1/4sandbox/compose/db/table"
SQL_PREFIX = "/api/v1/4sandbox/table/sql"


def _require_env(name: str) -> str:
    val = os.environ.get(name)
    if not val:
        raise ValueError(f"{name} environment variable is required")
    return val


def _build_headers(access_key: str, sandbox_id: Optional[str]) -> Dict[str, str]:
    headers = {
        "Authorization": f"Bearer {access_key}",
    }
    if sandbox_id:
        headers["X-Sandbox-Id"] = sandbox_id
    return headers


def _base_url(base_url: Optional[str]) -> str:
    if base_url is not None:
        return base_url.rstrip("/")
    v = os.environ.get("PLATFORM_BASE_URL")
    if not v:
        raise ValueError("PLATFORM_BASE_URL environment variable is required")
    return v.rstrip("/")


def _access_key(access_key: Optional[str]) -> str:
    return access_key or os.environ.get("SANDBOX_ACCESS_KEY")


def _sandbox_id(sandbox_id: Optional[str]) -> Optional[str]:
    return sandbox_id or os.environ.get("SANDBOX_ID")


def _request_json(
    method: str,
    url: str,
    headers: Dict[str, str],
    *,
    json_body: Optional[Dict[str, Any]] = None,
    params: Optional[Dict[str, Any]] = None,
    timeout_s: int = 60,
) -> Any:
    resp = requests.request(method, url, headers=headers, json=json_body, params=params, timeout=timeout_s)
    resp.raise_for_status()
    return resp.json() if resp.content else None


# ---------------------------------------------------------------------------
# Table Definition APIs
# ---------------------------------------------------------------------------

def _space_id() -> int:
    """Read the project space id from the DEV_SPACE_ID environment variable.

    The table must be created/queried inside the project's space, NOT the user's
    personal space. The caller guarantees the source is DEV_SPACE_ID.
    """
    raw = os.environ.get("DEV_SPACE_ID")
    if not raw or not raw.strip():
        raise ValueError(
            "DEV_SPACE_ID environment variable is required so tables are created "
            "in the project space instead of the personal space"
        )
    return int(raw.strip())


def add_table(
    *,
    name: str,
    description: Optional[str] = None,
    icon: Optional[str] = None,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Create a new table definition in the project space (DEV_SPACE_ID)."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/add"
    headers = _build_headers(access_key, sandbox_id)

    # spaceId MUST come from DEV_SPACE_ID so the table lands in the project space,
    # not the caller's personal space (which is the backend's fallback when null).
    payload: Dict[str, Any] = {"tableName": name, "spaceId": _space_id()}
    if description is not None:
        payload["tableDescription"] = description
    if icon is not None:
        payload["icon"] = icon

    return _request_json("POST", url, headers, json_body=payload)


def update_table_name(
    *,
    id: int,
    name: Optional[str] = None,
    description: Optional[str] = None,
    icon: Optional[str] = None,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Update table name / description / icon."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/updateTableName"
    headers = _build_headers(access_key, sandbox_id)

    payload: Dict[str, Any] = {"id": id}
    if name is not None:
        payload["tableName"] = name
    if description is not None:
        payload["tableDescription"] = description
    if icon is not None:
        payload["icon"] = icon

    return _request_json("POST", url, headers, json_body=payload)


def update_table_definition(
    *,
    id: int,
    field_list: List[Dict[str, Any]],
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Update table field definitions (columns)."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/updateTableDefinition"
    headers = _build_headers(access_key, sandbox_id)

    payload = {"id": id, "fieldList": field_list}
    return _request_json("POST", url, headers, json_body=payload)


def delete_table(
    *,
    id: int,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Delete a table definition by id."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/delete/{id}"
    headers = _build_headers(access_key, sandbox_id)
    return _request_json("POST", url, headers)


def list_tables(
    *,
    table_name: Optional[str] = None,
    table_description: Optional[str] = None,
    page_no: int = 1,
    page_size: int = 20,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """List table definitions (paginated) in the project space (DEV_SPACE_ID)."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/list"
    headers = _build_headers(access_key, sandbox_id)

    # spaceId MUST come from DEV_SPACE_ID so we list the project's tables,
    # not the caller's personal space tables (which is the backend's fallback when null).
    query_filter: Dict[str, Any] = {"spaceId": _space_id()}
    if table_name is not None:
        query_filter["tableName"] = table_name
    if table_description is not None:
        query_filter["tableDescription"] = table_description

    payload: Dict[str, Any] = {
        "pageNo": page_no,
        "pageSize": page_size,
        "queryFilter": query_filter,
    }
    return _request_json("POST", url, headers, json_body=payload)


def get_table(
    *,
    table_id: int,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Get table definition detail by id."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/detailById"
    headers = _build_headers(access_key, sandbox_id)
    return _request_json("GET", url, headers, params={"id": table_id})


def exist_table_data(
    *,
    table_id: int,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Check if a table has any business data rows."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/existTableData"
    headers = _build_headers(access_key, sandbox_id)
    return _request_json("GET", url, headers, params={"tableId": table_id})


def copy_table(
    *,
    table_id: int,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Copy a table structure definition to a new table."""
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{TABLE_PREFIX}/copyTableDefinition"
    headers = _build_headers(access_key, sandbox_id)
    return _request_json("POST", url, headers, params={"tableId": table_id})


# ---------------------------------------------------------------------------
# Table SQL API
# ---------------------------------------------------------------------------

def table_sql_new(
    *,
    project_id: str,
    api_name: str,
    description: str,
    sql: str,
    table_id: Optional[int] = None,
    group_name: Optional[str] = None,
    args: Optional[List[Dict[str, Any]]] = None,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Create a new SQL-based table operation API for page components.

    SQL placeholders: use {{var}} for ALL variables, including LIKE fuzzy queries.
      For LIKE, the caller must wrap the value with % itself (e.g. '%keyword%') — the
      platform does NOT auto-wrap %. Do NOT use ${{var}} (it drops quotes and causes
      "Unknown column" errors in this sandbox).
    The endpoint token is carried in the returned payload's path `.../page/w/{token}`;
    record it in .project.md immediately.
    Args format: list of dicts with keys like name, description, require, dataType, etc.
    """
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{SQL_PREFIX}/new"
    headers = _build_headers(access_key, sandbox_id)

    payload: Dict[str, Any] = {
        "projectId": project_id,
        "apiName": api_name,
        "description": description,
        "sql": sql,
    }
    if table_id is not None:
        payload["tableId"] = table_id
    if group_name is not None:
        payload["groupName"] = group_name
    if args is not None:
        payload["args"] = args

    return _request_json("POST", url, headers, json_body=payload)


def table_sql_update(
    *,
    project_id: str,
    api_id: int,
    api_name: str,
    description: str,
    sql: str,
    args: Optional[List[Dict[str, Any]]] = None,
    base_url: Optional[str] = None,
    access_key: Optional[str] = None,
    sandbox_id: Optional[str] = None,
) -> Any:
    """Update an existing SQL-based table operation API.

    SQL placeholders: same as table_sql_new — use {{var}} (+ caller-wrapped %) for LIKE;
      do NOT use ${{var}}.
    ⚠️ The endpoint token is OFTEN REGENERATED after update. The returned payload's path
      `.../page/w/{token}` carries the NEW token; you MUST refresh .project.md and the
      front-end lib's token map, or page calls will silently fail with the stale token.
    Args format: list of dicts with keys like name, description, require, dataType, etc.
    """
    access_key = _access_key(access_key) or _require_env("SANDBOX_ACCESS_KEY")
    sandbox_id = _sandbox_id(sandbox_id)
    url = f"{_base_url(base_url)}{SQL_PREFIX}/update"
    headers = _build_headers(access_key, sandbox_id)

    payload: Dict[str, Any] = {
        "projectId": project_id,
        "apiId": api_id,
        "apiName": api_name,
        "description": description,
        "sql": sql,
    }
    if args is not None:
        payload["args"] = args

    return _request_json("POST", url, headers, json_body=payload)


def _parse_json_arg(s: Optional[str]) -> Optional[Any]:
    if s is None:
        return None
    s = s.strip()
    if not s:
        return None
    return json.loads(s)


def main():
    parser = argparse.ArgumentParser(description="Data Table for Page API Client (sandbox)")
    parser.add_argument("--base-url", help="PLATFORM_BASE_URL override")
    parser.add_argument("--access-key", help="SANDBOX_ACCESS_KEY override")
    parser.add_argument("--sandbox-id", help="SANDBOX_ID override")
    parser.add_argument("--raw", action="store_true", help="print raw JSON")

    sub = parser.add_subparsers(dest="command", required=True)

    # ---- Table Definition ----

    p_add = sub.add_parser("add-table", help="Create a new table definition")
    p_add.add_argument("--name", required=True, help="Table name")
    p_add.add_argument("--description", help="Table description")
    p_add.add_argument("--icon", help="Table icon")

    p_utn = sub.add_parser("update-table-name", help="Update table name/description/icon")
    p_utn.add_argument("--id", type=int, required=True, help="Table ID")
    p_utn.add_argument("--name", help="New table name")
    p_utn.add_argument("--description", help="New table description")
    p_utn.add_argument("--icon", help="New table icon")

    p_utf = sub.add_parser("update-table-definition", help="Update table field definitions")
    p_utf.add_argument("--id", type=int, required=True, help="Table ID")
    p_utf.add_argument("--field-list", required=True, help="JSON array of field definitions")

    p_del = sub.add_parser("delete-table", help="Delete a table definition")
    p_del.add_argument("--id", type=int, required=True, help="Table ID")

    p_list = sub.add_parser("list-tables", help="List table definitions (paginated)")
    p_list.add_argument("--table-name", help="Filter by table name")
    p_list.add_argument("--table-description", help="Filter by table description")
    p_list.add_argument("--page-no", type=int, default=1, help="Page number (default: 1)")
    p_list.add_argument("--page-size", type=int, default=20, help="Page size (default: 20)")

    p_get = sub.add_parser("get-table", help="Get table definition detail")
    p_get.add_argument("--table-id", type=int, required=True, help="Table ID")

    p_exist = sub.add_parser("exist-table-data", help="Check if table has business data")
    p_exist.add_argument("--table-id", type=int, required=True, help="Table ID")

    p_copy = sub.add_parser("copy-table", help="Copy a table structure")
    p_copy.add_argument("--table-id", type=int, required=True, help="Source table ID")

    # ---- Table SQL API ----

    p_tsn = sub.add_parser("table-sql-new", help="Create a new SQL table operation API")
    p_tsn.add_argument("--project-id", required=True, help="Project ID")
    p_tsn.add_argument("--table-id", type=int, help="Table ID")
    p_tsn.add_argument("--api-name", required=True, help="API name")
    p_tsn.add_argument("--description", required=True, help="API description")
    p_tsn.add_argument("--sql", required=True, help="SQL statement (use {{var}} for placeholders)")
    p_tsn.add_argument("--group-name", help="Group name for the API")
    p_tsn.add_argument("--args", help="JSON array of parameter definitions")

    p_tsu = sub.add_parser("table-sql-update", help="Update an existing SQL table operation API")
    p_tsu.add_argument("--project-id", required=True, help="Project ID")
    p_tsu.add_argument("--api-id", type=int, required=True, help="API ID to update")
    p_tsu.add_argument("--api-name", required=True, help="API name")
    p_tsu.add_argument("--description", required=True, help="API description")
    p_tsu.add_argument("--sql", required=True, help="SQL statement (use {{var}} for placeholders)")
    p_tsu.add_argument("--args", help="JSON array of parameter definitions")

    args = parser.parse_args()
    common = dict(
        base_url=args.base_url,
        access_key=args.access_key,
        sandbox_id=args.sandbox_id,
    )

    result = None

    if args.command == "add-table":
        result = add_table(
            name=args.name,
            description=args.description,
            icon=args.icon,
            **common,
        )
    elif args.command == "update-table-name":
        result = update_table_name(
            id=args.id,
            name=args.name,
            description=args.description,
            icon=args.icon,
            **common,
        )
    elif args.command == "update-table-definition":
        field_list = _parse_json_arg(args.field_list)
        result = update_table_definition(
            id=args.id,
            field_list=field_list,
            **common,
        )
    elif args.command == "delete-table":
        result = delete_table(id=args.id, **common)
    elif args.command == "list-tables":
        result = list_tables(
            table_name=args.table_name,
            table_description=args.table_description,
            page_no=args.page_no,
            page_size=args.page_size,
            **common,
        )
    elif args.command == "get-table":
        result = get_table(table_id=args.table_id, **common)
    elif args.command == "exist-table-data":
        result = exist_table_data(table_id=args.table_id, **common)
    elif args.command == "copy-table":
        result = copy_table(table_id=args.table_id, **common)
    elif args.command == "table-sql-new":
        parsed_args = _parse_json_arg(args.args)
        result = table_sql_new(
            project_id=args.project_id,
            api_name=args.api_name,
            description=args.description,
            sql=args.sql,
            table_id=args.table_id,
            group_name=args.group_name,
            args=parsed_args,
            **common,
        )
    elif args.command == "table-sql-update":
        parsed_args = _parse_json_arg(args.args)
        result = table_sql_update(
            project_id=args.project_id,
            api_id=args.api_id,
            api_name=args.api_name,
            description=args.description,
            sql=args.sql,
            args=parsed_args,
            **common,
        )
    else:
        raise RuntimeError(f"unknown command: {args.command}")

    if result is not None:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
