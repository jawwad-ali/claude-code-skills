// ===========================================
// EXAMPLE: Root Layout
// File: app/layout.tsx
// ===========================================

import type { Metadata } from 'next'
import { Inter } from 'next/font/google'
import './globals.css'

const inter = Inter({ subsets: ['latin'] })

export const metadata: Metadata = {
  title: {
    template: '%s | My App',
    default: 'My App',
  },
  description: 'My application description',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en">
      <body className={inter.className}>
        <header>
          <nav>{/* Navigation */}</nav>
        </header>
        <main>{children}</main>
        <footer>{/* Footer */}</footer>
      </body>
    </html>
  )
}

// ===========================================
// EXAMPLE: Dashboard Layout with Sidebar
// File: app/dashboard/layout.tsx
// ===========================================

import Sidebar from '@/components/sidebar'

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div className="flex min-h-screen">
      <Sidebar />
      <div className="flex-1 p-8">{children}</div>
    </div>
  )
}

// ===========================================
// EXAMPLE: Server Component Page with Data Fetching
// File: app/posts/page.tsx
// ===========================================

import Link from 'next/link'
import { Suspense } from 'react'

interface Post {
  id: string
  title: string
  excerpt: string
  createdAt: string
}

async function getPosts(): Promise<Post[]> {
  const res = await fetch('https://api.example.com/posts', {
    next: { revalidate: 3600 }, // Revalidate every hour
  })

  if (!res.ok) {
    throw new Error('Failed to fetch posts')
  }

  return res.json()
}

async function PostList() {
  const posts = await getPosts()

  return (
    <ul className="space-y-4">
      {posts.map((post) => (
        <li key={post.id} className="border rounded-lg p-4">
          <Link href={`/posts/${post.id}`}>
            <h2 className="text-xl font-semibold">{post.title}</h2>
          </Link>
          <p className="text-gray-600">{post.excerpt}</p>
        </li>
      ))}
    </ul>
  )
}

export default function PostsPage() {
  return (
    <div>
      <h1 className="text-3xl font-bold mb-8">Blog Posts</h1>
      <Suspense fallback={<PostsSkeleton />}>
        <PostList />
      </Suspense>
    </div>
  )
}

function PostsSkeleton() {
  return (
    <div className="space-y-4">
      {[1, 2, 3].map((i) => (
        <div key={i} className="border rounded-lg p-4 animate-pulse">
          <div className="h-6 bg-gray-200 rounded w-3/4 mb-2" />
          <div className="h-4 bg-gray-200 rounded w-full" />
        </div>
      ))}
    </div>
  )
}

// ===========================================
// EXAMPLE: Dynamic Page with Params
// File: app/posts/[slug]/page.tsx
// ===========================================

import { notFound } from 'next/navigation'
import type { Metadata } from 'next'

interface Post {
  id: string
  slug: string
  title: string
  content: string
}

async function getPost(slug: string): Promise<Post | null> {
  const res = await fetch(`https://api.example.com/posts/${slug}`, {
    next: { tags: ['post', slug] },
  })

  if (!res.ok) return null
  return res.json()
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>
}): Promise<Metadata> {
  const { slug } = await params
  const post = await getPost(slug)

  if (!post) {
    return { title: 'Post Not Found' }
  }

  return {
    title: post.title,
    description: post.content.substring(0, 160),
  }
}

export async function generateStaticParams() {
  const res = await fetch('https://api.example.com/posts')
  const posts: Post[] = await res.json()

  return posts.map((post) => ({
    slug: post.slug,
  }))
}

export default async function PostPage({
  params,
}: {
  params: Promise<{ slug: string }>
}) {
  const { slug } = await params
  const post = await getPost(slug)

  if (!post) {
    notFound()
  }

  return (
    <article className="prose lg:prose-xl mx-auto">
      <h1>{post.title}</h1>
      <div dangerouslySetInnerHTML={{ __html: post.content }} />
    </article>
  )
}

// ===========================================
// EXAMPLE: Loading State
// File: app/posts/loading.tsx
// ===========================================

export function PostsLoading() {
  return (
    <div className="animate-pulse">
      <div className="h-10 bg-gray-200 rounded w-1/4 mb-8" />
      <div className="space-y-4">
        {[1, 2, 3].map((i) => (
          <div key={i} className="h-24 bg-gray-200 rounded" />
        ))}
      </div>
    </div>
  )
}

// ===========================================
// EXAMPLE: Error Boundary
// File: app/posts/error.tsx
// ===========================================

// Note: error.tsx must be a Client Component
// 'use client'

import { useEffect } from 'react'

export function PostsError({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    // Log error to error reporting service
    console.error(error)
  }, [error])

  return (
    <div className="text-center py-12">
      <h2 className="text-2xl font-bold text-red-600 mb-4">
        Something went wrong!
      </h2>
      <p className="text-gray-600 mb-4">{error.message}</p>
      <button
        onClick={reset}
        className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
      >
        Try again
      </button>
    </div>
  )
}

// ===========================================
// EXAMPLE: Not Found Page
// File: app/posts/[slug]/not-found.tsx
// ===========================================

import Link from 'next/link'

export function PostNotFound() {
  return (
    <div className="text-center py-12">
      <h2 className="text-2xl font-bold mb-4">Post Not Found</h2>
      <p className="text-gray-600 mb-4">
        The post you're looking for doesn't exist.
      </p>
      <Link
        href="/posts"
        className="text-blue-500 hover:underline"
      >
        Back to Posts
      </Link>
    </div>
  )
}
