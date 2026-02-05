"""
Full-Featured FastMCP Server Example

Demonstrates a complete MCP server with:
- Typed tools with Pydantic models
- Resource templates with parameters
- Context-aware operations
- Async operations
- Error handling
"""

from typing import Annotated, Literal
from datetime import datetime
import asyncio

from fastmcp import FastMCP, Context
from fastmcp.prompts.prompt import PromptMessage, TextContent
from pydantic import BaseModel, Field, EmailStr


# ============================================================
# Pydantic Models
# ============================================================

class Address(BaseModel):
    """Mailing address."""
    street: str
    city: str
    state: str = Field(max_length=2)
    zip_code: str = Field(pattern=r"^\d{5}$")


class Customer(BaseModel):
    """Customer information."""
    name: str = Field(min_length=1, max_length=100)
    email: EmailStr
    address: Address
    tier: Literal["basic", "premium", "enterprise"] = "basic"


class OrderItem(BaseModel):
    """Item in an order."""
    product_id: str
    name: str
    quantity: int = Field(gt=0)
    price: float = Field(gt=0)


class Order(BaseModel):
    """Customer order."""
    customer_id: str
    items: list[OrderItem]
    notes: str | None = None


# ============================================================
# Create Server
# ============================================================

mcp = FastMCP(
    name="E-Commerce API",
    version="2.0.0",
)


# ============================================================
# Customer Tools
# ============================================================

# Simulated database
customers_db: dict[str, Customer] = {}
orders_db: dict[str, Order] = {}


@mcp.tool
def create_customer(customer: Customer) -> dict:
    """Create a new customer.

    Args:
        customer: Customer details including name, email, and address.

    Returns:
        Created customer with ID.
    """
    import uuid
    customer_id = f"cust_{uuid.uuid4().hex[:8]}"
    customers_db[customer_id] = customer

    return {
        "id": customer_id,
        "customer": customer.model_dump(),
        "created_at": datetime.now().isoformat(),
    }


@mcp.tool
def get_customer(customer_id: str) -> dict:
    """Get a customer by ID.

    Args:
        customer_id: The customer's unique identifier.

    Returns:
        Customer details.
    """
    if customer_id not in customers_db:
        raise ValueError(f"Customer not found: {customer_id}")

    return {
        "id": customer_id,
        "customer": customers_db[customer_id].model_dump(),
    }


@mcp.tool
def search_customers(
    tier: Literal["basic", "premium", "enterprise"] | None = None,
    city: str | None = None,
    limit: Annotated[int, Field(description="Max results", ge=1, le=100)] = 10,
) -> list[dict]:
    """Search customers with filters.

    Args:
        tier: Filter by customer tier.
        city: Filter by city.
        limit: Maximum number of results.

    Returns:
        List of matching customers.
    """
    results = []

    for cust_id, customer in customers_db.items():
        if tier and customer.tier != tier:
            continue
        if city and customer.address.city.lower() != city.lower():
            continue

        results.append({"id": cust_id, "customer": customer.model_dump()})

        if len(results) >= limit:
            break

    return results


# ============================================================
# Order Tools
# ============================================================

@mcp.tool
def place_order(order: Order) -> dict:
    """Place a new order.

    Args:
        order: Order details including customer ID and items.

    Returns:
        Order confirmation with total.
    """
    import uuid

    if order.customer_id not in customers_db:
        raise ValueError(f"Customer not found: {order.customer_id}")

    order_id = f"ord_{uuid.uuid4().hex[:8]}"
    orders_db[order_id] = order

    total = sum(item.quantity * item.price for item in order.items)
    tax = total * 0.08
    grand_total = total + tax

    return {
        "order_id": order_id,
        "customer_id": order.customer_id,
        "items_count": len(order.items),
        "subtotal": round(total, 2),
        "tax": round(tax, 2),
        "total": round(grand_total, 2),
        "estimated_delivery": "3-5 business days",
    }


@mcp.tool
def get_order(order_id: str) -> dict:
    """Get an order by ID.

    Args:
        order_id: The order's unique identifier.

    Returns:
        Order details.
    """
    if order_id not in orders_db:
        raise ValueError(f"Order not found: {order_id}")

    order = orders_db[order_id]
    total = sum(item.quantity * item.price for item in order.items)

    return {
        "order_id": order_id,
        "order": order.model_dump(),
        "total": round(total * 1.08, 2),
    }


# ============================================================
# Context-Aware Tool
# ============================================================

@mcp.tool
async def process_bulk_orders(
    orders: list[Order],
    ctx: Context,
) -> dict:
    """Process multiple orders with progress reporting.

    Args:
        orders: List of orders to process.

    Returns:
        Processing summary.
    """
    await ctx.info(f"Starting bulk processing of {len(orders)} orders")

    successful = []
    failed = []

    for i, order in enumerate(orders):
        # Report progress
        await ctx.report_progress(progress=i, total=len(orders))

        try:
            result = place_order(order)
            successful.append(result["order_id"])
            await ctx.debug(f"Processed order {i + 1}: {result['order_id']}")
        except Exception as e:
            failed.append({"index": i, "error": str(e)})
            await ctx.warning(f"Failed to process order {i + 1}: {e}")

        # Simulate processing time
        await asyncio.sleep(0.1)

    await ctx.report_progress(progress=len(orders), total=len(orders))
    await ctx.info(f"Completed: {len(successful)} successful, {len(failed)} failed")

    return {
        "total": len(orders),
        "successful": len(successful),
        "failed": len(failed),
        "order_ids": successful,
        "errors": failed,
    }


# ============================================================
# Resources
# ============================================================

@mcp.resource("data://customers/count")
def customer_count() -> dict:
    """Get total customer count."""
    return {"count": len(customers_db)}


@mcp.resource("data://orders/count")
def order_count() -> dict:
    """Get total order count."""
    return {"count": len(orders_db)}


@mcp.resource("customer://{customer_id}")
def get_customer_resource(customer_id: str) -> dict:
    """Get customer details as resource.

    Args:
        customer_id: Customer identifier.
    """
    if customer_id not in customers_db:
        return {"error": "Customer not found"}
    return customers_db[customer_id].model_dump()


@mcp.resource("customer://{customer_id}/orders")
def get_customer_orders(customer_id: str) -> list:
    """Get orders for a customer.

    Args:
        customer_id: Customer identifier.
    """
    return [
        {"order_id": oid, **order.model_dump()}
        for oid, order in orders_db.items()
        if order.customer_id == customer_id
    ]


@mcp.resource("orders://{status}{?limit}")
def get_orders_by_status(
    status: str,
    limit: int = 10,
) -> list:
    """Get orders by status.

    Args:
        status: Order status filter.
        limit: Maximum results.
    """
    # In a real app, orders would have status
    return list(orders_db.keys())[:limit]


# ============================================================
# Prompts
# ============================================================

@mcp.prompt
def customer_welcome(customer_name: str) -> str:
    """Generate welcome message for customer.

    Args:
        customer_name: Name of the customer.
    """
    return f"""Generate a warm welcome message for our new customer, {customer_name}.

Include:
- Personalized greeting
- Brief overview of our services
- Contact information for support
- A special offer for new customers"""


@mcp.prompt
def order_confirmation(order_id: str) -> PromptMessage:
    """Generate order confirmation message.

    Args:
        order_id: The order identifier.
    """
    return PromptMessage(
        role="user",
        content=TextContent(
            type="text",
            text=f"""Generate a professional order confirmation email for order {order_id}.

Include:
- Order number and date
- Itemized list of products
- Total amount
- Estimated delivery date
- Return policy summary
- Customer support contact"""
        )
    )


@mcp.prompt(
    name="analyze_customer_segment",
    description="Request analysis of customer segments",
    tags={"analytics", "customers"},
)
def analyze_segment(
    tier: Literal["basic", "premium", "enterprise"],
    metric: str = "spending",
) -> str:
    """Generate prompt for customer segment analysis.

    Args:
        tier: Customer tier to analyze.
        metric: Metric to focus on.
    """
    return f"""Analyze our {tier} customer segment focusing on {metric}.

Provide insights on:
1. Current trends
2. Growth opportunities
3. Risk factors
4. Recommended actions"""


# ============================================================
# Run Server
# ============================================================

if __name__ == "__main__":
    print("=" * 50)
    print("E-Commerce API Server")
    print("=" * 50)
    print()
    print("Customer Tools:")
    print("  - create_customer, get_customer, search_customers")
    print()
    print("Order Tools:")
    print("  - place_order, get_order, process_bulk_orders")
    print()
    print("Resources:")
    print("  - data://customers/count")
    print("  - data://orders/count")
    print("  - customer://{customer_id}")
    print("  - customer://{customer_id}/orders")
    print("  - orders://{status}?limit=N")
    print()
    print("Prompts:")
    print("  - customer_welcome, order_confirmation")
    print("  - analyze_customer_segment")
    print()

    mcp.run()
