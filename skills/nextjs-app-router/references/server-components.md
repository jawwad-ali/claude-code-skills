# Server Components Patterns

## Understanding Server vs Client Components

### Server Components (Default)

All components in the `app/` directory are Server Components by default.

**Capabilities:**
- Direct database access
- File system access
- Access to environment variables (server-side)
- Async/await at component level
- Zero client-side JavaScript bundle impact

**Limitations:**
- No React hooks (useState, useEffect, etc.)
- No browser APIs (window, document, localStorage)
- No event handlers (onClick, onChange, etc.)
- No custom hooks that use state or effects

```tsx
// Server Component - fetches data directly
import { db } from '@/lib/db'

export default async function UserProfile({ userId }: { userId: string }) {
  const user = await db.user.findUnique({ where: { id: userId } })

  if (!user) return <div>User not found</div>

  return (
    <div>
      <h1>{user.name}</h1>
      <p>{user.email}</p>
    </div>
  )
}
```

### Client Components

Add `'use client'` directive to opt into client-side rendering.

```tsx
'use client'

import { useState, useEffect } from 'react'

export default function Counter() {
  const [count, setCount] = useState(0)

  useEffect(() => {
    console.log('Component mounted')
  }, [])

  return (
    <button onClick={() => setCount(c => c + 1)}>
      Count: {count}
    </button>
  )
}
```

## Composition Patterns

### Pattern 1: Server Component with Client Islands

Push Client Components down to leaves of the component tree.

```tsx
// app/page.tsx (Server Component)
import { getProducts } from '@/lib/data'
import ProductList from '@/components/product-list'
import AddToCartButton from '@/components/add-to-cart-button'

export default async function Page() {
  const products = await getProducts()

  return (
    <div>
      <h1>Products</h1>
      {products.map(product => (
        <div key={product.id}>
          <ProductList product={product} />
          {/* Client Component for interactivity */}
          <AddToCartButton productId={product.id} />
        </div>
      ))}
    </div>
  )
}
```

```tsx
// components/add-to-cart-button.tsx
'use client'

import { useCart } from '@/hooks/use-cart'

export default function AddToCartButton({ productId }: { productId: string }) {
  const { addItem } = useCart()

  return (
    <button onClick={() => addItem(productId)}>
      Add to Cart
    </button>
  )
}
```

### Pattern 2: Passing Server Components as Children

Pass Server Components through the `children` prop to avoid making them Client Components.

```tsx
// components/client-wrapper.tsx
'use client'

import { useState } from 'react'

export default function ClientWrapper({
  children,
}: {
  children: React.ReactNode
}) {
  const [isOpen, setIsOpen] = useState(false)

  return (
    <div>
      <button onClick={() => setIsOpen(!isOpen)}>Toggle</button>
      {isOpen && children}
    </div>
  )
}
```

```tsx
// app/page.tsx (Server Component)
import ClientWrapper from '@/components/client-wrapper'
import ServerContent from '@/components/server-content'

export default function Page() {
  return (
    <ClientWrapper>
      {/* ServerContent remains a Server Component */}
      <ServerContent />
    </ClientWrapper>
  )
}
```

### Pattern 3: Serializable Props Only

When passing props from Server to Client Components, ensure they are serializable.

```tsx
// ✅ Good - serializable data
<ClientComponent
  data={{
    id: '1',
    name: 'Item',
    count: 5,
    tags: ['a', 'b'],
  }}
/>

// ❌ Bad - functions are not serializable
<ClientComponent
  onClick={() => console.log('clicked')}
/>

// ❌ Bad - Date objects need conversion
<ClientComponent
  date={new Date()} // Convert to ISO string instead
/>

// ✅ Good - convert Date to string
<ClientComponent
  date={new Date().toISOString()}
/>
```

## Context in Server Components

### Creating a Provider Pattern

```tsx
// providers/theme-provider.tsx
'use client'

import { createContext, useContext, useState } from 'react'

const ThemeContext = createContext<{
  theme: 'light' | 'dark'
  toggleTheme: () => void
} | null>(null)

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setTheme] = useState<'light' | 'dark'>('light')

  return (
    <ThemeContext.Provider
      value={{
        theme,
        toggleTheme: () => setTheme(t => (t === 'light' ? 'dark' : 'light')),
      }}
    >
      {children}
    </ThemeContext.Provider>
  )
}

export function useTheme() {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('useTheme must be used within ThemeProvider')
  return context
}
```

```tsx
// app/layout.tsx
import { ThemeProvider } from '@/providers/theme-provider'

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html>
      <body>
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  )
}
```

## Streaming and Suspense

### Streaming with Loading UI

```tsx
// app/dashboard/page.tsx
import { Suspense } from 'react'
import Analytics from '@/components/analytics'
import RevenueChart from '@/components/revenue-chart'

export default function Dashboard() {
  return (
    <div>
      <h1>Dashboard</h1>

      <Suspense fallback={<div>Loading analytics...</div>}>
        <Analytics />
      </Suspense>

      <Suspense fallback={<div>Loading chart...</div>}>
        <RevenueChart />
      </Suspense>
    </div>
  )
}
```

### Parallel Data Fetching

```tsx
// Fetch data in parallel for better performance
export default async function Page() {
  // Start both fetches at the same time
  const userPromise = getUser()
  const postsPromise = getPosts()

  // Wait for both to complete
  const [user, posts] = await Promise.all([userPromise, postsPromise])

  return (
    <div>
      <UserProfile user={user} />
      <PostList posts={posts} />
    </div>
  )
}
```

### Sequential vs Parallel with Suspense

```tsx
// Option 1: Sequential (waterfall) - slower
export default async function Page() {
  const user = await getUser() // Wait
  const posts = await getPosts(user.id) // Then wait

  return <div>...</div>
}

// Option 2: Parallel with Suspense - faster
export default function Page() {
  return (
    <div>
      <Suspense fallback={<UserSkeleton />}>
        <User />
      </Suspense>
      <Suspense fallback={<PostsSkeleton />}>
        <Posts />
      </Suspense>
    </div>
  )
}

async function User() {
  const user = await getUser()
  return <UserProfile user={user} />
}

async function Posts() {
  const posts = await getPosts()
  return <PostList posts={posts} />
}
```

## Third-Party Libraries

### Using Client-Only Libraries

```tsx
// components/map.tsx
'use client'

import { useEffect, useRef } from 'react'
import mapboxgl from 'mapbox-gl'

export default function Map() {
  const mapContainer = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!mapContainer.current) return

    const map = new mapboxgl.Map({
      container: mapContainer.current,
      style: 'mapbox://styles/mapbox/streets-v11',
    })

    return () => map.remove()
  }, [])

  return <div ref={mapContainer} className="h-96 w-full" />
}
```

### Dynamic Import for Client Components

```tsx
// app/page.tsx
import dynamic from 'next/dynamic'

// Only load on client, with loading state
const Map = dynamic(() => import('@/components/map'), {
  loading: () => <div>Loading map...</div>,
  ssr: false,
})

export default function Page() {
  return (
    <div>
      <h1>Location</h1>
      <Map />
    </div>
  )
}
```
