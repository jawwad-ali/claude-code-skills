# FastMCP Server Composition Reference

## Overview

FastMCP supports composing multiple servers together, enabling modular architecture and code reuse. There are three main composition methods: mount, import, and proxy.

## Mounting Servers

Mounting creates a live link to another server. Changes to the mounted server are reflected in the main server.

### Basic Mount

```python
from fastmcp import FastMCP

# Create servers
main = FastMCP("Main")
auth = FastMCP("Auth")
data = FastMCP("Data")

# Add tools to sub-servers
@auth.tool
def login(username: str, password: str) -> dict:
    """Authenticate user."""
    return {"token": "abc123"}

@auth.tool
def logout(token: str) -> bool:
    """Logout user."""
    return True

@data.tool
def get_users() -> list:
    """Get all users."""
    return []

# Mount with prefixes
main.mount(auth, prefix="auth")
main.mount(data, prefix="data")

# Tools available as: auth_login, auth_logout, data_get_users
```

### Mount Without Prefix

```python
# Mount without prefix (tools keep original names)
main.mount(auth)  # Tools: login, logout
```

### Nested Mounting

```python
level1 = FastMCP("Level1")
level2 = FastMCP("Level2")
level3 = FastMCP("Level3")

@level3.tool
def deep_tool() -> str:
    return "Hello from deep!"

level2.mount(level3, prefix="l3")
level1.mount(level2, prefix="l2")
main.mount(level1, prefix="l1")

# Tool available as: l1_l2_l3_deep_tool
```

## Importing Servers

Importing creates a static copy of components. Changes to the original server after import are not reflected.

### Basic Import

```python
from fastmcp import FastMCP

main = FastMCP("Main")
utils = FastMCP("Utils")

@utils.tool
def format_date(date: str) -> str:
    """Format a date string."""
    return date

# Static import
main.import_server(utils, prefix="utils")

# Tool copied as: utils_format_date
```

### When to Use Import vs Mount

| Feature | Mount | Import |
|---------|-------|--------|
| Live updates | Yes | No |
| Performance | Slightly slower | Faster |
| Memory | Shared | Copied |
| Use case | Dynamic composition | Static bundling |

```python
# Use mount for dynamic servers
dynamic_server = FastMCP("Dynamic")
main.mount(dynamic_server, prefix="dynamic")

# Use import for utility libraries
utils = FastMCP("Utils")
main.import_server(utils, prefix="utils")
```

## Proxy Servers

Proxies mirror remote or local servers, allowing you to expose external MCP servers through your server.

### Proxy Remote Server

```python
from fastmcp import FastMCP, Client

# Create proxy for remote server
remote_client = Client("https://api.example.com/mcp")
remote_proxy = FastMCP.as_proxy(remote_client)

# Mount the proxy
main = FastMCP("Main")
main.mount(remote_proxy, prefix="remote")
```

### Proxy Local Script

```python
# Proxy a local Python MCP server
local_client = Client("./other_server.py")
local_proxy = FastMCP.as_proxy(local_client)

main.mount(local_proxy, prefix="local")
```

### Multi-Server Configuration

```python
from fastmcp import FastMCP

# Configuration for multiple servers
config = {
    "mcpServers": {
        "weather": {
            "url": "https://weather-api.example.com/mcp",
            "transport": "http",
        },
        "calendar": {
            "url": "https://calendar-api.example.com/mcp",
            "transport": "http",
        },
        "database": {
            "command": "python",
            "args": ["./db_server.py"],
            "transport": "stdio",
        },
    }
}

# Create server from config
mcp = FastMCP.from_config(config)

# All servers mounted with their config names as prefixes
# Tools: weather_*, calendar_*, database_*
```

## Resource URI Prefixing

When mounting servers, resource URIs are automatically prefixed:

```python
main = FastMCP("Main")
data = FastMCP("Data")

@data.resource("resource://users")
def get_users() -> list:
    return []

main.mount(data, prefix="data")

# Resource available at: resource://data/users
```

## Composition Patterns

### Microservices Pattern

```python
from fastmcp import FastMCP

# Create domain-specific servers
users_server = FastMCP("Users")
orders_server = FastMCP("Orders")
products_server = FastMCP("Products")

# Add domain tools
@users_server.tool
def create_user(name: str, email: str) -> dict:
    return {"id": "u1", "name": name, "email": email}

@orders_server.tool
def create_order(user_id: str, items: list) -> dict:
    return {"id": "o1", "user_id": user_id}

@products_server.tool
def search_products(query: str) -> list:
    return []

# Compose into main server
main = FastMCP("E-Commerce API")
main.mount(users_server, prefix="users")
main.mount(orders_server, prefix="orders")
main.mount(products_server, prefix="products")

if __name__ == "__main__":
    main.run()
```

### Plugin Architecture

```python
from fastmcp import FastMCP
from pathlib import Path
import importlib

main = FastMCP("PluginHost")

def load_plugins(plugin_dir: Path):
    """Dynamically load plugin servers."""
    for plugin_path in plugin_dir.glob("*/server.py"):
        plugin_name = plugin_path.parent.name

        # Import plugin module
        spec = importlib.util.spec_from_file_location(
            plugin_name, plugin_path
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        # Mount plugin server
        if hasattr(module, "mcp"):
            main.mount(module.mcp, prefix=plugin_name)
            print(f"Loaded plugin: {plugin_name}")

# Load all plugins
load_plugins(Path("./plugins"))
```

### Gateway Pattern

```python
from fastmcp import FastMCP, Client

# Create gateway server
gateway = FastMCP("API Gateway")

# Add authentication middleware
@gateway.tool
async def authenticated_call(
    service: str,
    tool: str,
    args: dict,
    token: str,
) -> dict:
    """Call a service tool with authentication."""
    # Validate token
    if not validate_token(token):
        raise ValueError("Invalid token")

    # Route to appropriate service
    services = {
        "users": "https://users.example.com/mcp",
        "orders": "https://orders.example.com/mcp",
    }

    if service not in services:
        raise ValueError(f"Unknown service: {service}")

    async with Client(services[service]) as client:
        return await client.call_tool(tool, args)
```

### Feature Flags

```python
from fastmcp import FastMCP
import os

main = FastMCP("FeatureServer")

# Conditionally mount features
if os.getenv("ENABLE_BETA_FEATURES"):
    beta = FastMCP("Beta")

    @beta.tool
    def beta_feature() -> str:
        return "Beta feature enabled!"

    main.mount(beta, prefix="beta")

if os.getenv("ENABLE_ADMIN"):
    admin = FastMCP("Admin")

    @admin.tool
    def admin_action() -> str:
        return "Admin action executed"

    main.mount(admin, prefix="admin")
```

## Best Practices

1. **Use Meaningful Prefixes**: Choose clear, descriptive prefixes
2. **Organize by Domain**: Group related tools in dedicated servers
3. **Mount for Dynamic**: Use mount when server content changes
4. **Import for Static**: Use import for stable utility libraries
5. **Proxy for External**: Use proxy for remote/external servers
6. **Avoid Deep Nesting**: Keep mounting hierarchy shallow
7. **Document Structure**: Document your server composition
8. **Test Composition**: Test that composed tools work correctly
9. **Handle Errors**: Proxied servers may be unavailable
10. **Consider Performance**: Many mounts add overhead

## Troubleshooting

### Name Conflicts

```python
# Problem: Two servers have tools with same name
auth1 = FastMCP("Auth1")
auth2 = FastMCP("Auth2")

@auth1.tool
def login(): pass

@auth2.tool
def login(): pass  # Conflict!

# Solution: Use different prefixes
main.mount(auth1, prefix="auth1")
main.mount(auth2, prefix="auth2")
# Tools: auth1_login, auth2_login
```

### Proxy Connection Issues

```python
from fastmcp import FastMCP, Client
import httpx

# Add timeout and retry for proxies
client = Client(
    "https://api.example.com/mcp",
    httpx_client=httpx.AsyncClient(
        timeout=30.0,
        limits=httpx.Limits(max_connections=10),
    ),
)

proxy = FastMCP.as_proxy(client)
```

### Resource URI Conflicts

```python
# Problem: Same URI in different servers
data1 = FastMCP("Data1")
data2 = FastMCP("Data2")

@data1.resource("resource://config")
def config1(): pass

@data2.resource("resource://config")
def config2(): pass

# Solution: Prefixes automatically resolve this
main.mount(data1, prefix="d1")  # resource://d1/config
main.mount(data2, prefix="d2")  # resource://d2/config
```
