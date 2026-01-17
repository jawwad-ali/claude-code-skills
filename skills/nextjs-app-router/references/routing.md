# Advanced Routing Patterns

## Route Groups

Route groups allow you to organize routes without affecting the URL structure.

```
app/
├── (marketing)/
│   ├── about/
│   │   └── page.tsx      # /about
│   └── blog/
│       └── page.tsx      # /blog
├── (shop)/
│   ├── cart/
│   │   └── page.tsx      # /cart
│   └── products/
│       └── page.tsx      # /products
└── layout.tsx
```

### Multiple Root Layouts

```
app/
├── (marketing)/
│   ├── layout.tsx        # Marketing layout
│   └── page.tsx
└── (app)/
    ├── layout.tsx        # App layout
    └── dashboard/
        └── page.tsx
```

## Parallel Routes

Parallel routes allow you to render multiple pages in the same layout simultaneously.

```
app/
├── @analytics/
│   └── page.tsx
├── @team/
│   └── page.tsx
├── layout.tsx
└── page.tsx
```

```tsx
// app/layout.tsx
export default function Layout({
  children,
  analytics,
  team,
}: {
  children: React.ReactNode
  analytics: React.ReactNode
  team: React.ReactNode
}) {
  return (
    <>
      {children}
      {analytics}
      {team}
    </>
  )
}
```

### Conditional Rendering with Parallel Routes

```tsx
// app/layout.tsx
import { auth } from '@/lib/auth'

export default async function Layout({
  children,
  admin,
  user,
}: {
  children: React.ReactNode
  admin: React.ReactNode
  user: React.ReactNode
}) {
  const session = await auth()
  const isAdmin = session?.user?.role === 'admin'

  return (
    <>
      {children}
      {isAdmin ? admin : user}
    </>
  )
}
```

## Intercepting Routes

Intercepting routes allow you to load a route within the current layout while showing a different URL.

Convention:
- `(.)` - Match same level
- `(..)` - Match one level above
- `(..)(..)` - Match two levels above
- `(...)` - Match from root

### Modal Pattern

```
app/
├── @modal/
│   └── (.)photo/
│       └── [id]/
│           └── page.tsx    # Modal view
├── photo/
│   └── [id]/
│       └── page.tsx        # Full page view
├── default.tsx
├── layout.tsx
└── page.tsx
```

```tsx
// app/@modal/(.)photo/[id]/page.tsx
import { Modal } from '@/components/modal'
import Photo from '@/components/photo'

export default async function PhotoModal({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  const { id } = await params
  return (
    <Modal>
      <Photo id={id} />
    </Modal>
  )
}

// app/photo/[id]/page.tsx
import Photo from '@/components/photo'

export default async function PhotoPage({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  const { id } = await params
  return <Photo id={id} />
}
```

## Dynamic Route Segments

### Required Dynamic Segments

```tsx
// app/blog/[slug]/page.tsx
export default async function Page({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  return <div>Blog post: {slug}</div>
}
```

### Catch-All Segments

```tsx
// app/docs/[...slug]/page.tsx
// Matches: /docs/a, /docs/a/b, /docs/a/b/c
export default async function Page({
  params,
}: {
  params: Promise<{ slug: string[] }>
}) {
  const { slug } = await params
  return <div>Path: {slug.join('/')}</div>
}
```

### Optional Catch-All Segments

```tsx
// app/docs/[[...slug]]/page.tsx
// Matches: /docs, /docs/a, /docs/a/b
export default async function Page({
  params,
}: {
  params: Promise<{ slug?: string[] }>
}) {
  const { slug } = await params
  return <div>Path: {slug?.join('/') ?? 'index'}</div>
}
```

## Middleware

```tsx
// middleware.ts (root of project)
import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

export function middleware(request: NextRequest) {
  // Redirect
  if (request.nextUrl.pathname === '/old-path') {
    return NextResponse.redirect(new URL('/new-path', request.url))
  }

  // Rewrite
  if (request.nextUrl.pathname.startsWith('/api/')) {
    return NextResponse.rewrite(new URL('/api/proxy', request.url))
  }

  // Add headers
  const response = NextResponse.next()
  response.headers.set('x-custom-header', 'value')
  return response
}

export const config = {
  matcher: [
    '/((?!_next/static|_next/image|favicon.ico).*)',
  ],
}
```

## Navigation

### Link Component

```tsx
import Link from 'next/link'

export function Navigation() {
  return (
    <nav>
      <Link href="/about">About</Link>
      <Link href="/blog/hello-world">Blog Post</Link>
      <Link href={{ pathname: '/search', query: { q: 'nextjs' } }}>
        Search
      </Link>
      <Link href="/dashboard" prefetch={false}>
        Dashboard
      </Link>
      <Link href="/form" scroll={false}>
        Form (no scroll)
      </Link>
      <Link href="/home" replace>
        Home (replace history)
      </Link>
    </nav>
  )
}
```

### Programmatic Navigation

```tsx
'use client'

import { useRouter } from 'next/navigation'

export function NavigationButtons() {
  const router = useRouter()

  return (
    <>
      <button onClick={() => router.push('/dashboard')}>
        Go to Dashboard
      </button>
      <button onClick={() => router.replace('/login')}>
        Replace with Login
      </button>
      <button onClick={() => router.refresh()}>
        Refresh
      </button>
      <button onClick={() => router.back()}>
        Go Back
      </button>
      <button onClick={() => router.forward()}>
        Go Forward
      </button>
    </>
  )
}
```

### Active Link Detection

```tsx
'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'

export function NavLink({
  href,
  children,
}: {
  href: string
  children: React.ReactNode
}) {
  const pathname = usePathname()
  const isActive = pathname === href

  return (
    <Link
      href={href}
      className={isActive ? 'text-blue-500 font-bold' : 'text-gray-600'}
    >
      {children}
    </Link>
  )
}
```
