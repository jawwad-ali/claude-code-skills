# FastMCP Resources Reference

## Overview

Resources expose data that LLMs can read. They provide a way to share files, configurations, API responses, and other data with AI models.

## Resource Types

### Dynamic Resources (Decorator)

Functions decorated with `@mcp.resource` that compute content on request:

```python
from fastmcp import FastMCP

mcp = FastMCP("DataServer")

@mcp.resource("resource://greeting")
def get_greeting() -> str:
    """Returns a greeting message."""
    return "Hello from FastMCP!"

@mcp.resource("data://config")
def get_config() -> dict:
    """Returns configuration as JSON."""
    return {"version": "1.0", "debug": False}
```

### Static Resources (Pre-defined)

Resources added directly without a function:

```python
from pathlib import Path
from fastmcp import FastMCP
from fastmcp.resources import FileResource, TextResource, DirectoryResource

mcp = FastMCP("DataServer")

# File resource
mcp.add_resource(FileResource(
    uri="file://readme",
    path=Path("./README.md"),
    name="README",
    description="Project documentation",
    mime_type="text/markdown",
))

# Text resource
mcp.add_resource(TextResource(
    uri="resource://notice",
    name="System Notice",
    text="Scheduled maintenance: Sunday 2AM",
))

# Directory listing
mcp.add_resource(DirectoryResource(
    uri="resource://data",
    path=Path("./data"),
    name="Data Files",
    description="Available data files",
    recursive=True,
))
```

## URI Templates

### Single Parameter

```python
@mcp.resource("weather://{city}/current")
def get_weather(city: str) -> dict:
    """Get weather for a city.

    Args:
        city: City name.
    """
    return {
        "city": city.capitalize(),
        "temperature": 72,
        "condition": "Sunny",
    }
```

### Multiple Parameters

```python
@mcp.resource("repos://{owner}/{repo}/info")
def get_repo(owner: str, repo: str) -> dict:
    """Get repository information.

    Args:
        owner: Repository owner.
        repo: Repository name.
    """
    return {
        "full_name": f"{owner}/{repo}",
        "stars": 100,
    }
```

### Wildcard Paths

```python
@mcp.resource("files://{path*}")
def read_file(path: str) -> str:
    """Read any file by path.

    Args:
        path: File path (can contain slashes).
    """
    from pathlib import Path
    return Path(path).read_text()
```

### Query Parameters

Query parameters must have default values:

```python
# Basic query parameter
@mcp.resource("data://{id}{?format}")
def get_data(id: str, format: str = "json") -> str:
    """Get data in specified format.

    Args:
        id: Data identifier.
        format: Output format (json or xml).
    """
    if format == "xml":
        return f"<data id='{id}' />"
    return f'{{"id": "{id}"}}'

# Multiple query parameters
@mcp.resource("api://{endpoint}{?limit,offset,sort}")
def api_query(
    endpoint: str,
    limit: int = 10,
    offset: int = 0,
    sort: str = "created",
) -> dict:
    """Query API endpoint with pagination.

    Args:
        endpoint: API endpoint.
        limit: Results per page.
        offset: Starting offset.
        sort: Sort field.
    """
    return {
        "endpoint": endpoint,
        "pagination": {"limit": limit, "offset": offset},
        "sort": sort,
    }

# Combined path and query
@mcp.resource("logs://{service}/{date}{?level,limit}")
def get_logs(
    service: str,
    date: str,
    level: str = "all",
    limit: int = 100,
) -> list:
    """Get logs for a service.

    Args:
        service: Service name.
        date: Log date (YYYY-MM-DD).
        level: Filter by log level.
        limit: Maximum entries.
    """
    return []
```

## Resource Metadata

```python
@mcp.resource(
    uri="data://metrics",
    name="SystemMetrics",
    description="Current system performance metrics",
    mime_type="application/json",
    tags={"monitoring", "metrics"},
    meta={"refresh_rate": 60},
)
def get_metrics() -> dict:
    """Get system metrics."""
    import psutil
    return {
        "cpu_percent": psutil.cpu_percent(),
        "memory_percent": psutil.virtual_memory().percent,
    }
```

## Resource Annotations

Annotations provide hints about resource behavior:

```python
@mcp.resource(
    uri="data://cache",
    annotations={
        "readOnlyHint": True,      # Content is read-only
        "idempotentHint": True,    # Safe to cache
        "audience": ["user"],      # For users, not assistants
    }
)
def get_cached_data() -> dict:
    return {"cached": True}
```

## Return Types

### String Content

```python
@mcp.resource("text://hello")
def text_resource() -> str:
    """Returns plain text."""
    return "Hello, World!"
```

### JSON Content

```python
@mcp.resource("json://data")
def json_resource() -> dict:
    """Returns JSON (dict auto-serialized)."""
    return {"key": "value", "items": [1, 2, 3]}

@mcp.resource("json://list")
def list_resource() -> list:
    """Returns JSON array."""
    return [{"id": 1}, {"id": 2}]
```

### Binary Content

```python
@mcp.resource("binary://image")
def binary_resource() -> bytes:
    """Returns binary data."""
    from pathlib import Path
    return Path("./image.png").read_bytes()
```

## Async Resources

```python
import httpx

@mcp.resource("api://{endpoint}")
async def fetch_api(endpoint: str) -> dict:
    """Fetch from external API.

    Args:
        endpoint: API endpoint path.
    """
    async with httpx.AsyncClient() as client:
        response = await client.get(f"https://api.example.com/{endpoint}")
        return response.json()
```

## Resource Patterns

### Configuration Resource

```python
import os
from functools import lru_cache

@mcp.resource("config://app")
@lru_cache
def get_app_config() -> dict:
    """Application configuration."""
    return {
        "env": os.getenv("ENV", "development"),
        "debug": os.getenv("DEBUG", "false").lower() == "true",
        "api_url": os.getenv("API_URL", "http://localhost:8000"),
    }
```

### Database Query Resource

```python
@mcp.resource("db://{table}{?limit,offset}")
async def query_table(
    table: str,
    limit: int = 100,
    offset: int = 0,
) -> list[dict]:
    """Query database table.

    Args:
        table: Table name.
        limit: Max rows.
        offset: Starting row.
    """
    # Validate table name to prevent SQL injection
    allowed_tables = {"users", "products", "orders"}
    if table not in allowed_tables:
        raise ValueError(f"Invalid table: {table}")

    # Query implementation
    return []
```

### Time-Based Resource

```python
from datetime import datetime

@mcp.resource("logs://{service}/{date}")
def get_logs_by_date(service: str, date: str) -> list[dict]:
    """Get logs for a specific date.

    Args:
        service: Service name.
        date: Date in YYYY-MM-DD format.
    """
    # Validate date format
    try:
        datetime.strptime(date, "%Y-%m-%d")
    except ValueError:
        raise ValueError("Date must be YYYY-MM-DD format")

    return []
```

### Aggregated Resource

```python
@mcp.resource("stats://summary")
async def get_summary_stats() -> dict:
    """Aggregated statistics from multiple sources."""
    # Gather from multiple sources
    users = await get_user_count()
    orders = await get_order_stats()
    revenue = await get_revenue()

    return {
        "users": users,
        "orders": orders,
        "revenue": revenue,
        "generated_at": datetime.now().isoformat(),
    }
```

## Best Practices

1. **Clear URIs**: Use descriptive, hierarchical URIs
2. **Document Templates**: Explain each URI parameter in docstrings
3. **Validate Input**: Validate template parameters before use
4. **Cache Appropriately**: Use caching for expensive operations
5. **Handle Errors**: Return meaningful errors for invalid requests
6. **Use Type Hints**: Type annotations help with serialization
7. **Set MIME Types**: Specify mime_type for non-JSON content
8. **Add Annotations**: Use hints for caching and behavior
9. **Async for I/O**: Use async for database/network resources
10. **Security**: Never expose sensitive data or allow path traversal
