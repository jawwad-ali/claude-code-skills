---
name: nextjs-app-router
description: This skill MUST be loaded when ANY file in an app/ directory is being read, reviewed, edited, or created - including page.tsx, layout.tsx, loading.tsx, error.tsx, not-found.tsx, route.ts, template.tsx, default.tsx, or any .tsx/.ts file using App Router patterns. Also use when the user asks to "read a page.tsx", "review a Next.js file", "edit a layout", "create a Next.js page", "add a route", "create a layout", "implement server components", "add client components", "create API routes", "implement server actions", "add loading states", "handle errors in Next.js", "create dynamic routes", "implement parallel routes", "add intercepting routes", or mentions Next.js App Router, React Server Components (RSC), 'use client', 'use server', next/navigation, or app directory patterns.
---

# Next.js App Router Development Guide

This skill provides comprehensive guidance for building Next.js applications using the App Router (app directory).

## Core Concepts

### File-Based Routing

The App Router uses a file-system based router where:
- **Folders** define routes
- **Files** define UI for route segments

Special files:
| File | Purpose |
|------|---------|
| `page.tsx` | Unique UI for a route (makes route publicly accessible) |
| `layout.tsx` | Shared UI for a segment and its children |
| `loading.tsx` | Loading UI (uses React Suspense) |
| `error.tsx` | Error UI (uses React Error Boundary) |
| `not-found.tsx` | Not found UI |
| `route.ts` | API endpoint (Route Handler) |
| `template.tsx` | Re-rendered layout |
| `default.tsx` | Fallback for parallel routes |

### Server Components vs Client Components

**Server Components (Default)**
- All components in `app/` are Server Components by default
- Can directly access backend resources (database, file system)
- Can use `async/await` at component level
- Cannot use hooks, browser APIs, or event handlers

**Client Components**
- Add `'use client'` directive at the top of the file
- Required for interactivity, hooks, browser APIs
- Should be pushed down the component tree

```tsx
// Server Component (default)
export default async function Page() {
  const data = await fetchData() // Direct data fetching
  return <div>{data.title}</div>
}
```

```tsx
'use client'

// Client Component
import { useState } from 'react'

export default function Counter() {
  const [count, setCount] = useState(0)
  return <button onClick={() => setCount(count + 1)}>{count}</button>
}
```

### Data Fetching Patterns

**In Server Components (Recommended)**
```tsx
async function getData() {
  const res = await fetch('https://api.example.com/data', {
    cache: 'force-cache', // default - caches indefinitely
    // cache: 'no-store', // never cache
    // next: { revalidate: 3600 }, // revalidate every hour
  })
  return res.json()
}

export default async function Page() {
  const data = await getData()
  return <main>{data.content}</main>
}
```

**Server Actions (for mutations)**
```tsx
// app/actions.ts
'use server'

export async function createItem(formData: FormData) {
  const name = formData.get('name')
  await db.items.create({ data: { name } })
  revalidatePath('/items')
}
```

### Routing Hooks (Client Components Only)

Import from `next/navigation`:
```tsx
'use client'

import { useRouter, usePathname, useSearchParams, useParams } from 'next/navigation'

export function Navigation() {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const params = useParams()

  // router.push('/dashboard')
  // router.replace('/login')
  // router.refresh()
  // router.back()
  // router.forward()
}
```

### Dynamic Routes

```
app/
├── blog/
│   └── [slug]/           # Dynamic segment
│       └── page.tsx
├── shop/
│   └── [...slug]/        # Catch-all segment
│       └── page.tsx
└── docs/
    └── [[...slug]]/      # Optional catch-all
        └── page.tsx
```

```tsx
// app/blog/[slug]/page.tsx
export default async function Page({
  params
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  return <article>Post: {slug}</article>
}

// Generate static paths
export async function generateStaticParams() {
  const posts = await getPosts()
  return posts.map((post) => ({ slug: post.slug }))
}
```

### Layouts and Templates

**Root Layout (Required)**
```tsx
// app/layout.tsx
export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  )
}
```

**Nested Layouts**
```tsx
// app/dashboard/layout.tsx
export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <section>
      <nav>Dashboard Nav</nav>
      {children}
    </section>
  )
}
```

### Loading and Error States

```tsx
// app/dashboard/loading.tsx
export default function Loading() {
  return <div>Loading...</div>
}

// app/dashboard/error.tsx
'use client'

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  return (
    <div>
      <h2>Something went wrong!</h2>
      <button onClick={() => reset()}>Try again</button>
    </div>
  )
}
```

### Route Handlers (API Routes)

```tsx
// app/api/items/route.ts
import { NextRequest, NextResponse } from 'next/server'

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams
  const query = searchParams.get('query')

  const items = await getItems(query)
  return NextResponse.json(items)
}

export async function POST(request: NextRequest) {
  const body = await request.json()
  const item = await createItem(body)
  return NextResponse.json(item, { status: 201 })
}
```

### Metadata

```tsx
// Static metadata
export const metadata = {
  title: 'My Page',
  description: 'Page description',
}

// Dynamic metadata
export async function generateMetadata({ params }) {
  const post = await getPost(params.slug)
  return {
    title: post.title,
    description: post.excerpt,
  }
}
```

## Production Patterns (MANDATORY)

These are NOT optional — apply them proactively on EVERY page you build.

### 1. Loading Skeletons (loading.tsx)

Every route that fetches data MUST have a `loading.tsx` with an animated skeleton matching the page layout:

```tsx
// app/dashboard/loading.tsx
export default function Loading() {
  return (
    <div className="animate-pulse">
      <div className="h-8 w-48 bg-slate-200 rounded mb-4" />
      <div className="grid grid-cols-4 gap-4">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="h-24 bg-slate-100 rounded-xl" />
        ))}
      </div>
    </div>
  );
}
```

**Never use a bare `<div>Loading...</div>`. Always use skeleton UI matching the actual page structure.**

### 2. Suspense Boundaries (for partial loading)

When a page has a fast shell (header, nav, tabs) and slow content (external API calls), split them:

```tsx
import { Suspense } from "react";

export default async function Page({ searchParams }) {
  const client = await getClientFromDB(id); // FAST: DB only

  return (
    <>
      <Header title={client.name} />       {/* Renders INSTANTLY */}
      <TabNavigation activeTab={tab} />     {/* Renders INSTANTLY */}
      <Suspense key={tab} fallback={<TabSkeleton />}>
        <TabContent tab={tab} />            {/* Async: slow API calls */}
      </Suspense>
    </>
  );
}

async function TabContent({ tab }) {
  const data = await fetchExternalAPI(); // SLOW: 3-5 seconds
  return <DisplayComponent data={data} />;
}
```

**Rule: Before writing any page, ask "What's fast (DB) vs slow (external API)?" Put slow parts in Suspense.**

The `key={tab}` prop is critical — it tells React to show the skeleton when the tab changes.

### 3. Cache External API Calls (unstable_cache)

NEVER call external APIs (Wix, HubSpot, OpenAI, Stripe, etc.) raw from page components. Always wrap with `unstable_cache`:

```tsx
import { unstable_cache } from "next/cache";

// Cached wrapper — 60s TTL, tag-based invalidation
export function getCachedData(clientId: string) {
  return unstable_cache(
    () => fetchFromExternalAPI(clientId),  // the actual slow call
    [`api-data-${clientId}`],              // cache key
    {
      revalidate: 60,                       // refresh every 60 seconds
      tags: [`client-${clientId}`],         // for manual invalidation
    }
  )();
}
```

**In page components, use `getCachedData()` not `fetchFromExternalAPI()`.**

### 4. Invalidate Cache After Mutations (revalidateTag)

When a server action modifies data, invalidate related cache tags:

```tsx
"use server";
import { revalidatePath, revalidateTag } from "next/cache";

export async function updateData(clientId: string) {
  await saveToDatabase();
  
  // Invalidate cached API data for this client
  revalidateTag(`client-${clientId}`);
  
  // Also revalidate the page path
  revalidatePath(`/clients/${clientId}`);
}
```

**Every server action that changes data should call both `revalidateTag` and `revalidatePath`.**

### 5. Error Boundaries (error.tsx)

Every route should have error handling:

```tsx
// app/dashboard/error.tsx
'use client'

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  return (
    <div className="text-center py-16">
      <h2 className="font-bold mb-2">Something went wrong</h2>
      <p className="text-sm text-gray-500 mb-4">{error.message}</p>
      <button onClick={() => reset()} className="px-4 py-2 bg-primary text-white rounded-xl">
        Try again
      </button>
    </div>
  );
}
```

## Decision Checklist (Before Writing Any Page)

Before creating or editing any `page.tsx`, answer these:

- [ ] **Does this route have a `loading.tsx`?** If it fetches data, it needs one with a skeleton.
- [ ] **Are there fast parts and slow parts?** Fast shell in the page, slow content in `<Suspense>`.
- [ ] **Does it call external APIs?** Wrap with `unstable_cache` + revalidation tags.
- [ ] **Do any actions mutate data?** Add `revalidateTag` after mutations.
- [ ] **Is there an `error.tsx`?** Every route that fetches data needs error handling.

## Best Practices

1. **Keep Client Components small** - Push `'use client'` as far down the tree as possible
2. **Colocate data fetching** - Fetch data in Server Components close to where it's used
3. **Use Server Actions** for mutations instead of API routes when possible
4. **Cache external API calls** - Always use `unstable_cache` for third-party APIs
5. **Handle loading states** - Skeleton UI in `loading.tsx` + Suspense for partial loading
6. **Handle errors** - `error.tsx` in every route with data fetching
7. **Invalidate on mutation** - `revalidateTag` + `revalidatePath` in every server action
8. **Use route groups** `(folder)` for organization without affecting URL
9. **Use parallel routes** `@folder` for complex layouts
10. **Split fast shell + slow content** - Header/nav render instantly, data content in Suspense

## Common Patterns

See the `references/` directory for detailed patterns:
- `routing.md` - Advanced routing patterns
- `server-components.md` - Server component patterns
- `data-fetching.md` - Data fetching strategies

See the `examples/` directory for working code:
- `page-layout.tsx` - Page and layout examples
- `server-action.ts` - Server action examples
- `route-handler.ts` - API route examples
